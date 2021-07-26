/*
 * EvilBlocker.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.net;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.R;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.model.Wish;
import net.cellar.supp.Log;
import net.cellar.supp.Util;
import net.cellar.worker.Downloader;
import net.cellar.worker.LoaderListener;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/**
 * Blocks downloads from known bad hosts.
 * The hosts are provided via one of the urls given in {@link net.cellar.R.array#entryvalues_list_evil}.
 */
public class EvilBlocker implements LoaderListener, Mores {

    private static final String FILENAME = "black.txt";
    private static final String TAG = "EvilBlocker";

    private final File uncompressedFile;
    private final File dir;
    private final ZipFile zipFile;
    private final Set<String> evil = new HashSet<>();
    private final int updateIntervalInHours;

    /** the url to download the blacklist from */
    private String url;
    private boolean loading;
    private Reference<LoadedCallback> refCallback;
    private Squeezer squeezer;
    private ExecutorService executor;

    /**
     * Constructor.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public EvilBlocker(@NonNull Context ctx) {
        super();
        this.dir = ctx.getFilesDir();
        this.uncompressedFile = new File(this.dir, FILENAME);
        this.zipFile = new ZipFile(this.uncompressedFile.getAbsolutePath() + ".zip");
        this.url = PreferenceManager.getDefaultSharedPreferences(ctx).getString(App.PREF_BLACKLIST, App.PREF_BLACKLIST_DEFAULT);
        this.updateIntervalInHours = ctx.getResources().getInteger(R.integer.blacklist_update);
        if (!TextUtils.isEmpty(this.url)) refreshIfNeeded(ctx);
    }

    /** {@inheritDoc} */
    @Override
    public void done(int id, boolean complete, @NonNull Set<Delivery> deliveries) {
        this.loading = false;
        this.executor.shutdown();
        this.executor = null;
        final LoadedCallback callback = this.refCallback != null ? this.refCallback.get() : null;
        if (!complete) {
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        if (deliveries.isEmpty()) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to load \"" + this.url + "\" - No delivery!");
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        Delivery d = deliveries.iterator().next();
        int rc = d.getRc();
        if (rc > 299 && rc != HttpURLConnection.HTTP_NOT_MODIFIED) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to load " + this.url + ", rc " + rc);
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "Loaded " + this.url + ", rc " + rc);
        final int count = parse();
        if (this.squeezer != null && this.squeezer.isAlive()) {
            try {
                this.squeezer.join(2_000L);
            } catch (InterruptedException ignored) {
            }
        }
        if (count <= 0) {
            if (callback != null) callback.loaded(false, count);
            return;
        }
        this.squeezer = new Squeezer(count, callback);
        this.squeezer.setPriority(Thread.NORM_PRIORITY - 1);
        this.squeezer.start();
    }

    /**
     * Determines whether the given Uri's host is considered evil.
     * @param uri Uri too check
     * @return {@code true} / {@code false}
     */
    @AnyThread
    public boolean isEvil(@Nullable Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        return isEvil(host);
    }

    /**
     * Determines whether the given host is considered evil.
     * @param host host too check
     * @return {@code true} / {@code false}
     */
    @AnyThread
    public boolean isEvil(String host) {
        if (host == null) return false;
        boolean boo;
        synchronized (this.evil) {
            boo = this.evil.contains(host);
        }
        return boo;
    }

    /**
     * Downloads the black list from a {@link #url remote resource}.
     * @param ctx Context
     * @param callback callback
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    private void load(@NonNull Context ctx,  @Nullable LoadedCallback callback) {
        if (this.loading || this.url == null) return;
        final OkHttpClient o = ((App)ctx.getApplicationContext()).getOkHttpClient();
        //noinspection ConstantConditions
        if (o == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "OkHttpClient is null!");
            if (callback != null) callback.loaded(false, 0);
            return;
        }
        Downloader dl = new Downloader(-1, o, this);
        Wish wish = new Wish(Uri.parse(this.url));
        Order order = new Order(wish);
        order.setDestination(this.dir.getAbsolutePath(), FILENAME);
        this.loading = true;
        this.refCallback = callback != null ? new SoftReference<>(callback) : null; // WeakReferences may not survive
        if (this.executor == null) this.executor = Executors.newSingleThreadExecutor();
        dl.executeOnExecutor(this.executor, order);
    }

    /**
     * Parses the {@link #uncompressedFile local file}.
     * Should be done on a worker thread as it takes some time (a few hundred ms).
     * @return number of entries
     */
    private int parse() {
        int n = 0;
        BufferedReader reader = null;
        try {
            if (this.zipFile.getFile().isFile()) {
                FileHeader fileHeader = this.zipFile.getFileHeader(FILENAME);
                if (fileHeader != null) reader = new BufferedReader(new InputStreamReader(this.zipFile.getInputStream(fileHeader)));
                else if (BuildConfig.DEBUG) Log.e(TAG, "Zip file does not contain " + FILENAME);
            } else if (this.uncompressedFile.isFile()) {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(this.uncompressedFile)));
            }
            if (reader == null) {
                return 0;
            }
            synchronized (this.evil) {
                this.evil.clear();
                for (; ; ) {
                    String line = reader.readLine();
                    if (line == null) break;
                    if (!line.startsWith("0.0.0.0") && !line.startsWith("127.0.0.1")) continue;
                    int space = line.indexOf(' ');
                    if (space <= 0) continue;
                    String host = line.substring(space + 1).trim();
                    if (host.length() == 0) continue;
                    this.evil.add(host);
                }
                n = this.evil.size();
            }
        } catch (FileNotFoundException e) {
            if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
        } catch (net.lingala.zip4j.exception.ZipException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
            Util.deleteFile(this.zipFile.getFile());
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        } finally {
            Util.close(reader);
        }
        return n;
    }

    /**
     * Returns the most recent modification of the zip file. 0 if the zip file does not exist or if it is invalid.
     * @return timestamp or 0
     */
    public long lastModified() {
        return this.zipFile != null && this.zipFile.isValidZipFile() ? this.zipFile.getFile().lastModified() : 0L;
    }

    /**
     * Loads the blacklist if it does not exist yet or if it is too old.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public void refreshIfNeeded(@NonNull Context ctx) {
        File zip = this.zipFile.getFile();
        if (!zip.isFile() || System.currentTimeMillis() - zip.lastModified() > this.updateIntervalInHours * 3_600_000L) {
            load(ctx, null);
        } else {
            //if (BuildConfig.DEBUG) Log.i(TAG, "Update not required, blacklist is " + Math.round((System.currentTimeMillis() - zip.lastModified()) / 3_600_000.) + " h old.");
            parse();
            if (!zip.isFile()) {
                // Something went wrong -> reload
                load(ctx, null);
            }
        }
    }

    /**
     * Sets the blacklist url.
     * @param ctx Context
     * @param url blacklist url - can be {@code null} to disable
     * @param callback optional callback
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public void setUrl(@NonNull Context ctx, @Nullable String url, @Nullable LoadedCallback callback) {
        if ("".equals(url)) url = null;
        boolean changed = (url != null && !url.equals(this.url)) || (url == null && this.url != null);
        if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "setUrl(…, " + url + ", …) - changed: " + changed);
        this.url = url;
        if (changed) {
            File zip = this.zipFile.getFile();
            if (zip.isFile()) Util.deleteFile(zip);
            if (this.url != null) {
                load(ctx, callback);
            } else {
                synchronized (this.evil) {
                    this.evil.clear();
                }
            }
        }
    }

    @Override
    @NonNull
    public String toString() {
        int n;
        synchronized (this.evil) {
            n = this.evil.size();
        }
        return getClass().getSimpleName() + " with " + n + " evil hosts";
    }

    /**
     * To be implemented if information about blacklist updates are required.
     */
    public interface LoadedCallback {
        /**
         * The blacklist has been loaded.<br>
         * <em>Note: This may be called on a worker thread!</em>
         * @param ok {@code true} if successful
         * @param count number of entries
         */
        @AnyThread
        void loaded(boolean ok, int count);
    }

    /**
     * Compresses the blacklist to ca. 24% of its original size (568 kB vs. 2372 kB).
     */
    private class Squeezer extends Thread {

        private final int count;
        private final Reference<LoadedCallback> rc;

        private Squeezer(int count, @Nullable LoadedCallback callback) {
            super();
            this.count = count;
            this.rc = (callback != null ? new SoftReference<>(callback) : null);
        }

        @Override
        public void run() {
            try {
                ZipParameters zp = new ZipParameters();
                zp.setCompressionLevel(CompressionLevel.MAXIMUM);
                if (EvilBlocker.this.zipFile.getFile().isFile()) {
                    EvilBlocker.this.zipFile.removeFile(FILENAME);
                }
                EvilBlocker.this.zipFile.addFile(EvilBlocker.this.uncompressedFile, zp);
                if (EvilBlocker.this.zipFile.isValidZipFile()) {
                    Util.deleteFile(EvilBlocker.this.uncompressedFile);
                    if (rc != null) {
                        LoadedCallback callback = rc.get();
                        if (callback != null) callback.loaded(true, count);
                        rc.clear();
                    }
                    return;
                } else {
                    throw new RuntimeException("Failed to compress " + uncompressedFile);
                }
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, "While squeezing: " + e.toString(), e);
                Util.deleteFile(EvilBlocker.this.zipFile.getFile());
            }
            if (rc != null) {
                LoadedCallback callback = rc.get();
                if (callback != null) callback.loaded(false, 0);
                rc.clear();
            }
        }
    }
}

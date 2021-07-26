/*
 * Ancestry.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Remembers the host that a file has been downloaded from.<br>
 * Must be initialised once via {@link #setup(Context)}!
 */
public class Ancestry {
    private static final String FILE = "ancestry.txt";
    private static final long SAVE_DELAY = 1_000L;
    private static final char SEP = ' ';
    private static final String TAG = "Ancestry";
    private static Ancestry instance;

    @NonNull
    public static Ancestry getInstance() {
        assert instance != null;
        return instance;
    }

    /**
     * Initialises the instance.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public static void setup(@NonNull Context ctx) {
        if (instance != null) return;
        instance = new Ancestry(ctx);
    }

    /** key: file name; value: host */
    private final Map<String, String> map = new HashMap<>();
    private final File file;
    private final File downloadsDir;
    private final Runnable saver = this::save;
    private final Handler handler = new Handler();

    /**
     * Constructor.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    private Ancestry(@NonNull Context ctx) {
        super();
        this.file = new File(ctx.getFilesDir(), FILE);
        this.downloadsDir = App.getDownloadsDir(ctx);
        new Thread() {
            @Override
            public void run() {
                load();
                cleanup();
            }
        }.start();
    }

    /**
     * Adds an entry. The host should be a remote one.
     * @param host host that supplied the file identified by {@code fileName}
     * @param fileName the file that {@code host} supplied
     */
    public void add(@Nullable String host, @Nullable String fileName) {
        if (TextUtils.isEmpty(host) || fileName == null) return;
        if (BuildConfig.DEBUG) Log.i(TAG, "add(" + host + ", " + fileName + ")");
        File expectedFile = new File(this.downloadsDir, fileName);
        if (!expectedFile.exists()) {
            return;
        }
        synchronized (this.map) {
            if (this.map.containsKey(fileName)) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Will not overwrite origin \"" + this.map.get(fileName) + "\" of \"" + fileName + "\" with \"" + host + "\"");
                return;
            }
            this.map.put(fileName, host);
        }
        this.handler.removeCallbacks(this.saver);
        this.handler.postDelayed(this.saver, SAVE_DELAY);
    }

    /**
     * Removes entries of files that do not exist any more.
     */
    @AnyThread
    private void cleanup() {
        this.handler.removeCallbacks(this.saver);
        File[] downloads = this.downloadsDir.listFiles();
        if (downloads == null || downloads.length == 0) {
            synchronized (this.map) {
                this.map.clear();
                Util.deleteFile(this.file);
            }
            return;
        }
        final Set<String> namesOfExistingFiles = new HashSet<>(downloads.length);
        for (File download : downloads) {
            namesOfExistingFiles.add(download.getName());
        }
        boolean modified = false;
        synchronized (this.map) {
            final Set<String> fileNames = new HashSet<>(this.map.keySet());
            for (String fileName : fileNames) {
                if (!namesOfExistingFiles.contains(fileName)) {
                    this.map.remove(fileName);
                    modified = true;
                }
            }
        }
        if (!modified) return;
        this.handler.postDelayed(this.saver, SAVE_DELAY);
    }

    /**
     * Returns the host that the given file has been downloaded from.
     * @param file download file
     * @return host
     */
    @Nullable
    public String getHost(File file) {
        if (file == null) return null;
        String host;
        synchronized (this.map) {
            host = this.map.get(file.getName());
        }
        return host;
    }

    /**
     * Tells whether a record for the given File exists.
     * @param file File to check
     * @return true / false
     */
    public boolean knowsFile(File file) {
        if (file == null) return false;
        boolean knows;
        synchronized (this.map) {
            knows = this.map.containsKey(file.getName());
        }
        return knows;
    }

    @AnyThread
    private void load() {
        if (!this.file.isFile()) return;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(this.file)));
            synchronized (this.map) {
                this.map.clear();
                for (; ; ) {
                    String line = reader.readLine();
                    if (line == null) break;
                    int sep = line.lastIndexOf(SEP);
                    if (sep <= 0) continue;
                    String filename = line.substring(0, sep);
                    String host = line.substring(sep + 1);
                    if (!TextUtils.isEmpty(filename) && !TextUtils.isEmpty(host)) {
                        this.map.put(filename, host);
                    }
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        } finally {
            Util.close(reader);
        }
    }

    /**
     * Removes the entry for the given file <em>which must not exist any more</em>.
     * @param file non-existing File
     */
    public void remove(@Nullable File file) {
        if (file == null) return;
        if (file.exists()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Not removing origin for " + file.getName() + " because the file exists!");
            return;
        }
        synchronized (this.map) {
            String removed = this.map.remove(file.getName());
            if (removed == null) {
                if (BuildConfig.DEBUG) Log.w(TAG, "No origin for " + file.getName() + " had been stored!");
                return;
            }
        }
        this.handler.removeCallbacks(this.saver);
        this.handler.postDelayed(this.saver, SAVE_DELAY);
    }

    private void save() {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(this.file)));
            final Set<Map.Entry<String, String>> entries;
            synchronized (this.map) {
                entries = this.map.entrySet();
            }
            for (Map.Entry<String, String> entry : entries) {
                writer.write(entry.getKey() + SEP + entry.getValue());
                writer.newLine();
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        } finally {
            Util.close(writer);
        }
    }

    /**
     * When a file has been renamed, transfers the origin from the old entry to a new one.
     * @param old old file
     * @param renamed new renamed file
     */
    public void transfer(@Nullable final File old, @Nullable final File renamed) {
        if (old == null || renamed == null) return;
        synchronized (this.map) {
            String host = this.map.get(old.getName());
            if (host == null) return;
            this.map.remove(old.getName());
            this.map.put(renamed.getName(), host);
        }
        this.handler.removeCallbacks(this.saver);
        this.handler.postDelayed(this.saver, SAVE_DELAY);
    }
}

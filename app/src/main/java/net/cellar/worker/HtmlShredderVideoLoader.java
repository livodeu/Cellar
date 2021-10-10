/*
 * HtmlShredderVideoLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.R;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

import okhttp3.OkHttpClient;

/**
 * Loads a video that is referenced in a web page.
 */
public class HtmlShredderVideoLoader extends Downloader implements MediaPlayer.EventListener {

    private static final String TAG = "HtmlShredderVideoLoader";

    protected final Reference<Context> refctx;
    /** {@code true} if a {@link MediaPlayer.Event#EndReached} or {@link MediaPlayer.Event#Stopped} event has been received */
    private volatile boolean mediaEnded;
    /** {@code true} if a {@link MediaPlayer.Event#EncounteredError} event has been received */
    private volatile boolean mediaError;
    /** the value of the first TimeChanged event that has been received */
    private volatile long firstTimeChanged;
    /** the value of the latest TimeChanged event that has been received */
    private volatile long latestTimeChanged;
    /** total length in milliseconds */
    private volatile long msTotal;

    protected String overriddenTitle;


    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public HtmlShredderVideoLoader(int id, @Nullable Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, client, loaderListener);
        this.refctx = ctx != null ? new WeakReference<>(ctx) : null;
    }

    /**
     * Returns UrlExtractor implementations.
     * The first one to return a non-null value from parsing a line wins.
     * @return array of UrlExtractor implementations
     */
    @NonNull
    protected UrlExtractor[] getExtractors() {
        return new UrlExtractor[] {new VideoSrcExtractor(), new OgVideoExtractor()};
    }

    /**
     * If the extracted Uri points to a resource that should be handled by LibVLC, this is done here.
     * @param order original Order
     * @param targetDirectory target folder
     * @param videoUri Uri to download from
     * @return Delivery
     */
    @NonNull
    @SuppressWarnings("BusyWait")
    private Delivery handleStream(@NonNull Order order, @NonNull String targetDirectory, @NonNull Uri videoUri) {
        Context ctx = (this.refctx != null ? this.refctx.get() : null);
        if (ctx == null) return new Delivery(order, LoaderService.ERROR_CONTEXT_GONE, null, null);
        final Order followUpOrder = new Order(order.getWish(), videoUri);
        followUpOrder.setDestinationFolder(targetDirectory);
        followUpOrder.setDestinationFilename(this.overriddenTitle != null ? this.overriddenTitle : videoUri.getLastPathSegment());
        followUpOrder.setMime("application/vnd.apple.mpegurl");
        File df = new File(targetDirectory, followUpOrder.getDestinationFilename());
        String alt = Util.suggestAlternativeFilename(df);
        if (alt != null) {
            df = new File(targetDirectory, alt);
        }
        LibVLC libVLC = null;
        MediaPlayer mediaPlayer = null;
        Media media = null;
        try {
            final ArrayList<String> args = new ArrayList<>(BuildConfig.DEBUG ? 4 : 3);
            if (BuildConfig.DEBUG) args.add("-vvv");
            args.add("--no-audio");
            args.add("--no-video");
            args.add("--no-spu");
            libVLC = new LibVLC(ctx, args);
            mediaPlayer = new MediaPlayer(libVLC);
            media = new Media(libVLC, videoUri);
            media.addOption(":network-caching=1500");
            media.addOption(":sout-mux-caching=15000");
            media.addOption(":adaptive-logic=highest");
            media.addOption(":sout-keep");
            media.addOption(":sout=#standard{access=file,mux=mp4,dst=\"" + df.getAbsolutePath() + "\"}");
            media.setHWDecoderEnabled(true, false);
            mediaPlayer.setMedia(media);
            mediaPlayer.setEventListener(this);
            media.release();
            media = null;
            mediaPlayer.play();
            //if (BuildConfig.DEBUG) onProgressUpdate(Progress.msg("Streaming…", false));
            while (!this.mediaEnded && !this.mediaError && !isCancelled()) {
                Thread.sleep(750L);
            }
        } catch (InterruptedException e) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Interrupted.");
        } finally {
            if (media != null) media.release();
            if (mediaPlayer != null) mediaPlayer.release();
            if (libVLC != null) libVLC.release();
        }
        if (this.mediaError) {
            Util.deleteFile(df);
            return new Delivery(followUpOrder, LoaderService.ERROR_OTHER, null, null);
        }
        if (isCancelled()) {
            if (!isDeferred()) Util.deleteFile(df);
            return new Delivery(followUpOrder, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, null, order.getMime());
        }
        return new Delivery(followUpOrder, 200, df, "video/mp4");
    }

    @NonNull
    @Override
    protected Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        if (BuildConfig.DEBUG) Log.i(TAG, "load(" + order + ")");
        super.ignoreListener = true;
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null) return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        File temp = new File(tmp);
        final String realDownloadsFolder = order.getDestinationFolder();
        order.setDestinationFolder(temp.getAbsolutePath());
        Delivery delivery = super.load(order, progressBefore, progressPerOrder);
        if (delivery.getRc() > 299) return delivery;
        final File html = delivery.getFile();
        if (html == null || html.length() == 0L) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Did not load a file from " + order.getUrl());
            return delivery;
        }
        super.ignoreListener = false;
        boolean sourceFound = false;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(html)));
            String src = null;
            String mime;
            final UrlExtractor[] extractors = getExtractors();
            for (;;) {
                String line = reader.readLine();
                if (line == null) break;

                UrlExtractor winningExtractor = null;
                for (UrlExtractor urlExtractor : extractors) {
                    src = urlExtractor.extract(line);
                    if (src != null) {
                        winningExtractor = urlExtractor;
                        break;
                    }
                }
                if (src == null) continue;
                String lsrc = src.toLowerCase(java.util.Locale.US);
                mime = winningExtractor.getMime();
                if (!lsrc.startsWith("https://") && !lsrc.startsWith("http://")) {
                    src = order.getUri().getScheme() + "://"  + order.getUri().getHost() + (!src.startsWith("/") ? "/" : "") + src;
                    lsrc = src.toLowerCase(java.util.Locale.US);
                }
                Util.close(reader);
                reader = null;
                Util.deleteFile(html);
                sourceFound = true;
                Uri videoUri = Uri.parse(src);
                if (lsrc.endsWith(".m3u") || lsrc.endsWith(".m3u8")) {
                    return handleStream(order, realDownloadsFolder, videoUri);
                } else {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Trying to load '" + mime + "' medium from '" + videoUri + "'");
                    Order order2 = new Order(order.getWish(), videoUri);
                    if (Build.VERSION.SDK_INT >= 24 && !LoaderService.isProtocolAllowed(order2.getUri())) {
                        order2 = Order.toHttps(order2);
                    }
                    order2.setDestinationFolder(realDownloadsFolder);
                    order2.setDestinationFilename(this.overriddenTitle != null ? this.overriddenTitle : videoUri.getLastPathSegment());
                    order2.setMime(mime);
                    delivery = super.load(order2, progressBefore, progressPerOrder);
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "Result of medium download: " + delivery);
                break;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While shredding HTML file: " + e.toString());
        } finally {
            Util.close(reader);
        }
        Util.deleteFile(html);
        if (!sourceFound) {
            return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        }
        return delivery;
    }

    /** {@inheritDoc} */
    @Override
    public void onEvent(MediaPlayer.Event event) {
        if (BuildConfig.DEBUG) Log.i(TAG, Streamer.eventToString(event));
        if (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.Stopped) {
            this.mediaEnded = true;
        } else if (event.type == MediaPlayer.Event.EncounteredError) {
            Context ctx = (this.refctx != null ? this.refctx.get() : null);
            if (ctx != null) publishProgress(Progress.msg(ctx.getString(R.string.msg_download_failed), true));
            this.mediaError = true;
        } else if (event.type == MediaPlayer.Event.LengthChanged) {
            this.msTotal = event.getLengthChanged();
        } else if (event.type == MediaPlayer.Event.TimeChanged) {
            long previousTimeChanged = this.latestTimeChanged;
            this.latestTimeChanged = event.getTimeChanged();
            if (this.firstTimeChanged == 0L && this.latestTimeChanged > 0L) this.firstTimeChanged = this.latestTimeChanged;
            if (this.firstTimeChanged > 0L && this.latestTimeChanged > this.firstTimeChanged && this.latestTimeChanged > previousTimeChanged) {
                publishProgress(Progress.msRecorded((int)(this.latestTimeChanged - this.firstTimeChanged), this.msTotal));
            }
        } else if (event.type == MediaPlayer.Event.Buffering) {
            publishProgress(Progress.buffering(event.getBuffering()));
        }
    }

    /**
     * For items like<br>
     * &lt;meta property="og:video" content="https://www.example.com/OlceKcy.mp4"&gt;
     */
    static class OgVideoExtractor extends UrlExtractor {

        @Override
        @Nullable
        String extract(@NonNull String line) {
            int start = line.indexOf("<meta property=\"og:video:secure_url\"");
            if (start < 0) start = line.indexOf("<meta property=\"og:video\"");
            if (start < 0) return null;
            int end = line.indexOf('>', start + 25);
            if (end < 0) return null;
            start = line.indexOf("content=\"", start + 25);
            if (start < 0 || start > end) return null;
            end = line.indexOf('"', start + 9);
            if (end < 0) return null;
            String src = line.substring(start + 9, end);
            super.mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.substring(src.lastIndexOf('.') + 1));
            return src;
        }

    }

    /**
     * For something like<ul>
     * <li>&lt;source src="/device_barchext/dev1/2014/02-12/26/87/file6d516o3rrhx16cfgma42.webm" type="video/webm"&gt;&lt;/source&gt;</li>
     * <li>&lt;source src="/device_barchext/dev1/2014/02-12/fa/1e/file6d516o3jb5x18esgra45.mp4" type="video/mp4"&gt;&lt;/source&gt;</li>
     * </ul>
     */
    static class VideoSrcExtractor extends UrlExtractor {

        private static final String END1a = "\"";
        private static final String KEY1a = "<source src=\"";
        private static final String KEY3 = "type=\"";

        @Override
        String extract(@NonNull String line) {
            int start = line.indexOf(KEY1a);
            if (start < 0) return null;
            int end = line.indexOf(END1a, start + KEY1a.length());
            if (end < 0) return null;
            String src = line.substring(start + KEY1a.length(), end);
            //
            int i = line.indexOf(KEY3);
            if (i > 0) {
                int j = line.indexOf('"', i + KEY3.length());
                super.mime = j > i ? line.substring(i + KEY3.length(), j) : "video/mp4";
            } else {
                super.mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.substring(src.lastIndexOf('.') + 1));
            }
            //
            return src;
        }
    }

    /**
     * Extracts video stream urls from the html page.
     */
    protected abstract static class UrlExtractor {

        protected String mime = null;

        /**
         * Constructor.
         */
        protected UrlExtractor() {
            super();
        }

        /**
         * Attempts to extract a video url from a line.
         * @param line line from the HTML source
         * @return video url or null
         */
        @Nullable
        abstract String extract(@NonNull String line);

        @Nullable
        final String getMime() {
            return this.mime;
        }
    }
}

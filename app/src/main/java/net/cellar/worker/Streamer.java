/*
 * Streamer.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.CallSuper;
import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.R;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.model.pl.Playlist;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayList;

/**
 * Employs libvlc to load streaming video.
 */
public class Streamer extends Loader implements MediaPlayer.EventListener {
    private static final String TAG = "Streamer";

    /**
     * Calls {@link Media#addOption(String)} with suitable options for the given Order.
     * @param media Media
     * @param order Order
     * @param destinationPath abs. path to output file
     */
    private static void configureMedia(@NonNull final Media media, @NonNull final Order order, @NonNull final String destinationPath) {
        @Nullable final String mime = order.getMime();
        if (BuildConfig.DEBUG) Log.i(TAG, "Configuring libvlc for mime '" + mime + "'");
        if ("audio/aac".equals(mime)) {
            media.addOption(":sout=#standard{access=file,mux=mp4,dst=\"" + destinationPath + "\"}");
        } else if ("video/mp4".equals(mime)) {
            media.addOption(":sout=#standard{access=file,mux=mp4,dst=\"" + destinationPath + "\"}");
        } else if ("audio/x-pn-realaudio".equals(mime)) {
            media.addOption(":sout=#standard{access=file,mux=mp4,dst=\"" + destinationPath + "\"}");
        } else if (mime != null && mime.startsWith("audio/")) {
            media.addOption(":sout=#standard{access=file,mux=dummy,dst=\"" + destinationPath + "\"}");
        } else if ("application/ogg".equals(mime)) {
            media.addOption(":sout=#standard{access=file,mux=ogg,dst=\"" + destinationPath + "\"}");
        } else {
            String uris = order.getUri().toString();
            // uris.contains("") is of course strictly unscientific, but our last resort!
            if (uris.contains("mp3")) {
                media.addOption(":sout=#standard{access=file,mux=dummy,dst=\"" + destinationPath + "\"}");
            } else if (uris.contains("aac")) {
                media.addOption(":sout=#standard{access=file,mux=mp4,dst=\"" + destinationPath + "\"}");
            } else {
                media.addOption(":sout=#standard{access=file,mux=mp4,dst=\"" + destinationPath + "\"}");
            }
        }

    }

    /**
     * Converts a MediaPlayer.Event into a loggable String. For debug only, of course.
     * @param event MediaPlayer.Event
     * @return String representation
     */
    @NonNull
    static String eventToString(@NonNull final MediaPlayer.Event event) {
        switch (event.type) {
            case 0x100: return "Media changed";
            case 0x102: return "Opening";
            case 0x103: return "Buffering: " + event.getBuffering(); // apparently [0..100]
            case 0x104: return "Playing";
            case 0x105: return "Paused";
            case 0x106: return "Stopped";
            case 0x109: return "End reached";
            case 0x10a: return "Error";
            case 0x10b: return "Time changed: " + NumberFormat.getIntegerInstance().format(event.getTimeChanged()); // apparently media pos. in milliseconds
            case 0x10c: return "Position changed: " + NumberFormat.getNumberInstance().format(event.getPositionChanged()); // apparently [0..1]
            case 0x10d: return "Seekable changed: " + event.getSeekable();
            case 0x10e: return "Pausable changed: " + event.getPausable();
            case 0x111: return "Length changed: " + NumberFormat.getIntegerInstance().format(event.getLengthChanged()) + " ms"; // apparently media length in milliseconds, not in live streams
            case 0x114: return "ESAdded: id: " + event.getEsChangedID() + ", type: " + event.getEsChangedType();
            case 0x115: return "ESDeleted: id: " + event.getEsChangedID() + ", type: " + event.getEsChangedType();
            case 0x116: return "ESSelected: id: " + event.getEsChangedID() + ", type: " + event.getEsChangedType();
            case 0x11e: return "Record changed: " + event.getRecordPath();
        }
        return "Event $" + Integer.toHexString(event.type);
    }

    /** HTTP User-Agent value */
    @NonNull final String httpUserAgent;
    private Reference<Context> refctx;
    private LibVLC libVLC = null;
    private float progressBeforeCurrentOrder;
    private float progressPerOrder;
    /** the value of the first TimeChanged event that has been received */
    private long firstTimeChanged;
    /** the value of the latest TimeChanged event that has been received */
    private long latestTimeChanged;
    /** total length in milliseconds */
    private long msTotal;
    private boolean liveStream;
    /** {@code true} if a {@link MediaPlayer.Event#EndReached} or {@link MediaPlayer.Event#Stopped} event has been received */
    private boolean mediaEnded;
    /** {@code true} if libvlc reported an unspecified error */
    private boolean vlcError;
    private Progress latestCompletion;

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context (required)
     * @param loaderListener Listener (optional)
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public Streamer(int id, @NonNull Context ctx, @Nullable LoaderListener loaderListener) {
        super(id, loaderListener);
        this.refctx = new WeakReference<>(ctx);
        this.httpUserAgent = ctx.getString(R.string.http_useragent);
    }

    /** {@inheritDoc} */
    @Override
    @CallSuper
    protected void cleanup() {
        if (this.libVLC != null) {
            this.libVLC.release();
            this.libVLC = null;
        }
        this.refctx = null;
    }

    /** {@inheritDoc}*/
    @SuppressWarnings("BusyWait")
    @Override
    @NonNull
    protected Delivery load(@NonNull Order order, @FloatRange(from = 0, to = 1) final float progressBefore, @FloatRange(from = 0, to = 1) final float progressPerOrder) {
        if (BuildConfig.DEBUG) Log.i(TAG, "load(" + order + "…, …)");
        this.progressBeforeCurrentOrder = progressBefore;
        this.progressPerOrder = progressPerOrder;
        Uri uri = order.getUri();
        final File destinationDir = new File(order.getDestinationFolder());
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        if (order.getDestinationFilename() == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }

        Context ctx = this.refctx.get();
        if (ctx == null) return new Delivery(order, LoaderService.ERROR_CONTEXT_GONE, null, null);

        File destinationFile = new File(destinationDir, order.getDestinationFilename());
        String alt = Util.suggestAlternativeFilename(destinationFile);
        if (alt != null) {
            order.setDestinationFilename(alt);
            destinationFile = new File(destinationDir, order.getDestinationFilename());
        }
        final String destinationPath = destinationFile.getAbsolutePath();

        publishProgress(Progress.resourcename(destinationFile.getName()));

        if (this.libVLC == null) {
            // see org.videolan.libvlc.util.Dumper.java
            final ArrayList<String> args = new ArrayList<>(BuildConfig.DEBUG ? 4 : 3);
            if (BuildConfig.DEBUG) args.add("-vvv");
            args.add("--no-audio");
            args.add("--no-video");
            args.add("--no-spu");   // no subtitles
            this.libVLC = new LibVLC(ctx, args);
            this.libVLC.setUserAgent(this.httpUserAgent, this.httpUserAgent);
        }
        final MediaPlayer mediaPlayer = new MediaPlayer(this.libVLC);
        try {
            final Media media = new Media(this.libVLC, uri);
            media.addOption(":network-caching=1500");
            media.addOption(":sout-mux-caching=15000");
            media.setHWDecoderEnabled(true, false);
            // what does 'gather' mean? -> https://wiki.videolan.org/Documentation:Modules/gather/
            if (order.hasAdditionalAudioUrls()) {
                for (String additionalAudioUrl : order.getAdditionalAudioUrls()) {
                    media.addOption(":input-slave=" + additionalAudioUrl);
                }
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
                int audiobitrate = prefs.getInt(App.PREF_VLC_BITRATE, App.PREF_VLC_BITRATE_DEFAULT);
                int samplerate = prefs.getInt(App.PREF_VLC_SAMPLERATE, App.PREF_VLC_SAMPLERATE_DEFAULT);
                // Transcoding with audio bitrate of " + audiobitrate + " kb/s and audio samplerate of " + samplerate + " Hz"
                media.addOption(":sout=#gather:transcode{vcodec=h264,vb=0,scale=0" +
                        ",acodec=mp4a,ab=" + audiobitrate + ",channels=2,samplerate=" + samplerate
                        + "}" +
                        ":standard{access=file,mux=mp4,dst=\"" + destinationPath + "\"}");
            } else {
                configureMedia(media, order, destinationPath);
            }
            if (Playlist.isPlaylist(order.getUrl())) {
                media.addOption(":adaptive-logic=highest");
                media.addOption(":sout-keep");
            }
            mediaPlayer.setMedia(media);
            mediaPlayer.setEventListener(this);
            media.release();
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While preparing Media: " + e.toString());
            mediaPlayer.release();
            return new Delivery(order, LoaderService.ERROR_VLC, destinationFile, order.getMime(), e, null);
        }
        mediaPlayer.play();
        boolean interruptedWhilePlaying = false;
        while (!mediaPlayer.isPlaying() && !super.stopRequested && !this.mediaEnded) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                if (BuildConfig.DEBUG) Log.e(TAG, "While waiting for MediaPlayer to start playing: " + e.toString());
                Util.deleteFile(destinationFile);
                mediaPlayer.release();
                return new Delivery(order, this.vlcError ? LoaderService.ERROR_VLC : LoaderService.ERROR_CANCELLED, destinationFile, order.getMime());
            }
        }
        while (!isCancelled() && !super.stopRequested && !this.mediaEnded) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                interruptedWhilePlaying = true;
                if (BuildConfig.DEBUG && !super.stopRequested && !this.mediaEnded) Log.e(TAG, "While MediaPlayer was playing: " + e.toString());
                break;
            }
            if (!mediaPlayer.isPlaying()) break;
        }
        mediaPlayer.release();  // MediaPlayer.release() is usually quite fast (ca. 50 ms)
        if (this.mediaEnded) {
            // if the destination file has any content, consider the outcome to be successful
            if (destinationFile.length() > 0L) return new Delivery(order, 200, destinationFile, order.getMime());
        }
        if (isCancelled() || interruptedWhilePlaying || this.vlcError) {
            if (!isDeferred()) Util.deleteFile(destinationFile);
            return new Delivery(order, this.vlcError ? LoaderService.ERROR_VLC : (isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED), destinationFile, order.getMime());
        }
        return new Delivery(order, 200, destinationFile, order.getMime());
    }

    /** {@inheritDoc} */
    @Override
    public void onEvent(final MediaPlayer.Event event) {
        if (event.type == MediaPlayer.Event.PositionChanged) {
            // positionChanged will not occur with live streams; they will cause a timeChanged, though (starting with an arbitrary number like 72231446)
            float percent = event.getPositionChanged();
            // publish the percentage only if no number of seconds/minutes has ever been published
            if (percent > 0.01f && !this.liveStream && (this.firstTimeChanged == 0L || this.firstTimeChanged == this.latestTimeChanged)) {
                this.latestCompletion = Progress.completing(this.progressBeforeCurrentOrder + percent * this.progressPerOrder, this.latestCompletion);
                publishProgress(this.latestCompletion);
            }
        } else if (event.type == MediaPlayer.Event.LengthChanged) {
            this.liveStream = false;
            this.msTotal = event.getLengthChanged();
            if (BuildConfig.DEBUG) Log.i(TAG, eventToString(event));
        } else if (event.type == MediaPlayer.Event.TimeChanged) {
            // positionChanged will not occur with live streams; they will cause a timeChanged, though (starting with an arbitrary number like 72231446)
            if (event.getTimeChanged() - this.latestTimeChanged >= 60_000L) {
                this.liveStream = true;
            }
            long previousTimeChanged = this.latestTimeChanged;
            this.latestTimeChanged = event.getTimeChanged();
            if (this.firstTimeChanged == 0L && this.latestTimeChanged > 0L) this.firstTimeChanged = this.latestTimeChanged;
            if (this.firstTimeChanged > 0L && this.latestTimeChanged > this.firstTimeChanged && this.latestTimeChanged > previousTimeChanged) {
                publishProgress(Progress.msRecorded((int)(this.latestTimeChanged - this.firstTimeChanged), this.liveStream ? -1 : this.msTotal, this.liveStream));
            }
        } else if (event.type == MediaPlayer.Event.EndReached || event.type == MediaPlayer.Event.Stopped) {
            if (BuildConfig.DEBUG) Log.i(TAG, eventToString(event));
            this.mediaEnded = true;
            cancel(true);
        } else if (event.type == MediaPlayer.Event.EncounteredError) {
            if (BuildConfig.DEBUG) Log.e(TAG, eventToString(event));
            this.vlcError = true;
            Context ctx = (this.refctx != null ? this.refctx.get() : null);
            if (ctx != null) publishProgress(Progress.msg(ctx.getString(R.string.msg_download_failed_no_more_info), true));
            cancel(true);
        } else if (event.type == MediaPlayer.Event.Buffering) {
            publishProgress(Progress.buffering(event.getBuffering()));
        }
    }
}

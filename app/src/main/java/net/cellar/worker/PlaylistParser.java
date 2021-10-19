/*
 * PlaylistParser.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;

import net.cellar.BuildConfig;
import net.cellar.model.UriPair;
import net.cellar.model.pl.M3UPlaylist;
import net.cellar.model.pl.Playlist;
import net.cellar.model.pl.PlsPlaylist;
import net.cellar.model.pl.RamPlaylist;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

/**
 *
 */
public class PlaylistParser extends AsyncTask<UriPair, Float, Playlist> {

            /*
            What to do with playlist uris (where the real content behind it is unknown)
            1. if uri points to a remote resource, download playlist from uri (e.g. "Folk_Alley.m3u")
            2. parse playlist for source entries (e.g. "https://www.folkalley.com/folkalley.ram")
            3. download suitable source entry (HEAD should be enough)
            3a. watch response headers for "Content-Type" (e.g. "audio/x-pn-realaudio")
             */

    private static final String TAG = "PlaylistParser";
    private final Reference<Context> refctx;
    private Listener listener;

    /**
     * Constructor.
     * @param ctx Context
     * @param listener listener
     */
    public PlaylistParser(@NonNull Context ctx, @Nullable Listener listener) {
        super();
        this.refctx = new WeakReference<>(ctx);
        this.listener = listener;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    protected Playlist doInBackground(@Size(min = 1) UriPair... uriPairs) {
        // e.g. UriPair{local=file:///data/user/0/net.cellar/cache/classic_fm.pls, remote=http://www.abc.net.au/res/streaming/audio/mp3/classic_fm.pls}
        BufferedReader reader = null;
        UriPair uriPair = uriPairs[0];
        final Playlist playlist;
        String lps = uriPair.getLocal().getLastPathSegment();
        int dot = lps != null ? lps.lastIndexOf('.') : -1;
        String tag = dot > 0 ? lps.substring(dot + 1).toLowerCase(java.util.Locale.US) : null;
        if ("ram".equals(tag)) {
            playlist = new RamPlaylist(uriPair.getRemote());
        } else if ("pls".equals(tag)) {
            playlist = new PlsPlaylist(uriPair.getRemote());
        } else {
            playlist = new M3UPlaylist(uriPair.getRemote());
        }
        Context ctx = this.refctx.get();
        if (ctx == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Context is gone!");
            return playlist;
        }
        try {
            reader = new BufferedReader(new InputStreamReader(ctx.getContentResolver().openInputStream(uriPair.getLocal())));
            while (!isCancelled()) {
                String line = reader.readLine();
                if (line == null) break;
                playlist.parseLine(line);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While parsing playlist " + uriPair + ": " + e.toString(), e);
        } finally {
            Util.close(reader);
        }
        return playlist;
    }

    /** {@inheritDoc} */
    @Override
    protected void onCancelled(@NonNull Playlist playlist) {
        if (this.listener != null) {
            this.listener.done(false, playlist);
            this.listener = null;
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onPostExecute(@NonNull Playlist playlist) {
        if (this.listener != null) {
            this.listener.done(true, playlist);
            this.listener = null;
        }
    }

    public interface Listener {
        void done(boolean completed, @NonNull Playlist playlist);
    }

}

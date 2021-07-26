package net.cellar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Settings;

import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Util;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@LargeTest
public class PlaylistTest extends BroadcastReceiver {

    private Context ctx;
    private boolean receiverRegistered;
    private volatile boolean playlistLoaded;
    private volatile boolean streamingStarted;
    private File destination;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean airplaneMode = (Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        Assume.assumeFalse("Device is in airplane mode", airplaneMode);
        IntentFilter f = new IntentFilter();
        f.addAction(App.ACTION_DOWNLOAD_STREAMING_STARTED);
        f.addAction(App.ACTION_DOWNLOAD_PLAYLIST_LOADED);
        ctx.registerReceiver(this, f);
        receiverRegistered = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.i(getClass().getSimpleName(), "onReceive() - action=\"" + intent.getAction() + "\" - filename=\"" + intent.getStringExtra(LoaderService.EXTRA_FILE) + "\"");
        if (App.ACTION_DOWNLOAD_PLAYLIST_LOADED.equals(intent.getAction())) {
            this.playlistLoaded = true;
        } else if (App.ACTION_DOWNLOAD_STREAMING_STARTED.equals(intent.getAction())) {
            this.streamingStarted = true;
        }
    }

    @After
    public void exit() {
        Util.deleteFile(destination);
        try {
            if (receiverRegistered) ctx.unregisterReceiver(this);
        } catch (Exception ignored) {
        }
    }

    /**
     * Tests offering to load a video via a master playlist which means the user has to select the video quality.
     */
    @Test
    public void testMasterPlaylist() {
        final String url = "https://wdradaptiv-vh.akamaihd.net/i/medp/ondemand/weltweit/fsk0/234/2346067/,2346067_31953016,2346067_31953017,2346067_31953018,2346067_31953015,2346067_31953014,2346067_31953019,.mp4.csmil/master.m3u8";
        final Uri uri =  Uri.parse(url);
        SimpleLife sl = new SimpleLife();
        ((App)ctx.getApplicationContext()).registerActivityLifecycleCallbacks(sl);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1_000L);
                if (playlistLoaded) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Playlist " + uri.getLastPathSegment() + " not loaded!", playlistLoaded);
        try {
            Thread.sleep(2_000L);
        } catch (Throwable ignored) {
        }
        assertTrue(sl.currentActivity instanceof MainActivity);
        MainActivity ma = (MainActivity)sl.currentActivity;
        assertNotNull(ma.dialogVariantSelector);
        assertTrue(ma.dialogVariantSelector.isShowing());
        destination = new File(App.getDownloadsDir(ctx), "master.m3u8");
        assertTrue("Playlist file does not exist", destination.isFile());
        ma.dialogVariantSelector.cancel();
        try {
            Thread.sleep(2_000L);
        } catch (Throwable ignored) {
        }
        assertFalse("Selector dialog has not been removed", ma.dialogVariantSelector != null && ma.dialogVariantSelector.isShowing());
        assertFalse("Playlist file has not been deleted", destination.isFile());
    }

    @Test
    public void testLiveAudioViaM3u() {
        final String url = "https://cdnlive.shooowit.net/rtvalive/smil:channel7.smil/chunklist_b250000.m3u8";
        final Uri uri =  Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1_000L);
                if (playlistLoaded) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Playlist " + uri.getLastPathSegment() + " not loaded!", playlistLoaded);
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(1_000L);
                if (streamingStarted) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Streaming has not started", streamingStarted);
        destination = new File(App.getDownloadsDir(ctx), "smil:channel7.smil.aac");
        assertTrue(destination.isFile());
    }

}

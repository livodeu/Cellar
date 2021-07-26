package net.cellar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.UiThread;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests downloading a m3u media playlist and queueing all the files that the list refers to.<br>
 * This Test does not delete the files because it ends before the download finishes.<br>
 * Note: This Test will (if successful) initiate a download of ca. 26 MB of data.<br>
 * Succeeded 2021-07-14
 */
@LargeTest
public class AcidTest extends BroadcastReceiver {

    private static final int SECS_TO_QUEUE_THEM_ALL = 10;
    private static final String URL = "https://archive.org/download/gd65-11-03.sbd.vernon.9044.sbeok.shnf/gd65-11-03.sbd.vernon.9044.sbeok.shnf_vbr.m3u";
    private static final int EXPECTED = 6;
    private Context ctx;
    private volatile int counter;
    private boolean receiverRegistered;

    @After
    public void exit() {
        if (receiverRegistered) ctx.unregisterReceiver(this);
    }

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean airplaneMode = (Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        Assume.assumeFalse("Device is in airplane mode", airplaneMode);
        IntentFilter f = new IntentFilter();
        f.addAction(App.ACTION_DOWNLOAD_QUEUED);
        f.addAction(App.ACTION_DOWNLOAD_FINISHED);
        ctx.registerReceiver(this, f);
        receiverRegistered = true;
    }

    /** {@inheritDoc} */
    @Override
    @UiThread
    public void onReceive(Context context, Intent intent) {
        if (App.ACTION_DOWNLOAD_QUEUED.equals(intent.getAction())) {
            synchronized (this) {
                this.counter++;
            }
        }
    }

    @Test
    public void loadM3u() {
        android.util.Log.i(getClass().getSimpleName(), "Loading \"" + URL + "\"…");
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        for (int i = 0; i < SECS_TO_QUEUE_THEM_ALL; i++) {
            try {
                Thread.sleep(1_000L);
                synchronized (this) {
                    if (counter == EXPECTED) break;
                }
            } catch (Throwable ignored) {
            }
        }
        assertEquals("Queued " + counter + " resources", EXPECTED, counter);
        StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
        android.util.Log.i(getClass().getSimpleName(), "Passed " + ste.getClassName() + "." + ste.getMethodName() + "()");
    }
}

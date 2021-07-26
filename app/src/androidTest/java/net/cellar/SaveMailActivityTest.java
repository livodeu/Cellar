package net.cellar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

import androidx.annotation.UiThread;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Tests the ability to intercept and save e-mails.<br>
 * Succeeded 2021-07-01
 */
@SmallTest
public class SaveMailActivityTest extends BroadcastReceiver {

    private Context ctx;
    private volatile boolean downloadStarted;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        IntentFilter f = new IntentFilter();
        f.addAction(App.ACTION_DOWNLOAD_STARTED);
        ctx.registerReceiver(this, f);
    }

    @After
    public void exit() {
        ctx.unregisterReceiver(this);
    }

    /** {@inheritDoc} */
    @Override
    @UiThread
    public void onReceive(Context context, Intent intent) {
        if (App.ACTION_DOWNLOAD_STARTED.equals(intent.getAction())) this.downloadStarted = true;
    }

    @Test
    public void testMail() {
        final Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.putExtra(Intent.EXTRA_EMAIL, new String[] {"person@jeemale.com"});
        intent.putExtra(Intent.EXTRA_SUBJECT, "Hey Person!");
        intent.putExtra(Intent.EXTRA_TEXT, "Hello Person!");
        intent.putExtra(Intent.EXTRA_HTML_TEXT, "<!DOCTYPE html><html><head><title>Message to Person</title></head><body><p>Hello Person!</p></body></html>");
        String drawableUri = ContentResolver.SCHEME_ANDROID_RESOURCE  + "://" + ctx.getPackageName() + "/" + R.mipmap.ic_launcher;
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(drawableUri));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), SaveMailActivity.class));
        ctx.startActivity(intent);
        try {
            Thread.sleep(3_000L);
        } catch (Throwable ignored) {
        }
        assertTrue("Did not handle e-mail!", this.downloadStarted);
        android.util.Log.i(getClass().getSimpleName(), "Passed " + Thread.currentThread().getStackTrace()[2].getMethodName());
    }

}

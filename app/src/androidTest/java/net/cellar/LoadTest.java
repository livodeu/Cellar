package net.cellar;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Util;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests successful loading of a resource.
 */
@MediumTest
public abstract class LoadTest extends BroadcastReceiver {

    static final int SECS_TO_FINISH = 300;
    static final int SECS_TO_START = 15;
    protected static File downloadsDir;
    Context ctx;
    private boolean receiverRegistered;
    volatile boolean downloadStarted;
    volatile boolean downloadResuming;
    volatile boolean downloadFinished;
    volatile String fileName;

    @BeforeClass
    public static void initclass() {
        downloadsDir = App.getDownloadsDir(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @NonNull
    abstract String getUrl();

    @After
    @CallSuper
    public void exit() {
        if (fileName != null) Util.deleteFile(new File(downloadsDir, fileName));
        try {
            if (receiverRegistered) ctx.unregisterReceiver(this);
        } catch (Exception ignored) {
        }
    }

    @Before
    @CallSuper
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean airplaneMode = (Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        Assume.assumeFalse("Device is in airplane mode", airplaneMode);
        IntentFilter f = new IntentFilter();
        f.addAction(App.ACTION_DOWNLOAD_STARTED);
        f.addAction(App.ACTION_DOWNLOAD_FINISHED);
        f.addAction(App.ACTION_DOWNLOAD_RESUMING);
        f.addAction(App.ACTION_DOWNLOAD_FILE_RENAMED);
        ctx.registerReceiver(this, f);
        receiverRegistered = true;
    }

    /** {@inheritDoc} */
    @Override
    @UiThread
    @CallSuper
    public void onReceive(Context context, Intent intent) {
        android.util.Log.i(getClass().getSimpleName(), "onReceive() - action=\"" + intent.getAction() + "\" - filename=\"" + intent.getStringExtra(LoaderService.EXTRA_FILE) + "\"");
        if (App.ACTION_DOWNLOAD_STARTED.equals(intent.getAction())) {
            this.downloadStarted = true;
        } else if (App.ACTION_DOWNLOAD_FINISHED.equals(intent.getAction())) {
            this.downloadFinished = true;
            // contrary to the docs, here EXTRA_FILE contains the file name only, no path.
            this.fileName = intent.getStringExtra(LoaderService.EXTRA_FILE);
        } else if (App.ACTION_DOWNLOAD_RESUMING.equals(intent.getAction())) {
            String msg = intent.getStringExtra("RESUMING");
            assertNotNull(msg);
            int space = msg.lastIndexOf(' ');
            this.downloadResuming = Boolean.parseBoolean(msg.substring(space + 1).trim());
        } else if (App.ACTION_DOWNLOAD_FILE_RENAMED.equals(intent.getAction())) {
            // contrary to the docs, here EXTRA_FILE contains the file name only, no path.
            this.fileName = intent.getStringExtra(LoaderService.EXTRA_FILE);
            android.util.Log.i(getClass().getSimpleName(), "File name is \"" + this.fileName + "\"");
        }
    }

    @Test
    abstract public void execute();

    @WorkerThread
    final void load() {
        this.downloadStarted = this.downloadFinished = false;
        final String url = getUrl();
        android.util.Log.i(getClass().getSimpleName(), "Loading \"" + url + "\"…");
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        for (int i = 0; i < SECS_TO_START; i++) {
            try {
                Thread.sleep(1_000L);
                if (downloadStarted) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Download of " + url + " not started!", this.downloadStarted);
        for (int i = 0; i < SECS_TO_FINISH; i++) {
            try {
                Thread.sleep(1_000L);
                if (downloadFinished) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Download of " + url + " not finished!", this.downloadFinished);
        assertNotNull(fileName);
        File dest = new File(downloadsDir, fileName);
        assertTrue("File " + dest.getName() + " does not exist!", dest.isFile());
        assertTrue("Ancestry has no record about " + dest.getName(), Ancestry.getInstance().knowsFile(dest));
        StackTraceElement ste = Thread.currentThread().getStackTrace()[2];
        android.util.Log.i(getClass().getSimpleName(), "Passed " + ste.getClassName() + "." + ste.getMethodName() + "()");
    }

}

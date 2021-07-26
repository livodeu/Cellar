package net.cellar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

@SuppressWarnings("BusyWait")
@LargeTest
public class BackupTest extends BroadcastReceiver {

    private Context ctx;
    private File file;
    private boolean backupRestoreStarted = false;

    @After
    public void exit() {
        Util.deleteFile(file);
        ctx.unregisterReceiver(this);
    }

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.registerReceiver(this, new IntentFilter(App.ACTION_BACKUP_RESTORE_STARTED));
        file = new File(App.getDownloadsDir(ctx), "test.zip");
    }

    /** {@inheritDoc} */
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(getClass().getSimpleName(), "onReceive(…, " + intent + ")");
        if (App.ACTION_BACKUP_RESTORE_STARTED.equals(intent.getAction())) {
            this.backupRestoreStarted = true;
        }
    }

    /**
     * Requires files in the app's downloads folder.
     */
    @Test
    public void testBackup() {
        backupRestoreStarted = false;
        Intent intent = new Intent(ctx, BackupService.class);
        intent.setAction(BackupService.ACTION_ZIP);
        intent.putExtra(BackupService.EXTRA_DEST, file.getAbsolutePath());
        ctx.startService(intent);
        for (int i = 0; i < 15 && !backupRestoreStarted; i++) {
            Log.i(getClass().getSimpleName(), "Waiting " + i + " secs…");
            try {
                Thread.sleep(1_000L);
            } catch (Throwable ignored) {
            }
        }
        assertTrue(backupRestoreStarted);
    }

    /**
     * Requires a) user interaction and b) an existing zip file (accessible to the documents ui) to succeed!
     */
    @Test
    @FlakyTest
    public void testRestore() {
        backupRestoreStarted = false;
        Intent intentImport = new Intent(ctx, ImportArchiveActivity.class);
        intentImport.setAction(ImportArchiveActivity.ACTION_PICK_ARCHIVE);
        intentImport.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intentImport);
        for (int i = 0; i < 45 && !backupRestoreStarted; i++) {
            Log.i(getClass().getSimpleName(), "Waiting " + i + " secs…");
            try {
                Thread.sleep(1_000L);
            } catch (Throwable ignored) {
            }
        }
        assertTrue(backupRestoreStarted);
    }
}

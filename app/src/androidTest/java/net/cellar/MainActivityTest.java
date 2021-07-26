package net.cellar;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;

import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.net.NetworkChangedReceiver;
import net.cellar.supp.IdSupply;
import net.cellar.supp.Util;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * It is not useful to run all test of this class together because some require a network connection, some require to have no network connection.
 * Thus, it is not possible for all to succeed at one time.<br>
 * Succeeded 2021-07-02 (@Test-annotated methods executed separately)
 */
@LargeTest
public class MainActivityTest extends LoadTest {

    private static final int SECS_TO_START = 5;
    private static final int SECS_TO_USER_INTERACTION = 20;
    private static final String URL_HTTPS = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/ff/Fish_and_chips_blackpool.jpg/550px-Fish_and_chips_blackpool.jpg";
    private static final String URL_HTTPS2 = "https://archive.org/download/mma_cypresses_437980/437980.jpg";

    private String url;

    /**
     * Tests the download of a https resource.
     */
    @Test
    public void execute() {
        url = URL_HTTPS;
        super.load();
    }

    @Override
    @NonNull
    String getUrl() {
        return url;
    }

    @Test
    public void testM3u() {
        url = "https://cdnlive.shooowit.net/rtvalive/smil:channel7.smil/chunklist_b250000.m3u8";
        super.load();
    }

    /**
     * Tests the {@link MainActivity#copyFile(File, File)} method.
     */
    @Test
    public void testCopyFile() {
        File src = null, dest = null;
        try {
            final byte[] data = "Hello World!".getBytes();
            src = File.createTempFile("tmp", null);
            dest = File.createTempFile("tmp", null);
            OutputStream out = new FileOutputStream(src);
            out.write(data);
            Util.close(out);
            assertTrue(MainActivity.copyFile(src, dest));
            assertEquals(data.length, dest.length());
            InputStream in = new FileInputStream(dest);
            byte[] b = new byte[data.length];
            int read = in.read(b);
            Util.close(in);
            assertEquals(data.length, read);
            assertArrayEquals(data, b);
        } catch (Exception e) {
            fail(e.toString());
        } finally {
            Util.deleteFile(dest, src);
        }
    }

    /**
     * Tests that an error notification is displayed when another app sends one of this app's files back to this app.
     */
    @Test
    public void testGotOwnFile() {
        NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        String url = "content://" + BuildConfig.FILEPROVIDER_AUTH + "/file.txt";
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        try {
            Thread.sleep(3_000L);
        } catch (Throwable ignored) {
        }
        StatusBarNotification[] sbns = nm.getActiveNotifications();
        assertNotNull(sbns);
        assertTrue(sbns.length > 0);
        boolean titleMatch = false;
        String expectedTitle = ctx.getString(android.R.string.dialog_alert_title);
        for (StatusBarNotification sbn : sbns) {
            assertNotNull(sbn);
            Notification n = sbn.getNotification();
            assertNotNull(n);
            Bundle b = n.extras;
            assertNotNull(b);
            java.util.Set<String> keys = b.keySet();
            for (String key : keys) {
                Object value = b.get(key);
                //android.util.Log.i(getClass().getSimpleName(), "Key \"" + key + "\"=\"" + value + "\"");
                if (Notification.EXTRA_TITLE.equals(key) && value != null && value.toString().equals(expectedTitle)) {
                    titleMatch = true;
                    break;
                }
            }
            if (titleMatch) break;
        }
        assertTrue("Did not show a message titled " + expectedTitle, titleMatch);
    }

    /**
     * Test loading of device-local content.<br>
     * <b>This requires user interaction!</b><br>
     * The user must select a file from the documents ui within {@link #SECS_TO_USER_INTERACTION} seconds.
     */
    @Test
    @WorkerThread
    @FlakyTest
    public void testLoadContent() {
        Intent intent = new Intent(ctx, GetContentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        for (int i = 0; i < SECS_TO_USER_INTERACTION; i++) {
            try {
                Thread.sleep(1_000L);
                if (downloadStarted) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Download not started!", downloadStarted);
        android.util.Log.i(getClass().getSimpleName(), "Passed " + Thread.currentThread().getStackTrace()[2].getMethodName());
    }

    /**
     * Tests the app's reaction to lacking network connection.
     */
    @Test
    @WorkerThread
    @RequiresApi(Build.VERSION_CODES.O)
    @SdkSuppress(minSdkVersion=Build.VERSION_CODES.O)
    public void testLoadFailNoNetwork() {
        // NetworkChangedReceiver returns a correct value only from API 26 on
        Assume.assumeTrue("This test must be run on a device >= API 26 (O)",Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);
        NetworkInfo.State state = NetworkChangedReceiver.getInstance().getState();
        Assume.assumeTrue("The device must be disconnected for this test to run!",NetworkInfo.State.DISCONNECTED == state);
        this.downloadStarted = this.downloadFinished = false;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(URL_HTTPS));
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
        assertFalse("Download of " + Uri.parse(URL_HTTPS).getLastPathSegment() + " allegedly started!", this.downloadStarted);
        NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        StatusBarNotification[] sbns = nm.getActiveNotifications();
        assertNotNull(sbns);
        assertTrue(sbns.length > 0);
        boolean found = false;
        String expectedMsg = ctx.getString(R.string.msg_network_conn_lost_queue);
        for (StatusBarNotification sbn : sbns) {
            Notification n = sbn.getNotification();
            CharSequence txt = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (txt != null && txt.toString().contains(expectedMsg)) found = true;
        }
        assertTrue("Expected a notification showing \"" + expectedMsg + "\"", found);
        android.util.Log.i(getClass().getSimpleName(), "Passed " + Thread.currentThread().getStackTrace()[2].getMethodName());
    }

    /**
     * Tests that the {@link MainActivity} displays a dialog for confirmation to download multiple files.
     */
    @Test
    public void testMulti() {
        SimpleLife sl = new SimpleLife();
        App app = (App)ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(sl);
        ArrayList<Uri> list = new ArrayList<>(2);
        list.add(Uri.parse(URL_HTTPS));
        list.add(Uri.parse(URL_HTTPS2));
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        try {
            Thread.sleep(3_000L);
        } catch (Throwable ignored) {
        }
        assertTrue(sl.currentActivity instanceof MainActivity);
        MainActivity ma = (MainActivity)sl.currentActivity;
        assertNotNull(ma.dialogConfirmMulti);
        assertTrue(ma.dialogConfirmMulti.isShowing());
        app.unregisterActivityLifecycleCallbacks(sl);
    }

    /**
     * Tests invocation with no data.
     * Should launch {@link UiActivity} then.
     */
    @Test
    public void testNoData() {
        SimpleLife sl = new SimpleLife();
        App app = (App)ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(sl);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setComponent(new ComponentName(ctx.getApplicationContext(), MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        try {
            Thread.sleep(5_000L);
        } catch (Throwable ignored) {
        }
        assertTrue("Did not launch expected Activity", sl.currentActivity instanceof UiActivity);
        app.unregisterActivityLifecycleCallbacks(sl);
    }

    /**
     * Tests that the {@link MainActivity} displays a dialog for entering credentials if required.
     */
    @Test
    public void testRetry401() {
        SimpleLife sl = new SimpleLife();
        App app = (App)ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(sl);
        Uri uri = Uri.parse(URL_HTTPS);
        int downloadId = IdSupply.DOWNLOAD_ID_OFFSET;
        Order order = new Order(uri);
        Delivery.AuthenticateInfo ai = new Delivery.AuthenticateInfo("Basic", "kingdom");
        final Intent intentRetry401 = new Intent(ctx, MainActivity.class);
        intentRetry401.setAction(MainActivity.ACTION_RETRY_401);
        intentRetry401.putExtra(LoaderService.EXTRA_ORDER, order);
        intentRetry401.putExtra(LoaderService.EXTRA_AUTH_REALM, ai.realm);
        intentRetry401.putExtra(LoaderService.EXTRA_AUTH_SCHEME, ai.scheme);
        intentRetry401.putExtra(LoaderService.EXTRA_DOWNLOAD_ID, downloadId);
        intentRetry401.putExtra(LoaderService.EXTRA_NOTIFICATION_ID, IdSupply.completionNotificationId(downloadId));
        intentRetry401.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intentRetry401);
        try {
            Thread.sleep(3_000L);
        } catch (Throwable ignored) {
        }
        assertTrue(sl.currentActivity instanceof MainActivity);
        MainActivity ma = (MainActivity)sl.currentActivity;
        assertNotNull(ma.dialogCredentials);
        assertTrue("Credentials dialog not showing", ma.dialogCredentials.isShowing());
        app.unregisterActivityLifecycleCallbacks(sl);
    }

}

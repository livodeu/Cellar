package net.cellar;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.IdSupply;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests some features of {@link LoaderService}.<br>
 * Succeeded 2021-07-16
 */
@MediumTest
public class LoaderServiceTest implements ServiceConnection {

    private Context ctx;
    private ContextWrapper cw;
    private LoaderService service;

    @After
    public void exit() {
        try {
            if (service != null) cw.unbindService(this);
        } catch (Exception e) {
            Log.e(getClass().getSimpleName(), e.toString());
        }
    }

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cw = new ContextWrapper(ctx);
        cw.bindService(new Intent(ctx, LoaderService.class), this, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((LoaderService.LoaderServiceBinder)binder).getLoaderService();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Test
    public void testNotification() {
        try {
            Thread.sleep(3_000);
        } catch (Exception ignored) {
        }
        assertNotNull(service);
        final int maxLengthTitle = ctx.getResources().getInteger(R.integer.notification_title_maxlength);
        final String url = "https://example.com/afilewithaverylongnamethatwontfitintothetitle.txt";
        final Uri uri = Uri.parse(url);
        final String lastPathSegment = uri.getLastPathSegment();
        final int id = IdSupply.DOWNLOAD_ID_OFFSET;
        final int notificationid = IdSupply.progressNotificationId(id);
        assertNotNull(lastPathSegment);
        Assume.assumeFalse("The file name is too short to test truncation", lastPathSegment.length() < maxLengthTitle);
        Notification n = service.makeNotification(id, uri, null, true, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertNotNull(n.getChannelId());
        }
        assertNotNull(n);
        NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        assertNotNull(nm);
        nm.cancelAll();
        nm.notify(notificationid, n);
        StatusBarNotification[] sbns = nm.getActiveNotifications();
        assertNotNull(sbns);
        assertEquals(1, sbns.length);
        StatusBarNotification sbn = sbns[0];
        Bundle e = sbn.getNotification().extras;
        String title = e.getString("android.title");
        assertNotNull(title);
        assertTrue(title.length() > 0);
        assertEquals(lastPathSegment.charAt(0), title.charAt(0));
        assertTrue(title.length() < maxLengthTitle || (title.length() == maxLengthTitle && title.endsWith("…")));
        assertNull(e.getString("android.text"));
        Notification.Action[] actions = sbn.getNotification().actions;
        assertNotNull(actions);
        assertTrue("Expected at least 3 actions: stop, cancel, defer",actions.length >= 3);
        boolean cancelActionFound = false, deferActionFound = false;
        String sCancel = ctx.getString(android.R.string.cancel);
        String sDefer = ctx.getString(R.string.action_defer);
        for (Notification.Action a : actions) {
            String actiontitle = a.title.toString();
            assertFalse(TextUtils.isEmpty(actiontitle));
            if (sCancel.equals(actiontitle)) cancelActionFound = true;
            else if (sDefer.equals(actiontitle)) deferActionFound = true;
        }
        assertTrue(cancelActionFound);
        assertTrue(deferActionFound);
        nm.cancel(notificationid);
        ((App)ctx.getApplicationContext()).removeNotificationBuilder(id);
    }

    @Test
    public void testWakelock() {
        try {
            Thread.sleep(3_000);
        } catch (Exception ignored) {
        }
        assertNotNull(service);
        assertNull(service.getWakeLock());
        service.keepAwake();
        try {
            Thread.sleep(1_000);
        } catch (Exception ignored) {
        }
        assertNotNull(service.getWakeLock());
        assertTrue(service.getWakeLock().isHeld());
        service.letSleep();
        try {
            Thread.sleep(1_000);
        } catch (Exception ignored) {
        }
        assertNull(service.getWakeLock());
    }
}

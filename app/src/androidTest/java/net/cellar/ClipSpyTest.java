package net.cellar;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.GuardedBy;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.IdSupply;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Succeeded 2021-07-01
 */
@MediumTest
@SdkSuppress(maxSdkVersion = 29)
public class ClipSpyTest implements ClipboardManager.OnPrimaryClipChangedListener {

    private final Object cdsync = new Object();
    private Context ctx;
    private ClipboardManager cm;
    @GuardedBy("cdsync") private volatile ClipData cd;

    private void clearClipboard() {
        if (ctx == null) return;
        if (cm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip();
        } else {
            cm.setPrimaryClip(ClipData.newPlainText(null, null));
        }
    }

    @After
    public void exit() {
        clearClipboard();
        cm.removePrimaryClipChangedListener(this);
    }

    @Before
    public void init() {
        try {
            Looper.prepare();
        } catch (Exception ignored) {
        }
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        cm = (ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cm);
        cm.addPrimaryClipChangedListener(this);
        InputMethodManager im = (InputMethodManager)ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    public void onPrimaryClipChanged() {
        android.util.Log.i(getClass().getSimpleName(), "onPrimaryClipChanged()");
        synchronized (cdsync) {
            cd = cm.getPrimaryClip();
            android.util.Log.i(getClass().getSimpleName(), "onPrimaryClipChanged() - ClipData is " + (cd == null ? "<null>" : cd.toString()));
        }
    }

    /**
     * Tests whether ClipSpy reacts to a change in the system clipboard.
     */
    @Test
    @FlakyTest
    @SdkSuppress(maxSdkVersion = 29)
    public void testReaction() {
        ClipSpy.launch(ctx);
        try {
            Thread.sleep(5_000);
        } catch (Exception e) {
            fail(e.toString());
        }
        final NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        assertNotNull(nm);
        final String url = "https://archive.org/download/1939-advertisement-for-arnotts-biscuits/1939%20advertisement%20for%20Arnott%27s%20biscuits.png";
        Uri uri = Uri.parse(url);
        cm.setPrimaryClip(ClipData.newPlainText("Test", url));
        assertNotNull(cm.getPrimaryClip());

        try {
            Thread.sleep(5_000);
        } catch (Exception e) {
            fail(e.toString());
        }

        ClipData clipData;
        synchronized (cdsync) {
            clipData = cd;
        }
        assertNotNull(clipData);
        assertEquals(1, clipData.getItemCount());
        assertNotNull("Primary clip has no items", clipData.getItemAt(0));
        assertEquals(url, clipData.getItemAt(0).getText());

        try {
            Thread.sleep(5_000);
        } catch (Exception e) {
            fail(e.toString());
        }

        String expectedMsg = ctx.getString(R.string.msg_download, uri.getLastPathSegment(), uri.getHost());
        final StatusBarNotification[] sbn = nm.getActiveNotifications();
        assertNotNull(sbn);
        for (StatusBarNotification sb : sbn) {
            if (IdSupply.NOTIFICATION_ID_CLIPSPY == sb.getId()) {
                Notification n = sb.getNotification();
                assertNotNull(n);
                assertNotNull(n.actions);
                Object txt = n.extras.get(Notification.EXTRA_TEXT);
                assertTrue(txt instanceof CharSequence);
                assertEquals(expectedMsg, txt.toString());
                assertEquals(Notification.CATEGORY_RECOMMENDATION, n.category);
                try {
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                }
                return;
            }
        }
        fail("Notification not shown!");
    }

}

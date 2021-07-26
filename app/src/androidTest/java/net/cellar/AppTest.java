package net.cellar;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.annotation.IntRange;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.auth.AuthManager;
import net.cellar.auth.EncryptionHelper;
import net.cellar.model.Credential;
import net.cellar.model.Order;
import net.cellar.queue.QueueManager;
import net.cellar.supp.IdSupply;
import net.cellar.supp.Util;
import net.cellar.worker.Copier;
import net.cellar.worker.Loader;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Calendar;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests some basic functionality.<br>
 * Succeeded 2021-07-02
 */
@SmallTest
public class AppTest {

    private Context ctx;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testApp() {
        assertTrue(ctx.getApplicationContext() instanceof App);
        final App app = (App)ctx.getApplicationContext();

        // wait for a moment - there is a Thread in App.onCreate()
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            fail(e.toString());
        }

        File downloadsDir = App.getDownloadsDir(ctx);
        assertNotNull(downloadsDir);
        assertTrue(downloadsDir.isDirectory());

        assertEquals(2, App.SUPPORTED_LOCAL_SCHEMES.length);
        assertEquals(4, App.SUPPORTED_REMOTE_SCHEMES.length);
        assertEquals(6, App.SUPPORTED_PREFIXES.length);

        assertNotNull(app.getOkHttpClient());
        assertNotNull(app.getLoaderFactory());
        assertNotNull(app.getProxyPicker());
        assertNotNull(app.getThumbsManager());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertNotNull(app.getNc());
            assertNotNull(app.getNcImportant());
        }
        assertNotNull(app.getEvilBlocker());

        Loader loader = new Copier(IdSupply.DOWNLOAD_ID_OFFSET, ctx, null);
        app.addLoader(IdSupply.DOWNLOAD_ID_OFFSET, loader);
        Loader sameLoader = app.getLoader(IdSupply.DOWNLOAD_ID_OFFSET);
        assertSame(loader, sameLoader);
        app.removeLoader(IdSupply.DOWNLOAD_ID_OFFSET);
        sameLoader = app.getLoader(IdSupply.DOWNLOAD_ID_OFFSET);
        assertNull(sameLoader);

        Notification.Builder builder = new Notification.Builder(ctx);
        app.addNotificationBuilder(IdSupply.DOWNLOAD_ID_OFFSET, builder);
        Notification.Builder sameBuilder = app.getNotificationBuilder(IdSupply.DOWNLOAD_ID_OFFSET);
        assertSame(builder, sameBuilder);
        app.removeNotificationBuilder(IdSupply.DOWNLOAD_ID_OFFSET);
        sameBuilder = app.getNotificationBuilder(IdSupply.DOWNLOAD_ID_OFFSET);
        assertNull(sameBuilder);

        assertFalse(app.hasActiveLoaders());
        File iAmNotHere = new File(ctx.getFilesDir(), "tmp" + System.currentTimeMillis());
        assertFalse(app.isBeingDownloaded(iAmNotHere));
    }

    @Test
    public void testAuthManager() {
        final AuthManager am = AuthManager.getInstance();
        assertNotNull(am);

        assertTrue(AuthManager.isAuthSchemeSupported(AuthManager.SCHEME_BASIC));
        assertTrue(AuthManager.isAuthSchemeSupported(AuthManager.SCHEME_FTP));
        assertTrue(AuthManager.isAuthSchemeSupported(AuthManager.SCHEME_SFTP));

        final Credential c = new Credential("realm" + System.currentTimeMillis(), null, "pwd" + System.currentTimeMillis(), Credential.TYPE_HTTP_BASIC);
        assertFalse(am.getCredentials().contains(c));
        am.addOrReplaceCredential(c);
        assertTrue(am.getCredentials().contains(c));
        am.removeCredential(c);
        assertFalse(am.getCredentials().contains(c));

        while (!am.isSetupFinished()) {
            try {
                //noinspection BusyWait
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                fail(e.toString());
            }
        }

        EncryptionHelper eh = am.getEh();
        assertNotNull("EncryptionHelper is null (DEBUG: " + BuildConfig.DEBUG + ")", eh);
        String s = "secret" + System.currentTimeMillis();
        try {
            byte[] encrypted = eh.encrypt(s.getBytes(StandardCharsets.UTF_8));
            assertNotNull(encrypted);
            assertTrue(encrypted.length > 0);
            CharSequence hex = EncryptionHelper.asHex(encrypted);
            assertNotNull(hex);
            assertEquals("EncryptionHelper.asHex() returned dubious result", encrypted.length << 1, hex.length());
            assertTrue(TextUtils.indexOf(hex, 'g') < 0);
            byte[] decimal = EncryptionHelper.fromHex(hex);
            assertNotNull(decimal);
            assertArrayEquals("EncryptionHelper.fromHex() returned dubious result", encrypted, decimal);
            byte[] decrypted = eh.decrypt(encrypted);
            String decryptedString = new String(decrypted, StandardCharsets.UTF_8);
            assertEquals("EncryptionHelper.decrypt() returned dubious result", s, decryptedString);
        } catch (GeneralSecurityException e) {
            fail(e.toString());
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String uuid = prefs.getString(App.PREF_UUID, null);
        assertNotNull(uuid);
    }

    /**
     * Tests whether {@link ClipSpy} has been disabled on devices running API 30 or above.
     * See {@link App#onCreate()}.
     */
    @Test
    public void testClipSpyState() {
        PackageManager pm = ctx.getPackageManager();
        assertNotNull(pm);
        ComponentName clipSpyComponent = new ComponentName(ctx, ClipSpy.class.getName());
        int clipSpyState = pm.getComponentEnabledSetting(clipSpyComponent);
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, clipSpyState);
        } else {
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, clipSpyState);
        }
    }

    /**
     * Tests {@link net.cellar.R.xml#filepaths}
     */
    @Test
    public void testFilePaths() {
        // Part 1: these are the elements that we need
        final String[] required = new String[] {"downloads", "apk", "backups", "logs", "icons"};
        assertEquals(required.length, App.FilePath.values().length);
        final boolean[] found = new boolean[required.length];
        XmlResourceParser xpp = ctx.getResources().getXml(R.xml.filepaths);
        try {
            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = xpp.getName();
                    assertTrue("paths".equals(tagName) || "files-path".equals(tagName) || "cache-path".equals(tagName));
                    if ("files-path".equals(tagName) || "cache-path".equals(tagName)) {
                        int na = xpp.getAttributeCount();
                        assertEquals(2, na);
                        String a0 = xpp.getAttributeName(0);
                        String a1 = xpp.getAttributeName(1);
                        String v0 = xpp.getAttributeValue(0);
                        String v1 = xpp.getAttributeValue(1);
                        String nameAttr = null;
                        if ("path".equals(a0)) {
                            assertTrue(v0.endsWith("/"));
                            assertEquals("name", a1);
                            nameAttr = v1;
                        } else if ("path".equals(a1)) {
                            assertTrue(v1.endsWith("/"));
                            assertEquals("name", a0);
                            nameAttr = v0;
                        } else {
                            fail("path attr. missing at " + tagName);
                        }
                        for (int i = 0; i < required.length; i++) {
                            if (required[i].equals(nameAttr)) {found[i] = true; break;}
                        }
                    }
                }
                eventType = xpp.next();
            }
            for (int i = 0; i < found.length; i++)  {
                assertTrue("Not found: \"" + required[i] + "\"", found[i]);
            }
        } catch (Exception e) {
            fail(e.toString());
        }
        // Part 2: test the corresponding utility method
        File d0 = Util.getFilePath(ctx, App.FilePath.DOWNLOADS, false);
        assertNotNull(d0);
    }

    @Test
    public void testNightMode() {
        Assume.assumeFalse("Cannot test night mode - device is in battery saver mode", ((PowerManager)ctx.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode());

        SimpleLife sl = new SimpleLife();
        App app = (App)ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(sl);

        final Calendar now = Calendar.getInstance();
        final int nowHour = now.get(Calendar.HOUR_OF_DAY);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        final boolean nightmodeByTimeOfDay = prefs.getBoolean(App.PREF_NIGHT, ctx.getResources().getBoolean(R.bool.night_mode_by_time));

        @IntRange(from = 0, to = 23) final int nightFrom = prefs.getInt(App.PREF_NIGHT_FROM, ctx.getResources().getInteger(R.integer.night_from_default));
        @IntRange(from = 0, to = 23) final int nightTo = prefs.getInt(App.PREF_NIGHT_TO, ctx.getResources().getInteger(R.integer.night_to_default));

        boolean shouldBeNight = nightFrom < nightTo ? (nowHour >= nightFrom && nowHour < nightTo) : (nowHour >= nightFrom || nowHour < nightTo);

        Intent intent = new Intent(ctx, UiActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);

        try {
            Thread.sleep(3_000L);
        } catch (Throwable ignored) {
        }

        assertTrue(sl.currentActivity instanceof UiActivity);
        UiActivity uia = (UiActivity)sl.currentActivity;

        if (nightmodeByTimeOfDay) {
            int currentNightMode = uia.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (shouldBeNight) {
                assertEquals(Configuration.UI_MODE_NIGHT_YES, currentNightMode);
            } else {
                assertEquals(Configuration.UI_MODE_NIGHT_NO, currentNightMode);
            }
        } else {
            AppCompatDelegate delegate = uia.getDelegate();
            assertNotNull(delegate);
            assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, delegate.getLocalNightMode());
        }
    }

    @Test
    public void testSingletons() {
        assertNotNull(Ancestry.getInstance());
        assertNotNull(AuthManager.getInstance());
        assertNotNull(QueueManager.getInstance());
    }

    /**
     * Tests removal of user id and password from a url.
     * See {@link Order#stripCredentials(Order)}.
     */
    @Test
    public void testStripCredentials() {
        Uri uriWithCredentials = Uri.parse("https://user:password@host.com/path/file.txt");
        final Order orderWithCredentials = new Order(uriWithCredentials);
        orderWithCredentials.setDestinationFolder(App.getDownloadsDir(ctx).getAbsolutePath());
        orderWithCredentials.setDestinationFilename("file.txt");
        orderWithCredentials.setMime("text/plain");
        final Order stripped = Order.stripCredentials(orderWithCredentials);
        String strippedUrl = stripped.getUri().toString();
        assertEquals("Got: " + strippedUrl, strippedUrl, "https://host.com/path/file.txt");
        assertEquals(orderWithCredentials.getDestinationFilename(), stripped.getDestinationFilename());
        assertEquals(orderWithCredentials.getDestinationFolder(), stripped.getDestinationFolder());
        assertEquals(orderWithCredentials.getMime(), stripped.getMime());
    }
}

package net.cellar;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreferenceDialogFragmentCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the blacklist selection.<br>
 * Succeeded 2021-07-06
 */
@MediumTest
public class BlacklistTest {

    private Context ctx;

    @Before
    public void init() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /**
     * Make sure that there are valid blacklist entries in the resources.
     */
    @Test
    public void testBlacklistRes() {
        final Locale[] locales = new Locale[] {Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN};
        final Resources r = ctx.getResources();
        try {
            final String[] lists = r.getStringArray(R.array.entryvalues_list_evil);
            assertNotNull(lists);
            final int n = lists.length;
            assertTrue(n >= 2);
            assertTrue(TextUtils.isEmpty(lists[0]));
            for (int i = 1; i < n; i++) {
                assertTrue(lists[i].startsWith("https://"));
            }
            final Configuration c = r.getConfiguration();
            final Locale defaultLocale = c.locale;
            for (Locale locale : locales) {
                c.setLocale(locale);
                r.updateConfiguration(c, r.getDisplayMetrics());
                String[] labels = r.getStringArray(R.array.entries_list_evil);
                assertNotNull(labels);
                assertEquals(n, labels.length);
                for (String label : labels) {
                    assertFalse(label.startsWith("https://"));
                }
            }
            c.setLocale(defaultLocale);
            r.updateConfiguration(c, r.getDisplayMetrics());
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    /**
     * Tests that evil hosts are blocked (regarding HTTP/HTTPS only).
     * See {@link App#makeOkhttpClient()}.
     */
    @Test
    public void testDns() {
        App app = (App)ctx.getApplicationContext();
        try {
            // wait for a moment - there is a Thread in App.onCreate()
            Thread.sleep(1_500);
            //
            final String evil = "googletagmanager.com";
            if (TextUtils.isEmpty(PreferenceManager.getDefaultSharedPreferences(app).getString(App.PREF_BLACKLIST, App.PREF_BLACKLIST_DEFAULT))) {
                assertFalse(app.getEvilBlocker().isEvil(evil));
                List<InetAddress> ias = app.getOkHttpClient().dns().lookup(evil);
                assertNotNull(ias);
                return;
            }
            assertTrue(evil + " is allegedly not evil", app.getEvilBlocker().isEvil(evil));
            List<InetAddress> ias = app.getOkHttpClient().dns().lookup(evil);
            fail("Got Inetaddresses for " + evil + ": " + ias);
        } catch (InterruptedException e) {
            fail(e.toString());
        } catch (UnknownHostException e) {
            // if a blacklist is set, then the test is successful when we are here
            android.util.Log.i(AppTest.class.getSimpleName(), e.toString());
        }
    }

    /**
     * Selects a blacklist from the available ones via the ListPreference dialog.
     */
    @Test
    public void testSelection() {

        SimpleLife sl = new SimpleLife();
        App app = (App)ctx.getApplicationContext();
        app.registerActivityLifecycleCallbacks(sl);

        // randomly select one of the blacklists
        final String[] lists = ctx.getResources().getStringArray(R.array.entryvalues_list_evil);
        final int indexToSelect = (int)(Math.random() * lists.length);

        try {
            Looper.prepare();

            Handler handler = new Handler();

            // launch SettingsActivity
            Intent intent = new Intent(ctx, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);

            try {
                Thread.sleep(3_000L);
            } catch (Throwable ignored) {
            }

            assertTrue(sl.currentActivity instanceof SettingsActivity);
            SettingsActivity sa = (SettingsActivity)sl.currentActivity;

            SettingsActivity.SettingsFragment settingsFragment = sa.settingsFragment;
            assertNotNull(settingsFragment);

            // find the blacklist preference
            Preference p = settingsFragment.findPreference(App.PREF_BLACKLIST);
            assertNotNull(p);

            // display the dialog
            settingsFragment.onDisplayPreferenceDialog(p);

            try {
                Thread.sleep(2_000L);
            } catch (Throwable ignored) {
            }

            // fake selection by setting the "mClickedDialogEntryIndex" field
            Fragment df = settingsFragment.getParentFragmentManager().findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
            assertNotNull(df);
            assertTrue(df instanceof ListPreferenceDialogFragmentCompat);
            ListPreferenceDialogFragmentCompat lpdf = (ListPreferenceDialogFragmentCompat)df;
            try {
                Field f = ListPreferenceDialogFragmentCompat.class.getDeclaredField("mClickedDialogEntryIndex");
                f.setAccessible(true);
                f.set(lpdf, indexToSelect);
            } catch (Exception e) {
                fail(e.toString());
            }

            // accept and close
            handler.post(() -> {
                lpdf.onClick(null, DialogInterface.BUTTON_POSITIVE);
                lpdf.onDialogClosed(true);
            });

            try {
                Thread.sleep(2_000L);
            } catch (Throwable ignored) {
            }

            handler.post(() -> assertEquals(ctx.getResources().getStringArray(R.array.entries_list_evil)[indexToSelect], p.getSummary()));

            try {
                Thread.sleep(1_000L);
            } catch (Throwable ignored) {
            }

        } catch (Exception e) {
            fail(e.toString());
        }

    }

}

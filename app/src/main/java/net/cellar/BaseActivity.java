/*
 * BaseActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.transition.Slide;
import android.transition.Transition;
import android.view.Gravity;
import android.view.Window;

import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import net.cellar.supp.UriUtil;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;

/**
 *
 */
public abstract class BaseActivity extends AppCompatActivity {

    private static final File[] EMPTY_FILES_ARRAY = new File[0];

    /**
     * Returns the Uri that is currently in the clipboard.
     * @param cm ClipboardManager
     * @return Uri or {@code null}
     * @throws NullPointerException if {@code cm} is {@code null}
     */
    @Nullable
    protected static Uri getUriFromClipboard(@NonNull ClipboardManager cm) {
        ClipData cd = cm.getPrimaryClip();
        if (cd == null) return null;
        final int nc = cd.getItemCount();
        for (int i = 0; i < nc; i++) {
            ClipData.Item clipboardItem = cd.getItemAt(i);
            if (clipboardItem == null) continue;
            Uri uri = clipboardItem.getUri();
            final CharSequence txt = uri != null ? uri.toString() : clipboardItem.getText();
            if (txt == null || TextUtils.getTrimmedLength(txt) <= 6) {
                continue;
            }
            final String urls = txt.toString().trim();
            // Patterns.WEB_URL does not work because it contains rtsp but not ftp; it also accepts urls that end with a slash
            if (!UriUtil.isRemoteUrl(urls)) {
                return null;
            }
            if (urls.endsWith("/")) {
                Uri.parse(urls.substring(0, urls.length() - 1));
            }
            return Uri.parse(urls);
        }
        return null;
    }

    /**
     * Sets the Window enter and exit transitions.
     * @param a Activity
     * @param enter Transition ({@code null} for default behaviour)
     * @param exit Transition ({@code null} for default behaviour)
     * @return Window (for possible re-use)
     * @throws NullPointerException if {@code a} is {@code null}
     */
    static Window setAnimations(@NonNull Activity a, @Nullable Transition enter, @Nullable Transition exit) {
        final Window window = a.getWindow();
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        window.setAllowEnterTransitionOverlap(true);
        window.setAllowReturnTransitionOverlap(true);
        window.setEnterTransition(enter != null ? enter : new Slide(Gravity.END));
        window.setExitTransition(exit != null ? exit : new Slide(Gravity.START));
        return window;
    }

    /**
     * Sets the Window default enter and exit transitions.
     * @param a Activity
     * @return Window (for possible re-use)
     * @throws NullPointerException if {@code a} is {@code null}
     */
    public static Window setAnimations(@NonNull Activity a) {
        return setAnimations(a, null, null);
    }

    /**
     * Applies the dark or light mode according to the preferences.
     * @param a Activity to apply the dark mode to
     */
    public static void setDarkMode(@Nullable final AppCompatActivity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) return;
        final Calendar cal = Calendar.getInstance();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        final Resources res = a.getResources();
        @IntRange(from = 0, to = 23) final int nightFrom = prefs.getInt(App.PREF_NIGHT_FROM, res.getInteger(R.integer.night_from_default));
        @IntRange(from = 0, to = 23) final int nightTo = prefs.getInt(App.PREF_NIGHT_TO, res.getInteger(R.integer.night_to_default));
        @AppCompatDelegate.NightMode final int localNightMode = a.getDelegate().getLocalNightMode();
        if (prefs.getBoolean(App.PREF_NIGHT, res.getBoolean(R.bool.night_mode_by_time)) && nightFrom != nightTo) {
            // the user has requested to toggle the night mode by time of day
            final int h = cal.get(Calendar.HOUR_OF_DAY);
            final boolean night = nightFrom < nightTo ? (h >= nightFrom && h < nightTo) : (h >= nightFrom || h < nightTo);
            if (night && localNightMode != AppCompatDelegate.MODE_NIGHT_YES
                    || !night && localNightMode != AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY) {
                a.getDelegate().setLocalNightMode(night ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
            }
        } else {
            // night mode will follow the system
            if (localNightMode != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
                a.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            }
        }

        if (a instanceof BaseActivity) {
            BaseActivity ba = (BaseActivity)a;
            ba.handler.removeCallbacks(ba.nightmodeChecker);
            if (!a.isDestroyed()) {
                int secondsToFullHour = 60 - cal.get(Calendar.SECOND) + (59 - cal.get(Calendar.MINUTE)) * 60;
                ba.handler.postDelayed(ba.nightmodeChecker, secondsToFullHour * 1_000L + 2_000L);
            }
        }
    }

    protected final Handler handler = new Handler();
    @GuardedBy("downloads")
    final ArrayList<File> downloads = new ArrayList<>();
    /** Calls {@link #setDarkMode(AppCompatActivity)} shortly after every full hour */
    @NonNull private final NightmodeChecker nightmodeChecker;
    /** the directory where the downloads are stored */
    private File dir;
    /**
     * Constructor.
     */
    BaseActivity() {
        super();
        this.nightmodeChecker = new NightmodeChecker(this);
    }

    /**
     * Clears the clipboard. On versions before {@link Build.VERSION_CODES#P P} (API 28), this is done by pasting empty text to it.
     */
    protected final void clearClipboard() {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip();
        } else {
            cm.setPrimaryClip(ClipData.newPlainText(null, null));
        }
    }

    /**
     * The clipboard contents have changed.
     */
    protected void clipboardChanged() {
    }

    /** {@inheritDoc} */
    @CallSuper
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setAnimations(this);
        setDarkMode(this);
        super.onCreate(savedInstanceState);
        this.dir = App.getDownloadsDir(this);
    }

    /** {@inheritDoc} */
    @Override
    @CallSuper
    protected void onDestroy() {
        this.handler.removeCallbacks(this.nightmodeChecker);
        super.onDestroy();
    }

    /**
     * Refreshes the data.
     * @return number of downloads
     */
    @CallSuper
    @IntRange(from = 0)
    int refresh() {
        final File[] files = this.dir.isDirectory() ? this.dir.listFiles() : EMPTY_FILES_ARRAY;
        final int n = files != null ? files.length : 0;
        if (n == 0) {
            synchronized (this.downloads) {
                this.downloads.clear();
            }
            return 0;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        @App.SortMode final int sort = prefs.getInt(App.PREF_SORT, App.SORT_DATE);
        final boolean inv = prefs.getBoolean(App.PREF_SORT_INV, false);
        switch (sort) {
            case App.SORT_NAME:
                // ascending (arrow up): Z at the top; descending (arrow down): numbers at the top, followed by A
                if (inv) Arrays.sort(files, (o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));
                else Arrays.sort(files, (o1, o2) -> o2.getName().compareToIgnoreCase(o1.getName()));
                break;
            case App.SORT_SIZE:
                // ascending (arrow up): largest at the top; descending (arrow down): smallest at the top
                if (inv) Arrays.sort(files, Comparator.comparingLong(File::length));
                else Arrays.sort(files, (o1, o2) -> Long.compare(o2.length(), o1.length()));
                break;
            case App.SORT_DATE:
            default:
                // ascending (arrow up): newest at the top; descending (arrow down): oldest at the top
                if (inv) Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                else Arrays.sort(files, (o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
        }
        synchronized (this.downloads) {
            this.downloads.clear();
            this.downloads.ensureCapacity(n);
            this.downloads.addAll(Arrays.asList(files));
        }
        return n;
    }

    /**
     * Calls {@link #setDarkMode(AppCompatActivity)}.
     */
    private static class NightmodeChecker implements Runnable {

        private final Reference<AppCompatActivity> ref;

        private NightmodeChecker(@NonNull AppCompatActivity appCompatActivity) {
            super();
            this.ref = new WeakReference<>(appCompatActivity);
        }

        /** {@inheritDoc} */
        @Override
        public void run() {
            AppCompatActivity appCompatActivity = this.ref.get();
            if (appCompatActivity == null) return;
            setDarkMode(appCompatActivity);
        }
    }

    /**
     * This class exists to avoid memory leaks when the {@link ClipboardManager} holds on to a reference to a listener.
     */
    protected static class ClipboardListener implements ClipboardManager.OnPrimaryClipChangedListener {

        private final App app;
        private final Reference<BaseActivity> refa;

        /**
         * Private constructor.
         * @param activity UiActivity
         */
        protected ClipboardListener(@NonNull BaseActivity activity) {
            super();
            this.app = (App)activity.getApplicationContext();
            this.refa = new WeakReference<>(activity);
        }

        /** {@inheritDoc} */
        @Override
        public final void onPrimaryClipChanged() {
            BaseActivity activity = this.refa.get();
            if (activity == null) return;
            if (activity.isDestroyed()) {
                this.refa.clear();
                return;
            }
            activity.clipboardChanged();
        }

        protected final void register() {
            BaseActivity activity = this.refa.get();
            if (activity == null) return;
            ClipboardManager cm = (ClipboardManager)this.app.getSystemService(CLIPBOARD_SERVICE);
            cm.addPrimaryClipChangedListener(this);
        }

        protected final void unregister() {
            BaseActivity activity = this.refa.get();
            if (activity == null) return;
            ClipboardManager cm = (ClipboardManager)this.app.getSystemService(CLIPBOARD_SERVICE);
            cm.removePrimaryClipChangedListener(this);
            if (activity.isFinishing()) {
                this.refa.clear();
            }
        }
    }
}

/*
 * ClipSpy.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.cellar.supp.IdSupply;
import net.cellar.supp.Log;
import net.cellar.supp.UiUtil;
import net.cellar.supp.UriHandler;
import net.cellar.supp.Util;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

import static net.cellar.BaseActivity.getUriFromClipboard;

/**
 * Monitors the system clipboard for uris that represent suitable downloadable resources.
 * The boolean preference {@link App#PREF_CLIPSPY PREF_CLIPSPY} controls whether this Service runs.<br>
 * This service should be active while {@link UiActivity} is active.<br>
 * Only for API up to 29.<br>
 * See: <a href="https://source.android.com/setup/start/android-10-release#restrict_app_clipboard_access">https://source.android.com/setup/start/android-10-release#restrict_app_clipboard_access</a>
 */
public final class ClipSpy extends Service implements ClipboardManager.OnPrimaryClipChangedListener {

    private static final String ACTION_NOTHANKS = BuildConfig.APPLICATION_ID + ".nothanksclipspy";
    private static final String ACTION_START = BuildConfig.APPLICATION_ID + ".startclipspy";
    private static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".stopclipspy";
    private static final String ACTION_YESPLEASE = BuildConfig.APPLICATION_ID + ".yespleaseclipspy";
    /** boolean to be passed along {@link #ACTION_STOP} to indicate that the {@link App#PREF_CLIPSPY} flag should be set accordingly */
    private static final String EXTRA_FLIP_PREF = BuildConfig.APPLICATION_ID + ".flippref";
    /** after this period of time the download offer will be retracted */
    private static final long OFFER_DURATION = 30_000L;
    private static final String TAG = "ClipSpy";

    /**
     * Stops this Service (without altering the preferences).
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    static void abort(@NonNull Context ctx) {
        try {
            Intent intent = new Intent(ctx, ClipSpy.class);
            intent.setAction(ACTION_STOP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
        }
    }

    /**
     * Determines whether the given Uri is suitable to offer a download.
     * @param clip Uri to check
     * @return true / false
     */
    static boolean isAcceptable(@Nullable final Uri clip) {
        if (clip == null) return false;
        UriHandler uh = UriHandler.checkUri(clip);
        if (uh != null && uh.hasLoader()) return true;
        // only offer if an acceptable MIME type is detected (for example, don't offer HTML pages)
        final String mime = Util.getMime(clip);
        if (mime == null) return false;
        for (String m : App.MIMES_AUTO) {
            if (mime.startsWith(m)) return true;
        }
        return false;
    }

    /**
     * Starts this Service.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    static void launch(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Attempted to launch " + ClipSpy.class.getSimpleName() + " on API " + Build.VERSION.SDK_INT);
            return;
        }
        try {
            final Intent intent = new Intent(ctx, ClipSpy.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
        }
    }

    private final Handler handler = new Handler();
    private final ClipSpy.ClipSpyBinder binder = new ClipSpy.ClipSpyBinder(this);
    private Notification.Builder stdNotificationBuilder;
    /** Replaces the crossed-out clipboard icon with the std. icon */
    private final Runnable watchingNotificationResetter = () -> {
        ClipSpy.this.stdNotificationBuilder.setSmallIcon(R.drawable.ic_n_clip);
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(IdSupply.NOTIFICATION_ID_CLIPSPY, ClipSpy.this.stdNotificationBuilder.build());
    };
    private Intent intentNo;
    private ClipboardManager cm;
    private boolean registered;
    private boolean active;
    private Runnable canceller;

    @NonNull
    private Notification makeStandardNotification() {
        if (this.stdNotificationBuilder == null) {
            this.stdNotificationBuilder = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_n_clip)
                    .setContentText(getString(R.string.msg_clipspy_monitoring))
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setShowWhen(false)
            ;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.stdNotificationBuilder.setChannelId(((App)getApplicationContext()).getNc().getId());
            }
            Intent intentStop = new Intent(this, ClipSpy.class);
            intentStop.setAction(ACTION_STOP);
            intentStop.putExtra(EXTRA_FLIP_PREF, Boolean.TRUE);
            PendingIntent pi = PendingIntent.getService(this, 1, intentStop, PendingIntent.FLAG_UPDATE_CURRENT);
            this.stdNotificationBuilder.addAction(UiUtil.makeNotificationAction(this, R.drawable.ic_baseline_stop_24, R.string.action_stop, pi));
        }
        return this.stdNotificationBuilder.build();
    }

    /** {@inheritDoc} */
    @Override
    public IBinder onBind(Intent intent) {
        return this.binder;
    }

    /** {@inheritDoc} */
    @Override
    public void onCreate() {
        this.cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (this.cm == null) {stopSelf(); return;}
        this.cm.addPrimaryClipChangedListener(this);
        this.registered = true;
        this.active = true;

        this.intentNo = new Intent(this, ClipSpy.class);
        this.intentNo.setAction(ACTION_NOTHANKS);
        this.canceller = () -> startService(this.intentNo);
    }

    /** {@inheritDoc} */
    @Override
    public void onDestroy() {
        if (this.cm != null) {
            if (this.registered) this.cm.removePrimaryClipChangedListener(this);
            this.registered = false;
            this.cm = null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onPrimaryClipChanged() {
        if (BuildConfig.DEBUG) Log.i(TAG, "onPrimaryClipChanged() - active: " + this.active);
        if (!this.active) return;

        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        final Uri clip = getUriFromClipboard(this.cm);
        if (!isAcceptable(clip)) {
            if (this.stdNotificationBuilder != null) {
                this.handler.removeCallbacks(this.watchingNotificationResetter);
                this.stdNotificationBuilder.setSmallIcon(R.drawable.ic_n_noclip);
                nm.notify(IdSupply.NOTIFICATION_ID_CLIPSPY, this.stdNotificationBuilder.build());
                this.handler.postDelayed(this.watchingNotificationResetter, 1_000L);
            }
            return;
        }

        final String lps = clip.getLastPathSegment();
        UriHandler uh = UriHandler.checkUri(clip);
        final CharSequence title = (uh != null ? uh.getTitle() : null);

        String msg = getString(R.string.msg_download, title != null ? title : lps, clip.getHost());
        String msgShort = getString(R.string.msg_download_short, title != null ? title : lps);
        final Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_n_clip)
                .setContentText(msg)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                ;
        builder.setStyle(new Notification.BigTextStyle().bigText(msg).setBigContentTitle(msgShort));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(((App)getApplicationContext()).getNcImportant().getId());
        } else {
            builder.setPriority(Notification.PRIORITY_MAX);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setUsesChronometer(true).setChronometerCountDown(true).setWhen(System.currentTimeMillis() + OFFER_DURATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowSystemGeneratedContextualActions(false);
        }
        builder.setColor(getResources().getColor(R.color.colorSecondary));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColorized(true);
        }

        PendingIntent piYes, piNo;

        Intent intentYes = new Intent(this, ClipSpy.class);
        intentYes.setAction(ACTION_YESPLEASE);
        intentYes.setData(clip);
        if (title != null) intentYes.putExtra(Intent.EXTRA_TITLE, title);
        piYes = PendingIntent.getService(this, 1, intentYes, 0);

        piNo = PendingIntent.getService(this, 1, this.intentNo, 0);

        builder.addAction(UiUtil.makeNotificationAction(this, R.drawable.ic_file_download_black_24dp, R.string.label_yes, piYes));
        builder.addAction(UiUtil.makeNotificationAction(this, android.R.drawable.ic_delete, R.string.label_no_thanks, piNo));

        nm.notify(IdSupply.NOTIFICATION_ID_CLIPSPY, builder.build());

        this.handler.removeCallbacks(this.canceller);
        this.handler.postDelayed(this.canceller, OFFER_DURATION);
    }

    /** {@inheritDoc} */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (BuildConfig.DEBUG) Log.i(TAG, "onStartCommand(" + intent + ", …)");
        if (intent == null) return super.onStartCommand(null, flags, startId);
        final String action = intent.getAction();
        if (ACTION_YESPLEASE.equals(action)) {
            this.handler.removeCallbacks(this.canceller);
            final Uri data = intent.getData();
            final String title = intent.getStringExtra(Intent.EXTRA_TITLE);
            // pass it on to the MainActivity
            final Intent intentMain = new Intent(this, MainActivity.class);
            intentMain.setAction(Intent.ACTION_VIEW);
            intentMain.setData(data);
            if (title != null) intentMain.putExtra(Intent.EXTRA_TITLE, title);
            intentMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intentMain);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            }
            // reset the notification
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(IdSupply.NOTIFICATION_ID_CLIPSPY, makeStandardNotification());
            //
            resetClipboard();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            start();
            return START_REDELIVER_INTENT;
        }
        if (ACTION_STOP.equals(action)) {
            this.handler.removeCallbacks(this.canceller);
            stop(intent.getBooleanExtra(EXTRA_FLIP_PREF, false));
            return START_NOT_STICKY;
        }
        if (ACTION_NOTHANKS.equals(action)) {
            this.handler.removeCallbacks(this.canceller);
            // reset the notification
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(IdSupply.NOTIFICATION_ID_CLIPSPY, makeStandardNotification());
            resetClipboard();
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * Clears the clipboard
     */
    private void resetClipboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            this.cm.clearPrimaryClip();
        } else {
            this.cm.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    void setActive(boolean active) {
        this.active = active;
        if (this.active) {
            start();
        } else {
            stopForeground(true);
        }
    }

    private void start() {
        Notification n = makeStandardNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(IdSupply.NOTIFICATION_ID_CLIPSPY, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(IdSupply.NOTIFICATION_ID_CLIPSPY, n);
        }
        if (!this.registered) {
            this.cm.addPrimaryClipChangedListener(this);
            this.registered = true;
        }
    }

    private void stop(boolean flipPref) {
        if (flipPref) {
            SharedPreferences.Editor ed = PreferenceManager.getDefaultSharedPreferences(this).edit();
            ed.putBoolean(App.PREF_CLIPSPY, false);
            ed.apply();
            // now the App's OnSharedPreferenceChangeListener should take care of stopping this Service (and we'll be in the else branch below in a few ms).
        } else {
            stopForeground(true);
            stopSelf();
        }
    }

    /**
     * The Binder implementation.
     */
    static final class ClipSpyBinder extends Binder {

        @NonNull
        private final Reference<ClipSpy> refService;

        /**
         * Constructor.
         * @param service ClipSpy
         */
        private ClipSpyBinder(@NonNull ClipSpy service) {
            super();
            this.refService = new WeakReference<>(service);
        }

        /**
         * @return ClipSpy
         */
        @Nullable
        ClipSpy getClipSpy() {
            return this.refService.get();
        }

        /** {@inheritDoc} */
        @NonNull
        @Override
        public String toString() {
            return getClass().getSimpleName();
        }
    }

}

/*
 * MainActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import net.cellar.auth.AuthManager;
import net.cellar.model.Credential;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.model.UriPair;
import net.cellar.model.Wish;
import net.cellar.model.pl.M3UPlaylist;
import net.cellar.model.pl.Playlist;
import net.cellar.model.pl.PlaylistItem;
import net.cellar.net.NetworkChangedReceiver;
import net.cellar.queue.QueueManager;
import net.cellar.supp.DebugUtil;
import net.cellar.supp.IdSupply;
import net.cellar.supp.Log;
import net.cellar.supp.UiUtil;
import net.cellar.supp.UriHandler;
import net.cellar.supp.UriUtil;
import net.cellar.supp.Util;
import net.cellar.worker.Copier;
import net.cellar.worker.Downloader;
import net.cellar.worker.LoaderListener;
import net.cellar.worker.PlaylistParser;
import net.cellar.worker.Streamer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ServiceConnection {

    /** a Wish has just been popped off the queue - we should try to load it */
    public static final String ACTION_PROCESS_WISH = BuildConfig.APPLICATION_ID + ".process_wish";
    /** the download has failed with http code 401 - the user may enter credentials and try again */
    static final String ACTION_RETRY_401 = BuildConfig.APPLICATION_ID + ".retry401";
    /** the download has failed - the user may try again */
    static final String ACTION_RETRY = BuildConfig.APPLICATION_ID + ".retry";
    /** Wish */
    public static final String EXTRA_WISH = BuildConfig.APPLICATION_ID + ".wish";
    /** Parcelable Arraylist */
    private static final String EXTRA_WISHES = BuildConfig.APPLICATION_ID + ".extra_add_wishes";

    private static final String ACTION_ADD_TO_QUEUE = BuildConfig.APPLICATION_ID + ".add_to_queue";
    private static final String ACTION_JUST_REMOVE_NOTIFICATION = BuildConfig.APPLICATION_ID + ".zilch";
    private static final String STATE_WISH = "wish";
    private static final String TAG = "MainActivity";

    /**
     * Copies a file. Deals with files directly, so probably not suited to access something out the app's reach.
     * @param source source file
     * @param destination destination file
     * @return {@code true} if all went well
     */
    @VisibleForTesting
    public static boolean copyFile(@NonNull File source, @NonNull File destination) {
        OutputStream out = null;
        InputStream in = null;
        boolean copied = false;
        boolean destinationExistedBefore = destination.isFile();
        try {
            out = new FileOutputStream(destination);
            if (source.length() > 0L) {
                in = new FileInputStream(source);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FileUtils.copy(in, out);
                } else {
                    final byte[] buf = new byte[Math.min((int) source.length(), 8192)];
                    for (; ; ) {
                        int read = in.read(buf);
                        if (read <= 0) break;
                        out.write(buf, 0, read);
                    }
                }
            }
            copied = true;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While copying " + source + " to " + destination + ": " + e.toString());
        } finally {
            Util.close(in, out);
            if (!copied && !destinationExistedBefore) Util.deleteFile(destination);
        }
        return copied;
    }

    /**
     * Displays an error notification.<br>
     * See <a href="https://developer.android.com/guide/topics/ui/notifiers/notifications">here</a>.
     * @param ctx Context
     * @param msg message
     * @throws NullPointerException if any parameter is {@code null}
     */
    private static void notifyError(@NonNull Context ctx, @NonNull final CharSequence msg) {
        App app = (App)ctx.getApplicationContext();
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        final Notification.Builder builder = new Notification.Builder(app)
                .setDefaults(Notification.DEFAULT_SOUND)
                .setSmallIcon(R.drawable.ic_n_error)
                .setContentTitle(ctx.getString(android.R.string.dialog_alert_title))
                .setSubText(null)
                .setContentText(msg)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setOngoing(false);
        if (msg.length() > 32 || TextUtils.indexOf(msg, '\n') >= 0) {
            builder.setStyle(new Notification.BigTextStyle(builder).bigText(msg));
        }
        @ColorInt int color = ctx.getResources().getColor(R.color.colorAccent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColor(color)
                    .setColorized(true)
                    .setTimeoutAfter(20_000L)
                    .setChannelId(app.getNc().getId());
        } else {
            builder.setColor(color)
                    .setPriority(Notification.PRIORITY_HIGH);
        }
        nm.notify(App.NOTIFICATION_ID, builder.build());
    }

    /** the relevant contents of the Intent that was passed to this Activity are stored in this thing */
    private final Wish wish = new Wish(Uri.EMPTY);
    private Wish[] multiWishes = null;
    /**  the service that we bind to and that is responsible for the actual download */
    private LoaderService service;
    /** although lint might warn about this, this can NOT be a local variable - it is required to hold a reference while the Downloader is working because the Loader only holds a WeakReference to it! */
    private LoaderListener playlistLoaderListener;
    /** the dialog that allows the user to pick a stream variant */
    @VisibleForTesting
    public AlertDialog dialogVariantSelector;
    /** the dialog that allows the user to enter credentials */
    @VisibleForTesting
    public AlertDialog dialogCredentials;
    @VisibleForTesting
    public AlertDialog dialogConfirmMulti;
    /** the Order to retry when Credentials are required */
    private Order retryOrder;
    /** the realm that the Credentials must be applied to */
    private String retryOrderRealm;
    private String retryOrderAuthScheme;
    private String retryOrderAuthUser;
    private int retryOrderId = -1;

    /**
     * Finds out whether some naughty app tried to pass one of our own files to us again.
     * We don't like that.
     * An error notification will be displayed accordingly.
     * @return {@code true} if it did
     */
    private boolean gotOwnFile() {
        final String host = this.wish.getUri().getHost();
        if (BuildConfig.FILEPROVIDER_AUTH.equals(host) || BuildConfig.DOCSPROVIDER_AUTH.equals(host)) {
            // some activity called us with "content://net.cellar.fileprovider/…" or "content://net.cellar.dogs/…"
            Uri referrer = null;
            String badapp = null;
            String badpackage = null;
            Intent intent = getIntent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                referrer = getReferrer();
            }
            if (referrer == null) {
                referrer = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
            }
            if (referrer != null && "android-app".equals(referrer.getScheme())) {
                badpackage = referrer.getHost();
            }
            if (TextUtils.isEmpty(badpackage) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                badpackage = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME);
            }
            if (TextUtils.isEmpty(badpackage)) {
                badpackage = intent.getStringExtra("com.android.browser.application_id");
            }
            if (!TextUtils.isEmpty(badpackage)) {
                ApplicationInfo ai;
                try {
                    //noinspection ConstantConditions
                    ai = getPackageManager().getApplicationInfo(badpackage, 0);
                    CharSequence label = getPackageManager().getApplicationLabel(ai);
                    badapp = label.toString();
                } catch (Exception ignored) {
                }
                if (badapp == null) badapp = badpackage;
            }
            String msg;
            String iam = getString(R.string.app_name);
            if (!TextUtils.isEmpty(badapp)) {
                msg = getString(R.string.error_own_file, badapp, iam, iam);
            } else {
                msg = getString(R.string.error_own_file, getString(R.string.error_own_file_naughty), iam, iam);
            }
            notifyError(this, msg);
            return true;
        }
        return false;
    }

    /**
     * Handles the Intent that this Activity has received.<br>
     * It contains all the data that we have at our disposal at the moment.<br>
     * Based on the data contained in the Intent, the instance variable {@link #wish} is filled.
     * @param intent Intent
     */
    private void handleIntent(@Nullable Intent intent) {
        if (BuildConfig.DEBUG) Log.i(TAG, "handleIntent(" + intent + ")");
        if (intent == null) {
            setResult(RESULT_CANCELED);
            finishAndRemoveTask();
            return;
        }
        final String action = intent.getAction();

        if (BuildConfig.DEBUG) {
            try {
                DebugUtil.logIntent(TAG, intent, Util.getReferrer(this));
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            }
        }

        if (Intent.ACTION_VIEW.equals(action)
                && intent.getType() != null && intent.getType().startsWith("vnd.")) {
            intent.setComponent(null);
            final Intent chooserIntent = Intent.createChooser(intent, null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {new ComponentName(this, MainActivity.class)});
            }
            startActivity(chooserIntent);

            setResult(RESULT_CANCELED);
            finishAndRemoveTask();
            return;
        }

        // if we got a notification id, this means that the corresponding notification should be removed
        int notificationId = intent.getIntExtra(LoaderService.EXTRA_NOTIFICATION_ID, -1);
        if (notificationId != -1) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(notificationId);

        if (ACTION_JUST_REMOVE_NOTIFICATION.equals(action)) {
            // the notification removal was all that was asked for => fini
            finishAndRemoveTask();
            return;
        }

        if (ACTION_PROCESS_WISH.equals(action)) {
            // we have received a Wish
            this.wish.fill(intent.getParcelableExtra(EXTRA_WISH));
            return;
        }

        if (ACTION_ADD_TO_QUEUE.equals(action)) {
            ArrayList<Parcelable> params = getIntent().getParcelableArrayListExtra(EXTRA_WISHES);
            if (params != null && params.size() > 0) {
                // cancel the notification that had offered to enqueue the download(s)
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(IdSupply.NOTIFICATION_ID_MULTI);
                //
                try {
                    final int n = params.size();
                    if (n == 0) {
                        finish();
                        return;
                    }
                    final Wish[] wishes = new Wish[n];
                    for (int i = 0; i < n; i++) {
                        wishes[i] = ((Wish) params.get(i));
                    }
                    QueueManager.getInstance().add(wishes);
                    QueueManager.getInstance().nextPlease(true);
                    setResult(RESULT_OK);
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), e.toString(), e);
                }
            } else {
                // we have been asked to add the current data to the download queue
                this.wish.fill(intent.getParcelableExtra(EXTRA_WISH));
                if (this.wish.hasUri()) {
                    QueueManager.getInstance().add(this.wish);
                    setResult(RESULT_OK);
                } else {
                    if (BuildConfig.DEBUG) Log.e(TAG, "ACTION_ADD_TO_QUEUE without data!");
                }
            }
            finish();
            return;
        }

        if (ACTION_RETRY.equals(action)) {
            this.retryOrder = intent.getParcelableExtra(LoaderService.EXTRA_ORDER);
            if (this.retryOrder != null) {
                this.wish.fill(this.retryOrder.getWish());
                this.retryOrder = null;
            } else {
                if (BuildConfig.DEBUG) Log.e(TAG, "Got ACTION_RETRY without info about WHAT to retry!");
                finish();
            }
            return;
        }

        this.retryOrder = intent.getParcelableExtra(LoaderService.EXTRA_ORDER);
        this.retryOrderRealm = intent.getStringExtra(LoaderService.EXTRA_AUTH_REALM);
        this.retryOrderAuthScheme = intent.getStringExtra(LoaderService.EXTRA_AUTH_SCHEME);
        this.retryOrderAuthUser = intent.getStringExtra(LoaderService.EXTRA_AUTH_USER);
        this.retryOrderId = intent.getIntExtra(LoaderService.EXTRA_DOWNLOAD_ID, -1);
        if (this.retryOrder != null && this.retryOrderId != -1) {
            this.wish.setUri(this.retryOrder.getUri());
            this.wish.setMime(this.retryOrder.getMime());
            Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(this.retryOrderId);
            this.wish.setTitle(builder != null ? builder.getExtras().getCharSequence(Notification.EXTRA_TITLE) : null);
            return;
        }
        this.wish.setMime(intent.getType());
        // get standard sources of uri and title
        if (intent.getData() != null) this.wish.setUri(intent.getData());
        this.wish.setTitle(intent.getStringExtra(Intent.EXTRA_TITLE));
        final Bundle extras = intent.getExtras();

        // a) try alternative sources for title
        if (!this.wish.hasTitle()) {
            this.wish.setTitle(intent.getStringExtra(Intent.EXTRA_SUBJECT));
        }
        if (!this.wish.hasTitle()) {
            // https://developer.android.com/guide/components/intents-common?hl=en#NewNote
            this.wish.setTitle(intent.getStringExtra("com.google.android.gms.actions.extra.NAME"));
        }
        if (!this.wish.hasTitle()) {
            this.wish.setTitle(intent.getStringExtra(Intent.EXTRA_TEXT));
        }
        if (this.wish.hasTitle()) {
            // remove unwanted chars from the title and make sure that it is not longer than 256 chars
            final int l = Math.min(256, this.wish.getTitle().length());
            final StringBuilder sb = new StringBuilder(l);
            for (int i = 0; i < l; i++) {
                char c = this.wish.getTitle().charAt(i);
                if (c == '/' || c == '>' || c == '<' || c == ':' || c == '\\') sb.append('-');
                else sb.append(c);
            }
            this.wish.setTitle(sb);
        }

        // b) try alternative sources for uri
        if (!this.wish.hasUri() && extras != null) {
            Object extraStream = extras.get(Intent.EXTRA_STREAM);
            if (extraStream instanceof Uri) {
                this.wish.setUri((Uri)extraStream);
            } else if (extraStream instanceof List) {
                List<?> urilist = (List<?>)extraStream;
                final int nuris = urilist.size();
                if (nuris == 1) {
                    try {
                        this.wish.setUri((Uri) urilist.get(0));
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "Received unexpected data: " + urilist + " instead of an uri list!");
                        notifyError(this, "Unexpected data!");
                        finish();
                        return;
                    }
                } else if (nuris > 1) {
                    handleMulti(urilist);
                    return;
                }
            }
            if (!this.wish.hasUri()) {
                String extraText = extras.getString(Intent.EXTRA_TEXT);
                if (UriUtil.isUrl(extraText)) {
                    this.wish.setUri(Uri.parse(extraText));
                } else {
                    String containedUrl = UriUtil.extractUrl(extraText);
                    if (containedUrl != null) this.wish.setUri(Uri.parse(containedUrl));
                }
            }
            if (!this.wish.hasUri()) {
                String subject = extras.getString(Intent.EXTRA_SUBJECT);
                if (UriUtil.isUrl(subject)) this.wish.setUri(Uri.parse(subject));
            }
        }

        if (this.wish.hasUri()) {
            if (gotOwnFile()) {
                // some activity called us with "content://net.cellar.fileprovider/…" or "content://net.cellar.dogs/…"
                finishAndRemoveTask();
                return;
            }
            // Find out whether uri needs special handling
            UriHandler uh = UriHandler.checkUri(this.wish.getUri());
            if (uh != null) {
                this.wish.setUri(uh.getUri());
                if (!this.wish.hasTitle()) this.wish.setTitle(uh.getTitle());
                if (uh.hasLoader()) {
                    this.wish.setUriHandler(uh);
                    return;
                }
            }
        }

        if (!this.wish.hasUri() && !this.wish.hasTitle()) {
            // abort - no uri and no title received
            if (BuildConfig.DEBUG) {
                Uri referrer = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
                Log.e(TAG, "Got no uri from " + (referrer != null ? referrer.toString() : "sending app"));
            }
            setResult(RESULT_CANCELED);

            // launch UiActivity
            Intent intentUi = new Intent(this, UiActivity.class);
            intentUi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intentUi.putExtra(UiActivity.EXTRA_MIME_FILTER, intent.getType());
            startActivity(intentUi);

            finish();
        }
    }

    /**
     * Handles multiple Uris that have been received via an {@link Intent#ACTION_SEND_MULTIPLE} action.<br>
     * Always finishes this Activity!
     * @param urilist List of (hopefully) Uris
     * @throws NullPointerException if {@code urilist} is {@code null}
     */
    private void handleMulti(@NonNull final List<?> urilist) {
        final int nuris = urilist.size();
        final Uri[] uris = new Uri[nuris];
        try {
            for (int i = 0; i < nuris; i++) uris[i] = (Uri) urilist.get(i);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Received unexpected data: " + urilist + " instead of an uri list!");
            notifyError(this, getString(R.string.error_cant_handle_that));
            finish();
            return;
        }
        final Wish[] wishes = new Wish[nuris];
        final MimeTypeMap mtm = MimeTypeMap.getSingleton();
        final ContentResolver cr = getContentResolver();
        // total number of bytes to load
        long total = 0L;
        // whether <total> contains a valid value
        boolean totalIsValid = true;
        for (int i = 0; i < nuris; i++) {
            Uri uri = uris[i];
            String scheme = uri.getScheme();
            String fileName = null;
            if (UriUtil.isSupportedLocalScheme(scheme)) {
                Cursor cursor = cr.query(uri, null, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex > -1) {
                        long fileSize = cursor.getLong(sizeIndex);
                        if (fileSize >= 0L) total += fileSize; else totalIsValid = false;
                    } else {
                        totalIsValid = false;
                    }
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex > -1) fileName = cursor.getString(nameIndex);
                }
                Util.close(cursor);
            } else {
                totalIsValid = false;
            }
            wishes[i] = new Wish(uri);
            String s = uri.toString();
            int dot = s.lastIndexOf('.');
            if (dot > 0) {
                String extension = s.substring(dot + 1);
                String mime = mtm.getMimeTypeFromExtension(extension);
                if (mime != null) wishes[i].setMime(mime);
            }
            if (fileName != null) {
                wishes[i].setTitle(fileName);
            }
        }
        if (totalIsValid && total >= getFilesDir().getFreeSpace()) {
            notifyError(this, getString(R.string.msg_downloaded_file_1012_ws, UiUtil.formatBytes(total)));
            finish();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.msg_confirmation)
                .setIcon(R.drawable.ic_file_download_black_24dp)
                .setCancelable(false)
                .setMessage(totalIsValid && total > 0L ? getString(R.string.msg_download_all, nuris, UiUtil.formatBytes(total)) : getString(R.string.msg_download_all_nosize, nuris))
                .setNegativeButton(R.string.label_no, (dialog, which) -> {dialog.cancel(); finish();})
                .setPositiveButton(R.string.label_yes, (dialog, which) -> {
                    dialog.dismiss();
                    if (this.service != null) {
                        this.multiWishes = wishes;
                        proceed();
                    }
                })
                ;
        this.dialogConfirmMulti = builder.create();
        Window window = this.dialogConfirmMulti.getWindow();
        if (window != null) window.setBackgroundDrawableResource(R.drawable.background);
        this.dialogConfirmMulti.show();
    }

    /**
     * Displays a notification that allows the user to add the requested download to the queue to process it later.<br>
     * This method must only be invoked <em>after</em> parsing the Intent, that is, the {@link #wish} must have been filled.
     * @param why message to display to the user
     */
    private void offerEnqueueBecauseDownloadImpossible(@StringRes int why) {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        App app = (App)getApplicationContext();
        String msg = getString(why);

        final Intent intentPleaseEnqueue = (Intent)getIntent().clone();
        intentPleaseEnqueue.setAction(ACTION_ADD_TO_QUEUE);
        intentPleaseEnqueue.setComponent(getComponentName());
        intentPleaseEnqueue.putExtra(EXTRA_WISH, this.wish);
        intentPleaseEnqueue.putExtra(LoaderService.EXTRA_NOTIFICATION_ID, IdSupply.NOTIFICATION_ID_ENQUEUE);
        PendingIntent piEnqueue = PendingIntent.getActivity(app, 1, intentPleaseEnqueue, PendingIntent.FLAG_UPDATE_CURRENT);

        final Intent intentNoThanks = new Intent(this, MainActivity.class);
        intentNoThanks.setAction(ACTION_JUST_REMOVE_NOTIFICATION);
        intentNoThanks.putExtra(LoaderService.EXTRA_NOTIFICATION_ID, IdSupply.NOTIFICATION_ID_ENQUEUE);
        PendingIntent piNoThanks = PendingIntent.getActivity(app, 1, intentNoThanks, PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification.Builder builder = new Notification.Builder(this)
                .setDefaults(Notification.DEFAULT_SOUND)
                .setSmallIcon(R.drawable.ic_n_download)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(msg)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setOnlyAlertOnce(true)
                .setOngoing(false);
        if (msg.length() > getResources().getInteger(R.integer.notification_text_maxlength)) {
            builder.setStyle(new Notification.BigTextStyle().bigText(msg).setSummaryText(msg));
        }
        builder
                .addAction(UiUtil.makeNotificationAction(this, R.drawable.ic_baseline_queue_24, R.string.action_queue_add, piEnqueue))
                .addAction(UiUtil.makeNotificationAction(this, android.R.drawable.ic_menu_close_clear_cancel, R.string.label_no_thanks, piNoThanks));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(app.getNcImportant().getId());
        }
        nm.notify(IdSupply.NOTIFICATION_ID_ENQUEUE, builder.build());
        finish();
    }

    /** {@inheritDoc} */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        BaseActivity.setDarkMode(this);
        super.onCreate(savedInstanceState);
        boolean gotInstanceState;
        if (savedInstanceState != null) {
            this.wish.fill(savedInstanceState.getParcelable(STATE_WISH));
            gotInstanceState = this.wish.hasUri() || this.wish.hasTitle();
        } else {
            gotInstanceState = false;
        }
        if (!gotInstanceState) {
            handleIntent(getIntent());
            if (this.service != null) proceed();
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        if (this.service != null) proceed();
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        if (BuildConfig.DEBUG) Log.i(TAG, "onPause()");
        super.onPause();
        UiUtil.dismissDialog(this.dialogVariantSelector, this.dialogCredentials, this.dialogConfirmMulti);
        this.dialogVariantSelector = this.dialogCredentials = this.dialogConfirmMulti = null;
        if (this.service != null) {
            if (!((App)getApplicationContext()).hasActiveLoaders()) {
                this.service.letSleep();
            }
            unbindService(this);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        if (BuildConfig.DEBUG) Log.i(TAG, "onResume()");
        try {
            super.onResume();
            bindService(new Intent(this, LoaderService.class), this, BIND_AUTO_CREATE | BIND_IMPORTANT);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        }
    }

    /** {@inheritDoc}<br>
     * According to the docs this is called when the activity is "temporarily destroyed" due to system constraints.
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (this.wish.hasUri()) outState.putParcelable(STATE_WISH, this.wish);
        super.onSaveInstanceState(outState);
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        this.service = ((LoaderService.LoaderServiceBinder)service).getLoaderService();

        if (UriUtil.isRemoteUri(this.wish.getUri())) {
            NetworkInfo.State networkState = NetworkChangedReceiver.getInstance().getState();
            if (networkState != NetworkInfo.State.CONNECTED) {
                offerEnqueueBecauseDownloadImpossible(R.string.msg_network_conn_lost_queue);
                return;
            }
            if (((App)getApplicationContext()).hasActiveLoaders()) {
                QueueManager.getInstance().add(this.wish);
                finish();
                return;
            }
        }

        if (this.dialogConfirmMulti != null && this.dialogConfirmMulti.isShowing()) {
            return;
        }

        if (this.multiWishes != null) {
            proceed();
            return;
        }

        String action = getIntent().getAction();

        if (ACTION_RETRY.equals(action) || ACTION_RETRY_401.equals(action)) {
            proceed();
            return;
        }

        if (Intent.ACTION_MAIN.equals(action) || (!this.wish.hasUri() && !this.wish.hasTitle())) {
            finishAndRemoveTask();
            return;
        }

        proceed();
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        this.service = null;
    }

    /**
     * Copies the data identified by {@link #uri} to the downloads folder.<br>
     * For playlists, this is done via a {@link Streamer Streamer};
     * for other http and https schemes, this is done via a {@link Downloader Downloader};
     * for content and file schemes, this is done via a {@link Copier Copier}.<br>
     * <em>Needs the {@link #service Service}, so can be called only after {@link #onServiceConnected(ComponentName, IBinder)} has been invoked.</em>
     */
    @UiThread
    private void proceed() {
        if (BuildConfig.DEBUG) Log.i(TAG, "proceed() - wish=" + wish + ", retryOrder=" + retryOrder + ", retryOrderRealm=" + retryOrderRealm);

        if (this.retryOrder != null) {
            if (this.retryOrderRealm == null) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Retrying " + this.retryOrder);
                this.wish.fill(this.retryOrder.getWish());
                // clear retryOrder and go back to the top of this method
                this.retryOrder = null;
                if (this.service != null) proceed();
                return;
            }
            ViewGroup cred = (ViewGroup)LayoutInflater.from(this).inflate(R.layout.credential_edit, null);
            EditText editTextUser = cred.findViewById(R.id.editTextUser);
            EditText editTextPwd = cred.findViewById(R.id.editTextPwd);
            if (!TextUtils.isEmpty(this.retryOrderAuthUser)) {
                editTextUser.setText(this.retryOrderAuthUser);
                editTextPwd.requestFocus();
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.msg_enter_credentials, retryOrderRealm))
                    .setView(cred)
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                        dialog.cancel();
                        this.retryOrder = null;
                        this.retryOrderRealm = null;
                        finish();
                    })
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        dialog.dismiss();
                        String userid = editTextUser.getText().toString().trim();
                        CharSequence pwd = editTextPwd.getText();
                        if (userid.length() == 0 || pwd.length() == 0) {
                            dialog.cancel();
                            this.retryOrder = null;
                            this.retryOrderRealm = null;
                            finish();
                            return;
                        }
                        int credentialType = Credential.typeForScheme(this.retryOrderAuthScheme);
                        if (credentialType != Credential.TYPE_UNKNOWN) {
                            AuthManager.getInstance().addOrReplaceCredential(new Credential(this.retryOrderRealm, userid, pwd, credentialType));
                        } else {
                            if (BuildConfig.DEBUG) Log.e(TAG, "Unhandled authorization scheme \"" + this.retryOrderAuthScheme + "\"!");
                        }
                        if (BuildConfig.DEBUG) Log.i(TAG, "***************************************************************************************************************");
                        if (BuildConfig.DEBUG) Log.i(TAG, "Retrying " + this.retryOrder);
                        Wish wishToRetry = this.retryOrder.getWish();
                        this.wish.fill(wishToRetry);
                        // retryOrder must be nulled now so that we don't arrive here again…
                        this.retryOrder = null;
                        if (this.service != null) proceed();
                    })
                    ;
            this.dialogCredentials = builder.create();
            Window dialogWindow = this.dialogCredentials.getWindow();
            if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
            this.dialogCredentials.show();
            return;
        }

        if (this.multiWishes != null) {
            final long orderGroup = System.currentTimeMillis();
            for (Wish wish : this.multiWishes) {
                this.wish.fill(wish);
                instructLoaderService(false, orderGroup);
            }
            setResult(RESULT_OK);
            finish();
            return;
        }

        //TODO this cannot handle local content:// playlists

        // download playlists to see what's in it
        if (Playlist.isPlaylist(this.wish.getUri().toString())) {
            proceedWithRemoteOrLocalPlaylist();
            // do not finish() here; proceedWithLocalPlaylist() does it
            return;
        }

        instructLoaderService(true, Order.NO_ORDER_GROUP);
    }

    /**
     * Instructs the {@link LoaderService} to load the data that {@link #wish} points to.
     * @param finish {@code true} to finish this Activity before returning from this method
     * @param ordergroup an arbitrary identifier - could be a timestamp - or {@link Order#NO_ORDER_GROUP}
     */
    private void instructLoaderService(final boolean finish, final long ordergroup) {
        if (!this.wish.hasUri()) {
            if (this.wish.hasTitle()) {
                // probably via the intent-filter with just "<data android:mimeType="*/*" />" set
                Uri referrer = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ? getReferrer() : null;
                this.service.saveJustText(this.wish.getTitle(), referrer);
                if (finish) {setResult(RESULT_OK); finish();}
            } else {
                if (BuildConfig.DEBUG) Log.e(TAG, "Cannot proceed, uri and title are both null!");
                if (finish) {setResult(RESULT_CANCELED); finishAndRemoveTask();}
            }
            return;
        }

        final String scheme = this.wish.getUri().getScheme();

        CharSequence destinationFilename = this.wish.getTitle();
        if (TextUtils.isEmpty(destinationFilename)) destinationFilename = this.wish.getUri().getLastPathSegment();
        if (TextUtils.isEmpty(destinationFilename)) {
            setResult(RESULT_CANCELED);
            notifyError(this, getString(R.string.error_cant_handle, this.wish.getUri().toString()));
            if (finish) finishAndRemoveTask();
        }

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (this.wish.hasUriHandler() && this.wish.getUriHandler().hasLoader()) {
                this.service.loadViaUriHandler(this.wish, this.wish.getUriHandler());
            } else {
                // use Downloader to download from a remote resource
                this.service.load(this.wish);
            }
            if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STARTED));
            if (finish) {
                setResult(RESULT_OK);
                finish();
            }
        } else if ("ftp".equalsIgnoreCase(scheme)) {
            this.service.loadftp(this.wish);
            if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STARTED));
            if (finish) {
                setResult(RESULT_OK);
                finish();
            }
        } else if ("sftp".equalsIgnoreCase(scheme)) {
            // https://www.iana.org/assignments/uri-schemes/prov/sftp
            // Scheme syntax: sftp://[<user>[;fingerprint=<host-key fingerprint>]@]<host>[:<port>]/<path>/<file>
            this.service.loadsftp(this.wish);
            if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STARTED));
            if (finish) {
                setResult(RESULT_OK);
                finish();
            }
        } else if (UriUtil.isSupportedLocalScheme(scheme)) {
            // use Copier to copy from a local provider or file
            this.service.copy(this.wish, ordergroup);
            if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STARTED));
            if (finish) {
                setResult(RESULT_OK);
                finish();
            }
        } else {
            notifyError(this, getString(R.string.error_cant_handle, this.wish.getUriHandler().toString()));
            if (finish) {
                setResult(RESULT_CANCELED);
                finishAndRemoveTask();
            }
        }
    }

    /**
     * Proceed to load a resource that is referred to in a playlist file stored on the device.
     * @param localPlaylistUri Uri pointing to a local playlist file
     * @param localPlaylistMime MIME type as returned by the remote host when the playlist has been loaded ({@code null} if the playlist was sourced locally, via content: or file:)
     */
    private void proceedWithLocalPlaylist(@NonNull final Uri localPlaylistUri, final @Nullable String localPlaylistMime) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Parsing local playlist file \"" + localPlaylistUri + "\", mime: " + (localPlaylistMime != null ? '"' + localPlaylistMime + '"' : "<null>"));
        PlaylistParser pp = new PlaylistParser(this, new PlaylistParser.Listener() {
            /** {@inheritDoc} */
            @SuppressWarnings("ConstantConditions")
            @Override
            public void done(boolean completed, @NonNull Playlist parsedPlaylist) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Parsed local playlist file: " + parsedPlaylist);
                final File localPlaylistfile = UriUtil.fromUri(localPlaylistUri);
                if (!completed) {
                    Util.deleteFile(localPlaylistfile);
                    setResult(RESULT_CANCELED);
                    finish();
                    return;
                }
                if (!parsedPlaylist.isValid()) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Playlist loaded from " + MainActivity.this.wish.getUri() + " is not valid!");
                    Util.deleteFile(localPlaylistfile);
                    notifyError(MainActivity.this, getString(R.string.error_cant_handle, MainActivity.this.wish.getUri().toString()));
                    setResult(RESULT_CANCELED);
                    finish();
                    return;
                }
                parsedPlaylist.makeRelativeUrisAbsolute();
                final String title = MainActivity.this.wish.hasTitle() ? MainActivity.this.wish.getTitle().toString() : null;
                if (parsedPlaylist.isMediaPlaylist()) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Playlist identified as media playlist (items are a sequence, not alternatives)");
                    /*
                     Although the playlist items are a sequence,
                     they can be either an independent list of files (like .mp3 files from an album),
                     or parts of one big file that must be stitched together (like .ts files from a video stream)
                     */
                    if (parsedPlaylist instanceof M3UPlaylist && ((M3UPlaylist)parsedPlaylist).isPlain()) {
                        // playlist is just a bunch of files - just load them after another
                        final List<PlaylistItem> itemList = parsedPlaylist.getItems();
                        final Wish[] wishes = new Wish[itemList.size()];
                        for (int i = 0; i < wishes.length; i++) {
                            wishes[i] = new Wish(itemList.get(i).getUri());
                        }
                        QueueManager.getInstance().add(wishes);
                    } else {
                        @NonNull final PlaylistItem item;
                        if (parsedPlaylist.getCount() == 1) {
                            item = parsedPlaylist.getFirstItem();
                        } else {
                            item = new PlaylistItem(MainActivity.this.wish.getUri());
                        }
                        // playlist contains a sequence of media segments that must be played one after another
                        // therefore we pass the original playlist url to libvlc;
                        // but first, examine the individual playlist media items to see their type (like *.aac or *.m4a etc.)
                        String ext = parsedPlaylist.getMediaPlaylistUniqueMediaType();
                        if (ext != null) {
                            String mimeFromExt = Util.getMime(ext.substring(1).toLowerCase(java.util.Locale.US), App.MIME_DEFAULT);
                            if (!App.MIME_DEFAULT.equals(mimeFromExt)) {
                                MainActivity.this.service.stream(item, title, mimeFromExt);
                            } else {
                                MainActivity.this.service.stream(item, title, null);
                            }
                        } else {
                            MainActivity.this.service.stream(item, title, null);
                        }
                    }
                } else if (parsedPlaylist.isMasterPlaylist()) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Playlist identified as master playlist (items are alternatives, not a sequence)");
                    // let the user select the stream…
                    final List<PlaylistItem> playlistItems = parsedPlaylist.getUniqueItems(MainActivity.this);
                    final int nItems = playlistItems.size();
                    if (nItems == 1) {
                        // nothing to select
                        MainActivity.this.service.stream(playlistItems.get(0), title, null);
                        setResult(RESULT_OK);
                        Util.deleteFile(localPlaylistfile);
                        finish();
                        return;
                    }
                    final CharSequence[] labels = new CharSequence[nItems];
                    for (int i = 0; i < nItems; i++) {
                        labels[i] = playlistItems.get(i).toUserString(MainActivity.this);
                    }
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this)
                            .setIcon(R.drawable.ic_baseline_playlist_play_24)
                            .setTitle(R.string.msg_select_variant)
                            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
                            .setOnCancelListener(dialog -> {
                                setResult(RESULT_CANCELED);
                                Util.deleteFile(localPlaylistfile);
                                finish();
                            })
                            ;
                    if (parsedPlaylist instanceof M3UPlaylist) {
                        builder.setAdapter(new DialogSingleChoiceAdapter(MainActivity.this, R.layout.singlechoice, android.R.id.text1, labels), (dialog, which) -> {
                            PlaylistItem item = playlistItems.get(which);
                            String extXMediaAudioGroupId = item.getExtXMediaAudioGroupId();
                            M3UPlaylist.ExtXMedia extXMedia = ((M3UPlaylist)parsedPlaylist).getDefaultExtXMedia(extXMediaAudioGroupId);
                            dialog.dismiss();
                            if (extXMedia != null) {
                                MainActivity.this.service.stream(item, title, null, extXMedia.getUri());
                            } else {
                                MainActivity.this.service.stream(item, title, null);
                            }
                            Util.deleteFile(localPlaylistfile);
                            setResult(RESULT_OK);
                            finish();
                        });
                    } else {
                        builder.setAdapter(new DialogSingleChoiceAdapter(MainActivity.this, R.layout.singlechoice, android.R.id.text1, labels), (dialog, which) -> {
                            PlaylistItem item = playlistItems.get(which);
                            dialog.dismiss();
                            MainActivity.this.service.stream(item, title, null);
                            setResult(RESULT_OK);
                            Util.deleteFile(localPlaylistfile);
                            finish();
                        });
                    }
                    MainActivity.this.dialogVariantSelector = builder.show();
                    return;
                } else {
                    // we cannot handle the playlist, so just copy the playlist file from the app's cache dir to the app's files dir
                    if (localPlaylistfile != null && localPlaylistfile.isFile()) {
                        File destination = new File(App.getDownloadsDir(MainActivity.this), localPlaylistfile.getName());
                        String alt = Util.suggestAlternativeFilename(destination);
                        if (alt != null) destination = new File(App.getDownloadsDir(MainActivity.this), alt);
                        boolean copied = copyFile(localPlaylistfile, destination);
                        if (copied) {
                            setResult(RESULT_OK);
                            App app = (App)getApplicationContext();
                            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                            Intent contentIntent = new Intent(app, UiActivity.class);
                            contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            Notification.Builder nb = new Notification.Builder(app)
                                    .setSmallIcon(R.drawable.ic_n_download_done)
                                    .setContentTitle(getString(R.string.msg_download_complete))
                                    .setContentText(getString(R.string.msg_downloaded_file, MainActivity.this.wish.getUri().toString()))
                                    .setAutoCancel(true)
                                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                                    .setCategory(Notification.CATEGORY_STATUS)
                                    .setContentIntent(PendingIntent.getActivity(MainActivity.this, 1, contentIntent, 0))
                                    .setOngoing(false)
                                    ;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                nb.setChannelId(app.getNc().getId());
                            }
                            nm.notify(IdSupply.NOTIFICATION_ID_FOR_DOWNLOADS_WITHOUT_ID, nb.build());
                        } else {
                            setResult(RESULT_CANCELED);
                            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to copy " + localPlaylistfile + " to files dir!");
                        }
                        Util.deleteFile(localPlaylistfile);
                    }
                }
                finish();
            }
        });
        pp.execute(new UriPair(this.wish.getUri(), localPlaylistUri));
    }

    /**
     * The uri has been identified to point to a playlist.
     * If the playlist is a remote resource, it will be downloaded.
     */
    private void proceedWithRemoteOrLocalPlaylist() {
        if (!this.wish.hasUri()) return;
        final Uri uri = this.wish.getUri();
        if (BuildConfig.DEBUG) Log.i(TAG, "Uri " + uri + " identified as playlist.");
        if (UriUtil.isSupportedRemoteScheme(uri.getScheme())) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Loading remote playlist \"" + uri + "\"…");
            this.playlistLoaderListener = (id, complete, deliveries) -> {
                if (BuildConfig.DEBUG) Log.i(TAG, "Loaded remote playlist. Complete: " + complete);
                if (!complete) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Cancelled download from " + uri);
                    setResult(RESULT_CANCELED);
                    finishAndRemoveTask();
                    return;
                }
                if (deliveries.isEmpty()) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to download from " + uri);
                    setResult(RESULT_CANCELED);
                    notifyError(this, getString(R.string.error_cant_handle, uri.toString()));
                    finish();
                    return;
                }
                // there should be exactly one Delivery
                Delivery delivery = deliveries.iterator().next();
                final int rc = delivery.getRc();
                if (rc < 200 || rc > 299) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to download from " + uri + ": " + rc + (delivery.getThrowable() != null ? " - " + delivery.getThrowable() : ""));
                    setResult(RESULT_CANCELED);
                    if (rc == 403) {
                        notifyError(this, getString(R.string.msg_downloaded_file_403, uri.toString()));
                    } else if (rc == 404) {
                        notifyError(this, getString(R.string.msg_downloaded_file_404, uri.toString()));
                    } else if (rc == 410) {
                        notifyError(this, getString(R.string.msg_downloaded_file_410, uri.toString()));
                    } else if (rc == 429) {
                        notifyError(this, getString(R.string.msg_downloaded_file_429));
                    } else if (rc == 451) {
                        notifyError(this, getString(R.string.msg_downloaded_file_451, uri.toString()));
                    } else if (rc == 503) {
                        notifyError(this, getString(R.string.msg_downloaded_file_503, uri.toString()));
                    } else if (rc == LoaderService.ERROR_CANNOT_CONNECT || rc == LoaderService.ERROR_SSL_PEER_UNVERIFIED) {
                        String host = uri.getHost();
                        Throwable e = delivery.getThrowable();
                        if (e != null) host = Util.getHostAndPort(e, host);
                        if (host != null && host.length() > 0) {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                            String proxy = prefs.getString(App.PREF_PROXY_SERVER, null);
                            if (BuildConfig.DEBUG) Log.i(TAG, "Offending host: '" + host + "', proxy: '" + proxy + "'");
                            String proxyType = prefs.getString(App.PREF_PROXY_TYPE, Proxy.Type.DIRECT.toString());
                            if (proxy != null && !Proxy.Type.DIRECT.toString().equals(proxyType) && proxy.equals(host)) {
                                notifyError(this, getString(R.string.msg_download_error_connect_to_proxy, host));
                            } else {
                                notifyError(this, getString(R.string.msg_download_error_connect_to, host));
                            }
                        }
                        else notifyError(this, getString(R.string.msg_download_error_connect));
                    } else if (rc == LoaderService.ERROR_VLC) {
                        notifyError(this, getString(R.string.msg_download_failed_no_more_info));
                    } else if (rc == LoaderService.ERROR_SSL_HANDSHAKE) {
                        String reason = (delivery.getThrowable() != null) ? delivery.getThrowable().getMessage() : null;
                        StringBuilder msg = new StringBuilder();
                        msg.append(getString(R.string.msg_downloaded_file_1015)).append(!TextUtils.isEmpty(reason) ? ": " + reason : "");
                        if (msg.charAt(msg.length() - 1) != '.') msg.append('.');
                        notifyError(this, msg);
                    } else if (rc == LoaderService.ERROR_CLEARTEXT_NOT_PERMITTED) {
                        notifyError(this, getString(R.string.msg_downloaded_file_1011, uri.getHost()));
                    } else {
                        notifyError(this, getString(R.string.error_cant_handle, uri.toString()));
                    }
                    finish();
                    return;
                }
                File playlist = delivery.getFile();
                if (playlist == null || !playlist.isFile()) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to download playlist from " + uri + ", rc was " + rc);
                    setResult(RESULT_CANCELED);
                    notifyError(this, getString(R.string.error_cant_handle, uri.toString()));
                    finish();
                    return;
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "Delivery received by playlist download: " + delivery);
                if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_PLAYLIST_LOADED));
                proceedWithLocalPlaylist(Uri.fromFile(playlist), delivery.getMediaType());
            };
            this.service.loadPlaylist(this.wish, this.playlistLoaderListener);
        } else if (UriUtil.isSupportedLocalScheme(uri.getScheme())) {
            // this.uri points to data on the device
            proceedWithLocalPlaylist(uri, null);
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG,"Unsupported scheme: " + uri);
            setResult(RESULT_CANCELED);
            finishAndRemoveTask();
        }
    }

    /**
     * Used to display single choice items in a dialog.
     */
    private static class DialogSingleChoiceAdapter extends ArrayAdapter<CharSequence> {

        /**
         * Constructor.
         * @param ctx Context
         * @param resource layout resource
         * @param textViewResourceId text view resource id
         * @param objects labels
         */
        private DialogSingleChoiceAdapter(Context ctx, int resource, int textViewResourceId, CharSequence[] objects) {
            super(ctx, resource, textViewResourceId, objects);
        }

        /** {@inheritDoc} */
        @Override
        public long getItemId(int position) {
            return position;
        }

        /** {@inheritDoc} */
        @Override
        public boolean hasStableIds() {
            return true;
        }
    }

}

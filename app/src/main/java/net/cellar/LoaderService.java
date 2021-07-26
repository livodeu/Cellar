/*
 * LoaderService.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.security.NetworkSecurityPolicy;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.annotation.AnyThread;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import net.cellar.auth.AuthManager;
import net.cellar.model.Credential;
import net.cellar.model.Delivery;
import net.cellar.model.FileInfo;
import net.cellar.model.Order;
import net.cellar.model.Wish;
import net.cellar.model.pl.PlaylistItem;
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
import net.cellar.worker.FtpLoader;
import net.cellar.worker.Loader;
import net.cellar.worker.LoaderListener;
import net.cellar.worker.ResourceTooLargeException;
import net.cellar.worker.SftpLoader;
import net.cellar.worker.Streamer;

import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoaderService extends Service implements LoaderListener {

    @VisibleForTesting
    public static final String ACTION_DEFER = BuildConfig.APPLICATION_ID + ".defer";
    @LoadError
    public static final int ERROR_CANCELLED = 1002;
    @LoadError
    public static final int ERROR_CANNOT_CONNECT = 1009;
    @LoadError
    public static final int ERROR_CLEARTEXT_NOT_PERMITTED = 1011;
    @LoadError
    public static final int ERROR_CONTEXT_GONE = 1004;
    @LoadError
    public static final int ERROR_COPY_FROM_MYSELF = 1006;
    @LoadError
    public static final int ERROR_DEFERRED = 1018;
    @LoadError
    public static final int ERROR_DEST_DIRECTORY_NOT_EXISTENT = 1001;
    @LoadError
    public static final int ERROR_EVIL = 1013;
    @LoadError
    public static final int ERROR_INTERRUPTED = 1010;
    @LoadError
    public static final int ERROR_LACKING_SPACE = 1012;
    @LoadError
    public static final int ERROR_NO_FILENAME = 1007;
    @LoadError
    public static final int ERROR_NO_SOURCE_FOUND = 1005;
    @LoadError
    public static final int ERROR_OTHER = 1000;
    @LoadError
    public static final int ERROR_SSL_HANDSHAKE = 1015;
    @LoadError
    public static final int ERROR_SSL_PEER_UNVERIFIED = 1008;
    @LoadError
    public static final int ERROR_VLC = 1016;
    @LoadError
    public static final int ERROR_YOUTUBE_CAPTCHA = 1014;
    @LoadError
    public static final int ERROR_YOUTUBE_LIVESTREAM = 1017;
    /** contains the absolute path of a file */
    @VisibleForTesting
    public static final String EXTRA_FILE = BuildConfig.APPLICATION_ID + ".file";
    /** replacement char for {@link File#separatorChar} in file names */
    public static final char REPLACEMENT_FOR_FILESEPARATOR = '_';
    /** the 'realm' data as given in a <a href="https://tools.ietf.org/html/rfc2616#section-14.47">HTTP 401 response header</a> */
    static final String EXTRA_AUTH_REALM = BuildConfig.APPLICATION_ID + ".authrealm";
    /** one of {@link AuthManager#SUPPORTED_SCHEMES} ("Basic" or "ftp" or "sftp") */
    static final String EXTRA_AUTH_SCHEME = BuildConfig.APPLICATION_ID + ".authscheme";
    /** user id for authentication */
    static final String EXTRA_AUTH_USER = BuildConfig.APPLICATION_ID + ".authuser";
    /** the download id - the progress notification id can be calculated via {@link IdSupply#progressNotificationId(int)} */
    static final String EXTRA_DOWNLOAD_ID = BuildConfig.APPLICATION_ID + ".ID";
    /** if this is set, the corresponding notification should be cancelled */
    static final String EXTRA_NOTIFICATION_ID = BuildConfig.APPLICATION_ID + ".notificationid";
    /** the {@link Order} to retry with credentials */
    static final String EXTRA_ORDER = BuildConfig.APPLICATION_ID + ".order";
    /** ArrayList of Wishes that should be removed from the {@link QueueManager} */
    static final String EXTRA_UNQUEUE_US = BuildConfig.APPLICATION_ID + ".unqueue";
    private static final String ACTION_CANCEL = BuildConfig.APPLICATION_ID + ".cancel";
    private static final String ACTION_CANCEL_INSTALLATION = BuildConfig.APPLICATION_ID + ".cancel_installation";
    private static final String ACTION_DELETE = BuildConfig.APPLICATION_ID + ".delete";
    private static final String ACTION_QUEUE = BuildConfig.APPLICATION_ID + ".queue";
    private static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".stop";
    /** one or more Wishes shall be removed from the download queue - the wishes are passed in {@link #EXTRA_UNQUEUE_US} */
    private static final String ACTION_UNQUEUE = BuildConfig.APPLICATION_ID + ".unqueue";
    private static final Object DOWNLOAD_ID_LOCK = new Object();
    private static final int REQUEST_CODE_CANCEL = 101;
    private static final int REQUEST_CODE_DEFER = 102;
    private static final int REQUEST_CODE_DELETE = 103;
    private static final int REQUEST_CODE_RETRY = 104;
    private static final int REQUEST_CODE_STOP = 105;
    private static final int REQUEST_CODE_UNQUEUE = 106;
    private static final String TAG =  "LoaderService";
    /** the id of the next download */
    private static int DOWNLOAD_ID = IdSupply.DOWNLOAD_ID_OFFSET;

    /**
     * Checks whether the given Uri's scheme is allowed for the Uri's host.
     * Actually the only scheme that could fail here is "http".
     * @param uri uri to check
     * @return true / false
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean isProtocolAllowed(@NonNull Uri uri) {
        String scheme = uri.getScheme();
        if (!"http".equals(scheme)) return true;
        String host = uri.getHost();
        boolean allowed = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host);
        if (BuildConfig.DEBUG) {
            if (allowed) Log.i(TAG, "HTTP connection to " + host + " is allowed");
            else Log.w(TAG, "HTTP connection to " + host + " is NOT allowed");
        }
        return allowed;
    }

    /**
     * Returns the id for the next download.
     * @return next download id
     */
    @AnyThread
    @IntRange(from = IdSupply.DOWNLOAD_ID_OFFSET)
    private static int nextDownloadId() {
        int next;
        synchronized (DOWNLOAD_ID_LOCK) {
            next = DOWNLOAD_ID++;
        }
        return next;
    }
    private final LoaderServiceBinder binder = new LoaderServiceBinder(this);
    private final Handler handler = new Handler();
    /** key: order group id; value: number of downloads per group */
    private final Map<Long, Integer> orderGroupCounter = new HashMap<>();
    /** download ids whose Notification.Builder has gotten a "Stop" action */
    private final Set<Integer> stopActionAdded = new HashSet<>();
    private Reference<DoneListener> refDoneListener;
    private ExecutorService loaderExecutor;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    /** max. length of a notification content title - the title will be cut off after that position - see also <a href="https://material.io/design/platform-guidance/android-notifications.html#style">here</a> */
    private int notificationTitleMaxLength;
    /** max. length of a notification content text - the text will not be cut off but the {@link android.app.Notification.BigTextStyle} will be applied then */
    private int notificationTextMaxLength;

    /**
     * Constructor.
     */
    public LoaderService() {
        super();
    }

    /** {@inheritDoc} */
    @Override
    public void buffering(int downloadId, float buffer) {
        final Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
        if (builder == null) return;
        builder.setProgress(100, 0, true)
                .setContentText(getString(R.string.msg_buffering, Math.round(buffer)));
        this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
    }

    /** {@inheritDoc} */
    @Override
    @WorkerThread
    public void contentlength(final int downloadId, final long contentLength) {
        if (contentLength <= 0L) return;
        this.handler.post(() -> {
            Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
            if (builder == null) return;
            builder.setSubText(UiUtil.formatBytes(contentLength));
        });
    }

    /**
     * Copies device-local data from the given Uri (<i>content</i> or <i>file</i> scheme) to this App's downloads directory.
     * @param wish the Wish that is the origin
     * @param group order group id
     */
    @MainThread
    void copy(@NonNull final Wish wish, final long group) {
        if (BuildConfig.DEBUG) Log.i(TAG, "copy(" + wish + ", " + group + ")");
        final Copier copier = new Copier(nextDownloadId(), this, this);
        final Order order = new Order(wish);
        order.setGroup(group);
        // determine file name
        ContentResolver cr = getContentResolver();
        // the uri will be queried on a separate thread because the query might hang
        FileInfo fileInfo = new FileInfo(cr, wish.getUri());
        fileInfo.start();
        try {
            fileInfo.join(3_000L);
        } catch (InterruptedException e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "While getting file info: " + e.toString());
        }
        //
        String mime = wish.getMime();
        if (TextUtils.isEmpty(mime)) {
            mime = cr.getType(wish.getUri());
        }
        String tag = (mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null);
        String fileInfoDisplayname = fileInfo.getFileDisplayname();
        String filename;
        if (fileInfoDisplayname != null) {
            filename = fileInfoDisplayname;
            //noinspection ConstantConditions
            if (!TextUtils.isEmpty(tag) && !filename.toLowerCase().endsWith(tag.toLowerCase())) {
                filename = filename + (!tag.startsWith(".") ? "." : "") + tag;
            }
        } else {
            filename = App.generateFilename(wish.getTitle(), wish.getUri(), tag);
        }
        final File folder = App.getDownloadsDir(this);
        File destination = new File(folder, filename);
        String alt = Util.suggestAlternativeFilename(destination);
        if (alt != null) {
            destination = new File(folder, alt);
        }
        //
        long fileSize = fileInfo.getFileSize();
        if (fileSize > 0L) order.setFileSize(fileSize);
        order.setDestination(folder.getAbsolutePath(), destination.getName());
        order.setMime(mime);
        copier.executeOnExecutor(this.loaderExecutor, order);
    }

    /** {@inheritDoc} */
    @Override
    @UiThread
    public void done(final int downloadId, boolean complete, @NonNull final Set<Delivery> deliveries) {
        int n = deliveries.size();
        if (BuildConfig.DEBUG && n != 1) Log.e(TAG, "Received " + n + " deliveries!");
        if (n == 0) return;
        Delivery delivery = deliveries.iterator().next();
        if (delivery == null) return;
        done(downloadId, delivery);
    }

    @UiThread
    private void done(final int downloadId, @NonNull final Delivery delivery) {
        final StringBuilder msg = new StringBuilder(64);
        final int notificationId = IdSupply.completionNotificationId(downloadId);
        File downloadedFile = null;
        boolean cancelled = false;
        boolean deferred = false;
        boolean failed = false;

        Notification.Action deleteFileAction = null;
        Notification.Action retryWithAuthorizationAction = null;
        Notification.Action retryAction = null;
        Notification.Action cancelQueuedAction = null;
        Notification.Action queueAction = null;

        final int rc = delivery.getRc();
        final Order order = delivery.getOrder();
        final String fileName = order.getDestinationFilename();
        final String failedFileName = (rc > 299 ? fileName : null);
        final long orderGroup = order.getGroup();

        if (DebugUtil.TEST) {
            Intent i = new Intent(App.ACTION_DOWNLOAD_FINISHED);
            i.putExtra(EXTRA_FILE, fileName);
            sendBroadcast(i);
        }

        // count number of downloads per order group
        if (orderGroup != Order.NO_ORDER_GROUP) {
            Integer previousCount = this.orderGroupCounter.get(order.getGroup());
            this.orderGroupCounter.put(order.getGroup(), previousCount == null ? 1 : previousCount + 1);
        }

        // remember where remote files came from - this helps when resuming partial downloads
        if (UriUtil.isSupportedRemoteScheme(order.getUri().getScheme())) {
            Ancestry.getInstance().add(order.getUri().getHost(), fileName);
        }

        //
        if (rc >= ERROR_OTHER) {
            if (rc == ERROR_CANCELLED) {
                cancelled = true;
                msg.append(getString(R.string.msg_downloaded_cancelled, fileName));
            } else if (rc == ERROR_DEFERRED) {
                deferred = true;
                msg.append(getString(R.string.msg_downloaded_deferred, fileName));
            } else if (rc == ERROR_CANNOT_CONNECT) {
                failed = true;
                String culprit = Util.getHostAndPort(delivery.getThrowable(), null);
                if (culprit != null) {
                    if (culprit.startsWith("/")) culprit = culprit.substring(1);
                    msg.append(getString(R.string.msg_download_error_connect_to, culprit));

                    // offer the chance to simply try again
                    retryAction = makeRetryAction(order, downloadId, notificationId);

                    int colon = culprit.lastIndexOf(':');
                    if (colon > 0) culprit = culprit.substring(0, colon);
                    ArrayList<Wish> queuedForCulprit = QueueManager.getInstance().getAllForHost(culprit);
                    if (!queuedForCulprit.isEmpty()) {
                        // offer to remove remaining downloads for the same host
                        final Intent intentUnqueue = new Intent(this, LoaderService.class);
                        intentUnqueue.setAction(ACTION_UNQUEUE);
                        intentUnqueue.putParcelableArrayListExtra(EXTRA_UNQUEUE_US, queuedForCulprit);
                        intentUnqueue.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
                        PendingIntent piUnqueue = PendingIntent.getService(this, REQUEST_CODE_UNQUEUE, intentUnqueue, PendingIntent.FLAG_UPDATE_CURRENT);
                        cancelQueuedAction = UiUtil.makeNotificationAction(this, android.R.drawable.ic_delete, R.string.action_queue_clear, piUnqueue);
                    }

                } else {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Could not determine culprit");
                    msg.append(getString(R.string.msg_download_error_connect));
                }
            } else if (rc == ERROR_NO_SOURCE_FOUND) {
                failed = true;
                msg.append(getString(R.string.msg_downloaded_file_1005, fileName));
            } else if (rc == ERROR_YOUTUBE_LIVESTREAM) {
                failed = true;
                msg.append(getString(R.string.msg_downloaded_file_1017));
            } else if (rc == ERROR_YOUTUBE_CAPTCHA) {
                failed = true;
                msg.append(getString(R.string.msg_downloaded_file_1014));
            } else if (rc == ERROR_SSL_PEER_UNVERIFIED) {
                failed = true;
                String failedHost = order.getUri().getHost();
                if (failedHost == null) failedHost = "";
                msg.append(getString(R.string.msg_downloaded_file_1008, fileName, failedHost));
            } else if (rc == ERROR_CLEARTEXT_NOT_PERMITTED) {
                failed = true;
                String failedHost = order.getUri().getHost();
                if (failedHost == null) failedHost = "?";
                msg.append(getString(R.string.msg_downloaded_file_1011, failedHost));
            } else if (rc == ERROR_INTERRUPTED) {
                failed = true;
                File file = delivery.getFile();
                // offer to delete the file (the way it is implemented below, it works for a single file only - sorry)
                if (file != null && file.exists()) {
                    deleteFileAction = makeDeleteAction(file, notificationId);
                    msg.append(getString(R.string.msg_downloaded_file_1010, UiUtil.formatBytes(file.length())));
                } else {
                    msg.append(getString(R.string.msg_downloaded_file_1010_nofile));
                }
                // offer to retry the download
                retryAction = makeRetryAction(order, downloadId, notificationId);
            } else if (rc == ERROR_LACKING_SPACE) {
                failed = true;
                if (delivery.getThrowable() instanceof ResourceTooLargeException) {
                    msg.append(getString(R.string.msg_downloaded_file_1012_ws, UiUtil.formatBytes(((ResourceTooLargeException)delivery.getThrowable()).getSize())));
                } else {
                    msg.append(getString(R.string.msg_downloaded_file_1012));
                }
                // the user could choose to delete other files and try again…
                retryAction = makeRetryAction(order, downloadId, notificationId);
                //
                queueAction = makeQueueAction(order, notificationId);
            } else if (rc == ERROR_EVIL) {
                failed = true;
                msg.append(getString(R.string.msg_downloaded_file_1013));
            } else if (rc == ERROR_SSL_HANDSHAKE) {
                failed = true;
                String reason = (delivery.getThrowable() != null) ? delivery.getThrowable().getMessage() : null;
                msg.append(getString(R.string.msg_downloaded_file_1015)).append(!TextUtils.isEmpty(reason) ? ": " + reason : "");
                if (msg.charAt(msg.length() - 1) != '.') msg.append('.');
            } else if (rc == ERROR_VLC) {
                failed = true;
                msg.append(getString(R.string.msg_download_failed_no_more_info));
            } else {
                failed = true;
                String reason = (delivery.getThrowable() != null) ? delivery.getThrowable().getMessage() : null;
                if (!TextUtils.isEmpty(reason)) msg.append(getString(R.string.msg_downloaded_file_msg, fileName, reason));
                else msg.append(getString(R.string.msg_downloaded_file_rc, fileName, rc));
            }
        } else if (rc == 401) {
            failed = true;
            msg.append(getString(R.string.msg_downloaded_file_401));
            Delivery.AuthenticateInfo ai = delivery.getAuthenticateInfo();
            // allow the user to enter credentials and try again
            if (ai != null && AuthManager.isAuthSchemeSupported(ai.scheme)) {
                retryWithAuthorizationAction = makeRetry401Action(ai, order, downloadId, notificationId);
            }
        } else if (rc == 403) {
            failed = true;
            Uri src = order.getUri();
            String scheme = src.getScheme();
            if (UriUtil.isSupportedLocalScheme(scheme)) {
                String file = src.getSchemeSpecificPart();
                if (file.startsWith("///")) file = file.substring(2);
                msg.append(getString(R.string.msg_downloaded_file_403, file));
            } else {
                msg.append(getString(R.string.msg_downloaded_file_403, delivery.getOrder().getUrl()));
            }
        } else if (rc == 404) {
            failed = true;
            msg.append(getString(R.string.msg_downloaded_file_404, fileName));
        } else if (rc == 410) {
            failed = true;
            msg.append(getString(R.string.msg_downloaded_file_410, fileName));
        } else if (rc == 429) {
            failed = true;
            msg.append(getString(R.string.msg_downloaded_file_429));
        } else if (rc == 451) {
            failed = true;
            msg.append(getString(R.string.msg_downloaded_file_451, delivery.getOrder().getUrl()));
        } else if (rc == 503) {
            failed = true;
            String failedHostOrResource = order.getUri().getHost();
            if (failedHostOrResource == null) failedHostOrResource = fileName;
            msg.append(getString(R.string.msg_downloaded_file_503, failedHostOrResource));
        } else if (rc > 299 && rc != 304) {
            failed = true;
            msg.append(getString(R.string.msg_downloaded_file_rc, fileName, rc));
        } else if (rc == 304) {
            msg.append(getString(R.string.msg_downloaded_file_304, fileName));
        } else {
            // ##################################### Yay! Success! #####################################
            File downloaded = new File(order.getDestinationFolder(), fileName);
            final File result;
            int dot = fileName.lastIndexOf('.');
            // if there is no file extension, try to append one based on the media type that had been sent by the host
            if (dot < 0 && !TextUtils.isEmpty(delivery.getMediaType())) {
                result = Util.addExtensionFromMimeType(downloaded, delivery.getMediaType());
            } else {
                result = downloaded;
            }
            msg.append(getString(R.string.msg_downloaded_file, result.getName()));
            downloadedFile = result;
            getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);
        }
        msg.append('\n');

        if (msg.charAt(msg.length() - 1) == '\n') msg.deleteCharAt(msg.length() - 1);
        App app = (App)getApplicationContext();

        Intent intentUiActivity = new Intent(this, UiActivity.class);
        intentUiActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingIntentUiActivity = PendingIntent.getActivity(this, 1, intentUiActivity, PendingIntent.FLAG_UPDATE_CURRENT);

        app.removeNotificationBuilder(downloadId);
        CharSequence notificationTitle = cancelled ? getString(R.string.msg_download_cancelled)
                : (deferred ? getString(R.string.msg_download_deferred)
                : (failed ? (failedFileName != null ? getString(R.string.msg_download_failed_w_name, failedFileName) : getString(R.string.msg_download_failed))
                : getString(R.string.msg_download_complete)));
        final Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(failed ? R.drawable.ic_n_error : R.drawable.ic_n_download_done)
                .setContentTitle(notificationTitle)
                .setContentText(msg)
                .setAutoCancel(true)
                .setCategory(failed ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
                .setShowWhen(true)
                .setContentIntent(pendingIntentUiActivity)
        ;

        //
        if (msg.length() > this.notificationTextMaxLength) {
            // do not set summary text because that would end up in the place of the notification's subtext
            builder.setStyle(new Notification.BigTextStyle().bigText(msg));
        }

        //
        if (orderGroup != Order.NO_ORDER_GROUP) {
            builder.setGroup(String.valueOf(orderGroup));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
            }
        }

        // add Actions
        if (deleteFileAction != null) builder.addAction(deleteFileAction);
        if (retryAction != null) builder.addAction(retryAction);
        if (retryWithAuthorizationAction != null) builder.addAction(retryWithAuthorizationAction);
        if (cancelQueuedAction != null) builder.addAction(cancelQueuedAction);
        if (queueAction != null) builder.addAction(queueAction);

        // set color
        if (failed) {
            builder.setColor(Color.RED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setColorized(true);
            }
        }

        // set channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(app.getNc().getId());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowSystemGeneratedContextualActions(false);
        }

        // finally, show notification
        this.nm.notify(notificationId, builder.build());
        app.removeLoader(downloadId);
        this.nm.cancel(IdSupply.progressNotificationId(downloadId));

        if (!failed && orderGroup != Order.NO_ORDER_GROUP) {
            // https://developer.android.com/training/notify-user/group#java
            Integer icount = this.orderGroupCounter.get(orderGroup);
            final int count = (icount != null ? icount : 0);
            Notification.Builder summaryBuilder = new Notification.Builder(this)
                    .setContentTitle(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(orderGroup)))
                    .setContentText(getResources().getQuantityString(R.plurals.msg_download_all_count, count, count))
                    .setGroup(String.valueOf(orderGroup))
                    .setGroupSummary(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setContentIntent(pendingIntentUiActivity)
                    ;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && count > 0 && count <= 20) {
                int smallIconSize = UiUtil.getNotificationSmallIconSize(this);
                // https://en.wikibooks.org/wiki/Unicode/Character_reference/2000-2FFF
                @SuppressLint("UseValueOf")
                Bitmap bitmap = Util.makeCharBitmap(String.valueOf(new Character((char)(0x245F + count))), 0f, smallIconSize, smallIconSize, 0xff7f7f7f, Color.TRANSPARENT, null);
                summaryBuilder.setSmallIcon(Icon.createWithBitmap(bitmap));
            } else {
                summaryBuilder.setSmallIcon(R.drawable.ic_n_download_done);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                summaryBuilder.setChannelId(app.getNc().getId());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                summaryBuilder.setAllowSystemGeneratedContextualActions(false);
            }
            this.nm.notify(IdSupply.NOTIFICATION_ID_GROUP_SUMMARY, summaryBuilder.build());
        }

        if (downloadedFile != null) {
            app.getThumbsManager().refresh(app, downloadedFile);
        }

        if (this.refDoneListener != null) {
            DoneListener doneListener = this.refDoneListener.get();
            if (doneListener != null) doneListener.done();
        }

        if (!QueueManager.getInstance().nextPlease()) {
            stopForeground(true);
            letSleep();
        }

    }

    /**
     * Invokes {@link #startForeground(int, Notification)}.
     * @param downloadId download id
     * @param n Notification to show
     */
    private void foreground(@IntRange(from = IdSupply.DOWNLOAD_ID_OFFSET, to = IdSupply.NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId, @NonNull Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(IdSupply.progressNotificationId(downloadId), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(IdSupply.progressNotificationId(downloadId), n);
        }
    }

    @VisibleForTesting
    @TestOnly
    public PowerManager.WakeLock getWakeLock() {
        assert BuildConfig.DEBUG;
        return this.wakeLock;
    }

    @VisibleForTesting
    public void keepAwake() {
        if (this.wakeLock != null && this.wakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, BuildConfig.APPLICATION_ID + ":Lock");
        this.wakeLock.acquire(60 * 60_000L);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public void letSleep() {
        boolean wakelockHeld = this.wakeLock != null && this.wakeLock.isHeld();
        if (!wakelockHeld) return;
        this.wakeLock.release();
        this.wakeLock = null;
    }

    @Override
    public void liveStreamDetected(final int downloadId) {
        if (!this.stopActionAdded.contains(downloadId)) {
            App app = (App)getApplicationContext();
            final Notification.Builder builder = app.getNotificationBuilder(downloadId);
            if (builder == null) return;
            Intent stop = new Intent(this, LoaderService.class);
            stop.setAction(ACTION_STOP);
            stop.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            PendingIntent piStop = PendingIntent.getService(this, REQUEST_CODE_STOP, stop, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(UiUtil.makeNotificationAction(app, R.drawable.ic_baseline_stop_24, R.string.action_stop, piStop));
            this.stopActionAdded.add(downloadId);
            // we don not have to update the notification here because a regular progress*() call will follow now (See Loader.onProgressUpdate())
            //this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
        }
    }

    /**
     * Initiates a download via HTTP(S).
     * @param wish Wish, encompassing Uri to load from and optional title which will be shown in the notification (if null, the uri's last path segment will be shown)
     */
    @MainThread
    void load(@NonNull Wish wish) {
        if (BuildConfig.DEBUG) Log.i(TAG, "load(" + wish + ")");
        final int downloadId = nextDownloadId();
        foreground(downloadId, makeNotification(downloadId, wish.getUri(), wish.getTitle(), false, true));
        App app = (App)getApplicationContext();
        Downloader dl = new Downloader(downloadId, app.getOkHttpClient(), this);
        app.addLoader(downloadId, dl);
        Order order = new Order(wish);
        order.setDestinationFolder(App.getDownloadsDir(this).getAbsolutePath());
        if (wish.hasFileName()) {
            order.setDestinationFilename(wish.getFileName());
        } else {
            order.setDestinationFilename(!TextUtils.isEmpty(wish.getTitle()) ? wish.getTitle() : wish.getUri().getLastPathSegment());
        }
        dl.executeOnExecutor(this.loaderExecutor, order);
        keepAwake();
    }

    @MainThread
    void loadPlaylist(@NonNull Wish wish, @NonNull LoaderListener l) {
        App app = (App)getApplicationContext();
        Downloader dl = new Downloader(-1, app.getOkHttpClient(), l);
        final Order order = new Order(wish);
        order.setDestinationFolder(App.getDownloadsDir(this).getAbsolutePath());
        if (wish.hasFileName()) {
            order.setDestinationFilename(wish.getFileName());
        } else {
            order.setDestinationFilename(wish.getUri().getLastPathSegment());
        }
        File existing = new File(order.getDestinationFolder(), order.getDestinationFilename());
        // important: delete the file before attempting to download because we might get a 304 which can be misleading
        // because playlist files often have same names like "master.m3u8" or similar
        Util.deleteFile(existing);
        dl.executeOnExecutor(this.loaderExecutor, order);
    }

    @MainThread
    void loadViaUriHandler(@NonNull Wish wish, @NonNull UriHandler uriHandler) {
        if (BuildConfig.DEBUG) Log.i(TAG, "loadViaUriHandler(…, " + uriHandler + ")");
        Class<? extends Loader> loaderClass = uriHandler.getLoaderClass();
        if (loaderClass == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "loadViaUriHandler() called without a Loader!");
            Wish w = new Wish(uriHandler.getUri());
            w.setTitle(uriHandler.getTitle());
            load(w);
            return;
        }
        final int downloadId = nextDownloadId();
        Uri uri = uriHandler.getUri();
        foreground(downloadId, makeNotification(downloadId, uri, uriHandler.getTitle(), false, true));
        App app = (App)getApplicationContext();
        Loader loader = app.getLoaderFactory().create(downloadId, loaderClass, this);
        app.addLoader(downloadId, loader);
        final Order order = new Order(wish, uri);
        order.setDestinationFolder(App.getDownloadsDir(this).getAbsolutePath());
        if (wish.hasFileName()) {
            order.setDestinationFilename(wish.getFileName());
        } else {
            order.setDestinationFilename(uri.getLastPathSegment());
        }
        loader.executeOnExecutor(this.loaderExecutor, order);
    }

    /**
     * Loads a resource via FTP.
     * @param wish Wish representing the resource to load
     */
    void loadftp(@NonNull final Wish wish) {
        if (BuildConfig.DEBUG) Log.i(TAG, "loadftp(" + wish + ")");
        final int downloadId = nextDownloadId();
        foreground(downloadId, makeNotification(downloadId, wish.getUri(), wish.getTitle(), false, true));
        FtpLoader ftpLoader = new FtpLoader(downloadId, this);
        ftpLoader.setMores(((App)getApplicationContext()).getEvilBlocker());
        ((App)getApplicationContext()).addLoader(downloadId, ftpLoader);
        Order order = new Order(wish);
        order.setDestinationFolder(App.getDownloadsDir(this).getAbsolutePath());
        if (wish.hasFileName()) {
            order.setDestinationFilename(wish.getFileName());
        } else {
            order.setDestinationFilename(wish.getUri().getLastPathSegment());
        }
        ftpLoader.executeOnExecutor(this.loaderExecutor, order);
    }

    /**
     * Loads a resource via SFTP.<br>
     * <a href="https://en.wikipedia.org/wiki/SSH_File_Transfer_Protocol">https://en.wikipedia.org/wiki/SSH_File_Transfer_Protocol</a>
     * @param wish Wish representing the resource to load
     */
    void loadsftp(@NonNull final Wish wish) {
        if (BuildConfig.DEBUG) Log.i(TAG, "loadsftp(" + wish + ")");
        final int downloadId = nextDownloadId();
        foreground(downloadId, makeNotification(downloadId, wish.getUri(), wish.getTitle(), false, true));
        SftpLoader sftpLoader = new SftpLoader(downloadId, (App)getApplicationContext(), this);
        sftpLoader.setMores(((App)getApplicationContext()).getEvilBlocker());
        ((App)getApplicationContext()).addLoader(downloadId, sftpLoader);
        Order order = new Order(wish);
        order.setDestinationFolder(App.getDownloadsDir(this).getAbsolutePath());
        if (wish.hasFileName()) {
            order.setDestinationFilename(wish.getFileName());
        } else {
            order.setDestinationFilename(wish.getUri().getLastPathSegment());
        }
        sftpLoader.executeOnExecutor(this.loaderExecutor, order);
    }

    /**
     * Builds a Notification.Action offering to delete a downloaded file.
     * @param file  file
     * @param notificationId notification id
     * @return Notification.Action
     */
    @NonNull
    private Notification.Action makeDeleteAction(@NonNull File file, int notificationId) {
        Intent intentDeleteFile = new Intent(this, LoaderService.class);
        intentDeleteFile.setAction(ACTION_DELETE);
        intentDeleteFile.putExtra(EXTRA_FILE, file.getAbsolutePath());
        intentDeleteFile.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        PendingIntent piDeleteFile = PendingIntent.getService(this, REQUEST_CODE_DELETE, intentDeleteFile, PendingIntent.FLAG_UPDATE_CURRENT);
        return UiUtil.makeNotificationAction(this, android.R.drawable.ic_menu_delete, R.string.action_delete, piDeleteFile);
    }

    /**
     * Builds a Notification for a download.<br>
     * The resource name goes into the notification title.<br>
     * The notification content text is not set here.<br>
     * The notification will contain a 'cancel' action.
     * @param downloadId download id
     * @param uri download source
     * @param title download title
     * @param addStop true to add a 'stop' action
     * @param addDefer true to add a 'defer' action
     * @return Notification
     */
    @NonNull
    @VisibleForTesting
    public Notification makeNotification(final int downloadId, final @NonNull Uri uri, final @Nullable CharSequence title, boolean addStop, boolean addDefer) {

    /*
    Rules for notifications:
    notification small icon:    "download" symbol
    notification category:      CATEGORY_PROGRESS while running, CATEGORY_STATUS or CATEGORY_ERROR when finished
    notification content title: resource name
    notification content text:  depends on current status; may be like "Buffering 12 %…", "Recorded 1:23 h" or a special (error) message.
    notification subtext:       resource length (bytes or period of time)
    notification progress:      progress [0..100]
    notification ongoing:       true while running, false when finished

    According to https://material.io/design/platform-guidance/android-notifications.html#style,
    notification content titles should have no more than 30 chars,
    notification content texts should have no more than 40 chars.
     */
        App app = (App)getApplicationContext();
        String notificationTitle = title != null ? title.toString() : uri.getLastPathSegment();
        notificationTitle = UiUtil.trim(notificationTitle, this.notificationTitleMaxLength).toString();
        final Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_n_download)
                .setContentTitle(notificationTitle)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                ;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(app.getNc().getId());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowSystemGeneratedContextualActions(false);
        }

        final Intent cancel = new Intent(this, LoaderService.class);
        cancel.setAction(ACTION_CANCEL);
        cancel.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        PendingIntent piCancel = PendingIntent.getService(this, REQUEST_CODE_CANCEL, cancel, PendingIntent.FLAG_ONE_SHOT);
        builder.addAction(UiUtil.makeNotificationAction(app, R.drawable.ic_baseline_delete_24, android.R.string.cancel, piCancel));

        if (addDefer) {
            final Intent defer = new Intent(this, LoaderService.class);
            defer.setAction(ACTION_DEFER);
            defer.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            PendingIntent piDefer = PendingIntent.getService(this, REQUEST_CODE_DEFER, defer, PendingIntent.FLAG_ONE_SHOT);
            //TODO better icon for defer
            builder.addAction(UiUtil.makeNotificationAction(app, R.drawable.ic_baseline_queue_24, R.string.action_defer, piDefer));
        }

        if (addStop) {
            final Intent stop = new Intent(this, LoaderService.class);
            stop.setAction(ACTION_STOP);
            stop.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
            PendingIntent piStop = PendingIntent.getService(this, REQUEST_CODE_STOP, stop, PendingIntent.FLAG_ONE_SHOT);
            builder.addAction(UiUtil.makeNotificationAction(app, R.drawable.ic_baseline_stop_24, R.string.action_stop, piStop));
            // as there is no "getActions()" method in Notification.Builder, we must remember this in some other way:
            this.stopActionAdded.add(downloadId);
        }

        app.addNotificationBuilder(downloadId, builder);
        return builder.build();
    }

    /**
     * Builds a Notification.Action offering to add a download to the queue.
     * @param order Order
     * @param notificationId notification id
     * @return Notification.Action
     */
    @NonNull
    private Notification.Action makeQueueAction(@NonNull Order order, int notificationId) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Offering to add " + order + " to the queue and passing notification id of " + notificationId);
        final Intent intentQueue = new Intent(this, LoaderService.class);
        intentQueue.setAction(LoaderService.ACTION_QUEUE);
        intentQueue.putExtra(EXTRA_ORDER, order);
        intentQueue.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        PendingIntent piQueue = PendingIntent.getService(this, 1, intentQueue, PendingIntent.FLAG_UPDATE_CURRENT);
        return UiUtil.makeNotificationAction(this, R.drawable.ic_baseline_refresh_24, R.string.action_queue_add, piQueue);
    }

    /**
     * @param ai Delivery.AuthenticateInfo
     * @param order Order
     * @param downloadId download id
     * @param notificationId notification id
     * @return Notification.Action
     */
    @NonNull
    private Notification.Action makeRetry401Action(@NonNull final Delivery.AuthenticateInfo ai, @NonNull Order order, int downloadId, int notificationId) {
        Credential c = UriUtil.getCredential(order.getUri());
        if (c != null) {
            // strip user id and password from the obviously wrong Credential
            order = Order.stripCredentials(order);
        }
        final Intent intentRetry401 = new Intent(this, MainActivity.class);
        intentRetry401.setAction(MainActivity.ACTION_RETRY_401);
        intentRetry401.putExtra(EXTRA_ORDER, order);
        intentRetry401.putExtra(EXTRA_AUTH_REALM, ai.realm);
        intentRetry401.putExtra(EXTRA_AUTH_SCHEME, ai.scheme);
        if (!TextUtils.isEmpty(ai.userid)) intentRetry401.putExtra(EXTRA_AUTH_USER, ai.userid);
        intentRetry401.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        intentRetry401.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        intentRetry401.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piRetryWithAuthorization = PendingIntent.getActivity(this, REQUEST_CODE_RETRY, intentRetry401, PendingIntent.FLAG_UPDATE_CURRENT);
        return UiUtil.makeNotificationAction(this, R.drawable.ic_baseline_refresh_24, R.string.action_retry_authorization, piRetryWithAuthorization);
    }

    /**
     * Builds a Notification.Action offering to retry a download.
     * @param order Order
     * @param downloadId download id
     * @param notificationId notification id
     * @return Notification.Action
     */
    @NonNull
    private Notification.Action makeRetryAction(@NonNull Order order, int downloadId, int notificationId) {
        if (BuildConfig.DEBUG) Log.i(TAG, "Offering to retry " + order + " and passing notification id of " + notificationId);
        final Intent intentRetry = new Intent(this, MainActivity.class);
        intentRetry.setAction(MainActivity.ACTION_RETRY);
        intentRetry.putExtra(EXTRA_ORDER, order);
        intentRetry.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        intentRetry.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        intentRetry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? ActivityOptions.makeBasic() : null);
        PendingIntent piRetry = PendingIntent.getActivity(this, 1, intentRetry, PendingIntent.FLAG_UPDATE_CURRENT, options != null ? options.toBundle() : null);
        return UiUtil.makeNotificationAction(this, R.drawable.ic_baseline_refresh_24, R.string.action_retry, piRetry);
    }

    /** {@inheritDoc} */
    @Override
    public void message(int downloadId, @NonNull String msg, boolean isError) {
        if (DebugUtil.TEST) {
            if (msg.startsWith("Resuming: ")) {
                Intent b = new Intent(App.ACTION_DOWNLOAD_RESUMING);
                b.putExtra("RESUMING", msg);
                sendBroadcast(b);
            }
        }
        final Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
        if (builder == null) return;
        builder.setContentText(msg);
        if (msg.length() > this.notificationTextMaxLength) {
            builder.setStyle(new Notification.BigTextStyle().bigText(msg));
        }
        if (isError) {
            builder.setColor(Color.RED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColorized(true);
        } else {
            builder.setColor(Color.BLACK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColorized(false);
        }
        this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
    }

    /** {@inheritDoc} */
    @Override
    public void noprogress(int downloadId) {
        Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
        if (builder == null) return;
        builder.setProgress(100, 0, true).setContentText(getString(R.string.msg_progress_unknown));
        this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
    }

    /** {@inheritDoc} */
    @Override
    public IBinder onBind(Intent intent) {
        return this.binder;
    }

    /** {@inheritDoc} */
    @Override
    public void onCreate() {
        super.onCreate();
        this.loaderExecutor = Executors.newCachedThreadPool();
        this.nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        this.notificationTitleMaxLength = getResources().getInteger(R.integer.notification_title_maxlength);
        this.notificationTextMaxLength = getResources().getInteger(R.integer.notification_text_maxlength);
        try {
            startService(new Intent(this, getClass()));
        } catch (IllegalStateException e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "onCreate() - startService(): " + e.toString(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onDestroy() {
        letSleep();
        if (this.loaderExecutor != null && !this.loaderExecutor.isShutdown()) {
            this.loaderExecutor.shutdown();
            this.loaderExecutor = null;
        }
        super.onDestroy();
    }

    /** {@inheritDoc} */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return super.onStartCommand(null, flags, startId);
        final String action = intent.getAction();
        if (App.ACTION_CHECK_NIGHT.equals(action)) {
            App.checkDefaultNightMode(this);
            return START_NOT_STICKY;
        } else if (ACTION_CANCEL.equals(action)) {
            App app = (App) getApplicationContext();
            int downloadId = intent.getIntExtra(EXTRA_DOWNLOAD_ID, Integer.MIN_VALUE);
            if (downloadId > Integer.MIN_VALUE) {
                Loader loader = app.getLoader(downloadId);
                if (loader != null) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Cancelling " + loader);
                    loader.cancel(true);
                } else {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Cannot cancel loader " + downloadId);
                    this.nm.cancel(IdSupply.progressNotificationId(downloadId));
                }
            } else if (BuildConfig.DEBUG) Log.e(TAG, "No ID");
            letSleep();
            return START_REDELIVER_INTENT;
        } else if (ACTION_DEFER.equals(action)) {
            App app = (App) getApplicationContext();
            int downloadId = intent.getIntExtra(EXTRA_DOWNLOAD_ID, Integer.MIN_VALUE);
            if (downloadId > Integer.MIN_VALUE) {
                Loader loader = app.getLoader(downloadId);
                if (loader != null) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Deferring " + loader);
                    Order[] orders = loader.getOrders();
                    loader.defer();
                    if (orders != null && orders.length > 0) {
                        QueueManager queueManager = QueueManager.getInstance();
                        for (Order order : orders) {
                            if (order == null) continue;
                            // http://archive.org/download/gd88-07-03.sbd.ststephen.3908.sbeok.shnf/gd88-07-03d1t01.mp3
                            Wish wish = order.getWish();
                            if (wish == null) {
                                if (BuildConfig.DEBUG) Log.e(TAG, "Cannot queue " + order + ": no Wish!");
                                continue;
                            }
                            if (BuildConfig.DEBUG) Log.i(TAG, "Adding to queue " + wish);
                            wish.setHeld(true);
                            queueManager.add(wish);
                        }
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Cannot defer loader " + downloadId + " - unknown to App");
                    this.nm.cancel(IdSupply.progressNotificationId(downloadId));
                }
            } else if (BuildConfig.DEBUG) Log.e(TAG, "No Loader ID");
            letSleep();
            return START_REDELIVER_INTENT;
        } else if (ACTION_STOP.equals(action)) {
            App app = (App)getApplicationContext();
            int id = intent.getIntExtra(EXTRA_DOWNLOAD_ID, Integer.MIN_VALUE);
            if (id > Integer.MIN_VALUE) {
                Loader d = app.getLoader(id);
                if (d != null) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Stopping " + d);
                    d.holdon();
                } else if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Cannot stop loader " + id);
                    this.nm.cancel(IdSupply.progressNotificationId(id));
                }
            } else if (BuildConfig.DEBUG) Log.e(TAG, "No ID");
            letSleep();
            return START_REDELIVER_INTENT;
        } else if (ACTION_DELETE.equals(action)) {
            String path = intent.getStringExtra(EXTRA_FILE);
            File file = path != null ? new File(path) : null;
            if (file != null && file.exists() && !file.delete()) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to delete " + file);
            }
            int nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
            if (nid != -1) this.nm.cancel(nid);
            return START_REDELIVER_INTENT;
        } else if (ACTION_QUEUE.equals(action)) {
            Order order = intent.getParcelableExtra(EXTRA_ORDER);
            if (order == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "No order given with ACTION_QUEUE");
                return START_NOT_STICKY;
            }
            Wish wish = order.getWish();
            if (wish == null) {
                wish = new Wish(order.getUri());
            }
            QueueManager.getInstance().add(wish);
            int nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
            if (nid != -1) this.nm.cancel(nid);
            return START_REDELIVER_INTENT;
        } else if (ACTION_UNQUEUE.equals(action)) {
            ArrayList<Wish> unqueueUs = intent.getParcelableArrayListExtra(EXTRA_UNQUEUE_US);
            if (unqueueUs != null) {
                Wish[] array = new Wish[unqueueUs.size()];
                unqueueUs.toArray(array);
                int removed = QueueManager.getInstance().remove(array);
                if (BuildConfig.DEBUG) Log.i(TAG, "Removed " + removed + " item(s).");
            } else if (BuildConfig.DEBUG) {
                Log.e(TAG, "Got ACTION_UNQUEUE with no Wishes!");
            }
            int nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
            if (nid != -1) this.nm.cancel(nid);
        } else if (UiActivity.ACTION_INSTALL.equals(action)) {
            if (BuildConfig.DEBUG) DebugUtil.logIntent(TAG, intent);
            int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0);
            String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            Intent intentForUserAction = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (intentForUserAction != null) {
                // this is an Intent with action "android.content.pm.action.CONFIRM_PERMISSIONS" and Integer extra "android.content.pm.extra.SESSION_ID"
                try {
                    intentForUserAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intentForUserAction);
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                    try {if (id != -1) getPackageManager().getPackageInstaller().abandonSession(id);} catch (Throwable ignored) {}
                }
            }
            if (id != -1) {
                final Notification.Builder builder = new Notification.Builder(this);
                builder.setContentTitle("Installation").setSmallIcon(R.drawable.ic_baseline_hardware_24).setCategory(Notification.CATEGORY_STATUS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(((App) getApplicationContext()).getNc().getId());

                String msg;
                switch (status) {
                    case PackageInstaller.STATUS_PENDING_USER_ACTION: msg = getString(R.string.msg_installation_waiting); break;
                    case PackageInstaller.STATUS_SUCCESS: msg = packageName != null ? getString(R.string.msg_installation_successful_w_label, packageName) : getString(R.string.msg_installation_successful); break;
                    case PackageInstaller.STATUS_FAILURE_ABORTED: msg = getString(R.string.msg_installation_aborted); break;
                    default: msg = packageName != null ? getString(R.string.msg_installation_failed_w_label, packageName) : getString(R.string.msg_installation_failed);
                }
                builder
                        .setContentText(msg)
                        .setOngoing(status < 0)
                        .setAutoCancel(status >= 0)
                ;
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    builder.setColor(getResources().getColor(R.color.colorSecondary));
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColorized(true).setTimeoutAfter(30_000L);
                } else if (status >= PackageInstaller.STATUS_FAILURE) {
                    builder.setColor(0xffff0000).setCategory(Notification.CATEGORY_ERROR);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColorized(true).setTimeoutAfter(60_000L);
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    builder.setShowWhen(true);
                    if (packageName != null) {
                        Intent contentIntent = getPackageManager().getLaunchIntentForPackage(packageName);
                        if (contentIntent != null) builder.setContentIntent(PendingIntent.getActivity(this, 1, contentIntent, 0));
                    }
                } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent cancelIntent = new Intent(this, LoaderService.class);
                    cancelIntent.setAction(ACTION_CANCEL_INSTALLATION);
                    cancelIntent.putExtra(PackageInstaller.EXTRA_SESSION_ID, id);
                    cancelIntent.putExtra(EXTRA_NOTIFICATION_ID, IdSupply.NOTIFICATION_ID_INSTALL_OFFSET + id);
                    PendingIntent pi = PendingIntent.getService(this, id, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                    Notification.Action cancelAction = UiUtil.makeNotificationAction(this, R.drawable.ic_baseline_stop_24, R.string.action_install_cancel, pi);
                    builder.addAction(cancelAction);
                }
                this.nm.notify(IdSupply.NOTIFICATION_ID_INSTALL_OFFSET + id, builder.build());
            }
        } else if (ACTION_CANCEL_INSTALLATION.equals(action)) {
            int installationSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            this.nm.cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0));
            try {
                getPackageManager().getPackageInstaller().abandonSession(installationSessionId);
                Intent intentBackToUiActivity = new Intent(this, UiActivity.class);
                intentBackToUiActivity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(intentBackToUiActivity);
            } catch (SecurityException e) {
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            }
        }
        return START_STICKY;
    }

    /** {@inheritDoc} */
    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) letSleep();
    }

    /** {@inheritDoc} */
    @Override
    public void progress(int downloadId, @FloatRange(to = 1) final float progress, final int remainingSeconds) {
        //if (BuildConfig.DEBUG) Log.i(TAG,"progress(" + downloadId + ", " + progress + ", " + remainingSeconds + ")");
        final Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
        if (builder == null) return;
        if (progress < 0f) {
            builder.setProgress(100, 0, true);
        } else {
            int p = Math.round(100f * progress);
            String msg = (remainingSeconds > 0 && remainingSeconds < Integer.MAX_VALUE)
                    ? getString(R.string.msg_progress_w_remaining, p, UiUtil.formatMs(1_000 * remainingSeconds))
                    : getString(R.string.msg_progress, p);
            builder.setProgress(100, p, false)
                    .setContentText(msg);
            if (msg.length() > this.notificationTextMaxLength) {
                // do not set summary text because that would end up in the place of the notification's subtext
                builder.setStyle(new Notification.BigTextStyle().bigText(msg));
            }
        }
        this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
    }

    /** {@inheritDoc} */
    @Override
    public void progressAbsolute(int downloadId, final float ms, final long msTotal, final int remainingSeconds) {
        //if (BuildConfig.DEBUG) Log.i(TAG, "progressAbsolute(" + downloadId + ", " + ms  + ", " + msTotal + ", " + remainingSeconds + ")");
        final Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
        if (builder == null) return;
        if (msTotal <= ms) {
            builder.setProgress(0, 0, true);
            liveStreamDetected(downloadId);
        } else {
            builder.setProgress(100, Math.round(100f * ms / (float)msTotal), false)
                   .setSubText(UiUtil.formatMs(msTotal));
        }
        if (ms >= 0f) {
            String msg = (remainingSeconds > 0 && remainingSeconds < 10_000_000)
                    ? getString(R.string.msg_recorded_w_remaining, UiUtil.formatMs(ms), UiUtil.formatMs(1_000 * remainingSeconds))
                    : getString(R.string.msg_recorded, UiUtil.formatMs(ms));
            builder.setContentText(msg);
            if (msg.length() > this.notificationTextMaxLength) {
                // do not set summary text because that would end up in the place of the notification's subtext
                builder.setStyle(new Notification.BigTextStyle().bigText(msg));
            }
        }
        this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
    }

    /** {@inheritDoc} */
    @Override
    public void receivedResourceName(int downloadId, @NonNull String resourceName) {
        final Notification.Builder builder = ((App)getApplicationContext()).getNotificationBuilder(downloadId);
        if (builder == null) return;
        resourceName = UiUtil.trim(resourceName, this.notificationTitleMaxLength).toString();
        builder.setContentTitle(resourceName);
        this.nm.notify(IdSupply.progressNotificationId(downloadId), builder.build());
    }

    /**
     * Writes plain text that has been passed to this app to a text file.<br>
     * If a referrer is given, it is used to create a file name.
     * @param text text to save
     * @param referrer optional result of {@link Activity#getReferrer()}
     */
    void saveJustText(@NonNull CharSequence text, @Nullable Uri referrer) {
        String filename = null;
        if (BuildConfig.DEBUG) Log.i(TAG, "Got \"" + text + "\" from " + referrer);
        final int downloadId = nextDownloadId();
        if (referrer != null) {
            String ssp = referrer.getSchemeSpecificPart();
            if (ssp.startsWith("//")) ssp = ssp.substring(2);
            ApplicationInfo ai;
            try {
                ai = getPackageManager().getApplicationInfo(ssp, 0);
                CharSequence label = getPackageManager().getApplicationLabel(ai);
                ssp = label.toString();
            } catch (Exception ignored) {
            }
            filename = getString(R.string.label_file_justtext, ssp.replace(File.separatorChar, REPLACEMENT_FOR_FILESEPARATOR).replace(':', REPLACEMENT_FOR_FILESEPARATOR));
        }
        if (filename == null) {
            filename = UiUtil.trim(text, 252) + ".txt";
        }
        File dir = App.getDownloadsDir(this);
        File file = new File(dir, filename);
        String alt = Util.suggestAlternativeFilename(file);
        if (alt != null) {
            file = new File(dir, alt);
        }
        Intent contentIntent = new Intent(this, UiActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_n_download_done)
                .setContentTitle(UiUtil.trim(file.getName(), this.notificationTitleMaxLength))
                .setContentText(getString(R.string.msg_download_stored))
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 1, contentIntent, 0))
                ;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(((App)getApplicationContext()).getNc().getId());
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            Util.close(out);
            out = null;
            //Uri notifyUri = buildChildDocumentsUri(Dogs.AUTHORITY, Dogs.ROOT_DOC);
            getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(IdSupply.completionNotificationId(downloadId), builder.build());
        } catch (IOException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            Util.deleteFile(file);
        } finally {
            Util.close(out);
        }
    }

    /**
     * Sets a DoneListener that will be called when a Download has finished.
     * DoneListener is useful for classes that are not intereted in download progress etc. as provided via {@link LoaderListener}.
     * @param doneListener DoneListener
     */
    public void setDoneListener(@Nullable DoneListener doneListener) {
        this.refDoneListener = doneListener != null ? new WeakReference<>(doneListener) : null;
    }

    /**
     * Initiates a download via libVlc.
     * @param item PlaylistItem (required)
     * @param title optional title
     * @param mime playlist media type as received from the remote host, or null if sourced locally
     * @param additionalAudioUrls optional additional audio urls to add
     */
    @MainThread
    void stream(@NonNull final PlaylistItem item, @Nullable String title, @Nullable String mime, @Nullable String... additionalAudioUrls) {
        if (BuildConfig.DEBUG) Log.i(TAG, "stream(" + item + ", " + title + ", " + mime + ", " + (additionalAudioUrls != null ? Arrays.toString(additionalAudioUrls) : "<null>") + ")");

        final Order order = new Order(item.getUri());
        order.setDestinationFolder(App.getDownloadsDir(this).getAbsolutePath());

        String fileExtension;
        if (mime != null) {
            fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (fileExtension != null) fileExtension = "." + fileExtension;
            else fileExtension = item.suggestFileExtension();
        } else {
            fileExtension = item.suggestFileExtension();
        }

        // fill in fileExtension or mime, if not yet known
        if (fileExtension != null) {
            order.setMime(MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.startsWith(".") ? fileExtension.substring(1) : fileExtension));
        } else if (mime != null) {
            order.setMime(mime);
            fileExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (fileExtension != null) {
                fileExtension = '.' + fileExtension;
            } else {
                switch (mime) {
                    case "application/vnd.apple.mpegurl":
                        fileExtension = ".m3u8";
                        break;
                    case "audio/x-scpls":
                        fileExtension = ".pls";
                        break;
                    case "audio/x-pn-realaudio":
                        fileExtension = ".ram";
                        break;
                }
            }
        }

        // file extension still not known? Then just guess…
        if (fileExtension == null) {
            String uris = item.getUri().toString();
            if (uris.contains("mp3")) {
                fileExtension = ".mp3";
            } else if (uris.contains("ogg")) {
                fileExtension = ".ogg";
            } else {
                fileExtension = ".mp4";
            }
        }

        final String fileName;
        if (title != null) {
            fileName = title.replace(File.separatorChar, REPLACEMENT_FOR_FILESEPARATOR);
        } else {
            final List<String> parts = item.getUri().getPathSegments();
            if (parts.size() >= 2) {
                fileName = parts.get(parts.size() - 2);
            } else if (parts.size() == 1) {
                fileName = parts.get(0);
            } else {
                fileName = (item.getUri().getSchemeSpecificPart()).replace(File.separatorChar, REPLACEMENT_FOR_FILESEPARATOR);
            }
        }
        order.setDestinationFilename(fileName.endsWith(fileExtension) ? fileName : fileName + fileExtension);

        final int downloadId = nextDownloadId();
        foreground(downloadId, makeNotification(downloadId, item.getUri(), title, true, true));
        final Streamer s = new Streamer(downloadId, this, this);
        ((App)getApplicationContext()).addLoader(downloadId, s);

        // https://apasfiis.sf.apa.at/ipad/cms-worldwide/2021-01-08_1930_tl_02_ZIB-1_Buerger--ORF--u__14077665__o__1686697666__s14832459_9__BCK2HD_19333600P_19354616P_Q4A.mp4/playlist.m3u8
        // https://abc-iview-mediapackagestreams-2.akamaized.net/out/v1/6e1cc6d25ec0480ea099a5399d73bc4b/index.m3u8
        String alt = Util.suggestAlternativeFilename(new File(order.getDestinationFolder(), order.getDestinationFilename()));
        if (alt != null) {
            order.setDestinationFilename(alt);
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "Using \"" + order.getDestinationFilename() + "\" as file name");
        if (additionalAudioUrls != null) {
            for (String additionalUrl : additionalAudioUrls) order.addAudioUrl(additionalUrl);
        }
        s.executeOnExecutor(this.loaderExecutor, order);
        keepAwake();
        if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STREAMING_STARTED));
    }

    /**
     * Gets called when a download has finished.
     */
    interface DoneListener {

        /**
         * A download has finished.
         */
        @UiThread
        void done();    // a Delivery object could be passed here, if required…
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ERROR_OTHER, ERROR_DEFERRED, ERROR_DEST_DIRECTORY_NOT_EXISTENT, ERROR_CANCELLED, ERROR_CONTEXT_GONE, ERROR_NO_SOURCE_FOUND,
            ERROR_COPY_FROM_MYSELF, ERROR_NO_FILENAME, ERROR_SSL_PEER_UNVERIFIED, ERROR_CANNOT_CONNECT, ERROR_INTERRUPTED,
            ERROR_CLEARTEXT_NOT_PERMITTED, ERROR_LACKING_SPACE, ERROR_EVIL, ERROR_YOUTUBE_CAPTCHA, ERROR_SSL_HANDSHAKE,
            ERROR_VLC, ERROR_YOUTUBE_LIVESTREAM
    })
    public @interface LoadError {}

    /**
     *
     */
    static final class LoaderServiceBinder extends Binder {

        @NonNull
        private final Reference<LoaderService> refService;

        /**
         * Constructor.
         * @param service LoaderService
         */
        private LoaderServiceBinder(@NonNull LoaderService service) {
            super();
            this.refService = new WeakReference<>(service);
        }

        /**
         * @return LoaderService
         */
        @Nullable
        LoaderService getLoaderService() {
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

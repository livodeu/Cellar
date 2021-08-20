/*
 * App.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.text.TextUtils;
import android.util.SparseArray;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import net.cellar.auth.AuthManager;
import net.cellar.model.Credential;
import net.cellar.model.Order;
import net.cellar.model.UnsupportedAuthChallengeException;
import net.cellar.net.EvilBlocker;
import net.cellar.net.NetworkChangedReceiver;
import net.cellar.net.ProxyPicker;
import net.cellar.queue.QueueManager;
import net.cellar.supp.HttpLogger;
import net.cellar.supp.IdSupply;
import net.cellar.supp.Log;
import net.cellar.supp.ThreadLocalFFmpegMediaMetadataRetriever;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;
import net.cellar.worker.Loader;
import net.cellar.worker.LoaderFactory;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Challenge;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 *
 */
public class App extends Application implements SharedPreferences.OnSharedPreferenceChangeListener, okhttp3.Authenticator, NetworkChangedReceiver.ConnectivityChangedListener {

    public static final String ACTION_BACKUP_RESTORE_STARTED = BuildConfig.APPLICATION_ID + ".backuprestore";
    /** for testing only - delivers {@link Order#getDestinationFilename() the destination file name} as {@link LoaderService#EXTRA_FILE extra} */
    public static final String ACTION_DOWNLOAD_FINISHED = BuildConfig.APPLICATION_ID + ".downloaded";
    /** for testing only */
    public static final String ACTION_DOWNLOAD_QUEUED = BuildConfig.APPLICATION_ID + ".queued";
    /** for testing only */
    public static final String ACTION_DOWNLOAD_RESUMING = BuildConfig.APPLICATION_ID + ".resuming";
    /** for testing only */
    public static final String ACTION_DOWNLOAD_STARTED = BuildConfig.APPLICATION_ID + ".downloading";
    /** for testing only */
    public static final String ACTION_DOWNLOAD_PLAYLIST_LOADED = BuildConfig.APPLICATION_ID + ".playlistloaded";
    /** for testing only */
    public static final String ACTION_DOWNLOAD_STREAMING_STARTED = BuildConfig.APPLICATION_ID + ".streamingstarted";
    /** default proxy port (if not set by user) */
    public static final int DEFAULT_PROXY_PORT = 80;
    /** default MIME type to be used when a MIME type could not be determined */
    public static final String MIME_DEFAULT = "application/octet-stream";
    public static final ThreadLocalFFmpegMediaMetadataRetriever MMRT = new ThreadLocalFFmpegMediaMetadataRetriever();
    /**
     * boolean: allow download over metered network connections<br>
     * <a href="https://developer.android.com/reference/android/net/ConnectivityManager?hl=en#isActiveNetworkMetered()">What is metered?</a>
     */
    public static final String PREF_ALLOW_METERED = "pref_allow_metered";
    /** the default value for {@link #PREF_ALLOW_METERED} - set to true to avoid confusion when the App is opened the first time and "No network connection" would show… */
    public static final boolean PREF_ALLOW_METERED_DEFAULT = true;
    /** String: url to download blacklist from */
    public static final String PREF_BLACKLIST = "pref_blacklist";
    public static final String PREF_BLACKLIST_DEFAULT = null;
    /** boolean: activate {@link ClipSpy} */
    public static final String PREF_CLIPSPY = "pref_clipspy";
    /** must be false as it does not work from API 30 on */
    public static final boolean PREF_CLIPSPY_DEFAULT = false;
    /** boolean: if true, adjust night mode according to time of day; if false, follow the system rules - the default value comes from {@link R.bool#night_mode_by_time} */
    public static final String PREF_NIGHT = "pref_night";
    /** int [0..23] */
    public static final String PREF_NIGHT_FROM = "pref_night_from";
    /** int [0..23] */
    public static final String PREF_NIGHT_TO = "pref_night_to";
    /** String: list of endings of host names that the proxy should be used for */
    public static final String PREF_PROXY_RESTRICT = "pref_proxy_restrict";
    /** String: proxyserver:port */
    public static final String PREF_PROXY_SERVER = "pref_proxy_server";
    /** String: DIRECT, HTTP or SOCKS */
    public static final String PREF_PROXY_TYPE = "pref_proxy_type";
    /** int: preferred quality level when asking the user is not possible */
    public static final String PREF_QUALITY = "pref_quality";
    /** int */
    public static final String PREF_SORT = "pref_sort";
    /** boolean */
    public static final String PREF_SORT_INV = "pref_sort_inv";
    /** boolean: translucent navigation bar */
    public static final String PREF_TRANSLUCENT_NAVIGATION = "pref_translucent_navigation";
    public static final boolean PREF_TRANSLUCENT_NAVIGATION_DEFAULT = true;
    public static final String PREF_UUID = "pref_uuid";
    /** <em>String</em>: connect via VPNs */
    public static final String PREF_VIA_VPN = "pref_via_vpn";
    /** int: audio bit rate used when recoding is necessary */
    public static final String PREF_VLC_BITRATE = "pref_vlc_bitrate";
    /** default value for {@link #PREF_VLC_BITRATE} */
    public static final int PREF_VLC_BITRATE_DEFAULT = 128;
    /** int: audio sample rate used when recoding is necessary [0..48000] */
    public static final String PREF_VLC_SAMPLERATE = "pref_vlc_samplerate";
    /** default value for {@link #PREF_VLC_SAMPLERATE} */
    public static final int PREF_VLC_SAMPLERATE_DEFAULT = 44_100;
    /** long: warn if available storage is below this value */
    public static final String PREF_WARN_LOW_STORAGE = "pref_warn_low_storage";
    /** default value for {@link #PREF_WARN_LOW_STORAGE} */
    public static final long PREF_WARN_LOW_STORAGE_DEFAULT = 200_000_000L;
    @Quality public static final int QUALITY_HIGHEST = 3;
    @Quality public static final int QUALITY_LOWEST = 1;
    @Quality public static final int QUALITY_UNDEFINED = 0;
    /** the supported local uri schemes plus "://" that this app supports (e.g. "file://") */
    public static final String[] SUPPORTED_LOCAL_PREFIXES;
    /** the supported local uri schemes that this app supports */
    @Size(2)
    public static final String[] SUPPORTED_LOCAL_SCHEMES = new String[] {"content", "file"};
    /** the supported uri schemes plus "://" that this app supports (e.g. "https://") */
    public static final String[] SUPPORTED_PREFIXES;
    /** the supported remote uri schemes plus "://" that this app supports (e.g. "https://") */
    public static final String[] SUPPORTED_REMOTE_PREFIXES;
    /** the supported remote uri schemes that this app supports */
    @Size(4)
    public static final String[] SUPPORTED_REMOTE_SCHEMES = new String[] {"https", "http", "ftp", "sftp"};
    /** the supported uri schemes that this app supports */
    public static final String[] SUPPORTED_SCHEMES;
    /** timeout for connections in milliseconds */
    public static final int TIMEOUT_CONNECT = 15_000;
    /** timeout for read operations in milliseconds */
    public static final int TIMEOUT_READ = 20_000;
    @ViaVpn public static final int VIA_VPN_ALWAYS = 1;
    @ViaVpn public static final int VIA_VPN_NEVER = 2;
    @ViaVpn public static final int VIA_VPN_TOO = 0;
    public static final int PREF_VIA_VPN_DEFAULT = VIA_VPN_TOO;
    static final String ACTION_CHECK_NIGHT = BuildConfig.APPLICATION_ID + ".CHECK_NIGHT";
    /** if the clipboard contains a Uri that points to a resource of a type that starts with these prefixes, then offer to download that resource */
    static final String[] MIMES_AUTO = new String[] {
            "audio/", "image/", "video/",
            "application/epub+zip",
            "application/gzip",
            "application/msword",
            "application/ogg",
            "application/pdf",
            "application/rar",
            "application/tar",
            "application/vnd.android.package-archive",
            "application/vnd.apple.mpegurl",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.text",
            "application/x-flac",
            "application/x-iso9660-image",
            "application/x-shorten",
            "application/zip",
            "multipart/x-mixed-replace"
    };
    static final int NOTIFICATION_ID = 1;
    /** boolean: use multicolumn layout - the default is {@link R.bool#grid_by_default} */
    static final String PREF_UI_GRID_LAYOUT = "pref_ui_grid_layout";
    @SortMode static final int SORT_DATE = 0;
    @SortMode static final int SORT_NAME = 1;
    @SortMode static final int SORT_SIZE = 2;
    private static final char[] ILLEGAL_FILENAME_CHARS = new char[] {':', '>', '<', '\\'};
    private static final String TAG = "App";
    /** timeout for write operations in milliseconds */
    private static final int TIMEOUT_WRITE = 20_000;

    static {
        SUPPORTED_SCHEMES = new String[SUPPORTED_REMOTE_SCHEMES.length + SUPPORTED_LOCAL_SCHEMES.length];
        System.arraycopy(SUPPORTED_REMOTE_SCHEMES, 0, SUPPORTED_SCHEMES, 0, SUPPORTED_REMOTE_SCHEMES.length);
        System.arraycopy(SUPPORTED_LOCAL_SCHEMES, 0, SUPPORTED_SCHEMES, SUPPORTED_REMOTE_SCHEMES.length, SUPPORTED_LOCAL_SCHEMES.length);

        SUPPORTED_REMOTE_PREFIXES = new String[SUPPORTED_REMOTE_SCHEMES.length];
        SUPPORTED_LOCAL_PREFIXES = new String[SUPPORTED_LOCAL_SCHEMES.length];
        for (int i = 0; i < SUPPORTED_REMOTE_SCHEMES.length; i++) SUPPORTED_REMOTE_PREFIXES[i] = SUPPORTED_REMOTE_SCHEMES[i] + "://";
        for (int i = 0; i < SUPPORTED_LOCAL_SCHEMES.length; i++) SUPPORTED_LOCAL_PREFIXES[i] = SUPPORTED_LOCAL_SCHEMES[i] + "://";

        SUPPORTED_PREFIXES = new String[SUPPORTED_REMOTE_PREFIXES.length + SUPPORTED_LOCAL_PREFIXES.length];
        System.arraycopy(SUPPORTED_REMOTE_PREFIXES, 0, SUPPORTED_PREFIXES, 0, SUPPORTED_REMOTE_PREFIXES.length);
        System.arraycopy(SUPPORTED_LOCAL_PREFIXES, 0, SUPPORTED_PREFIXES, SUPPORTED_REMOTE_PREFIXES.length, SUPPORTED_LOCAL_PREFIXES.length);
    }

    /**
     * Sets the {@link AppCompatDelegate#setDefaultNightMode(int) default night mode}.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    static void checkDefaultNightMode(@NonNull Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        int nightFrom = prefs.getInt(App.PREF_NIGHT_FROM, ctx.getResources().getInteger(R.integer.night_from_default));
        int nightTo = prefs.getInt(App.PREF_NIGHT_TO, ctx.getResources().getInteger(R.integer.night_to_default));
        if (nightFrom != nightTo) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean night = nightFrom < nightTo ? (h >= nightFrom && h < nightTo) : (h >= nightFrom || h < nightTo);
            //if (BuildConfig.DEBUG) Log.i(ctx.getClass().getSimpleName(), "Default night mode from " + nightFrom + " to " + nightTo + ": now is " + (night ? "night" : "day"));
            AppCompatDelegate.setDefaultNightMode(night ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
        } else {
            //if (BuildConfig.DEBUG) Log.i(ctx.getClass().getSimpleName(), "Default night mode follows system");
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    /**
     * Generates a file name based on the given title or the given Uri.
     * @param title title
     * @param uri Uri
     * @param tag file extension without a leading '.' (optional)
     * @return file name
     */
    @SuppressWarnings("ConstantConditions")
    @NonNull
    static String generateFilename(@Nullable final CharSequence title, @Nullable final Uri uri, @Nullable final String tag) {
        if (BuildConfig.DEBUG) Log.i(TAG, "generateFilename(" + title + ", " + uri + ", " + tag + ")");
        CharSequence filename = title;
        if (TextUtils.isEmpty(filename) && uri != null) filename = uri.getLastPathSegment();
        if (TextUtils.isEmpty(filename)) filename = "file_" + System.currentTimeMillis();
        int lastSlash = TextUtils.lastIndexOf(filename, File.separatorChar);
        if (lastSlash > 0) {
            filename = filename.subSequence(lastSlash + 1, filename.length());
        }
        String filenames = filename.toString();
        for (char illegalFilenameChar : ILLEGAL_FILENAME_CHARS) {
            filenames = filenames.replace(illegalFilenameChar, '_');
        }
        if (!TextUtils.isEmpty(tag) && !filenames.toLowerCase(java.util.Locale.US).endsWith(tag.toLowerCase(java.util.Locale.US))) {
            if (tag.charAt(0) == '.') filenames = filenames + tag; else filenames = filenames + '.' + tag;
        }
        return filenames;
    }

    /**
     * Creates and/or returns the downloads directory.
     * @param ctx Context
     * @return the directory to receive and store the downloads in
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    @NonNull
    public static File getDownloadsDir(@NonNull Context ctx) {
        return Util.getFilePath(ctx, FilePath.DOWNLOADS, true);
    }

    private final SparseArray<Loader> loaders = new SparseArray<>(4);
    private final SparseArray<Notification.Builder> nbuilders = new SparseArray<>(4);
    private final Object okhttpclientLock = new Object();
    private ThumbsManager thumbsManager;
    private NotificationChannel nc;
    private NotificationChannel ncImportant;
    @GuardedBy("okhttpclientLock")
    private OkHttpClient okHttpClient;
    private LoaderFactory loaderFactory;
    private ProxyPicker proxyPicker;
    private EvilBlocker evilBlocker;

    /**
     * Adds a Loader.
     * This can be used later to interrupt (cancel or stop) the Loader that is identified by the given download id.
     * @param downloadId download id
     * @param loader Loader
     */
    @AnyThread
    void addLoader(@IntRange(from = IdSupply.DOWNLOAD_ID_OFFSET, to = IdSupply.NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId, @NonNull Loader loader) {
        if (BuildConfig.DEBUG && !IdSupply.isDownloadId(downloadId)) Log.e(TAG, "addLoader(" + downloadId + ", …): not a download id!");
        synchronized (this.loaders) {
            this.loaders.put(downloadId, loader);
        }
    }

    /**
     * Adds a Notification.Builder.
     * This can be used later to update the notification for the download identified by the given download id.
     * @param downloadId download id
     * @param b Notification.Builder
     */
    @AnyThread
    synchronized void addNotificationBuilder(@IntRange(from = IdSupply.DOWNLOAD_ID_OFFSET, to = IdSupply.NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId, @NonNull Notification.Builder b) {
        if (BuildConfig.DEBUG && !IdSupply.isDownloadId(downloadId)) Log.e(TAG, "addNotificationBuilder(" + downloadId + ", …): not a download id!");
        synchronized (this.nbuilders) {
            this.nbuilders.put(downloadId, b);
        }
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, @NotNull Response response) throws IOException {
        // https://stackoverflow.com/questions/22490057/android-okhttp-with-basic-authentication
        // https://square.github.io/okhttp/recipes/#handling-authentication-kt-java
        if (BuildConfig.DEBUG) AuthManager.getInstance().dumpAuth();
        List<Challenge> challenges = response.challenges();
        if (BuildConfig.DEBUG) Log.i(TAG, "Authenticating for server response: " + response + "; challanges: " + challenges);
        if (challenges.size() != 1) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Got " + challenges.size() + " challenges! Can't handle that.");
            return null;
        }
        Challenge challenge = challenges.get(0);
        String realm = challenge.realm();
        if (!"Basic".equals(challenge.scheme())) {
            String msg = getString(R.string.error_auth_unsupported, challenge.scheme());
            if (BuildConfig.DEBUG) Log.e(TAG, msg);
            throw new UnsupportedAuthChallengeException(msg);
        }
        final Credential credential;
        synchronized (AuthManager.getInstance().getCredentials()) {
            credential = Credential.findUsable(AuthManager.getInstance().getCredentials(), realm);
        }
        if (credential != null && credential.getUserid() != null) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Authenticating with " + credential);
            CharSequence pwd = credential.getPassword();
            String credentials = okhttp3.Credentials.basic(credential.getUserid(), pwd != null ? pwd.toString() : "");
            return response
                    .request()
                    .newBuilder()
                    .header("Authorization", credentials)
                    .build();
        } else {
            if (BuildConfig.DEBUG) Log.w(TAG, "Did not find credential for realm '" + realm + "'");
        }
        return null;
    }

    /**
     * Returns the EvilBlocker.
     * @return EvilBlocker (usually non-null)
     */
    public EvilBlocker getEvilBlocker() {
        return this.evilBlocker;
    }

    /**
     * Returns a particular Loader that is identified by a download id.
     * @param downloadId download id
     * @return Loader
     */
    @AnyThread
    @Nullable
    Loader getLoader(@IntRange(from = IdSupply.DOWNLOAD_ID_OFFSET, to = IdSupply.NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId) {
        if (BuildConfig.DEBUG && !IdSupply.isDownloadId(downloadId)) Log.e(TAG, "getLoader(" + downloadId + "): not a download id!");
        Loader d;
        synchronized (this.loaders) {
            d = this.loaders.get(downloadId);
        }
        return d;
    }

    @NonNull
    public LoaderFactory getLoaderFactory() {
        return this.loaderFactory;
    }

    /**
     * Returns the NotificationChannel for most notifications with an importance of {@link NotificationManager#IMPORTANCE_DEFAULT}.
     * @return NotificationChannel (non-null from API 26 on)
     */
    @TargetApi(android.os.Build.VERSION_CODES.O)
    public NotificationChannel getNc() {
        return this.nc;
    }

    /**
     * Returns the NotificationChannel for important notifications with an importance of {@link NotificationManager#IMPORTANCE_HIGH}.
     * @return NotificationChannel (non-null from API 26 on)
     */
    @TargetApi(android.os.Build.VERSION_CODES.O)
    public NotificationChannel getNcImportant() {
        return this.ncImportant;
    }

    /**
     * Returns the Notification.Builder for the given download id.
     * @param downloadId download id
     * @return Notification.Builder
     */
    @AnyThread
    @Nullable
    Notification.Builder getNotificationBuilder(@IntRange(from = IdSupply.DOWNLOAD_ID_OFFSET) int downloadId) {
        if (BuildConfig.DEBUG && !IdSupply.isDownloadId(downloadId)) Log.e(TAG, "getNotificationBuilder(" + downloadId + "): not a download id!");
        Notification.Builder builder;
        synchronized (this.nbuilders) {
            builder = this.nbuilders.get(downloadId);
        }
        return builder;
    }

    /**
     * Returns the OkHttpClient.
     * @return OkHttpClient
     */
    @NonNull
    public OkHttpClient getOkHttpClient() {
        final OkHttpClient ohc;
        synchronized (okhttpclientLock) {
            if (this.okHttpClient == null) makeOkhttpClient();
            ohc = this.okHttpClient;
        }
        return ohc;
    }

    public ProxyPicker getProxyPicker() {
        return this.proxyPicker;
    }

    @NonNull
    public ThumbsManager getThumbsManager() {
        return this.thumbsManager;
    }

    /**
     * @return {@code true} if there are any Loaders that are not yet in the {@link AsyncTask.Status#FINISHED FINISHED} state
     */
    @AnyThread
    public boolean hasActiveLoaders() {
        synchronized (this.loaders) {
            final int n = this.loaders.size();
            for (int i = 0; i < n; i++) {
                Loader loader = this.loaders.valueAt(i);
                if (loader != null && loader.getStatus() != AsyncTask.Status.FINISHED) return true;
            }
        }
        return false;
    }

    /**
     * Determines whether a given file is currently being written to.
     * @param destination destination file to check
     * @return true / false
     */
    @AnyThread
    public boolean isBeingDownloaded(@Nullable final File destination) {
        if (destination == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "isBeingDownloaded(null)");
            return false;
        }
        synchronized (this.loaders) {
            final int n = this.loaders.size();
            for (int i = 0; i < n; i++) {
                Loader loader = this.loaders.valueAt(i);
                if (loader.getStatus() == AsyncTask.Status.FINISHED) continue;
                Order[] orders = loader.getOrders();
                if (orders == null) continue;
                for (Order order : orders) {
                    File orderDestination = new File(order.getDestinationFolder(), order.getDestinationFilename());
                    if (destination.equals(orderDestination)) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "File \"" + orderDestination.getName() + "\" is currently the target of " + order.getUrl() + " being downloaded (id " + loader.getId() + ")");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Creates the {@link #okHttpClient OkHttpClient}.
     * Can take quite some time (xxx ms); so in {@link #onCreate()} it should be called on a separate thread.
     */
    @AnyThread
    private void makeOkhttpClient() {
        synchronized (okhttpclientLock) {
            if (this.okHttpClient != null) {
                try {
                    this.okHttpClient.dispatcher().executorService().shutdown();
                    this.okHttpClient.connectionPool().evictAll();
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) Log.e(App.class.getSimpleName(), e.toString());
                }
            }
            if (this.proxyPicker == null) this.proxyPicker = new ProxyPicker(this);
            final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
                    .readTimeout(TIMEOUT_READ, TimeUnit.MILLISECONDS)
                    .writeTimeout(TIMEOUT_WRITE, TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .authenticator(this)
                    .proxySelector(this.proxyPicker)
                    .dns(new Hal(this))
                    .cache(new okhttp3.Cache(new File(getCacheDir(), "okcache"), 10_000_000L));
            //
            if (BuildConfig.DEBUG) {
                HttpLogger.enable(builder);
            }
            //
            this.okHttpClient = builder.build();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onConnectivityChanged(@NonNull NetworkInfo.State old, @NonNull NetworkInfo.State state) {
        if (old.equals(state)) return;
        //TODO this does not help when the network connection has been lost; the Exception in the download loop will be there much earlier than this method is called
        // but does this help when the network conn. switched from free to metered (and metered is not allowed)?
        if (NetworkInfo.State.DISCONNECTED == state || NetworkInfo.State.DISCONNECTING == state) {
            synchronized (this.loaders) {
                final int n = this.loaders.size();
                for (int i = 0; i < n; i++) {
                    Loader d = this.loaders.valueAt(i);
                    if (d.getStatus() != AsyncTask.Status.RUNNING) continue;
                    d.holdon();
                }
            }
        }
    }

    /** {@inheritDoc} */
    //@SuppressWarnings("JavaReflectionMemberAccess")
    @Override
    public void onCreate() {
        super.onCreate();

        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.LAX);
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.LAX);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String uuid = prefs.getString(PREF_UUID, null);
        if (uuid == null) {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putString(PREF_UUID, UUID.randomUUID().toString());
            ed.apply();
        }

        NetworkChangedReceiver ncr = NetworkChangedReceiver.getInstance();
        ncr.init(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(ncr, new IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED));
        }

        this.proxyPicker = new ProxyPicker(this);

        Ancestry.setup(this);

        AlarmManager am = (AlarmManager)getSystemService(ALARM_SERVICE);

        AuthManager.getInstance().init(this);

        QueueManager.getInstance().init(this);

        BackupService.cleanup(this);

        Calendar cal = Calendar.getInstance();
        int secondsToFullHour = 60 - cal.get(Calendar.SECOND) + (59 - cal.get(Calendar.MINUTE)) * 60;
        Intent intentCheckNight = new Intent(this, LoaderService.class);
        intentCheckNight.setAction(ACTION_CHECK_NIGHT);
        PendingIntent piCheckNight = PendingIntent.getService(this, 798, intentCheckNight, PendingIntent.FLAG_UPDATE_CURRENT);
        long at = System.currentTimeMillis() + (secondsToFullHour * 1_000L + 2000L);
        am.setRepeating(AlarmManager.RTC, at, 3_600_000L, piCheckNight);
        try {
            startService(intentCheckNight);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to start LoaderService with " + intentCheckNight + ": " + e.toString());
        }

        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        // if there were any notifications left, remove them now
        nm.cancelAll();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String appName = getString(R.string.app_name);

            String nameNormal = getString(R.string.label_notifications_channel) + ' ' + appName;
            this.nc = new NotificationChannel(appName, nameNormal, NotificationManager.IMPORTANCE_DEFAULT);
            this.nc.setDescription(nameNormal);
            this.nc.enableLights(false);
            this.nc.setSound(null, null);
            this.nc.setShowBadge(false);
            nm.createNotificationChannel(this.nc);

            String nameImportant = getString(R.string.label_notifications_channel_important) + ' ' + appName;
            this.ncImportant = new NotificationChannel(appName + "-important", nameImportant, NotificationManager.IMPORTANCE_HIGH);
            this.ncImportant.setDescription(nameImportant);
            this.ncImportant.enableLights(false);
            this.ncImportant.setSound(null, null);
            this.ncImportant.setShowBadge(false);
            nm.createNotificationChannel(this.ncImportant);
        }

        this.thumbsManager = new ThumbsManager(this);

        this.loaderFactory = new LoaderFactory(this);

        Thread okHttpClientMaker = new Thread() {
            @Override
            public void run() {
                App.this.evilBlocker = new EvilBlocker(App.this);
                makeOkhttpClient();
            }
        };
        okHttpClientMaker.setPriority(Thread.NORM_PRIORITY - 1);
        okHttpClientMaker.start();

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
            String msg = e.getMessage();
            if (msg == null || msg.length() == 0) msg = e.toString();
            Notification.Builder builder = new Notification.Builder(this)
                    .setSmallIcon(R.mipmap.ic_launcher_foreground)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(msg)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setShowWhen(true)
                    .setColor(0xffff0000)
                    ;
            if (msg.length() > 40) {
                builder.setStyle(new Notification.BigTextStyle().bigText(msg).setSummaryText(msg.substring(0, 40).trim()));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder
                    .setColorized(true)
                    .setChannelId(getNc().getId());
            }
            Intent intentBugReport = new Intent(Intent.ACTION_APP_ERROR);
            intentBugReport.putExtra(Intent.EXTRA_BUG_REPORT, Util.makeErrorReport(this, e));
            Intent chooser = Intent.createChooser(intentBugReport, null);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // it's none of their business (actually it is, but it shouldn't be)
                chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {new ComponentName("com.google.android.gms", "com.google.android.gms.feedback.FeedbackActivity")});
            }
            PendingIntent piBugReport = PendingIntent.getActivity(this, 1, chooser, 0);
            builder.addAction(UiUtil.makeNotificationAction(this, R.mipmap.ic_launcher, R.string.label_error_report, piBugReport));
            NotificationManager notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (notificationManager != null) notificationManager.notify(IdSupply.NOTIFICATION_ID_WTF, builder.build());
            System.exit(-2);
        });

        if (Build.VERSION.SDK_INT >= 30) {
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, ClipSpy.class.getName()), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } else if (prefs.getBoolean(PREF_CLIPSPY, PREF_CLIPSPY_DEFAULT)) {
            ClipSpy.launch(this);
        }

        SettingsActivity.clearSharedApkDirectory(this);
    }

    /** {@inheritDoc} */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (BuildConfig.DEBUG) Log.i(TAG, "onSharedPreferenceChanged(…, \"" + key + "\")");
        if (PREF_PROXY_TYPE.equals(key) || PREF_PROXY_SERVER.equals(key)) {
            makeOkhttpClient();
        } else if (PREF_CLIPSPY.equals(key)) {
            boolean on = prefs.getBoolean(key, false);
            if (on) {
                ClipSpy.launch(this);
            } else {
                ClipSpy.abort(this);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            this.thumbsManager.clearCache();
            if (!this.hasActiveLoaders()) {
                synchronized (okhttpclientLock) {
                    try {
                        Cache c = this.okHttpClient.cache();
                        if (c != null) c.evictAll();
                    } catch (Exception ignored) {
                    }
                }
            }
            // clears the collection of ThumbCreators if all are in the FINISHED state
            this.thumbsManager.clearCreators();
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            MMRT.cleanup();
        }
    }

    /**
     * Removes a Loader.
     * @param downloadId download id that identifies the Loader
     */
    @AnyThread
    void removeLoader(@IntRange(to = IdSupply.NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId) {
        if (BuildConfig.DEBUG && !IdSupply.isDownloadId(downloadId)) Log.e(TAG, "removeDownloader(" + downloadId + "): not a download id!");
        synchronized (this.loaders) {
            this.loaders.remove(downloadId);
        }
    }

    /**
     * Removes a Notification.Builder.
     * @param downloadId download id that identifies the Notification.Builder
     */
    @AnyThread
    void removeNotificationBuilder(@IntRange(to = IdSupply.NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId) {
        if (BuildConfig.DEBUG && !IdSupply.isDownloadId(downloadId)) Log.e(TAG, "removeNotificationBuilder(" + downloadId + "): not a download id!");
        synchronized (this.nbuilders) {
            this.nbuilders.remove(downloadId);
        }
    }

    /**
     * The paths that are defined in {@link R.xml#filepaths R.xml.filepaths}.
     * LOGS is actually not used because a) Log works without a Context and b) the release version doesn't log anyway.
     * (But LOGS should stay here for tests, as the corresponding item in R.xml.filepaths is indeed required in the debug version)
     */
    public enum FilePath {

        DOWNLOADS("downloads"), APK("apk"), BACKUPS("backups"), @SuppressWarnings("unused") LOGS("logs"), ICONS("icons");

        private final String name;

        FilePath(String pname) {
            name = pname;
        }

        public String getName() {
            return name;
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SORT_DATE, SORT_NAME, SORT_SIZE})
    @interface SortMode {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({QUALITY_UNDEFINED, QUALITY_LOWEST, QUALITY_HIGHEST})
    public @interface Quality {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({VIA_VPN_TOO, VIA_VPN_ALWAYS, VIA_VPN_NEVER})
    public @interface ViaVpn {}

    /**
     * Implementation of the Dns interface that returns nothing for blocked hosts.
     */
    private static class Hal implements Dns {

        @NonNull private final App app;

        /**
         * Constructor.
         * @param app App
         */
        private Hal(@NonNull App app) {
            super();
            this.app = app;
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull final String hostname) throws UnknownHostException {
            if (this.app.evilBlocker != null && this.app.evilBlocker.isEvil(hostname)) throw new UnknownHostException(hostname);
            try {
                return Arrays.asList(InetAddress.getAllByName(hostname));
            } catch (RuntimeException e) {
                 throw new UnknownHostException(hostname);
            }
        }
    }
}

/*
 * UiActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import static android.provider.DocumentsContract.buildDocumentUri;
import static android.provider.DocumentsContract.buildTreeDocumentUri;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Display;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.LayoutRes;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.Size;
import androidx.annotation.StringDef;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import net.cellar.auth.AuthManager;
import net.cellar.auth.ManageCredentialsActivity;
import net.cellar.model.Wish;
import net.cellar.net.NetworkChangedReceiver;
import net.cellar.queue.ManageQueueActivity;
import net.cellar.queue.QueueManager;
import net.cellar.supp.CoordinatorLayoutHolder;
import net.cellar.supp.DebugUtil;
import net.cellar.supp.EpubAnalyzer;
import net.cellar.supp.Log;
import net.cellar.supp.MetadataReader;
import net.cellar.supp.SimpleTextWatcher;
import net.cellar.supp.SnackbarDisplayer;
import net.cellar.supp.UiUtil;
import net.cellar.supp.UriHandler;
import net.cellar.supp.Util;
import net.cellar.worker.Inspector;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import wseemann.media.FFmpegMediaMetadataRetriever;


/**
 * The Activity that provides the main user interface.
 */
public class UiActivity extends BaseActivity
        implements NetworkChangedReceiver.ConnectivityChangedListener, SnackbarDisplayer, LoaderService.DoneListener, ServiceConnection, SharedPreferences.OnSharedPreferenceChangeListener, SwipeRefreshLayout.OnRefreshListener, ThumbsManager.OnThumbCreatedListener {

    static final String ACTION_INSTALL = BuildConfig.APPLICATION_ID + ".action.install";
    static final String EXTRA_MIME_FILTER = BuildConfig.APPLICATION_ID + ".extra.mimefilter";
    @ViewType static final int VIEW_TYPE_GRID = 2;
    @ViewType static final int VIEW_TYPE_LINEAR = 1;
    private static final String TAG = "UiActivity";

    /**
     * Returns a label describing the file type identified by the given MIME type.
     * Can return {@code null} if no file type has been identified.
     * @param ctx Context
     * @param mime MIME type
     * @param tagUcase file extension in upper case
     * @return file type label
     */
    @Nullable
    static String getFileTypeLabel(@NonNull final Context ctx, @Nullable final String mime, @Nullable final String tagUcase) {
        if (mime == null && tagUcase == null) return null;
        final String typeLabel;
        final String s = tagUcase != null ? tagUcase : mime.substring(mime.indexOf('/') + 1).toUpperCase();
        if (mime == null) {
            if (s.length() > 0) {
                typeLabel = ctx.getString(R.string.label_filetype_application, s);
            } else {
                typeLabel = null;
            }
        } else {
            if (mime.startsWith("audio/")) {
                typeLabel = ctx.getString(R.string.label_filetype_audio, s);
            } else if (mime.startsWith("image/")) {
                typeLabel = ctx.getString(R.string.label_filetype_image, s);
            } else if (mime.startsWith("video/")) {
                typeLabel = ctx.getString(R.string.label_filetype_video, s);
            } else if ("application/ogg".equals(mime)) {
                typeLabel = ctx.getString(R.string.label_filetype_audio, "ogg");
            } else if ("text/calendar".equals(mime)) {
                typeLabel = ctx.getString(R.string.label_filetype_textcalendar);
            } else if ("text/x-vcard".equals(mime)) {
                typeLabel = ctx.getString(R.string.label_filetype_xvcard);
            } else if ("text/plain".equals(mime)) {
                typeLabel = ctx.getString(R.string.label_filetype_textplain);
            } else if (mime.startsWith("application/")) {
                typeLabel = ctx.getString(R.string.label_filetype_application, s);
            } else if (tagUcase != null && tagUcase.length() > 0) {
                typeLabel = ctx.getString(R.string.label_filetype_application, s);
            } else {
                typeLabel = mime;
            }
        }
        return typeLabel;
    }

    /** clears the {@link #toolbar}'s subtitle */
    private final ToolbarSubtitleResetter toolbarSubtitleResetter = new ToolbarSubtitleResetter();
    /** download files that match {@link #mimeFilter} */
    private final List<File> filteredList = new ArrayList<>(0);
    private final LoaderServiceConnection loaderServiceConnection = new LoaderServiceConnection();
    /** key: file name (w/o path) in lower case; value: int[2] with width and height */
    private final Map<String, int[]> imageSizeCache = new HashMap<>();
    private CoordinatorLayout coordinatorLayout;
    private RecyclerView recyclerViewDownloads;
    private RecyclerViewBottomMargin recyclerViewBottomMargin;
    private DownloadsAdapter downloadsAdapter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View viewNoDownloads;
    @Nullable private List<Uri> declinedDownloads;
    private ChecksumCalculator checksumCalculator;
    private ExecutorService executor;
    private Inspector inspector;
    private AlertDialog dialogDelete, dialogInfo, dialogLoad, dialogNewDownload, dialogRename;
    private Toolbar toolbar;
    private ClipboardListener clipboardListener;
    private Thread infoCreatorThread;
    /** a MIME type to filter by */
    private String mimeFilter = null;
    /** set to true when something changes that requires this Activity to be re-created (meant for changes caused by SettingsActivity) */
    private boolean needsRestart;
    private ClipSpy clipSpy;
    /** a Snackbar to be shown when this Activity {@link #onResume() resumes} */
    private Snackbar pendingSnackbar;
    /** a path of a file to scroll to at the next possible occasion */
    private String showMe;

    /** {@inheritDoc} */
    @Override
    protected void clipboardChanged() {
        offerDownloadClipboard();
    }

    /**
     * Asks the user for confirmation to delete either the selected files or the given file.
     * @param file File to delete (ignored if there is a selection)
     */
    private void delete(@Nullable File file) {
        int selectionSize = this.downloadsAdapter.selection.size();
        if (selectionSize == 0 && file == null) return;
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.msg_confirmation)
                .setIcon(R.drawable.ic_baseline_warning_amber_24)
                .setNegativeButton(R.string.label_no, (dialog, which) -> dialog.cancel());
        if (selectionSize > 0) {
            builder.setMessage(getResources().getQuantityString(R.plurals.msg_really_delete_selected, selectionSize))
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                        int fails = 0;
                        for (File selected : this.downloadsAdapter.selection) {
                            if (!selected.canWrite()) {
                                fails++;
                                continue;
                            }
                            try {
                                Uri uri = FileProvider.getUriForFile(this, BuildConfig.FILEPROVIDER_AUTH, selected);
                                revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception e) {
                                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                            }
                            if (selected.isFile() && !selected.delete()) {
                                fails++;
                            } else {
                                ((App) getApplicationContext()).getThumbsManager().removeThumbnail(selected);
                                Ancestry.getInstance().remove(selected);
                                String name = selected.getName().toLowerCase();
                                if (Util.isPicture(name)) this.imageSizeCache.remove(name);
                            }
                        }
                        this.downloadsAdapter.selection.clear();
                        getDelegate().invalidateOptionsMenu();
                        refresh();
                        getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);
                        if (fails > 0) {
                            Snackbar.make(getCoordinatorLayout(), getResources().getQuantityString(R.plurals.error_cant_delete_count, fails, fails), Snackbar.LENGTH_LONG).show();
                        }
                    })
            ;
        } else {
            builder.setMessage(R.string.msg_really_delete)
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                        dialog.dismiss();
                        if (!file.canWrite()) {
                            Snackbar.make(getCoordinatorLayout(), getString(R.string.error_cant_delete, file.getName()), Snackbar.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            Uri uri = FileProvider.getUriForFile(this, BuildConfig.FILEPROVIDER_AUTH, file);
                            revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                        }
                        if (!file.delete()) {
                            Snackbar.make(getCoordinatorLayout(), getString(R.string.error_cant_delete, file.getName()), Snackbar.LENGTH_LONG).show();
                        } else {
                            ((App) getApplicationContext()).getThumbsManager().removeThumbnail(file);
                            refresh();
                            getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);
                        }
                    })
            ;
        }
        this.dialogDelete = builder.create();
        Window dialogWindow = this.dialogDelete.getWindow();
        if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
        this.dialogDelete.show();
    }

    /** {@inheritDoc} */
    @UiThread
    @Override
    public void done() {
        refresh();
    }

    /**
     * Returns the number of columns displayed by the RecyclerView's current layout manager.
     * @return number of columns or 0
     */
    private int getColumnCount() {
        RecyclerView.LayoutManager lm = this.recyclerViewDownloads.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            return ((GridLayoutManager)lm).getSpanCount();
        } else if (lm instanceof LinearLayoutManager) {
            return 1;
        }
        if (BuildConfig.DEBUG) Log.e(TAG, "Not a supported RecyclerView.LayoutManager: " + lm);
        return 0;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public CoordinatorLayout getCoordinatorLayout() {
        return this.coordinatorLayout;
    }

    /**
     * Handles the given Intent
     * @param intent Intent
     */
    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) intent = getIntent();
        String extraMimeFilter = intent.getStringExtra(EXTRA_MIME_FILTER);
        if (extraMimeFilter != null) {
            extraMimeFilter = extraMimeFilter.trim().toLowerCase(Locale.US);
            if (extraMimeFilter.length() == 0) extraMimeFilter = null;
        }
        setMimeFilter(extraMimeFilter);
        // the documents ui might have called this activity
        if (DocumentsContract.ACTION_DOCUMENT_SETTINGS.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) this.showMe = uri.getLastPathSegment();
        }
    }

    @RequiresApi(21)
    @WorkerThread
    private void install(@NonNull File apk) {
        if (!apk.isFile() || ((App)getApplicationContext()).isBeingDownloaded(apk)) return;
        PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_META_DATA);
        if (packageInfo != null) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Installing " + packageInfo.packageName + ' ' + packageInfo.versionName);
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        PackageInstaller.Session session = null;
        OutputStream out = null;
        InputStream in = null;
        RandomAccessFile raf = null;
        FileChannel fc = null;
        FileLock lock = null;
        try {
            raf = new RandomAccessFile(apk, "rw");
            fc = raf.getChannel();
            lock = fc.tryLock();
            PackageInstaller pi = getPackageManager().getPackageInstaller();
            int id = pi.createSession(params);
            session = pi.openSession(id);
            if (BuildConfig.DEBUG) Log.i(TAG, "PackageInstaller Session " + id + " opened.");
            in = new FileInputStream(apk);
            out = session.openWrite(apk.getName(), 0L, apk.length());
            super.handler.post(() -> Snackbar.make(getCoordinatorLayout(), R.string.msg_installation_preparing, Snackbar.LENGTH_LONG).show());
            Util.copy(in, out, 65536);
            final byte[] buf = new byte[65536];
            for (;;) {
                int read = in.read(buf);
                if (read < 0) break;
                out.write(buf, 0, read);
            }
            session.fsync(out);
            Util.close(out, in);
            out = null; in = null;
            if (BuildConfig.DEBUG) Log.i(TAG, "Data copied.");
            Intent i = new Intent(this, LoaderService.class);
            i.setAction(ACTION_INSTALL);
            session.commit(PendingIntent.getService(this, id, i, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            if (BuildConfig.DEBUG) Log.i(TAG, "Session committed.");
        } catch (IOException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to install \"" + apk + "\": " + e.toString());
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            super.handler.post(() -> Snackbar.make(this.coordinatorLayout, getString(R.string.msg_installation_failed_w_reason, msg), Snackbar.LENGTH_LONG).show());
            if (session != null) session.abandon();
        } finally {
            Util.close(out, in, session);
            Util.close(lock);
            Util.close(fc, raf);
        }
    }

    /**
     * Offers to download the resource that the Uri that is currently on the clipboard points to.
     * Only considers the MIME types that are referred to in {@link App#MIMES_AUTO}.
     * Probably never does anything on Android 10 and above.
     */
    private void offerDownloadClipboard() {
        final Uri clip = getUriFromClipboard((ClipboardManager)getSystemService(CLIPBOARD_SERVICE));
        if (clip == null) return;

        final UriHandler uriHandler = UriHandler.checkUri(clip);
        final String host = clip.getHost();
        final String path = clip.getPath();
        final String lps = clip.getLastPathSegment();
        final CharSequence title = uriHandler != null ? uriHandler.getTitle() : null;

        if (uriHandler == null || !uriHandler.hasLoader()) {
            // only offer if an acceptable MIME type is detected (for example, don't offer HTML pages)
            String mime = Util.getMime(clip);
            if (BuildConfig.DEBUG) Log.i(TAG, "lps: '" + lps + "', host: '" + host + ", path: '" + path + "', mime: " + mime);
            if (mime == null) return;
            boolean acceptableMime = false;
            for (String m : App.MIMES_AUTO) {
                if (mime.startsWith(m)) {
                    acceptableMime = true;
                    break;
                }
            }
            if (!acceptableMime) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Mime type of '" + mime + "' is not among the acceptable ones.");
                return;
            }
        }
        // don't offer again if already declined
        if (this.declinedDownloads != null && this.declinedDownloads.contains(clip)) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Uri on clipboard (" + clip + ") had already been declined");
            return;
        }
        // display dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.msg_confirmation)
                .setMessage(getString(R.string.msg_download, title != null ? title : lps, host))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    dialog.cancel();
                    if (this.declinedDownloads == null) this.declinedDownloads = new ArrayList<>();
                    this.declinedDownloads.add(clip);
                })
                .setNeutralButton(R.string.label_later, (dialog, which) ->  {
                    dialog.dismiss();
                    Wish toTheQueue = new Wish(clip);
                    if (title != null) toTheQueue.setTitle(title);
                    toTheQueue.setHeld(true);
                    QueueManager.getInstance().add(toTheQueue);
                    clearClipboard();
                })
                .setPositiveButton(R.string.action_load, (dialog, which) -> {
                    dialog.dismiss();
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.setAction(Intent.ACTION_VIEW);
                    if (title != null) intent.putExtra(Intent.EXTRA_TITLE, title);
                    intent.setData(clip);
                    startActivity(intent);
                    clearClipboard();
                });
        this.dialogLoad = builder.create();
        Window dialogWindow = this.dialogLoad.getWindow();
        if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
        this.dialogLoad.show();
    }

    /** {@inheritDoc} */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        selectLayoutManager();
    }

    /** {@inheritDoc} */
    @MainThread
    @Override
    public void onConnectivityChanged(@NonNull final NetworkInfo.State old, @NonNull final NetworkInfo.State state) {
        super.handler.removeCallbacks(this.toolbarSubtitleResetter);
        if (state == NetworkInfo.State.CONNECTED) {
            if (old == NetworkInfo.State.DISCONNECTED || old == NetworkInfo.State.SUSPENDED) {
                this.toolbar.setSubtitle(getString(R.string.msg_network_conn_est));
                if (!isDestroyed()) super.handler.postDelayed(this.toolbarSubtitleResetter, 2_000L);
            } else {
                this.toolbar.setSubtitle(null);
            }
        } else if (state == NetworkInfo.State.DISCONNECTED || state == NetworkInfo.State.SUSPENDED) {
            this.toolbar.setSubtitle(getString(R.string.msg_network_conn_lost));
        } else {
            this.toolbar.setSubtitle(null);
        }
    }

    /** {@inheritDoc} */
    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(App.PREF_TRANSLUCENT_NAVIGATION, App.PREF_TRANSLUCENT_NAVIGATION_DEFAULT)) {
            setTheme(R.style.AppTheme_NoActionBar_WithTranslucentNavigation);
        } else {
            setTheme(R.style.AppTheme_NoActionBar);
        }

        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        final AppCompatDelegate delegate = getDelegate();

        delegate.setContentView(R.layout.activity_downloads);

        this.toolbar = delegate.findViewById(R.id.toolbar);
        delegate.setSupportActionBar(this.toolbar);

        if (BuildConfig.DEBUG) {
            ActionBar ab = delegate.getSupportActionBar();
            if (ab != null) ab.setTitle(getString(R.string.app_name));
        }

        this.coordinatorLayout = delegate.findViewById(R.id.coordinator_layout);
        this.viewNoDownloads = delegate.findViewById(R.id.textViewNoDownloads);
        this.swipeRefreshLayout = delegate.findViewById(R.id.swipeRefreshLayout);
        assert this.swipeRefreshLayout != null;
        this.swipeRefreshLayout.setOnRefreshListener(this);
        this.recyclerViewDownloads = delegate.findViewById(R.id.recyclerViewDownloads);
        assert this.recyclerViewDownloads != null;
        this.recyclerViewDownloads.setHasFixedSize(true);
        this.downloadsAdapter = new DownloadsAdapter();
        ((App)getApplicationContext()).getThumbsManager().addListener(this.downloadsAdapter);
        this.recyclerViewDownloads.setAdapter(this.downloadsAdapter);

        this.coordinatorLayout.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            int navigationbarHeight = windowInsets.getSystemWindowInsetBottom();
            // we cannot set android:fitsSystemWindows="true" in the layout file because the coordinatorLayout should only fit the status bar but NOT the navigation bar!
            // therefore we must calculate the height of the status bar and apply padding to the top of the coordinatorLayout
            v.setPadding(windowInsets.getSystemWindowInsetLeft(), windowInsets.getSystemWindowInsetTop(), windowInsets.getSystemWindowInsetRight(), 0);
            if (navigationbarHeight > 0) {
                if (this.recyclerViewDownloads.getItemDecorationCount() == 0) {
                    this.recyclerViewBottomMargin = new RecyclerViewBottomMargin(navigationbarHeight, getColumnCount());
                    this.recyclerViewDownloads.addItemDecoration(this.recyclerViewBottomMargin);
                }
            } else {
                if (this.recyclerViewBottomMargin != null) {
                    this.recyclerViewDownloads.removeItemDecoration(this.recyclerViewBottomMargin);
                    this.recyclerViewBottomMargin = null;
                }
            }
            return windowInsets.consumeSystemWindowInsets();
        });
        selectLayoutManager();

        this.clipboardListener = new ClipboardListener(this);

        SharedResultReceiver.refSnackbarDisplayer = new WeakReference<>(this);

        handleIntent(null);

        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.ui, menu);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void onDestroy() {
        this.needsRestart = false;
        // removing the OnThumbLoadedListener is important as this activity might be restarted shortly after creation (due to BaseActivity.setNightMode())
        // the GC cannot keep up with that, so the ThumbManager would have 2 listeners…
        ((App)getApplicationContext()).getThumbsManager().removeListener(this.downloadsAdapter);
        //
        if (this.executor != null && !this.executor.isShutdown()) {
            this.executor.shutdown();
        }
        super.handler.removeCallbacks(this.toolbarSubtitleResetter);
        SharedResultReceiver.refSnackbarDisplayer = null;
        super.onDestroy();
    }

    /** {@inheritDoc} */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            return super.onOptionsItemSelected(item);
        }
        if (id == R.id.action_create_download) {
            startActivity(new Intent(this, NewDownloadActivity.class), ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
            return true;
        }
        if (id == R.id.action_layout_grid) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            boolean wasGridLayout = prefs.getBoolean(App.PREF_UI_GRID_LAYOUT, getResources().getBoolean(R.bool.grid_by_default));
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(App.PREF_UI_GRID_LAYOUT, !wasGridLayout);
            ed.apply();
            getDelegate().invalidateOptionsMenu();
            selectLayoutManager();
            return true;
        }
        if (id == R.id.action_filter_reset) {
            setMimeFilter(null);
            return true;
        }
        if (id == R.id.action_manage_credentials) {
            startActivity(new Intent(this, ManageCredentialsActivity.class), ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
            return true;
        }
        if (id == R.id.action_manage_queue) {
            startActivity(new Intent(this, ManageQueueActivity.class), ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
            return true;
        }
        if (id == R.id.action_share_item) {
            // we should be here only if there is a selection
            // see ViewHolder.onMenuItemClick()
            Util.sendMulti(this, this.downloadsAdapter.selection, BuildConfig.FILEPROVIDER_AUTH);
            this.downloadsAdapter.selection.clear();
            getDelegate().invalidateOptionsMenu();
            return true;
        }
        if (id == R.id.action_delete) {
            // we should be here only if there is a selection
            delete(null);
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class), ActivityOptions.makeSceneTransitionAnimation(this).toBundle());
            return true;
        }
        if (id == R.id.action_sort_date) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor ed = prefs.edit();
            if (prefs.getInt(App.PREF_SORT, App.SORT_DATE) == App.SORT_DATE) {
                ed.putBoolean(App.PREF_SORT_INV, !prefs.getBoolean(App.PREF_SORT_INV, false));
            } else {
                ed.putInt(App.PREF_SORT, App.SORT_DATE);
            }
            ed.apply();
            refresh();
            return true;
        }
        if (id == R.id.action_sort_name) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor ed = prefs.edit();
            if (prefs.getInt(App.PREF_SORT, App.SORT_DATE) == App.SORT_NAME) {
                ed.putBoolean(App.PREF_SORT_INV, !prefs.getBoolean(App.PREF_SORT_INV, false));
            } else {
                ed.putInt(App.PREF_SORT, App.SORT_NAME);
            }
            ed.apply();
            refresh();
            return true;
        }
        if (id == R.id.action_sort_size) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor ed = prefs.edit();
            if (prefs.getInt(App.PREF_SORT, App.SORT_DATE) == App.SORT_SIZE) {
                ed.putBoolean(App.PREF_SORT_INV, !prefs.getBoolean(App.PREF_SORT_INV, false));
            } else {
                ed.putInt(App.PREF_SORT, App.SORT_SIZE);
            }
            ed.apply();
            refresh();
            return true;
        }
        if (id == R.id.action_network_info) {
            NetworkChangedReceiver.showInfo(this);
        }
        if (id == R.id.action_log) {
            String err = Log.share(this);
            if (err != null) {
                Snackbar.make(coordinatorLayout, err, Snackbar.LENGTH_LONG).show();
            }
        }
        return super.onOptionsItemSelected(item);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        this.recyclerViewDownloads.suppressLayout(true);
        NetworkChangedReceiver.getInstance().removeListener(this);
        if (this.checksumCalculator != null && this.checksumCalculator.isAlive()) {
            this.checksumCalculator.abort();
            this.checksumCalculator = null;
        }
        if (this.inspector != null && this.inspector.getStatus() != AsyncTask.Status.FINISHED) {
            this.inspector.cancel(true);
        }
        ((App)getApplicationContext()).getThumbsManager().setOnThumbCreatedListener(null);
        this.clipboardListener.unregister();
        UiUtil.dismissDialog(this.dialogDelete, this.dialogInfo, this.dialogLoad, this.dialogNewDownload, this.dialogRename);
        this.dialogDelete = this.dialogInfo = this.dialogLoad = this.dialogNewDownload = this.dialogRename = null;

        if (this.clipSpy != null) {
            this.clipSpy.setActive(true);
            try {
                unbindService(this);
                this.clipSpy = null;
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
            }
        }
        try {
            if (this.loaderServiceConnection.loaderService != null) {
                unbindService(this.loaderServiceConnection);
                this.loaderServiceConnection.loaderService = null;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
        }

        super.onPause();
    }

    /** {@inheritDoc} */
    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        MenuItem itemNew = menu.findItem(R.id.action_create_download);
        MenuItem itemLayoutGrid = menu.findItem(R.id.action_layout_grid);
        MenuItem itemResetFilter = menu.findItem(R.id.action_filter_reset);
        MenuItem itemSort = menu.findItem(R.id.action_sort);
        MenuItem itemSortDate = menu.findItem(R.id.action_sort_date);
        MenuItem itemSortName = menu.findItem(R.id.action_sort_name);
        MenuItem itemSortSize = menu.findItem(R.id.action_sort_size);
        MenuItem itemManageCredentials = menu.findItem(R.id.action_manage_credentials);
        MenuItem itemNetwork = menu.findItem(R.id.action_network_info);
        MenuItem itemManageQueue = menu.findItem(R.id.action_manage_queue);
        MenuItem itemShare = menu.findItem(R.id.action_share_item);
        MenuItem itemDelete = menu.findItem(R.id.action_delete);
        MenuItem itemSettings = menu.findItem(R.id.action_settings);
        MenuItem itemLog = menu.findItem(R.id.action_log);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean hasSelection = this.downloadsAdapter.hasSelection();
        final boolean hasSelectionWithProtectedFiles = hasSelection && this.downloadsAdapter.hasSelectionProtected();
        final boolean hasDownloads = !super.downloads.isEmpty();

        itemNew.setVisible(!hasSelection);
        itemLayoutGrid.setVisible(!hasSelection);
        itemLayoutGrid.setChecked(prefs.getBoolean(App.PREF_UI_GRID_LAYOUT, getResources().getBoolean(R.bool.grid_by_default)));
        itemResetFilter.setVisible(!TextUtils.isEmpty(this.mimeFilter));
        itemManageCredentials.setVisible(!hasSelection && !AuthManager.getInstance().getCredentials().isEmpty());
        itemManageQueue.setVisible(!hasSelection && QueueManager.getInstance().hasQueuedStuff());
        itemSort.setVisible(!hasSelection && hasDownloads);
        itemSortDate.setEnabled(!hasSelection && hasDownloads);
        itemSortName.setEnabled(!hasSelection && hasDownloads);
        itemSortSize.setEnabled(!hasSelection && hasDownloads);

        @App.SortMode final int sort = prefs.getInt(App.PREF_SORT, App.SORT_DATE);
        boolean reverse = prefs.getBoolean(App.PREF_SORT_INV, false);
        itemSortDate.setTitle(sort == App.SORT_DATE ? (reverse ? getString(R.string.pref_sort_date) + "  " + getString(R.string.pref_sort_dsc) : getString(R.string.pref_sort_date) + "  " + getString(R.string.pref_sort_asc)) : getString(R.string.pref_sort_date));
        itemSortName.setTitle(sort == App.SORT_NAME ? (reverse ? getString(R.string.pref_sort_name) + "  " + getString(R.string.pref_sort_dsc) : getString(R.string.pref_sort_name) + "  " + getString(R.string.pref_sort_asc)) : getString(R.string.pref_sort_name));
        itemSortSize.setTitle(sort == App.SORT_SIZE ? (reverse ? getString(R.string.pref_sort_size) + "  " + getString(R.string.pref_sort_dsc) : getString(R.string.pref_sort_size) + "  " + getString(R.string.pref_sort_asc)) : getString(R.string.pref_sort_size));

        itemNetwork.setVisible(BuildConfig.DEBUG && !hasSelection);
        itemSettings.setVisible(!hasSelection);

        itemShare.setVisible(hasSelection);
        itemDelete.setVisible(hasSelection);
        itemDelete.setEnabled(!hasSelectionWithProtectedFiles);

        if (itemLog != null) {
            if (BuildConfig.DEBUG) {
                long size = Log.getSize();
                itemLog.setVisible(size > 0L);
                itemLog.setTitle(getString(R.string.action_log) + " (" + UiUtil.formatBytes(size) + ")");
            } else {
                itemLog.setVisible(false);
            }
        }

        return super.onPrepareOptionsMenu(menu);
    }

    /** {@inheritDoc} */
    @Override
    public void onRefresh() {
        refresh();
        this.swipeRefreshLayout.setRefreshing(false);
    }

    /** {@inheritDoc} */
    @Override
    protected void onRestart() {
        super.onRestart();
        if (this.needsRestart) {
            this.needsRestart = false;
            final Intent restart = new Intent(getApplicationContext(), getClass());
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            finish();
            startActivity(restart);
        }
    }

    /** {@inheritDoc} */
    @SuppressLint("WrongConstant")
    @Override
    protected void onResume() {
        setDarkMode(this);
        super.onResume();
        NetworkChangedReceiver.getInstance().addListener(this);

        ((App)getApplicationContext()).getThumbsManager().wakeUp();
        this.recyclerViewDownloads.suppressLayout(false);
        refresh();

        if (Build.VERSION.SDK_INT < 30) {
            this.clipboardListener.register();
            offerDownloadClipboard();
        }

        if (this.inspector == null || this.inspector.getStatus() == AsyncTask.Status.FINISHED) {
            this.inspector = new Inspector(this, this::refresh);
            if (this.executor == null) this.executor = Executors.newCachedThreadPool();
            this.inspector.executeOnExecutor(this.executor);
        }

        // is there anything left that should be mentioned?
        if (this.pendingSnackbar != null) {
            this.pendingSnackbar.show();
            this.pendingSnackbar = null;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //
        long freeSpace = getFilesDir().getFreeSpace();
        long delta = freeSpace - prefs.getLong(App.PREF_WARN_LOW_STORAGE, App.PREF_WARN_LOW_STORAGE_DEFAULT);
        if (delta < 0L) {
            Snackbar.make(this.coordinatorLayout, getString(R.string.msg_warn_low_storage, UiUtil.formatBytes(freeSpace)), Snackbar.LENGTH_LONG).show();
        }

        if (Build.VERSION.SDK_INT < 30 && prefs.getBoolean(App.PREF_CLIPSPY, App.PREF_CLIPSPY_DEFAULT) && this.clipSpy == null) {
            bindService(new Intent(this, ClipSpy.class), this, BuildConfig.DEBUG ? BIND_DEBUG_UNBIND : 0);
        }
        bindService(new Intent(this, LoaderService.class), this.loaderServiceConnection, 0);

        ((App)getApplicationContext()).getThumbsManager().setOnThumbCreatedListener(this);
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (service instanceof ClipSpy.ClipSpyBinder) {
            this.clipSpy = ((ClipSpy.ClipSpyBinder) service).getClipSpy();
            if (this.clipSpy != null) this.clipSpy.setActive(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (ClipSpy.class.getName().equals(name.getClassName())) {
            this.clipSpy = null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (App.PREF_TRANSLUCENT_NAVIGATION.equals(key)) {
            // we cannot simply apply the new style here - the window must apparently be re-created
            this.needsRestart = true;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (hasFocus) {
                this.clipboardListener.register();
                offerDownloadClipboard();
            } else {
                this.clipboardListener.unregister();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    @UiThread
    int refresh() {
        int n = super.refresh();
        n = updateFilter(n);
        this.viewNoDownloads.setVisibility(n == 0 ? View.VISIBLE : View.GONE);

        final File[] files;
        synchronized (super.downloads) {
            int total = super.downloads.size();
            if (total > 0) {
                files = new File[total];
                super.downloads.toArray(files);
            } else {
                files = null;
            }
        }
        ((App)getApplicationContext()).getThumbsManager().refresh(this, files);
        if (this.showMe != null) {
            int index = this.downloadsAdapter.getIndex(new File(this.showMe));
            this.showMe = null;
            if (index >= 0) {
                RecyclerView.LayoutManager lm = this.recyclerViewDownloads.getLayoutManager();
                if (lm != null) {
                    // if possible, scroll a row further so that the desired file is not at the bottom
                    int columns = (lm instanceof GridLayoutManager ? ((GridLayoutManager)lm).getSpanCount() : 1);
                    int target = Math.min(this.downloadsAdapter.getItemCount() - 1, index + columns);
                    super.handler.postDelayed(() -> this.recyclerViewDownloads.smoothScrollToPosition(target), 500L);
                }
            }
        }
        return n;
    }

    /**
     * Picks a {@link androidx.recyclerview.widget.RecyclerView.LayoutManager LayoutManager} depending on the preferences and on the screen size.
     */
    @UiThread
    private void selectLayoutManager() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean useGridLayout = prefs.getBoolean(App.PREF_UI_GRID_LAYOUT, getResources().getBoolean(R.bool.grid_by_default));
        @IntRange(from = 1) final int columns = useGridLayout ? getResources().getInteger(R.integer.ui_columns) : 1;
        if (this.recyclerViewBottomMargin != null) this.recyclerViewBottomMargin.setColumns(columns);
        final RecyclerView.LayoutManager lm, old = this.recyclerViewDownloads.getLayoutManager();
        if (!useGridLayout) {
            if (old instanceof LinearLayoutManager && !(old instanceof GridLayoutManager)) return;
            lm = new LinearLayoutManager(this);
        } else {
            if (old instanceof GridLayoutManager) {
                if (((GridLayoutManager) old).getSpanCount() == columns) return;
                ((GridLayoutManager) old).setSpanCount(columns);
                return;
            }
            lm = new GridLayoutManager(this, columns, RecyclerView.VERTICAL, false);
        }
        this.recyclerViewDownloads.setLayoutManager(lm);
    }

    /**
     * Sets a MIME type filter. Only those download files are displayed whose MIME type matches this.
     * @param mimeFilter MIME type filter to apply
     */
    private void setMimeFilter(@Nullable final String mimeFilter) {
        if ((mimeFilter == null && this.mimeFilter == null) || (mimeFilter != null && mimeFilter.equalsIgnoreCase(this.mimeFilter))) return;
        this.mimeFilter = mimeFilter;
        refresh();
        super.handler.postDelayed(() -> getDelegate().invalidateOptionsMenu(), 1_000L);
    }

    /** {@inheritDoc} */
    @Override
    public void setSnackbar(@Nullable Snackbar snackbar) {
        this.pendingSnackbar = snackbar;
    }

    /**
     * Displays an informational dialog for files that are supported by {@link FFmpegMediaMetadataRetriever} or {@link ExifInterface}.<br>
     * Runs on a worker thread.
     * @param file File to show the info for
     */
    @SuppressWarnings({"deprecation", "ConstantConditions"})
    @AnyThread
    private void showInfoDialog(@NonNull final File file) {
        final String fileNameLower = file.getName().toLowerCase();
        final String origin = Ancestry.getInstance().getHost(file);
        final String mime = Util.getMime(file);
        int ms = 0;
        String title = null; boolean titleWithoutQuotes = false;
        String comment = null;
        String videocodec = null, audiocodec = null;
        int width = 0, height = 0, fps = 0, iso = 0;
        double fnumber = 0., exposuretime = 0.;
        String make = null, model = null;
        Date dateTime = null, dateTimeOriginal = null;
        String lat = null, lon = null, latref = null, lonref = null;
        int pageCount = 0;
        String software = null;
        SpannableString signature = null;
        SpannableStringBuilder permissions = null;
        boolean validFile = true;
        boolean encrypted = false;
        long uncompressedSize = 0L;
        String zipCompressionMethod = null, zipEncryptionMethod = null;
        String author = null;
        String language = null;

        if (fileNameLower.endsWith(".pdf")) {
            PdfRenderer renderer = null;
            ParcelFileDescriptor pfd = null;
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
                pageCount = renderer.getPageCount();
            } catch (Exception ignored) {
            } finally {
                if (renderer != null) renderer.close();
                else Util.close(pfd);   // usually the ParcelFileDescriptor is closed by the PdfRenderer
            }
        } else if (fileNameLower.endsWith(".apk")) {
            PackageManager pm = getPackageManager();
            final PackageInfo packageInfo = pm.getPackageArchiveInfo(file.getAbsolutePath(),
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES);
            if (packageInfo == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Did not get PackageInfo for " + file);
                Snackbar.make(UiActivity.this.coordinatorLayout, R.string.error_packageinfo, Snackbar.LENGTH_LONG).show();
                return;
            }
            if (packageInfo.packageName != null) {
                titleWithoutQuotes = true;
                title = packageInfo.packageName;
                String version = packageInfo.versionName;
                if (version != null && version.length() > 0) title = title + ' ' + version;
            }
            final String[] reqperms = packageInfo.requestedPermissions;
            if (reqperms != null && reqperms.length > 0) {
                final Map<String, Boolean> dangerous = new HashMap<>(reqperms.length);
                final Map<String, String> labels = new HashMap<>(reqperms.length);
                for (String reqperm : reqperms) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Requested permission by " + file + ": " + reqperm);
                    if (reqperm == null) continue;
                    PermissionInfo permissionInfo = null;
                    // we include something that the no-evil-company does not consider to be dangerous (to their business)
                    boolean isdangerous = "android.permission.INTERNET".equals(reqperm)
                            || "com.android.vending.BILLING".equals(reqperm)
                            || reqperm.startsWith("com.google.");
                    try {
                        permissionInfo = pm.getPermissionInfo(reqperm, PackageManager.GET_META_DATA);
                        if (permissionInfo == null) continue;
                        isdangerous = isdangerous || ((permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS);
                        dangerous.put(reqperm, isdangerous);
                    } catch (PackageManager.NameNotFoundException e) {
                        if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
                        dangerous.put(reqperm, isdangerous);
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                        dangerous.put(reqperm, isdangerous);
                    }
                    if (permissionInfo == null) continue;
                    try {
                        CharSequence label = permissionInfo.loadLabel(pm);
                        labels.put(reqperm, label.toString());
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) Log.w(TAG, e.toString());
                    }
                }

                Arrays.sort(reqperms, (o1, o2) -> {
                    Boolean o1isdangerous = dangerous.get(o1);
                    if (o1isdangerous == null) o1isdangerous = Boolean.FALSE;
                    Boolean o2isdangerous = dangerous.get(o2);
                    if (o2isdangerous == null) o2isdangerous = Boolean.FALSE;
                    if (o1isdangerous && !o2isdangerous) return -1;
                    if (o2isdangerous && !o1isdangerous) return 1;
                    return o1.compareToIgnoreCase(o2);
                });
                SpannableStringBuilder sb = new SpannableStringBuilder();
                for (String reqperm : reqperms) {
                    Boolean isdangerous = dangerous.get(reqperm);
                    String label = labels.get(reqperm);
                    if (label == null) label = reqperm;
                    if (label.startsWith("android.permission.")) label = label.substring(19);
                    if (isdangerous != null && isdangerous) {
                        label = "⚠" + label;
                        int pos = sb.length();
                        sb.append(label);
                        ForegroundColorSpan fsp = new ForegroundColorSpan(getResources().getColor(R.color.colorTextPrimaryReddish));
                        sb.setSpan(fsp, pos, pos + 1, 0);
                    } else {
                        sb.append(label);
                    }
                    sb.append(", ");
                }
                if (sb.length() > 2) sb.delete(sb.length() - 2, sb.length());
                permissions = sb;
            }
            try {
                android.content.pm.Signature[] sis = packageInfo.signatures;
                if (sis != null && sis.length > 0) {
                    X509Certificate[] certs;
                    CertificateFactory certFactory = CertificateFactory.getInstance("X509");
                    certs = new X509Certificate[sis.length];
                    for (int i = 0; i < sis.length; i++) {
                        certs[i] = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(sis[i].toByteArray()));
                    }
                    for (X509Certificate xcert : certs) {
                        if (xcert == null) continue;
                        boolean valid = true;
                        try {
                            xcert.checkValidity();
                        } catch (java.security.cert.CertificateException e) {
                            valid = false;
                        }
                        String issuer = xcert.getIssuerX500Principal().getName();
                        if (issuer == null || issuer.length() == 0) continue;
                        if (valid) {
                            signature = new SpannableString(UiUtil.formatIssuer(issuer, getString(R.string.label_from)));
                        } else {
                            String invalid = getString(R.string.label_invalid);
                            signature = new SpannableString(invalid + ' ' + UiUtil.formatIssuer(issuer, "in"));
                            ForegroundColorSpan fsp = new ForegroundColorSpan(getResources().getColor(R.color.design_default_color_error));
                            signature.setSpan(fsp, 0, invalid.length(), 0);
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        } else if (fileNameLower.endsWith(".epub")) {
            EpubAnalyzer epubAnalyzer = EpubAnalyzer.analyze(file);
            validFile = epubAnalyzer.isValid();
            title = epubAnalyzer.getTitle();
            author = epubAnalyzer.getCreator();
            language = epubAnalyzer.getLanguage();
            comment = epubAnalyzer.getDescription();
        } else if (fileNameLower.endsWith(".zip")) {
            final ZipFile zipFile = new ZipFile(file);
            try {
                validFile = zipFile.isValidZipFile();
                encrypted = zipFile.isEncrypted();
                final List<FileHeader> headers = zipFile.getFileHeaders();
                final Set<EncryptionMethod> encryptionMethods = new HashSet<>();
                boolean deflated = false;
                boolean stored = false;
                for (FileHeader header : headers) {
                    uncompressedSize += header.getUncompressedSize();
                    if (encrypted) {
                        EncryptionMethod em = header.getEncryptionMethod();
                        if (em != null) encryptionMethods.add(em);
                    }
                    CompressionMethod cm = header.getCompressionMethod();
                    if (cm == CompressionMethod.DEFLATE) deflated = true;
                    else if (cm == CompressionMethod.STORE) stored = true;
                }
                if (deflated) zipCompressionMethod = "deflate";
                else if (stored) zipCompressionMethod = "store";
                int numEncryptionMethods = encryptionMethods.size();
                if (numEncryptionMethods > 0) {
                    if (numEncryptionMethods == 1) {
                        zipEncryptionMethod = encryptionMethods.iterator().next().toString().replace('_', ' ');
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (EncryptionMethod em : encryptionMethods) sb.append(em.toString().replace('_', ' ')).append(' ');
                        zipEncryptionMethod = sb.toString().trim();
                    }
                }
                comment = zipFile.getComment();
            } catch (ZipException e) {
                validFile = false;
                if (BuildConfig.DEBUG) Log.e(TAG, "While inspecting " + file + ": " + e.toString());
            }
        } else {
            // use FFmpegMediaMetadataRetriever
            FFmpegMediaMetadataRetriever mmr = App.MMRT.get();
            try {
                mmr.setDataSource(file.getAbsolutePath());
                FFmpegMediaMetadataRetriever.Metadata md = mmr.getMetadata();
                super.handler.post(App.MMRT::remove);
                final HashMap<String, String> all = md.getAll();
                for (Map.Entry<String, String> entry : all.entrySet()) {
                    String key = entry.getKey();
                    //if (BuildConfig.DEBUG) Log.i(TAG, "FFMMR - " + entry.getKey() + "=" + entry.getValue());
                    if ("duration".equals(key)) {
                        ms = Util.parseInt(entry.getValue(), 0);
                    } else if ("title".equals(key)) {
                        title = entry.getValue();
                    } else if (mime.startsWith("video/") && "video_codec".equals(key)) {
                        videocodec = entry.getValue();
                    } else if ("audio_codec".equals(key)) {
                        audiocodec = entry.getValue();
                    } else if ("video_width".equals(key)) {
                        width = Util.parseInt(entry.getValue(), 0);
                    } else if ("video_height".equals(key)) {
                        height = Util.parseInt(entry.getValue(), 0);
                    } else if ("framerate".equals(key)) {
                        fps = Util.parseInt(entry.getValue(), 0);
                    }
                }
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) Log.e(TAG, "While letting FFmpegMediaMetadataRetriever get info about \"" + file.getName() + "\": " + e.toString());
            }

            // use ExifInterface
            if (ExifInterface.isSupportedMimeType(mime)) {
                InputStream in = null;
                try {
                    in = new FileInputStream(file);
                    Bundle metadata = MetadataReader.getMetadata(in, null);
                    @SuppressLint("InlinedApi")
                    Bundle exifData = metadata.getBundle(DocumentsContract.METADATA_EXIF);
                    if (exifData != null) {
                        if (BuildConfig.DEBUG) DebugUtil.logBundle(exifData);
                        Set<String> exifKeys = exifData.keySet();
                        for (String exifKey : exifKeys) {
                            if (ExifInterface.TAG_MAKE.equals(exifKey)) make = exifData.getString(exifKey);
                            else if (ExifInterface.TAG_MODEL.equals(exifKey)) model = exifData.getString(exifKey);
                            else if (ExifInterface.TAG_ISO_SPEED_RATINGS.equals(exifKey)) iso = exifData.getInt(exifKey);
                            else if (ExifInterface.TAG_F_NUMBER.equals(exifKey)) fnumber = exifData.getDouble(exifKey);
                            else if (ExifInterface.TAG_EXPOSURE_TIME.equals(exifKey)) exposuretime = exifData.getDouble(exifKey);
                            else if (ExifInterface.TAG_DATETIME_ORIGINAL.equals(exifKey)) dateTimeOriginal = MetadataReader.parseDateTime(exifData.getString(exifKey));
                            else if (ExifInterface.TAG_DATETIME.equals(exifKey)) dateTime = MetadataReader.parseDateTime(exifData.getString(exifKey));
                            else if (ExifInterface.TAG_GPS_LONGITUDE.equals(exifKey)) lon = MetadataReader.parseGps(exifData.getString(exifKey));
                            else if (ExifInterface.TAG_GPS_LATITUDE.equals(exifKey)) lat = MetadataReader.parseGps(exifData.getString(exifKey));
                            else if (ExifInterface.TAG_GPS_LONGITUDE_REF.equals(exifKey)) lonref = exifData.getString(exifKey);
                            else if (ExifInterface.TAG_GPS_LATITUDE_REF.equals(exifKey)) latref = exifData.getString(exifKey);
                            else if (ExifInterface.TAG_IMAGE_DESCRIPTION.equals(exifKey)) title = exifData.getString(exifKey);
                            else if (ExifInterface.TAG_SOFTWARE.equals(exifKey)) software = exifData.getString(exifKey);
                        }
                    }
                } catch (IOException e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                } finally {
                    Util.close(in);
                }
            }
        }

        /*
        Dolphin displays attributes for images in this order:
        width, height, f-number, shutter speed, ? EV, ISO, focal length,
        flash, orientation, latitude, longitude, altitude, maker, model, date, …
         */

        // build message
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(getString(R.string.label_size_bytes, NumberFormat.getNumberInstance().format(file.length()))).append('\n');
        if (!TextUtils.isEmpty(origin)) sb.append(getString(R.string.label_from_host, origin)).append('\n');
        if (pageCount > 0) sb.append(getString(R.string.label_pages, pageCount)).append('\n');
        if (title != null && TextUtils.getTrimmedLength(title) > 0) {
            if (titleWithoutQuotes) sb.append(title.trim()).append('\n');
            else sb.append(getString(R.string.label_title, title.trim())).append('\n');
        }
        if (author != null && TextUtils.getTrimmedLength(author) > 0) {
            sb.append(getString(R.string.label_author, author.trim())).append('\n');
        }
        if (language != null && TextUtils.getTrimmedLength(language) > 0) {
            language = language.trim();
            final Locale[] lls = Locale.getAvailableLocales();
            for (Locale l : lls) {
                if (language.equals(l.getLanguage())) {
                    language = l.getDisplayLanguage();
                    break;
                }
            }
            sb.append(getString(R.string.label_language, language)).append('\n');
        }
        if (ms > 0) sb.append(getString(R.string.label_duration, UiUtil.formatMs(ms))).append('\n');
        if (make != null || model != null) sb.append(getString(R.string.label_model, make != null ? (model != null ? make  + ' ' + model : make) : model)).append('\n');
        if (width > 0 && height > 0) {
            if (mime.startsWith("image/")) sb.append(getString(R.string.label_size_xy_mp, width, height, (int)Math.floor(width * height / 1_000_000.))).append('\n');
            else sb.append(getString(R.string.label_size_xy, width, height)).append('\n');
        }
        if (fnumber > 0.) sb.append(getString(R.string.label_fnumber, NumberFormat.getNumberInstance().format(fnumber))).append('\n');
        if (exposuretime > 0.) sb.append(getString(R.string.label_exposure, NumberFormat.getNumberInstance().format(1. / exposuretime))).append('\n');
        if (iso > 0) sb.append(getString(R.string.label_iso,iso)).append('\n');
        if (dateTimeOriginal != null) sb.append(getString(R.string.label_date, DateFormat.getDateTimeInstance().format(dateTimeOriginal))).append('\n');
        else if (dateTime != null) sb.append(getString(R.string.label_date, DateFormat.getDateTimeInstance().format(dateTime))).append('\n');
        if (lat != null && lon != null && latref != null && lonref != null) {
            sb.append(getString(R.string.label_location, lat, latref, lon, lonref)).append('\n');
        }
        if (fps > 1) sb.append(getString(R.string.label_fps, fps)).append('\n');
        if (ms > 0) {
            if (!TextUtils.isEmpty(videocodec)) sb.append(getString(R.string.label_videocodec, videocodec)).append('\n');
            if (!TextUtils.isEmpty(audiocodec)) sb.append(getString(R.string.label_audiocodec, audiocodec)).append('\n');
        }
        if (software != null) sb.append(getString(R.string.label_software, software)).append('\n');
        if (signature != null) sb.append(getString(R.string.label_signature)).append(": ").append(signature).append('\n');
        if (permissions != null) sb.append(getString(R.string.label_permissions)).append(": ").append(permissions).append('\n');

        if (!validFile) sb.append(getString(R.string.label_invalid)).append('\n');
        if (uncompressedSize > 0L) {
            sb.append(getString(R.string.label_uncompressed_size, UiUtil.formatBytes(uncompressedSize)));
            if (!TextUtils.isEmpty(zipCompressionMethod)) sb.append(" (").append(zipCompressionMethod).append(')');
            sb.append('\n');
        }
        if (encrypted) {
            sb.append(getString(R.string.label_encrypted));
            if (!TextUtils.isEmpty(zipEncryptionMethod)) sb.append(" (").append(zipEncryptionMethod).append(')');
            sb.append('\n');
        }

        if (comment != null && comment.length() > 0 && comment.length() <= 256) sb.append(getString(R.string.label_comment)).append(": ").append(comment).append('\n');

        if (sb.length() == 0) sb.append(getString(R.string.label_shrug));
        else if (sb.charAt(sb.length() - 1) == '\n') sb.delete(sb.length() - 1, sb.length());

        View v = LayoutInflater.from(this).inflate(R.layout.info, null);
        TextView mtw = v.findViewById(R.id.textViewInfo);
        mtw.setText(sb, TextView.BufferType.SPANNABLE);

        // display dialog
        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setIcon(R.drawable.ic_baseline_info_24)
                .setTitle(file.getName())
                .setView(v)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                .setNeutralButton(R.string.action_share, (dialog, which) -> {
                    dialog.dismiss();
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
                    intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
                    intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + getPackageName()));
                    Intent chooserIntent = Intent.createChooser(intent, null);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {new ComponentName(UiActivity.this, MainActivity.class)});
                    }
                    startActivity(chooserIntent);
                })
                ;
        super.handler.post(() -> {
            UiActivity.this.dialogInfo = builder.create();
            Window dialogWindow = UiActivity.this.dialogInfo.getWindow();
            if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
            UiActivity.this.dialogInfo.show();
        });
    }

    @Override
    @UiThread
    public void thumbCreated(@NonNull File file) {
        if (BuildConfig.DEBUG) Log.i(TAG, "A thumbnail picture for " + file.getName() + " has been created.");
        int index = this.downloadsAdapter.getIndex(file);
        if (index < 0) return;
        this.downloadsAdapter.notifyItemChanged(index);
    }

    /**
     * Updates the download files list according to the {@link #mimeFilter MIME filter}.
     * Does not do anything if there is no MIME filter.
     * @param unfilteredCount unfiltered number of download files
     * @return number of download files to display
     */
    private int updateFilter(int unfilteredCount) {
        final int count;
        this.filteredList.clear();
        if (this.mimeFilter == null) {
            count = unfilteredCount;
        } else {
            if (this.mimeFilter.indexOf('*') >= 0) {
                // with placeholder
                this.mimeFilter = this.mimeFilter.replace("*", ".*");
                final Pattern p = Pattern.compile(this.mimeFilter);
                synchronized (super.downloads) {
                    for (File download : super.downloads) {
                        String mime = Util.getMime(download);
                        Matcher matcher = p.matcher(mime);
                        if (matcher.matches()) this.filteredList.add(download);
                    }
                }
            } else {
                // exact match
                synchronized (super.downloads) {
                    for (File download : super.downloads) {
                        String mime = Util.getMime(download);
                        if (this.mimeFilter.equalsIgnoreCase(mime)) this.filteredList.add(download);
                    }
                }
            }
            count = this.filteredList.size();
        }
        this.downloadsAdapter.notifyDataSetChanged();
        return count;
    }

    /**
     * The type of View used to display a download file;
     * referring to either {@link R.layout#download_view} or  {@link R.layout#download_view_grid}.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({VIEW_TYPE_LINEAR, VIEW_TYPE_GRID})
    @interface ViewType {}

    /**
     * Adds some space below the last row of the RecyclerView so that the navigation bar does not cover it.
     */
    static class RecyclerViewBottomMargin extends RecyclerView.ItemDecoration {

        /** height of the navigation bar in pixels */
        private final int navbarheight;
        /** number of columns of the RecyclerView's LayoutManager - must be set when the LayoutManager is chosen */
        private int columns;

        /**
         * Constructor.
         * @param navbarheight height of the navigation bar
         * @param columns number of columns
         */
        RecyclerViewBottomMargin(int navbarheight, @IntRange(from = 1) int columns) {
            super();
            this.navbarheight = navbarheight;
            this.columns = columns;
        }

        /** {@inheritDoc} */
        @Override
        public void getItemOffsets(@NonNull final Rect outRect, @NonNull final View view, @NonNull final RecyclerView recyclerView, @NonNull RecyclerView.State ignored) {
            RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
            if (adapter == null) return;
            final int n = adapter.getItemCount();
            // the following means essentially: is the view in the bottom row?
            int inLastRow = n % this.columns;
            if (inLastRow == 0) inLastRow = Math.min(n, this.columns);
            if (recyclerView.getChildAdapterPosition(view) >= n - inLastRow) {
                outRect.bottom = this.navbarheight;
            } else {
                outRect.bottom = 0;
            }
        }

        void setColumns(@IntRange(from = 1) int columns) {
            this.columns = columns;
        }
    }

    /**
     *
     */
    private static class ViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener, PopupMenu.OnMenuItemClickListener {

        private static void toggleSelection(@NonNull UiActivity activity, @IntRange(from = 0) int position) {
            File file;
            synchronized (activity.downloads) {
                file = activity.downloads.get(position);
            }
            if (activity.downloadsAdapter.selection.contains(file)) {
                activity.downloadsAdapter.selection.remove(file);
            } else {
                activity.downloadsAdapter.selection.add(file);
            }
            activity.downloadsAdapter.notifyItemChanged(position);
            activity.getDelegate().invalidateOptionsMenu();
        }

        private final TextView textViewTitle;
        private final TextView textViewDate;
        private final TextView textViewSize;
        private final TextView textViewType;
        private final ImageView logoView;
        private final ImageView buttonMore;
        @LayoutRes private final int layout;
        @NonNull private final ImageSetter imageSetter;

        /**
         * Constructor.
         * @param itemView View
         * @param layout layout resource id
         */
        public ViewHolder(@NonNull final View itemView, @LayoutRes int layout) {
            super(itemView);

            this.layout = layout;

            this.textViewTitle = itemView.findViewById(R.id.textViewTitle);
            this.textViewDate = itemView.findViewById(R.id.textViewDate);
            this.textViewSize = itemView.findViewById(R.id.textViewSize);
            this.textViewType = itemView.findViewById(R.id.textViewType);
            this.logoView = itemView.findViewById(R.id.imageViewLogo);
            this.buttonMore = itemView.findViewById(R.id.buttonMore);

            this.itemView.setOnLongClickListener(this);
            this.logoView.setOnLongClickListener(this);
            this.textViewTitle.setOnLongClickListener(this);
            this.textViewDate.setOnLongClickListener(this);

            this.itemView.setOnClickListener(this);
            this.textViewTitle.setOnClickListener(this);
            this.textViewDate.setOnClickListener(this);
            this.textViewSize.setOnClickListener(this);
            this.textViewType.setOnClickListener(this);
            this.logoView.setOnClickListener(this);
            this.buttonMore.setOnClickListener(this);

            this.imageSetter = new ImageSetter();
        }

        /** {@inheritDoc} */
        @SuppressLint("RestrictedApi")
        @Override
        public void onClick(final View v) {
            Context ctx = v.getContext();
            if (!(ctx instanceof UiActivity)) return;
            final UiActivity activity = (UiActivity)ctx;
            final int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;

            final boolean hasSelection = activity.downloadsAdapter.hasSelection();
            final boolean hasSelectionWithProtectedFiles = hasSelection && activity.downloadsAdapter.hasSelectionProtected();

            final File file;
            synchronized (activity.downloads) {
                file = activity.downloads.get(position);
            }
            if (!file.isFile()) return;

            if (v == this.buttonMore) {
                final boolean downloading = ((App)activity.getApplicationContext()).isBeingDownloaded(file);
                PopupMenu popup = new PopupMenu(activity, v);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    popup.setForceShowIcon(true);
                }
                popup.setOnMenuItemClickListener(this);
                final Menu menu = popup.getMenu();
                popup.getMenuInflater().inflate(R.menu.download_view, menu);
                final String fileName = file.getName().toLowerCase();
                final boolean isPicture = Util.isPicture(fileName);
                final boolean writeable = file.canWrite();
                boolean isZip = Util.isZip(file);
                boolean isApk = fileName.endsWith(".apk") && isZip;  // the additional confirmation via isZip() is due to this page: https://github.com/dxopt/OpenFilesLeakTest/blob/master/README.md aka https://code.google.com/p/android/issues/detail?id=171099

                // show "use as wallpaper" only if a picture has a minimum size, either given by the WallpaperManager or via the screen size
                @Size(2) final int[] imageSize;
                if (!isPicture) {
                    imageSize = null;
                } else {
                    if (activity.imageSizeCache.containsKey(fileName)) {
                        imageSize = activity.imageSizeCache.get(fileName);
                    } else {
                        imageSize = Util.getImageSize(file);
                        activity.imageSizeCache.put(fileName, imageSize);
                    }
                }
                int wallpaperMinWidth = 0, wallpaperMinHeight = 0;
                if (imageSize != null) {
                    WallpaperManager wallpaperManager = (WallpaperManager)activity.getSystemService(WALLPAPER_SERVICE);
                    wallpaperMinWidth = wallpaperManager.getDesiredMinimumWidth();
                    wallpaperMinHeight = wallpaperManager.getDesiredMinimumHeight();
                    if (wallpaperMinWidth <= 0 || wallpaperMinHeight <= 0) {
                        Display display = activity.getWindowManager().getDefaultDisplay();
                        Point p = new Point();
                        display.getSize(p);
                        wallpaperMinWidth = wallpaperMinHeight = Math.max(p.x, p.y);
                    }
                }

                MenuItem menuItemShare = menu.findItem(R.id.action_share_item);
                MenuItem menuItemRename = menu.findItem(R.id.action_rename);
                MenuItem menuItemDelete = menu.findItem(R.id.action_delete);
                MenuItem menuItemProtect = menu.findItem(R.id.action_protect);
                MenuItem menuItemInfo = menu.findItem(R.id.action_more_info);
                MenuItem menuItemInstall = menu.findItem(R.id.action_install);
                MenuItem menuItemStoreSearch = menu.findItem(R.id.action_store_search);
                MenuItem menuItemAsWallpaper = menu.findItem(R.id.action_wallpaper);
                MenuItem menuItemChecksum = menu.findItem(R.id.action_checksum);

                menuItemShare.setTitle(hasSelection ? R.string.action_share_multi : R.string.action_share);
                menuItemDelete.setTitle(hasSelection ? R.string.action_delete_multi : R.string.action_delete);
                menuItemProtect.setTitle(writeable ? R.string.action_protect_from_deletion_on : R.string.action_protect_from_deletion_off);

                menuItemRename.setVisible(!hasSelection);
                menuItemAsWallpaper.setVisible(!downloading && !hasSelection && isPicture && imageSize != null && imageSize[0] > wallpaperMinWidth && imageSize[1] > wallpaperMinHeight);
                menuItemInstall.setVisible(!hasSelection && !downloading && isApk);
                menuItemStoreSearch.setVisible(!hasSelection && !downloading && isApk);
                menuItemProtect.setVisible(!hasSelection);
                menuItemInfo.setVisible(!hasSelection && file.length() > 0L && (activity.infoCreatorThread == null || !activity.infoCreatorThread.isAlive())
                        && (Util.isMovie(fileName)
                        || Util.isAudio(fileName)
                        || isPicture
                        || fileName.endsWith(".pdf")
                        || isApk
                        || isZip));
                menuItemChecksum.setVisible(!hasSelection);

                menuItemShare.setEnabled(!downloading);
                menuItemRename.setEnabled(!downloading && writeable);
                menuItemDelete.setEnabled(!downloading && writeable && !hasSelectionWithProtectedFiles);
                menuItemProtect.setEnabled(!downloading);
                menuItemChecksum.setEnabled(!downloading);

                popup.show();
                return;
            }

            // if there is already a selection, the short click will be interpreted as a long click
            if (hasSelection) {
                toggleSelection(activity, position);
                return;
            }

            // default action, when the logo, the title or the date/size views have been clicked, is to view the file
            String mime = null;
            String fileName = file.getName();
            int dot = fileName.lastIndexOf('.');
            if (dot > 0 && dot < fileName.length() - 1) {
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase(Locale.US));
            }
            Uri uri = FileProvider.getUriForFile(ctx, BuildConfig.FILEPROVIDER_AUTH, file);
            final Intent viewIntent = new Intent();
            viewIntent.setAction(Intent.ACTION_VIEW);
            Rect r = new Rect();
            v.getGlobalVisibleRect(r);
            viewIntent.setSourceBounds(r);
            viewIntent.setDataAndType(uri, mime != null ? mime : "*/*");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooserIntent = Intent.createChooser(viewIntent, null);
            chooserIntent.setSourceBounds(r);
            // https://developer.android.com/reference/android/content/Intent#EXTRA_EXCLUDE_COMPONENTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ComponentName[] excluded = new ComponentName[] {new ComponentName(ctx, MainActivity.class)};
                chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded);
            }
            ComponentName cn = chooserIntent.resolveActivity(ctx.getPackageManager());
            if (cn == null) {
                CoordinatorLayout cl = activity.getCoordinatorLayout();
                if (mime != null) {
                    Snackbar.make(cl, ctx.getString(R.string.msg_no_applications_for, mime), Snackbar.LENGTH_LONG).show();
                } else {
                    Snackbar.make(cl, R.string.msg_no_applications, Snackbar.LENGTH_LONG).show();
                }
                return;
            }
            ctx.grantUriPermission(cn.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(chooserIntent);
        }

        /** {@inheritDoc} */
        @Override
        public boolean onLongClick(View v) {
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;
            Context ctx = v.getContext();
            if (!(ctx instanceof UiActivity)) return false;
            toggleSelection((UiActivity)ctx, position);
            return true;
        }

        /** {@inheritDoc} */
        @Override
        public boolean onMenuItemClick(MenuItem item) {
            Context ctx = this.itemView.getContext();
            if (!(ctx instanceof UiActivity)) return false;
            final UiActivity activity = (UiActivity)ctx;
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;

            final int selectionSize = activity.downloadsAdapter.selection.size();

            final File file = activity.downloads.get(position);
            if (selectionSize == 0) {
                if (!file.isFile()) return false;
            }
            final int id = item.getItemId();
            if (id == R.id.action_share_item) {
                if (selectionSize > 0) {
                    Util.sendMulti(activity, activity.downloadsAdapter.selection, BuildConfig.FILEPROVIDER_AUTH);
                    activity.downloadsAdapter.selection.clear();
                    activity.getDelegate().invalidateOptionsMenu();
                } else {
                    Util.send(activity, file, BuildConfig.FILEPROVIDER_AUTH, Util.getMime(file));
                }
                return true;
            } else if (id == R.id.action_rename) {
                View v = LayoutInflater.from(activity).inflate(R.layout.filename_edit, null);
                final EditText editFilename = v.findViewById(R.id.editFilename);
                editFilename.setText(file.getName());
                editFilename.addTextChangedListener(new SimpleTextWatcher() {

                    private final String original = file.getName();
                    private final File dir = file.getParentFile();
                    private final Handler handler = new Handler();

                    @Override
                    public void afterTextChanged(final Editable s) {
                        boolean valid;
                        if (s == null || TextUtils.getTrimmedLength(s) == 0) {
                            valid = false;
                        } else if (TextUtils.indexOf(s, '/') >= 0) {
                            // replace normal slashes with similar looking ones
                            this.handler.post(() -> {
                                int slash = TextUtils.indexOf(s, '/');
                                // the replacement '∕' is 0x2215 (https://en.wikibooks.org/wiki/Unicode/Character_reference/2000-2FFF)
                                editFilename.setText(s.replace(slash, slash + 1, "∕"));
                                editFilename.setSelection(slash + 1);
                            });
                            valid = false;
                        } else {
                            File proposed = new File(this.dir, s.toString());
                            if (proposed.exists()) {
                                editFilename.setError(this.original.equals(s.toString()) ? null : activity.getString(R.string.error_file_already_exists), null);
                                valid = false;
                            } else {
                                valid = true;
                                editFilename.setError(null);
                            }
                        }
                        if (activity.dialogRename != null) activity.dialogRename.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(valid);
                    }
                });
                AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                        .setTitle(R.string.action_rename)
                        .setIcon(R.drawable.ic_baseline_edit_24)
                        .setView(v)
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
                        .setPositiveButton(R.string.action_rename, (dialog, which) -> {
                            dialog.dismiss();
                            CharSequence entered = editFilename.getText();
                            if (entered == null) return;
                            entered = entered.toString().trim();
                            if (entered.length() == 0) return;
                            entered = Util.sanitizeFilename(entered);
                            File parent = file.getParentFile();
                            File renamed = new File(parent, entered.toString());
                            boolean ok = file.renameTo(renamed);
                            if (ok) {
                                App app = (App)activity.getApplicationContext();
                                app.getThumbsManager().removeThumbnail(file);
                                Ancestry.getInstance().transfer(file, renamed);
                                activity.imageSizeCache.remove(file.getName().toLowerCase());
                                // see DocumentsProvider.revokeDocumentPermission()
                                String path = file.getAbsolutePath();
                                activity.revokeUriPermission(buildDocumentUri(BuildConfig.DOCSPROVIDER_AUTH, path), ~0);
                                activity.revokeUriPermission(buildTreeDocumentUri(BuildConfig.DOCSPROVIDER_AUTH, path), ~0);
                                activity.getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);
                                activity.refresh();
                            } else {
                                Snackbar.make(activity.coordinatorLayout, activity.getString(R.string.error_rename_failed,file.getName()), Snackbar.LENGTH_SHORT).show();
                            }
                        })
                        ;
                activity.dialogRename = builder.create();
                Window dialogWindow = activity.dialogRename.getWindow();
                if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
                activity.dialogRename.show();
                activity.dialogRename.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
                return true;
            } else if (id == R.id.action_more_info) {
                if (activity.infoCreatorThread != null && activity.infoCreatorThread.isAlive()) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Info for another file is being collected!");
                    return true;
                }
                activity.infoCreatorThread = new Thread() {
                    @Override
                    public void run() {
                        activity.showInfoDialog(file);
                    }
                };
                activity.infoCreatorThread.setPriority(Thread.NORM_PRIORITY - 1);
                activity.infoCreatorThread.start();
                return true;
            } else if (id == R.id.action_install) {
                Thread installer = new Thread() {
                    @Override
                    public void run() {
                        activity.install(file);
                    }
                };
                installer.setPriority(Thread.NORM_PRIORITY - 1);
                installer.start();
                return true;
            } else if (id == R.id.action_store_search) {
                PackageManager pm = activity.getPackageManager();
                PackageInfo packageInfo = pm.getPackageArchiveInfo(file.getAbsolutePath(),0);
                if (packageInfo  != null && packageInfo.packageName != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pname:" + packageInfo.packageName));
                    ComponentName cn = intent.resolveActivity(pm);
                    if (cn != null) {
                        activity.startActivity(intent);
                    } else {
                        Snackbar.make(activity.coordinatorLayout, R.string.msg_no_applications, Snackbar.LENGTH_SHORT).show();
                    }
                } else {
                    Snackbar.make(activity.coordinatorLayout, R.string.error_cant_handle_that, Snackbar.LENGTH_SHORT).show();
                }
                return true;
            } else if (id == R.id.action_wallpaper) {
                WallpaperManager wallpaperManager = (WallpaperManager) activity.getSystemService(WALLPAPER_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (!wallpaperManager.isSetWallpaperAllowed()) return true;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(activity, BuildConfig.FILEPROVIDER_AUTH, file);
                    Intent i = wallpaperManager.getCropAndSetWallpaperIntent(uri);
                    i.putExtra(Intent.EXTRA_TITLE, file.getName());
                    activity.startActivity(i);
                } catch (IllegalArgumentException e) {
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                int wid = wallpaperManager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM);
                                if (wid == 0) {
                                    Snackbar.make(activity.coordinatorLayout, R.string.error_cant_handle_that, Snackbar.LENGTH_SHORT).show();
                                }
                            } else {
                                wallpaperManager.setBitmap(bitmap);
                            }
                        } catch (Exception ioe) {
                            String msg = ioe.getMessage() != null ? ioe.getMessage() : ioe.toString();
                            Snackbar.make(activity.coordinatorLayout, msg, Snackbar.LENGTH_SHORT).show();
                        }
                    } else {
                        Snackbar.make(activity.coordinatorLayout, R.string.error_cant_handle_that, Snackbar.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    Snackbar.make(activity.coordinatorLayout, msg, Snackbar.LENGTH_SHORT).show();
                }
                return true;
            } else if (id == R.id.action_delete) {
                activity.delete(file);
                return true;
            } else if (id == R.id.action_protect) {
                boolean ok = file.setWritable(!file.canWrite(), false);
                if (!ok) Snackbar.make(activity.coordinatorLayout, R.string.error_cant_handle_that, Snackbar.LENGTH_SHORT).show();
            } else if (id == R.id.action_checksum_md5 || id == R.id.action_checksum_sha1 || id == R.id.action_checksum_sha256 || id == R.id.action_checksum_sha512) {
                final String algo;
                if (id == R.id.action_checksum_md5) algo = ChecksumCalculator.ALGO_MD5;
                else if (id == R.id.action_checksum_sha1) algo = ChecksumCalculator.ALGO_SHA1;
                else if (id == R.id.action_checksum_sha256) algo = ChecksumCalculator.ALGO_SHA256;
                else algo = ChecksumCalculator.ALGO_SHA512;
                if (activity.checksumCalculator != null && activity.checksumCalculator.isAlive()) {
                    activity.checksumCalculator.abort();
                }
                activity.checksumCalculator = new ChecksumCalculator(activity, file, algo);
                activity.checksumCalculator.start();
            }
            return false;
        }

        /**
         * Puts either a bitmap or a resource drawable into the logo view.
         */
        private class ImageSetter implements Runnable {
            private Bitmap image;
            @DrawableRes private int imageResource;

            @Override
            public void run() {
                if (this.image != null) {
                    ViewHolder.this.logoView.setImageBitmap(this.image);
                } else if (this.imageResource != 0) {
                    ViewHolder.this.logoView.setImageResource(this.imageResource);
                } else {
                    ViewHolder.this.logoView.setImageResource(R.drawable.blank);
                }
            }

            void setImage(Bitmap image) {
                this.image = image;
                this.imageResource = 0;
            }

            void setImageResource(@DrawableRes int imageResource) {
                this.imageResource = imageResource;
                this.image = null;
            }
        }
    }

    /**
     * Calculates a checksum of a file.<br>
     * Examples of execution times on a lower-middle-class device:<br>
     * <ul>
     * <li>7.8 sec for SHA-512 of a 746 MB video file.</li>
     * <li>17.4 sec for SHA-512 of a 1673 MB video file.</li>
     * <li>6.8 sec for MD5 of a 746 MB video file.</li>
     * </ul>
     */
    @VisibleForTesting
    public static class ChecksumCalculator extends Thread {

        @ChecksumAlgorithm static final String ALGO_MD5 = "MD5";
        @ChecksumAlgorithm static final String ALGO_SHA1 = "SHA-1";
        @ChecksumAlgorithm static final String ALGO_SHA256 = "SHA-256";
        @ChecksumAlgorithm static final String ALGO_SHA512 = "SHA-512";

        @Nullable private final Handler handler;
        @Nullable private final Reference<Activity> refa;
        @NonNull private final File file;
        @NonNull private final String algorithm;
        @Nullable private CharSequence checkSum;
        private Exception exception;
        private volatile boolean stop;

        /**
         * Constructor.<br>
         * Must be called on the ui thread unless activity is null.
         * @param activity UiActivity <em>, may be null only during testing!</em>
         * @param file File
         * @param algorithm algorithm
         */
        @VisibleForTesting
        public ChecksumCalculator(@Nullable Activity activity, @NonNull File file, @ChecksumAlgorithm @NonNull String algorithm) {
            super();
            // handler may be null only if activity is null, too
            this.handler = activity != null ? new Handler() : null;
            this.refa = activity != null ? new WeakReference<>(activity) : null;
            this.file = file;
            this.algorithm = algorithm;
            setPriority(Thread.NORM_PRIORITY - 1);
        }

        /**
         * Requests the calculation to stop.
         */
        private void abort() {
            if (BuildConfig.DEBUG) Log.i(TAG, "Stopping " + this.algorithm + " checksum calculation for " + this.file);
            this.stop = true;
        }

        /**
         * Calculates the checksum.
         * @param file File to calculate the checksum for
         * @param algorithm algorithm to use
         * @return checksum
         */
        @Nullable
        private CharSequence checkSum(@NonNull final File file, @ChecksumAlgorithm @NonNull String algorithm) {
            if (!file.isFile()) return null;
            CharSequence cs = null;
            InputStream in = null;
            try {
                final MessageDigest md = MessageDigest.getInstance(algorithm);
                final byte[] b = new byte[4096];
                in = new FileInputStream(file);
                while (!this.stop) {
                    int read = in.read(b);
                    if (read < 0) break;
                    md.update(b, 0, read);
                }
                Util.close(in);
                if (this.stop) return null;
                in = null;
                byte[] hash = md.digest();
                cs = Util.asHex(hash);
            } catch (Exception e) {
                this.exception = e;
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            } finally {
                Util.close(in);
            }
            return cs;
        }

        /** {@inheritDoc} */
        @Override
        public void run() {
            // if the file is rather large, display a notice to the user, indicating that the calculation might take a while
            if (this.refa != null) {
                final Activity activity = this.refa.get();
                if (activity != null) {
                    long l = this.file.length();
                    if (l > 100_000_000L) {
                        assert this.handler != null;
                        if (activity instanceof CoordinatorLayoutHolder) {
                            this.handler.post(() -> Snackbar.make(((CoordinatorLayoutHolder) activity).getCoordinatorLayout(), R.string.msg_calculating_checksum, l > 500_000_000L ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT).show());
                        } else {
                            this.handler.post(() -> Toast.makeText(activity, R.string.msg_calculating_checksum, l > 500_000_000L ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
                        }
                    }
                }
            }
            //
            this.checkSum = checkSum(this.file, this.algorithm);
            // quick return during test
            if (this.refa == null) return;
            //
            final Activity activity = this.refa.get();
            if (activity == null) return;
            assert this.handler != null;
            this.handler.post(() -> {
                if (this.checkSum != null) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.action_checksum) + ' ' + ChecksumCalculator.this.algorithm)
                            .setMessage(this.checkSum)
                            .setIcon(R.drawable.ic_baseline_check_circle_24)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                            .setNeutralButton(android.R.string.copy, (dialog, which) -> {
                                ClipboardManager cm = (ClipboardManager)activity.getSystemService(CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText(this.algorithm, this.checkSum));
                            })
                            ;
                    AlertDialog ad = builder.create();
                    Window dialogWindow = ad.getWindow();
                    if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
                    ad.show();
                } else {
                    String msg = activity.getString(R.string.error_checksum_failed);
                    if (this.exception != null) {
                        if (!TextUtils.isEmpty(this.exception.getMessage())) {
                            msg += " (" + this.exception.getMessage() + ")";
                        }
                    }
                    if (activity instanceof CoordinatorLayoutHolder) {
                        Snackbar.make(((CoordinatorLayoutHolder)activity).getCoordinatorLayout(), msg, Snackbar.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    }
                }
            });
        }


        @Retention(RetentionPolicy.SOURCE)
        @StringDef({ALGO_MD5, ALGO_SHA1, ALGO_SHA256, ALGO_SHA512})
        @interface ChecksumAlgorithm {}
    }

    /**
     * Gets notified when a file has been shared with another app.
     */
    public static class SharedResultReceiver extends BroadcastReceiver {

        public static final String EXTRA_FILE = BuildConfig.APPLICATION_ID + ".extra.FILE";
        private static Reference<SnackbarDisplayer> refSnackbarDisplayer = null;

        /**
         * This will be called when the Activity is paused (after onPause())!<br>
         * Therefore we won't display a Snackbar right here; instead we pass the Snackbar back to the UiActivity.
         */
        @TargetApi(Build.VERSION_CODES.LOLLIPOP_MR1)
        @Override
        public void onReceive(Context ctx, Intent intent) {
            ComponentName chosenComponent = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
            if (chosenComponent == null) return;
            String fileName = intent.getStringExtra(EXTRA_FILE);
            if (fileName == null) return;
            CharSequence label = chosenComponent.getPackageName();
            Drawable icon = null;
            try {
                PackageManager pm = ctx.getPackageManager();
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS : 0;
                ApplicationInfo ai = pm.getApplicationInfo(chosenComponent.getPackageName(), flags);
                label = pm.getApplicationLabel(ai);
                icon = pm.getApplicationIcon(ai);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(SharedResultReceiver.class.getSimpleName(), e.toString(), e);
            }
            String msg = ctx.getString(R.string.msg_shared_with, fileName, label);
            SpannableString ss = new SpannableString(msg);
            int plabel = TextUtils.indexOf(msg, label);
            if (plabel >= 0) {
                ss.setSpan(new ForegroundColorSpan(ctx.getResources().getColor(R.color.colorSecondary)), plabel, plabel + label.length(), 0);
            }
            if (refSnackbarDisplayer != null) {
                SnackbarDisplayer sd = refSnackbarDisplayer.get();
                if (sd == null) {
                    return;
                }
                Snackbar sb = Snackbar.make(sd.getCoordinatorLayout(), ss, 6_000);
                // /${HOME}/.gradle/caches/transforms-2/files-2.1/<somethingorother>/material-1.3.0/res/values
                View textView = sb.getView().findViewById(com.google.android.material.R.id.snackbar_text);
                if (textView instanceof TextView) {
                    ((TextView)textView).setMaxLines(8);
                    textView.setElevation(textView.getElevation() + 20f);
                    if (icon != null) ((TextView) textView).setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
                }
                sd.setSnackbar(sb);
            }
        }
    }

    /**
     * Clears the toolbar's subtitle.
     */
    private class ToolbarSubtitleResetter implements Runnable {
        @Override
        public void run() {
            UiActivity.this.toolbar.setSubtitle(null);
        }
    }

    /**
     * Connection to the {@link LoaderService}.
     */
    private class LoaderServiceConnection implements ServiceConnection {

        private LoaderService loaderService;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            this.loaderService = ((LoaderService.LoaderServiceBinder)service).getLoaderService();
            if (this.loaderService != null) this.loaderService.setDoneListener(UiActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            this.loaderService = null;
        }
    }

    /**
     * The adapter for the RecyclerView.
     */
    public class DownloadsAdapter extends RecyclerView.Adapter<UiActivity.ViewHolder> implements ThumbsManager.OnThumbLoadedListener {

        private final App app = (App)getApplicationContext();
        private final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(UiActivity.this);
        /** the selected files */
        private final Set<File> selection = new HashSet<>();
        /** stores pre-inflated Views for grid mode */
        private final Stack<View> gridViewStack = new Stack<>();
        /** stores pre-inflated Views for list mode */
        private final Stack<View> listViewStack = new Stack<>();
        /** background for selected files */
        private final ColorDrawable selectionBackground;
        /** thumbnail placeholder for recycled views */
        private final Drawable blank;
        /** Pre-inflates Views on a separate thread */
        private ViewStacker viewStacker;

        /**
         * Constructor.
         */
        @SuppressLint("UseCompatLoadingForDrawables")
        private DownloadsAdapter() {
            super();
            setHasStableIds(true);
            this.selectionBackground = new ColorDrawable(getResources().getColor(R.color.colorSecondarySemiTrans));
            this.blank = getResources().getDrawable(R.drawable.blank, getTheme());
        }

        @IntRange(from = -1)
        public int getIndex(@Nullable final File file) {
            if (file == null) return -1;
            int index = -1;
            if (isFiltered()) {
                final int n = UiActivity.this.filteredList.size();
                for (int i = 0; i < n; i++) {
                    if (file.equals(UiActivity.this.filteredList.get(i))) {index = i; break;}
                }
            } else {
                synchronized (UiActivity.super.downloads) {
                    final int n = UiActivity.super.downloads.size();
                    for (int i = 0; i < n; i++) {
                        if (file.equals(UiActivity.super.downloads.get(i))) {
                            index = i;
                            break;
                        }
                    }
                }
            }
            return index;
        }

        /** {@inheritDoc} */
        @Override
        public int getItemCount() {
            int n;
            synchronized (UiActivity.super.downloads) {
                n = isFiltered() ? UiActivity.this.filteredList.size() : UiActivity.super.downloads.size();
            }
            return n;
        }

        /** {@inheritDoc} */
        @Override
        public long getItemId(int position) {
            long id;
            synchronized (UiActivity.super.downloads) {
                id = isFiltered() ? UiActivity.this.filteredList.get(position).getName().hashCode() : UiActivity.super.downloads.get(position).getName().hashCode();
            }
            return id;
        }

        /** {@inheritDoc} */
        @Override
        @ViewType
        public int getItemViewType(int position) {
            return this.prefs.getBoolean(App.PREF_UI_GRID_LAYOUT, getResources().getBoolean(R.bool.grid_by_default)) ? VIEW_TYPE_GRID : VIEW_TYPE_LINEAR;
        }

        /**
         * Determines whether there is a selection.
         * @return true if at least one file has been selected
         */
        boolean hasSelection() {
            return !this.selection.isEmpty();
        }

        /**
         * Determines whether at least one selected file is protected.
         * @return true / false
         */
        boolean hasSelectionProtected() {
            for (File f : this.selection) {
                if (!f.canWrite()) return true;
            }
            return false;
        }

        boolean isFiltered() {
            return !UiActivity.this.filteredList.isEmpty();
        }

        /** {@inheritDoc} */
        @SuppressLint("SetTextI18n")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final File file;
            if (isFiltered()) {
                file = UiActivity.this.filteredList.get(position);
            } else {
                synchronized (UiActivity.super.downloads) {
                    file = UiActivity.super.downloads.get(position);
                }
            }

            final boolean inSelection = this.selection.contains(file);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                holder.itemView.setForeground(inSelection ? this.selectionBackground : null);
            } else {
                holder.itemView.setBackground(inSelection ? this.selectionBackground : null);
            }

            final String tagUcase = Util.getExtensionUcase(file);
            final String mime = Util.getMime(file);
            final String name = Util.removeExtension(file.getName());

            // display a thumbnail picture or a logo
            if (inSelection) {
                holder.imageSetter.setImageResource(R.drawable.ic_baseline_check_circle_24);
            } else {
                Bitmap thumb = this.app.getThumbsManager().getThumbnail(file);
                if (thumb != null) {
                    holder.imageSetter.setImage(thumb);
                } else {
                    if (mime.startsWith("video/")) {
                        holder.imageSetter.setImageResource(R.drawable.ic_baseline_movie_24);
                    } else if (mime.startsWith("image/")) {
                        holder.imageSetter.setImageResource(R.drawable.ic_baseline_image_24);
                    } else if (mime.startsWith("audio/") || "application/x-shorten".equals(mime)) {
                        holder.imageSetter.setImageResource(R.drawable.ic_baseline_audiotrack_24);
                    } else if ("text/calendar".equals(mime)) {
                        holder.imageSetter.setImageResource(R.drawable.ic_baseline_calendar_today_24);
                    } else if ("text/x-vcard".equals(mime)) {
                        holder.imageSetter.setImageResource(R.drawable.ic_baseline_person_24);
                    } else if ("application/epub+zip".equals(mime) || "application/vnd.amazon.mobi8-ebook".equals(mime)) {
                        holder.imageSetter.setImageResource(R.drawable.ic_baseline_menu_book_24);
                    } else {
                        holder.imageSetter.setImageResource(R.drawable.ic_file_24);
                    }
                }
            }
            holder.logoView.post(holder.imageSetter);

            // display the file name
            holder.textViewTitle.setText(name);
            holder.textViewTitle.setSelected(true);

            // display the last modification timestamp
            holder.textViewDate.setText(UiUtil.formatDate(file.lastModified(), DateFormat.SHORT));

            // display the file size
            holder.textViewSize.setText(UiUtil.formatBytes(file.length()));

            // display the file type - in grid view that will be just the extension
            if (holder.layout == R.layout.download_view_grid) {
                holder.textViewType.setText(tagUcase);
            } else {
                holder.textViewType.setText(getFileTypeLabel(UiActivity.this, mime, tagUcase));
            }
        }

        /** {@inheritDoc} */
        @NonNull
        @Override
        public UiActivity.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, @ViewType int viewType) {
            @LayoutRes final int layout = viewType == VIEW_TYPE_LINEAR ? R.layout.download_view : R.layout.download_view_grid;
            final View v;
            final Stack<View> stack = viewType == VIEW_TYPE_LINEAR ? this.listViewStack : this.gridViewStack;
            int stackSize = stack.size();
            if (stackSize > 0) {
                v = stack.pop();
                stackSize--;
            } else {
                v = getLayoutInflater().inflate(layout, parent, false);
            }
            if (stackSize < 4 && (this.viewStacker == null || !this.viewStacker.isAlive())) {
                this.viewStacker = new ViewStacker(layout, stack, parent);
                this.viewStacker.start();
            }
            return new UiActivity.ViewHolder(v, layout);
        }

        /** {@inheritDoc} */
        @Override
        public void onViewRecycled(@NonNull final ViewHolder holder) {
            holder.logoView.removeCallbacks(holder.imageSetter);
            holder.logoView.setImageDrawable(this.blank);
        }

        /** {@inheritDoc} */
        @UiThread
        @Override
        public void thumbnailsLoaded() {
            //if (BuildConfig.DEBUG) Log.i(TAG, "Received information that thumbnails have been loaded.");
            notifyDataSetChanged();
        }

        /**
         * Inflates a couple of Views and stores them in a stack.
         */
        private class ViewStacker extends Thread {

            @LayoutRes private final int layout;
            private Stack<View> target;
            private ViewGroup parent;

            /**
             * Constructor.
             * @param layout layout resource to inflate
             * @param target target Stack to add the View to
             * @param parent parent to pass to the inflater
             */
            private ViewStacker(@LayoutRes int layout, @NonNull Stack<View> target, @NonNull ViewGroup parent) {
                super();
                this.layout = layout;
                this.target = target;
                this.parent = parent;
                setPriority(Thread.NORM_PRIORITY - 1);
            }

            @Override
            public void run() {
                final LayoutInflater inflater = getLayoutInflater();
                try {
                    for (int i = 0; i < 16; i++) {
                        View v = inflater.inflate(this.layout, this.parent, false);
                        this.target.push(v);
                    }
                } catch (InflateException e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                }
                this.target = null;
                this.parent = null;
            }
        }
    }
}

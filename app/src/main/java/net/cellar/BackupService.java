/*
 * BackupService.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import net.cellar.supp.DebugUtil;
import net.cellar.supp.IdSupply;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Wraps the downloaded files into a zip file and shares it.
 * Will not run if a download is currently in progress.
 * Will ignore empty files.
 * Can encrypt the zip file.
 */
public class BackupService extends Service {

    static final String ACTION_UNZIP = BuildConfig.APPLICATION_ID + ".action_unzip";
    static final String ACTION_ZIP = BuildConfig.APPLICATION_ID + ".action_zip";
    static final String ACTION_ZIP_CANCEL = BuildConfig.APPLICATION_ID + ".action_zipcancel";
    /** boolean */
    static final String EXTRA_AES = BuildConfig.APPLICATION_ID + ".extra_aes";
    /** String: absolute path of the destination file */
    static final String EXTRA_DEST = BuildConfig.APPLICATION_ID + ".extra_dest";
    /** char[] */
    static final String EXTRA_PWD = BuildConfig.APPLICATION_ID + ".extra_pwd";
    /** the source file Uri */
    static final String EXTRA_SOURCE = BuildConfig.APPLICATION_ID + ".extra_source";
    static final String FILE_EXTENSION = ".zip";
    private static final boolean SKIP_EXISTING_WHEN_UNZIPPING = true;
    private static final String TAG = "BackupService";

    /**
     * Deletes all files in the backups folder.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    static void cleanup(@NonNull Context ctx) {
        File dir = Util.getFilePath(ctx, App.FilePath.BACKUPS, false);
        if (!dir.isDirectory()) return;
        final File[] files = dir.listFiles();
        if (files == null) return;
        Util.deleteFile(files);
    }

    /**
     * Copies the data pointed to by {@code source} to a temporary file.
     * @param ctx Context
     * @param source source Uri
     * @param suffix file suffix
     * @param listener FileCopyListener to receive the tmp file
     * @return the Thread that this is currently happening in
     * @throws NullPointerException if {@code ctx} or {@code listener} are {@code null}
     */
    @NonNull
    static Thread copyToTmpFile(@NonNull Context ctx, @NonNull final Uri source, @Nullable final String suffix, @NonNull final FileCopyListener listener) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final File dir = ctx.getCacheDir();
        final ContentResolver contentResolver = ctx.getContentResolver();
        Thread copier = new Thread() {
            @Override
            public void run() {
                File tmp = null;
                InputStream in = null;
                boolean success = true;
                try {
                    tmp = new File(dir, "tmp_" + System.currentTimeMillis() + (suffix != null ? suffix : ".tmp"));
                    in = contentResolver.openInputStream(source);
                    if (in == null) throw new FileNotFoundException(source.toString());
                    Util.copy(in, new FileOutputStream(tmp), 32768);
                } catch (Throwable e) {
                    success = false;
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
                } finally {
                    Util.close(in);
                }
                if (!success) {
                    Util.deleteFile(tmp);
                    tmp = null;
                }
                final File result = tmp;
                handler.post(() -> listener.copied(result));
            }
        };
        copier.start();
        return copier;
    }

    private final BackupServiceBinder binder = new BackupServiceBinder(this);
    private final Handler handler = new Handler();
    private Notification.Builder builder;
    private Notification notification;
    private Thread exporter, importer;
    private volatile boolean cancelRequested;

    /**
     * Lets this Service run in the foreground while unzipping.
     */
    private void foregroundUnzip() {
        this.builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_baseline_info_24)
                .setContentTitle(getString(R.string.action_import))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, 0, true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
        ;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.builder.setChannelId(((App)getApplicationContext()).getNcImportant().getId());
        }
        this.notification = this.builder.build();
        startForeground(IdSupply.NOTIFICATION_ID_BACKUP, this.notification);
    }

    /**
     * Lets this Service run in the foreground while zipping.
     */
    private void foregroundZip() {
        this.builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_n_download)
                .setContentTitle(getString(R.string.action_export_zip))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, 0, true)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
        ;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.builder.setChannelId(((App)getApplicationContext()).getNcImportant().getId());
        }
        Intent intentCancel = new Intent(this, getClass());
        intentCancel.setAction(ACTION_ZIP_CANCEL);
        PendingIntent piCancel = PendingIntent.getService(this, 1, intentCancel, PendingIntent.FLAG_UPDATE_CURRENT);
        this.builder.addAction(UiUtil.makeNotificationAction(this, android.R.drawable.ic_menu_close_clear_cancel, android.R.string.cancel, piCancel));
        this.notification = this.builder.build();
        startForeground(IdSupply.NOTIFICATION_ID_BACKUP, this.notification);
    }

    private void noMoreForegroundUnzip() {
        stopForeground(false);
        this.notification = null;
    }

    private void noMoreForegroundZip() {
        stopForeground(true);
        this.notification = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (BuildConfig.DEBUG) Log.i(TAG, "onStartCommand(" + intent + ", " + flags + ", " + startId + ")");
        if (DebugUtil.TEST) DebugUtil.logIntent(TAG, intent);
        final String action = intent.getAction();
        if (ACTION_ZIP.equals(action)) {
            String dest = intent.getStringExtra(EXTRA_DEST);
            char[] pwd = intent.getCharArrayExtra(EXTRA_PWD);
            boolean aes = intent.getBooleanExtra(EXTRA_AES, false);
            if (dest != null && dest.length() > 0) {
                foregroundZip();
                zip(dest, pwd, aes);
            }
            return START_NOT_STICKY;
        } else if (ACTION_ZIP_CANCEL.equals(action)) {
            this.cancelRequested = true;
        } else if (ACTION_UNZIP.equals(action)) {
            Uri source = intent.getParcelableExtra(EXTRA_SOURCE);
            if (source != null && (this.importer == null || !this.importer.isAlive())) {
                foregroundUnzip();
                this.importer = new Thread() {
                    @Override
                    public void run() {
                        copyToTmpFile(BackupService.this, source, ".zip", dest -> {
                            if (dest == null) {
                                stopSelf(startId);
                                return;
                            }
                            unzip(dest, intent.getCharArrayExtra(EXTRA_PWD));
                        });
                    }
                };
                this.importer.setPriority(Thread.NORM_PRIORITY - 1);
                this.importer.start();
            } else {
                if (BuildConfig.DEBUG) Log.e(TAG, "Received no source to unzip!");
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        } else if (BuildConfig.DEBUG) {
            Log.e(TAG, "Unknown action " + action);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @WorkerThread
    private void unzip(@NonNull File file, @Nullable char[] pwd) {
        if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_BACKUP_RESTORE_STARTED));
        final NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        try {
            File dir = App.getDownloadsDir(this);
            if (!dir.isDirectory()) {
                if (!dir.mkdirs()) {
                    this.builder
                            .setContentText(getString(R.string.error_import_failed, "Cannot create directory!"))
                            .setCategory(Notification.CATEGORY_ERROR)
                            .setSmallIcon(android.R.drawable.ic_delete)
                            .setProgress(100, 100, false)
                            .setAutoCancel(true)
                            .setOngoing(false);
                    nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.builder.build());
                    Util.deleteFile(file);
                    if (pwd != null) Arrays.fill(pwd, (char)0);
                    noMoreForegroundUnzip();
                    return;
                }
            }

            long availableSpace = dir.getFreeSpace();
            if (availableSpace < (file.length() << 1)) {
                String msg = getString(R.string.error_import_failed, getString(R.string.msg_downloaded_file_1012));
                this.builder
                        .setContentText(msg)
                        .setCategory(Notification.CATEGORY_ERROR)
                        .setSmallIcon(android.R.drawable.ic_delete)
                        .setColor(getResources().getColor(R.color.design_default_color_error))
                        .setProgress(100, 100, false)
                        .setAutoCancel(true)
                        .setOngoing(false)
                ;
                if (msg.length() > 35) {
                    this.builder.setStyle(new Notification.BigTextStyle().bigText(msg));
                }
                nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.builder.build());
                Util.deleteFile(file);
                if (pwd != null) Arrays.fill(pwd, (char)0);
                noMoreForegroundUnzip();
                return;
            }

            final String destinationPath = dir.getAbsolutePath();
            final ZipFile zip = pwd != null ? new ZipFile(file, pwd) : new ZipFile(file);
            if (!zip.isValidZipFile()) {
                String msg = getString(R.string.error_import_failed, "Not a valid zip file!");
                this.builder
                        .setContentText(msg)
                        .setCategory(Notification.CATEGORY_ERROR)
                        .setSmallIcon(android.R.drawable.ic_delete)
                        .setColor(getResources().getColor(R.color.design_default_color_error))
                        .setProgress(100, 100, false)
                        .setAutoCancel(true)
                        .setOngoing(false)
                ;
                if (msg.length() > 35) {
                    this.builder.setStyle(new Notification.BigTextStyle().bigText(msg));
                }
                nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.builder.build());
                Util.deleteFile(file);
                noMoreForegroundUnzip();
                return;
            }
            if (zip.isEncrypted() && (pwd == null || pwd.length == 0)) {
                String msg = getString(R.string.error_import_failed_pwd);
                this.builder
                        .setContentText(msg)
                        .setCategory(Notification.CATEGORY_ERROR)
                        .setSmallIcon(android.R.drawable.ic_delete)
                        .setColor(getResources().getColor(R.color.design_default_color_error))
                        .setProgress(100, 100, false)
                        .setAutoCancel(true)
                        .setOngoing(false)
                ;
                if (msg.length() > 35) {
                    this.builder.setStyle(new Notification.BigTextStyle().bigText(msg));
                }
                nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.builder.build());
                Util.deleteFile(file);
                noMoreForegroundUnzip();
                return;
            }
            final List<FileHeader> fileHeaderList = zip.getFileHeaders();
            final int n = fileHeaderList.size();
            int count = 0, extracted = 0, skipped = 0;
            for (FileHeader fileHeader : fileHeaderList) {
                final String originalFileName = fileHeader.getFileName();
                // currently we will not handle directories
                if (fileHeader.isDirectory()) {
                    count++;
                    skipped++;
                    continue;
                }
                // this is just a sanity check
                if (fileHeader.getCompressedSize() > Integer.MAX_VALUE) {
                    count++;
                    skipped++;
                    continue;
                }
                //
                File dest =  new File(destinationPath, originalFileName);
                if (dest.isFile() && SKIP_EXISTING_WHEN_UNZIPPING) {
                    count++;
                    skipped++;
                    continue;
                }
                String alt = Util.suggestAlternativeFilename(dest);
                String fileName = alt != null ? alt : originalFileName;
                fileName = fileName.replace(File.separatorChar, '_');
                zip.extractFile(fileHeader, destinationPath, fileName);
                if (BuildConfig.DEBUG) net.cellar.supp.Log.i(TAG, "Extracted \"" + fileName + "\"");
                count++; extracted++;
                this.builder.setContentText(fileName).setProgress(100, Math.round(100f * ((float)count / (float)n)), false);
                nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.builder.build());
            }

            getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);

            Intent ui = new Intent(this, UiActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent piUiActivity = PendingIntent.getActivity(this, 1, ui, PendingIntent.FLAG_UPDATE_CURRENT);
            String msg = getResources().getQuantityString(R.plurals.msg_import_successful, extracted, extracted);
            if (skipped > 0) msg = msg + '\n' + getString(R.string.msg_import_skipped_some);
            this.builder
                    .setContentText(msg)
                    .setProgress(100, 100, false)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setUsesChronometer(false)
                    .setContentIntent(piUiActivity)
            ;
            if (msg.length() > 35) {
                this.builder.setStyle(new Notification.BigTextStyle().bigText(msg));
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) net.cellar.supp.Log.e(TAG, e.toString());
            String msg;
            if (e instanceof ZipException && ((ZipException)e).getType() == ZipException.Type.WRONG_PASSWORD) {
                msg = getString(R.string.error_import_failed_pwd);
            } else {
                msg = e.getMessage();
                if (TextUtils.isEmpty(msg)) msg = e.toString();
                msg = getString(R.string.error_import_failed, msg);
            }
            this.builder
                    .setContentText(msg)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setSmallIcon(android.R.drawable.ic_delete)
                    .setProgress(100, 100, false)
                    .setColor(getResources().getColor(R.color.design_default_color_error))
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setUsesChronometer(false)
            ;
            if (msg.length() > 35) {
                this.builder.setStyle(new Notification.BigTextStyle().bigText(msg));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.builder.setColorized(true);
            }
        } finally {
            Util.deleteFile(file);
        }
        if (pwd != null) Arrays.fill(pwd, (char)0);
        nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.builder.build());
        noMoreForegroundUnzip();
    }

    private void zip(@NonNull String destination, @Nullable final char[] pwd, boolean aes) {
        if (this.exporter != null && this.exporter.isAlive()) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Another backup is still in progress!");
            this.builder.setContentText(getString(R.string.msg_backup_notpossible_active)).setColor(0xffff0000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.builder.setColorized(true);
            }
            this.notification = this.builder.build();
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.notification);
            noMoreForegroundZip();
            return;
        }
        this.cancelRequested = false;
        File dir = App.getDownloadsDir(this);
        final File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null || files.length == 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "No files to backup!");
            this.builder.setContentText(getString(R.string.label_root_summary_empty));
            this.notification = this.builder.build();
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, this.notification);
            noMoreForegroundZip();
            return;
        }
        File destinationFile = new File(destination);
        if (destinationFile.isFile()) {
            String alt = Util.suggestAlternativeFilename(destinationFile);
            if (alt != null) destinationFile = new File(destinationFile.getParent(), alt);
        }
        final ZipFile zipFile;
        final ZipParameters zipParameters = new ZipParameters();
        zipParameters.setCompressionLevel(CompressionLevel.MAXIMUM);
        if (pwd != null && pwd.length > 0) {
            zipParameters.setEncryptFiles(true);
            if (aes) {
                zipParameters.setEncryptionMethod(EncryptionMethod.AES);
                zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            } else {
                zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
            }
            zipFile = new ZipFile(destinationFile, pwd);
        } else {
            zipFile = new ZipFile(destinationFile);
        }
        this.exporter = new Thread() {
            @SuppressWarnings("ResultOfMethodCallIgnored")
            @Override
            public void run() {
                long processed = 0L;
                final NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                final App app = (App)getApplicationContext();
                int counter = 0;
                long totalBytes = 0L;
                if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_BACKUP_RESTORE_STARTED));
                for (File file : files) {
                    totalBytes += file.length();
                }
                for (File file : files) {
                    if (BackupService.this.cancelRequested) break;
                    if (file == null || !file.isFile() || file.length() == 0L || app.isBeingDownloaded(file)) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "Skipping " + file);
                        continue;
                    }
                    try {
                        zipFile.addFile(file, zipParameters);
                    } catch (ZipException e) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "While adding " + file + ": " + e.toString(), e);
                    }
                    processed += file.length();
                    counter++;
                    BackupService.this.builder.setProgress(100, (int)Math.round(100. * ((double)processed / totalBytes)), false);
                    BackupService.this.builder.setContentText(counter + "/" + files.length);
                    BackupService.this.notification = BackupService.this.builder.build();
                    nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, BackupService.this.notification);
                }
                if (pwd != null) Arrays.fill(pwd, (char)0);
                if (BackupService.this.cancelRequested) {
                    zipFile.getFile().delete();
                    noMoreForegroundZip();
                    return;
                }
                try {
                    zipFile.setComment(getString(R.string.msg_export_zip_comment, DateFormat.getDateTimeInstance().format(new Date())));
                } catch (ZipException e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
                }
                BackupService.this.builder.setProgress(100, 100, false);
                BackupService.this.builder.setContentText(getString(R.string.msg_backup_finished));
                BackupService.this.notification = BackupService.this.builder.build();
                nm.notify(IdSupply.NOTIFICATION_ID_BACKUP, BackupService.this.notification);
                BackupService.this.handler.post(() -> Util.send(BackupService.this, zipFile.getFile(), BuildConfig.FILEPROVIDER_AUTH, "application/zip"));
                noMoreForegroundZip();
            }
        };
        this.exporter.setPriority(Thread.NORM_PRIORITY - 1);
        this.exporter.start();
    }

    interface FileCopyListener {
        void copied(@Nullable File dest);
    }

    /**
     *
     */
    static final class BackupServiceBinder extends Binder {

        @NonNull
        private final Reference<BackupService> refService;

        /**
         * Constructor.
         * @param service BackupService
         */
        private BackupServiceBinder(@NonNull BackupService service) {
            super();
            this.refService = new WeakReference<>(service);
        }

        /**
         * @return BackupService
         */
        @Nullable
        BackupService getBackupService() {
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

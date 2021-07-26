/*
 * ImportArchiveActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.ActivityOptions;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;

import net.cellar.supp.DebugUtil;
import net.cellar.supp.Log;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;
import net.lingala.zip4j.ZipFile;

import java.util.Arrays;

/**
 * Lets the user select a source zip file and subsequently calls the BackupService with the {@link BackupService#ACTION_UNZIP} action.
 * If the zip file needs a passphrase, a dialog is shown that enables the user to enter that.
 */
public class ImportArchiveActivity extends AppCompatActivity {

    static final String ACTION_PICK_ARCHIVE = BuildConfig.APPLICATION_ID + ".pickarchive";
    /** ask user for confirmation if number of files in a zip file is larger than this */
    private static final int CONFIRM_REQUIRED_BEYOND = 47;
    private static final int REQUEST_CODE_PICK_ARCHIVE = 5678;
    private static final String TAG = "ImportArchiveActivity";

    /**
     * Returns the number of entries in a zip file.
     * @param zipFile zip file to check
     * @return number of files
     */
    @IntRange(from = 0)
    private static int getNumberOfFiles(ZipFile zipFile) {
        try {
            return zipFile.getFileHeaders().size();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private final Handler handler = new Handler();
    private AlertDialog dialogPwd = null, dialogConfirm = null;
    private Thread zipFileCopier = null;
    private DelayedBackReactor delayedBackReactor;

    /** {@inheritDoc} */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent intent) {
        if (BuildConfig.DEBUG) Log.i(TAG, "onActivityResult(" + requestCode + ", " + resultCode + ", " + intent + ")");
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_CODE_PICK_ARCHIVE) {
            if (resultCode == RESULT_CANCELED) {
                finishAndRemoveTask();
                return;
            }
            if (resultCode == RESULT_OK && intent != null) {
                Uri data = intent.getData();
                if (data != null) {
                    this.zipFileCopier = BackupService.copyToTmpFile(this, data, ".zip", dest -> {
                        if (dest == null) {
                            finishAndRemoveTask();
                            return;
                        }
                        ZipFile zipFile = new ZipFile(dest);
                        if (!zipFile.isValidZipFile()) {
                            Util.deleteFile(dest);
                            Toast.makeText(getApplicationContext(), R.string.error_import_failed_invalid_file, Toast.LENGTH_LONG).show();
                            finishAndRemoveTask();
                            return;
                        }
                        int numberOfFiles = getNumberOfFiles(zipFile);
                        if (numberOfFiles == 0) {
                            Util.deleteFile(dest);
                            Toast.makeText(getApplicationContext(), R.string.error_import_failed_empty_file, Toast.LENGTH_LONG).show();
                            finishAndRemoveTask();
                            return;
                        }
                        TextView textViewInspecting = findViewById(R.id.textViewInspecting);
                        if (textViewInspecting != null) textViewInspecting.setText("✅");
                        try {
                            if (zipFile.isEncrypted()) {
                                Util.deleteFile(dest);
                                final View v = getLayoutInflater().inflate(R.layout.import_zip, null);
                                final TextView textViewCounter = v.findViewById(R.id.textViewCounter);
                                final EditText editTextPwd = v.findViewById(R.id.editTextPassword);
                                if (numberOfFiles > 1) textViewCounter.setText(getString(R.string.msg_import_w_count, numberOfFiles));
                                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                                        .setTitle(getString(R.string.action_import))
                                        .setView(v)
                                        .setOnCancelListener(dialog -> finishAndRemoveTask())
                                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                                            dialog.cancel();
                                            finishAndRemoveTask();
                                        })
                                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                            dialog.dismiss();
                                            Editable e = editTextPwd.getText();
                                            char[] pwd = null;
                                            if (e.length() > 0) {
                                                pwd = new char[e.length()];
                                                e.getChars(0, e.length(), pwd, 0);
                                            }
                                            startService(data, pwd);
                                        })
                                        ;
                                if (numberOfFiles > CONFIRM_REQUIRED_BEYOND) builder.setIcon(R.drawable.ic_baseline_warning_amber_24);
                                this.dialogPwd = builder.show();
                                return;
                            } else if (numberOfFiles > CONFIRM_REQUIRED_BEYOND) {
                                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                                        .setTitle(R.string.msg_confirmation)
                                        .setIcon(R.drawable.ic_baseline_warning_amber_24)
                                        .setMessage(getString(R.string.msg_import_w_count, numberOfFiles))
                                        .setOnCancelListener(dialog -> finishAndRemoveTask())
                                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                                            dialog.cancel();
                                            finishAndRemoveTask();
                                        })
                                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                            dialog.dismiss();
                                            startService(data, null);
                                        })
                                        ;
                                this.dialogConfirm = builder.show();
                                return;
                            }
                        } catch (Exception e) {
                            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                            finishAndRemoveTask();
                            return;
                        } finally {
                            Util.deleteFile(dest);
                        }
                        startService(data, null);
                    });
                } else if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Received no data!");
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onBackPressed() {
        if (BuildConfig.DEBUG) Log.i(TAG, "onBackPressed()");
        if (this.zipFileCopier != null && this.zipFileCopier.isAlive()) {
            if (this.delayedBackReactor == null) {
                this.delayedBackReactor = new DelayedBackReactor();
                this.handler.postDelayed(this.delayedBackReactor, 10_000L);
                Snackbar snackbar = Snackbar.make(findViewById(R.id.coordinator_layout), R.string.msg_just_a_moment, Snackbar.LENGTH_INDEFINITE);
                snackbar.setAction(android.R.string.cancel, v -> finish());
                snackbar.show();
                return;
            }
            //  the user has pressed <back> again - he/she seems to mean it…
            this.handler.removeCallbacks(this.delayedBackReactor);
        }
        super.onBackPressed();
    }

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        BaseActivity.setDarkMode(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        if (BuildConfig.DEBUG) Log.i(TAG, "onPause()");
        if (this.zipFileCopier != null && this.zipFileCopier.isAlive()) {
            this.zipFileCopier.interrupt();
        }
        UiUtil.dismissDialog(this.dialogPwd, this.dialogConfirm);
        super.onPause();
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        if (BuildConfig.DEBUG) Log.i(TAG, "onResume() - dialogPwd showing: " + (this.dialogPwd != null && this.dialogPwd.isShowing()) + " - zipFileCopier running: " + (this.zipFileCopier != null && this.zipFileCopier.isAlive()));
        super.onResume();
        if (this.dialogPwd != null && this.dialogPwd.isShowing()) {
            return;
        }
        if (this.zipFileCopier != null && this.zipFileCopier.isAlive()) {
            return;
        }
        Intent intent = getIntent();
        if (BuildConfig.DEBUG) DebugUtil.logIntent(TAG, intent);
        if (ACTION_PICK_ARCHIVE.equals(intent.getAction())) {
            final Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("application/zip");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                ActivityOptions opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out);
                //noinspection deprecation
                startActivityForResult(pick, REQUEST_CODE_PICK_ARCHIVE, opts.toBundle());
            } else {
                //noinspection deprecation
                startActivityForResult(pick, REQUEST_CODE_PICK_ARCHIVE);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
            return;
        }
        finishAndRemoveTask();
    }

    /**
     * Starts the {@link BackupService} and instructs it to unzip the given zip file.
     * @param data Uri of zip file
     * @param pwd passphrase
     */
    private void startService(@NonNull Uri data, @Nullable char[] pwd) {
        if (BuildConfig.DEBUG) Log.i(TAG, "startService(" + data + ", " + Arrays.toString(pwd) + ")");
        final Intent intentService = new Intent(this, BackupService.class);
        intentService.setAction(BackupService.ACTION_UNZIP);
        intentService.putExtra(BackupService.EXTRA_SOURCE, data);
        if (pwd != null) intentService.putExtra(BackupService.EXTRA_PWD, pwd);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intentService);
        } else {
            startService(intentService);
        }
        setResult(RESULT_OK);
        finishAndRemoveTask();
    }

    /**
     * Runs after the user has pressed back and the source zip file has not yet been fully copied to the tmp file.
     */
    private class DelayedBackReactor implements Runnable {

        @Override
        public void run() {
            if (zipFileCopier != null && zipFileCopier.isAlive()) {
                Toast.makeText(getApplicationContext(), R.string.msg_cancelled, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}

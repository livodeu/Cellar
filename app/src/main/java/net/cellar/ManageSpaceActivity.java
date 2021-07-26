/*
 * ManageSpaceActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.view.Window;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import net.cellar.supp.Log;
import net.cellar.supp.UiUtil;

import java.io.File;

/**
 *
 */
public class ManageSpaceActivity extends BaseActivity {

    private static final String TAG = "ManageSpaceActivity";

    private AlertDialog dialogDeleteAll;

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        UiUtil.dismissDialog(this.dialogDeleteAll);
        this.dialogDeleteAll = null;
        super.onPause();
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        super.onResume();
        // the manifest should contain an entry called "manageSpaceActivity" which points to an Activity (hopefully this one) that will be called with the VIEW action
        deleteAll();
    }

    /**
     * Offers to delete all downloads.
     */
    private void deleteAll() {
        final int n = refresh();
        if (n == 0) {
            Toast.makeText(getApplicationContext(), getString(R.string.msg_no_downloads_detailed, getString(R.string.app_name)), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final App app = (App)getApplicationContext();
        int undeleteable = 0;
        synchronized (super.downloads) {
            for (File d : super.downloads) {
                if (!d.canWrite() || app.isBeingDownloaded(d)) undeleteable++;
            }
        }
        final int deletable = n - undeleteable;
        if (deletable <= 0) {
            Toast.makeText(getApplicationContext(), R.string.msg_delete_all_protected, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.msg_confirmation)
                .setIcon(R.drawable.ic_baseline_warning_amber_24)
                .setMessage(getResources().getQuantityString(deletable < n ? R.plurals.msg_delete_all_some_protected : R.plurals.msg_delete_all, deletable, deletable, n))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    dialog.cancel();
                    finish();
                })
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    dialog.dismiss();
                    boolean atLeastOneDeleted = false;
                    synchronized (super.downloads) {
                        for (File d : super.downloads) {
                            if (!d.canWrite() || app.isBeingDownloaded(d)) {
                                if (BuildConfig.DEBUG) Log.w(TAG, "Did not delete " + d);
                                continue;
                            }
                            if (d.delete()) {
                                atLeastOneDeleted = true;
                            } else {
                                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to delete " + d);
                            }
                        }
                        if (atLeastOneDeleted) {
                            getContentResolver().notifyChange(Dogs.buildNotifyUri(), null, false);
                        }
                    }
                    finish();
                })
                ;
        this.dialogDeleteAll = builder.create();
        Window dialogWindow = this.dialogDeleteAll.getWindow();
        if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background_alert);
        this.dialogDeleteAll.show();
    }

}

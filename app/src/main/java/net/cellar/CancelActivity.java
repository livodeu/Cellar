/*
 * CancelActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.Activity;
import android.app.NotificationManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Cancels all downloads that are known to the app and terminates the virtual machine.
 */
public class CancelActivity extends Activity {

    /** {@inheritDoc} */
    @Override
    protected void onCreate(Bundle ignored) {
        super.onCreate(null);
        final App app = (App)getApplicationContext();
        int cancelled = app.cancelAllLoaders();
        Toast.makeText(app, app.getResources().getQuantityString(R.plurals.msg_cancelled_downloads, cancelled, cancelled), Toast.LENGTH_SHORT).show();
        ((NotificationManager)app.getSystemService(NOTIFICATION_SERVICE)).cancelAll();
        finishAndRemoveTask();
        // safeguard - in case any rogue Loader is still running - kill the app
        app.exit(2_000L);
    }
}
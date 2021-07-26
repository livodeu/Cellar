/*
 * BootReceiver.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Gets called {@link Intent#ACTION_BOOT_COMPLETED "after the user has finished booting"}.
 * Only needed for {@link ClipSpy}.
 */
public class BootReceiver extends BroadcastReceiver {

    /** {@inheritDoc} */
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context ignored, Intent ignoredAsWell) {
        // ClipSpy does not have to be launched here because App.onCreate() has already been called by now
    }
}

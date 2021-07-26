/*
 * SnackbarDisplayer.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;

/**
 * Implemented when a component shall receive Snackbars from other components
 * in order to display them at its own discretion (= when it's suitable within its own lifecycle).
 * So a paused Activity can receive a Snackbar and subsequently display it when the Activity resumes.
 */
public interface SnackbarDisplayer extends CoordinatorLayoutHolder {

    /**
     * Sets the Snackbar that the implementor may want to show.
     * @param snackbar Snackbar to show
     */
    void setSnackbar(@Nullable Snackbar snackbar);
}

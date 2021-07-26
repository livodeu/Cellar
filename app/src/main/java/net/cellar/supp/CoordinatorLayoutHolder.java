/*
 * CoordinatorLayoutHolder.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import com.google.android.material.snackbar.Snackbar;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

/**
 * Implemented by Activities that use a {@link CoordinatorLayout}.<br>
 * The CoordinatorLayout can be used to display {@link Snackbar Snackbars}.
 * From N on, this could possibly be a {@link java.util.function.Supplier}…
 * <br><br>
 * <a href="https://docs.oracle.com/javase/specs/jls/se8/html/jls-9.html#jls-9.8">https://docs.oracle.com/javase/specs/jls/se8/html/jls-9.html#jls-9.8</a>
 */
@FunctionalInterface
public interface CoordinatorLayoutHolder {

    /**
     * Returns the CoordinatorLayout.
     * @return CoordinatorLayout
     */
    @NonNull
    CoordinatorLayout getCoordinatorLayout();
}

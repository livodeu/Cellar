/*
 * Mores.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.net;

import android.net.Uri;

/**
 * Knows what's evil and what's not.
 */
public interface Mores {

    default boolean isEvil(String host) {
        return false;
    }

    default boolean isEvil(Uri uri) {
        return false;
    }
}

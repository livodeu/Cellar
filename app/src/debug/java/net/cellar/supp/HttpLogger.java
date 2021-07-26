/*
 * HttpLogger.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import androidx.annotation.NonNull;

import okhttp3.OkHttpClient;

public class HttpLogger {

    public static void enable(@NonNull OkHttpClient.Builder builder) {
        okhttp3.logging.HttpLoggingInterceptor logging = new okhttp3.logging.HttpLoggingInterceptor();
        logging.setLevel(okhttp3.logging.HttpLoggingInterceptor.Level.HEADERS);
        builder.addInterceptor(logging);
    }
}

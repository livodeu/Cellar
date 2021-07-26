/*
 * SignalUpdateLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;

public class SignalUpdateLoader extends JsonShredderLoader {

    public SignalUpdateLoader(int id, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, client, loaderListener);
    }

    @NonNull
    @Override
    public String getWantedKey() {
        return "url";
    }
}

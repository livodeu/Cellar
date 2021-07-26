/*
 * FrogPoxLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;

/**
 * <a href="https://help.dropbox.com/files-folders/share/force-download">https://help.dropbox.com/files-folders/share/force-download</a>
 */
public class FrogPoxLoader extends RedirectLoader {

    /**
     * Constructor.
     * @param id             download id
     * @param client         OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public FrogPoxLoader(int id, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, client, loaderListener);
    }

    @Override
    @NonNull
    public String redirect(@NonNull String original) {
        return original.replace("://www.", "://dl.").replace("?dl=0", "?dl=2");
    }
}

/*
 * ImgurLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;

public class ImgurLoader extends HtmlShredderImageLoader {

    /**
     * Constructor.
     * @param id             download id
     * @param ctx Context
     * @param client         OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public ImgurLoader(int id, Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }
    /*
    TODO there may be more than 1 image on a site - these are contained in JSON
     */

    @Override
    @NonNull
    protected UrlExtractor[] getExtractors() {
        return new UrlExtractor[] {new OgVideoExtractor(), new OgImageExtractor()};
    }

}

/*
 * VideoObjectContenturlLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.OkHttpClient;

/**
 *
 */
public class VideoObjectContenturlLoader extends HtmlShredderVideoLoader {

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public VideoObjectContenturlLoader(int id, Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @NonNull
    @Override
    protected UrlExtractor [] getExtractors() {
        return new UrlExtractor[] {new VideoObjectContenturlExtractor()};
    }

    private static class VideoObjectContenturlExtractor extends UrlExtractor {

        @Nullable
        @Override
        String extract(@NonNull String line) {
            int i = line.indexOf("VideoObject");
            if (i < 0) return null;
            i = line.indexOf("contentUrl\":\"", i + 11);
            if (i < 0) return null;
            int j = line.indexOf('"', i + 13);
            if (j < i) return null;
            return line.substring(i + 13, j);
        }
    }
}

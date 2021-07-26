/*
 * MetacafeLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;

public class MetacafeLoader extends HtmlShredderVideoLoader {

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public MetacafeLoader(int id, Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @NonNull
    @Override
    protected UrlExtractor[] getExtractors() {
        return new UrlExtractor[] {new MetacafeExtractor()};
    }

    /**
     * Parses html data returned from {@link net.cellar.supp.UriHandler#PATTERN_METACAFE}-like urls.
     */
    static class MetacafeExtractor extends UrlExtractor {

        private final static String KEY = "\"sources\":[{\"src\":\"";

        @Nullable
        @Override
        String extract(@NonNull final String line) {
            if (!line.contains("json_video_data")) return null;
            int start = line.indexOf(KEY);
            if (start < 0) return null;
            int end = line.indexOf('\"', start + KEY.length());
            if (end < 0) return null;
            return line.substring(start + KEY.length(), end).replaceAll("\\\\/", "/");
        }
    }

}

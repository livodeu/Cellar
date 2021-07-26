/*
 * NyLoader.java
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
public class NyLoader extends HtmlShredderVideoLoader {

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public NyLoader(int id, Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @Override
    @NonNull
    protected UrlExtractor [] getExtractors() {
        return new UrlExtractor[] {new NyUrlExtractor()};
    }

    private static class NyUrlExtractor extends UrlExtractor {

        @Nullable
        @Override
        String extract(@NonNull String line) {
            int i = line.indexOf("\"contentType\":\"video\\u002Fmp4\",\"src\":\"");
            if (i < 0) return null;
            int j = line.indexOf('"', i + 38);
            if (j < i) return null;
            return line.substring(i + 38, j).replace("\\u002F", "/");
        }
    }
}

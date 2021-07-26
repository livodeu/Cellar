/*
 * NzzLoader.java
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
public class NzzLoader extends HtmlShredderVideoLoader {

    /**
     * Constructor.
     * @param id download id
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public NzzLoader(int id, Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @Override
    @NonNull
    protected UrlExtractor  [] getExtractors() {
        return new UrlExtractor[] {new NzzUrlExtractor()};
    }

    private static class NzzUrlExtractor extends UrlExtractor {

        @Nullable
        @Override
        String extract(@NonNull String line) {
            int i = line.indexOf("contentUrl\":\"");
            if (i < 0) return null;
            int j = line.indexOf('"', i + 13);
            if (j < i) return null;
            return line.substring(i + 13, j);
        }
    }
}

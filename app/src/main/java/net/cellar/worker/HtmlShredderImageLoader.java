/*
 * HtmlShredderImageLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.OkHttpClient;

/**
 * Yes, I know, an *ImageLoader should probably not extend a *VideoLoader…
 */
public class HtmlShredderImageLoader extends HtmlShredderVideoLoader {

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public HtmlShredderImageLoader(int id, Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @NonNull
    @Override
    protected UrlExtractor [] getExtractors() {
        return new UrlExtractor[] {new OgImageExtractor()};
    }

    /**
     * For items like<br>
     * &lt;meta property="og:image" data-react-helmet="true" content="https://v.postvid.cc/NLj22zIY/Near-Breach-Glen-Canyon-Dam-Article-Page-1.bmp"&gt;
     * (Don't try that url, it's made up)
     */
    static class OgImageExtractor extends UrlExtractor {

        @Nullable
        @Override
        String extract(@NonNull String line) {
            int start = line.indexOf("<meta property=\"og:image\"");
            if (start < 0) return null;
            int end = line.indexOf('>', start + 25);
            if (end < 0) return null;
            start = line.indexOf("content=\"", start + 25);
            if (start < 0 || start > end) return null;
            end = line.indexOf('"', start + 9);
            if (end < 0) return null;
            String src = line.substring(start + 9, end);
            super.mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.substring(src.lastIndexOf('.') + 1));
            return src;
        }

    }

}

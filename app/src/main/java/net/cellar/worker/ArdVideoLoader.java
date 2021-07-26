/*
 * ArdVideoLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.supp.Util;
import net.cellar.supp.Log;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;

import okhttp3.OkHttpClient;

/**
 * Loads videos from www.ardmediathek.de.
 */
public class ArdVideoLoader extends HtmlShredderVideoLoader {

    private static final String TAG = "ArdVideoLoader";

    /**
     * Recursive parsing of the json data.
     * @param reader JsonReader to use
     * @param nameOfCurrentObject the name of the current json object
     * @param streams <em>receives</em> the extracted video streams with the stream quality as key
     * @param title <em>receives</em> the extracted video title
     * @param level parsing level (for debugging only)
     * @throws IOException if the JsonReader thinks it's appropriate
     */
    private static void parseObject(@NonNull final JsonReader reader, @Nullable final String nameOfCurrentObject, @NonNull final SparseArray<String> streams, final StringBuilder title, final int level) throws IOException {
        String name = null;
        reader.beginObject();
        boolean inArray = false;
        boolean inObject = true;

        int quality = -1;
        String streamUrl = null;

        while (reader.hasNext()) {
            final JsonToken token = reader.peek();
            //if (BuildConfig.DEBUG && token != JsonToken.NAME) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- token: " + token);
            if (token == JsonToken.END_DOCUMENT) break;
            if (token == JsonToken.END_OBJECT) {
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "}");
                reader.endObject();
                inObject = false;
                break;
            }
            if (token == JsonToken.BEGIN_ARRAY) {
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- ARRAY " + name + " [");
                reader.beginArray();
                inArray = true;
                continue;
            }
            if (token == JsonToken.END_ARRAY) {
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "]");
                reader.endArray();
                inArray = false;
                continue;
            }
            if (token == JsonToken.NAME) {
                name = reader.nextName();
                continue;
            }
            if (token == JsonToken.BEGIN_OBJECT) {
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- OBJECT " + name + " {");
                parseObject(reader, name, streams, title, level + 1);
                continue;
            }
            final boolean iAmWithinAMediaObject = "_mediaStreamArray".equals(nameOfCurrentObject) || "_mediaArray".equals(nameOfCurrentObject) || "mediaCollection".equals(nameOfCurrentObject);
            if (token == JsonToken.STRING) {
                String s = reader.nextString();
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- STRING " + name + ": " + s);
                if ("title".equals(name) && level == 1 && title.length() == 0) {
                    title.append(s);
                } else if ("_stream".equals(name)
                        && iAmWithinAMediaObject
                        && s.contains(".mp4")) {
                    if (s.startsWith("//")) s = "https:" + s;
                    streamUrl = s;
                    if (quality > -1) {
                        streams.put(quality, streamUrl);
                    }
                }
                continue;
            }
            if (token == JsonToken.NUMBER) {
                int i = reader.nextInt();
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- NUMBER " + name + ": " + i);
                if ("_quality".equals(name) && iAmWithinAMediaObject) {
                    quality = i;
                    if (quality > -1 && streamUrl != null) {
                        streams.put(quality, streamUrl);
                    }
                }
                continue;
            }
            if (token == JsonToken.BOOLEAN) {
                boolean b = reader.nextBoolean();
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- BOOLEAN " + name + ": " + b);
                continue;
            }
            if (reader.hasNext()) {
                if (BuildConfig.DEBUG) Log.w(TAG, net.cellar.supp.DebugUtil.indent(level) + "- Skipping " + name);
                reader.skipValue();
            }
        }
        if (inArray && reader.peek() == JsonToken.END_ARRAY) {
            if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "]");
            reader.endArray();
        } else if (inObject && reader.peek() == JsonToken.END_OBJECT) {
            if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "}");
            reader.endObject();
        }
    }

    @App.Quality
    private final int preferredQuality;

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public ArdVideoLoader(int id, @Nullable Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
        this.preferredQuality = ctx != null ? PreferenceManager.getDefaultSharedPreferences(ctx).getInt(App.PREF_QUALITY, App.QUALITY_UNDEFINED) : App.QUALITY_UNDEFINED;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected UrlExtractor [] getExtractors() {
        return new UrlExtractor[] {new MediathekExtractor(this.preferredQuality)};
    }

    /** {@inheritDoc} */
    private class MediathekExtractor extends UrlExtractor {

        @App.Quality
        private final int preferredQuality;
        /** identifies the line that contains the json data ({@code <script id="fetchedContextValue" type="application/json">{…}}) */
        private final String key0="<script id=\"fetchedContextValue\"";

        /**
         * Constructor.
         * @param preferredQuality preferred quality level
         */
        private MediathekExtractor(@App.Quality int preferredQuality) {
            super();
            this.preferredQuality = preferredQuality;
        }

        /** {@inheritDoc} */
        @Nullable
        @Override
        String extract(@NonNull final String line) {
            // ignore possible live video
            if (line.contains("\"_isLive\":true")) return null;
            //
            int i = line.indexOf(key0);
            if (i < 0) return null;
            int opener = line.indexOf('{', i + key0.length());
            if (opener < 0) return null;
            int closer = line.lastIndexOf('}');
            if (closer < 0) return null;
            String json = line.substring(opener, closer + 1);
            JsonReader reader = null;
            final SparseArray<String> streams = new SparseArray<>();
            final StringBuilder title = new StringBuilder();
            try {
                reader = new JsonReader(new StringReader(json));
                reader.setLenient(true);
                parseObject(reader, null, streams, title, 0);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, reader + ": " + e.toString(), e);
            }
            Util.close(reader);
            int nStreams = streams.size();
            if (nStreams == 0) return null;
            if (BuildConfig.DEBUG) {
                for (i = 0; i < nStreams; i++) {
                    int key = streams.keyAt(i);
                    String url = streams.get(key);
                    Log.i(TAG, "Stream quality " + key + ": \"" + url + "\"");
                }
            }
            int q;
            String url = null;
            switch (this.preferredQuality) {
                case App.QUALITY_LOWEST:
                    q = Integer.MAX_VALUE;
                    for (i = 0; i < nStreams; i++) {
                        int quality = streams.keyAt(i);
                        if (quality < q) {q = quality; url = streams.get(quality);}
                    }
                    break;
                case App.QUALITY_HIGHEST:
                case App.QUALITY_UNDEFINED:
                default:
                    q = Integer.MIN_VALUE;
                    for (i = 0; i < nStreams; i++) {
                        int quality = streams.keyAt(i);
                        if (quality > q) {q = quality; url = streams.get(quality);}
                    }
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "Done. Extracted \"" + url + "\" and title \"" + title + "\"");
            if (url != null && title.length() > 0) {
                ArdVideoLoader.this.overriddenTitle = title.toString().trim().replace(File.separatorChar, LoaderService.REPLACEMENT_FOR_FILESEPARATOR);
            }
            return url;
        }
    }
}

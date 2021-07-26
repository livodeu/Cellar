/*
 * JsonShredderLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.net.Uri;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import okhttp3.OkHttpClient;

/**
 * Loads a json file and searches it for <u>one</u> particular String value.<br>
 * The structure of the json data is mostly ignored - the first match of {@link #getWantedKey()} is returned!
 */
abstract public class JsonShredderLoader extends Downloader {

    private static final String TAG = "JsonShredderLoader";

    /**
     * Recursive parsing of the json data.
     * @param reader JsonReader to use
     * @param nameOfCurrentObject the name of the current json object
     * @param url <em>receives</em> the extracted url
     * @param level parsing level (for debugging only)
     * @throws IOException if the JsonReader thinks it's appropriate
     */
    @VisibleForTesting
    public void parseObject(@NonNull final JsonReader reader, @Nullable final String nameOfCurrentObject, @NonNull final StringBuilder url, final int level) throws IOException {
        String name = nameOfCurrentObject;
        reader.beginObject();
        boolean inArray = false;
        boolean inObject = true;

        while (reader.hasNext()) {
            final JsonToken token = reader.peek();
            if (BuildConfig.DEBUG && token != JsonToken.NAME) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- token: " + token);
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
                parseObject(reader, name, url, level + 1);
                continue;
            }
            if (token == JsonToken.STRING) {
                String s = reader.nextString();
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- STRING " + name + ": " + s);
                if (getWantedKey().equals(name)) {
                    url.append(s);
                    return;
                }
                continue;
            }
            if (token == JsonToken.NUMBER) {
                int i = reader.nextInt();
                if (BuildConfig.DEBUG) Log.i(TAG, net.cellar.supp.DebugUtil.indent(level) + "- NUMBER " + name + ": " + i);
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

    /**
     * @return the name of the String value to find
     */
    @NonNull
    abstract public String getWantedKey();

    /**
     * Constructor.
     * @param id             download id
     * @param client         OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public JsonShredderLoader(int id, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, client, loaderListener);
    }

    @Override
    @NonNull
    protected Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        if (BuildConfig.DEBUG) Log.i(TAG, "load(" + order + ")");
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null) return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        File temp = new File(tmp);
        final String realDownloadsFolder = order.getDestinationFolder();
        order.setDestinationFolder(temp.getAbsolutePath());
        Delivery delivery = super.load(order, progressBefore, progressPerOrder);
        if (delivery.getRc() > 299) return delivery;
        final File json = delivery.getFile();
        if (json == null || json.length() == 0L) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Did not load a file from " + order.getUrl());
            return delivery;
        }
        super.ignoreListener = false;

        JsonReader reader = null;
        final StringBuilder url = new StringBuilder();
        try {
            reader = new JsonReader(new InputStreamReader(new FileInputStream(json)));
            reader.setLenient(true);
            parseObject(reader, null, url, 0);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        } finally {
            Util.close(reader);
        }
        if (url.length() == 0) {
            return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        }

        Uri uri = Uri.parse(url.toString());
        Order order2 = new Order(order.getWish(), uri);
        order2.setDestinationFolder(realDownloadsFolder);
        if (!TextUtils.isEmpty(uri.getLastPathSegment())) order2.setDestinationFilename(uri.getLastPathSegment()); else order2.setDestinationFilename(getWantedKey());
        order2.setMime(Util.getMime(uri));
        //
        // TODO test the following line
        publishProgress(Progress.resourcename(order2.getDestinationFilename()));
        //
        return super.load(order2, progressBefore, progressPerOrder);
    }
}

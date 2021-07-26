/*
 * France24Loader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Util;
import net.cellar.supp.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import okhttp3.OkHttpClient;

public class France24Loader extends YtVideoLoader {

    private static final String TAG = "France24Loader";

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public France24Loader(int id, @NonNull Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }


    @NonNull
    @Override
    protected Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        if (BuildConfig.DEBUG) Log.i(TAG, "load(" + order + ")");
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null) return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        File tmpFile = new File(tmp, "fra" + System.currentTimeMillis() + ".htm");
        int rc = Loader.downloadToFile(this.client, order.getUrl(), tmpFile);
        if (rc > 299) {
            return new Delivery(order, rc, null, null);
        }
        BufferedReader reader = null;
        String source = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(tmpFile)));
            while (source == null) {
                String line = reader.readLine();
                if (line == null) break;
                int start = line.indexOf("source=\"");
                if (start < 0) continue;
                int end = line.indexOf("\"", start + 8);
                if (end < 0) continue;
                // source="https://www.youtube.com/embed/videoid"
                source = line.substring(start + 8, end);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        } finally {
            Util.close(reader);
        }
        Util.deleteFile(tmpFile);
        if (source == null || !source.contains("youtube")) return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);

        Uri videoUri = Uri.parse(source);
        Order followUp = new Order(order.getWish(), videoUri);
        followUp.setDestinationFolder(order.getDestinationFolder());
        followUp.setDestinationFilename(videoUri.getLastPathSegment());

        return super.load(followUp, progressBefore, progressPerOrder);
    }

}

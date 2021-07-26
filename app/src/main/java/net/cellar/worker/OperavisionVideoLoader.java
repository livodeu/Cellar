/*
 * OperavisionVideoLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.net.Uri;

import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Util;
import net.cellar.supp.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 *
 */
public class OperavisionVideoLoader extends YtVideoLoader {

    private static final String TAG = "OperavisionVideoLoader";

    private static final String KEY = "<a class=\"youtube-button playbtn\"";
    private static final String KEY2 = "data-video-id=\"";

    public OperavisionVideoLoader(int id, @NonNull Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @NonNull
    @Override
    protected Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        /*
        1.: load HTML page like https://operavision.eu/en/library/performances/operas/les-contes-dhoffmann-komische-oper-berlin"
        2.: look for sth. like "<a class="youtube-button playbtn" href="#" data-center="opacity: 1" data-200-top="opacity: 1" data-wowza="" data-80-top="opacity: 0" data-anchor-target="#slide-1 h1" data-video-id="kYxyPWFAqzw"><span class="icon-play">"
            the end of the id value after 'data-video-id="' is the youtube video id
         */

        File tmp = null;
        Request.Builder requestBuilder = new Request.Builder()
                .url(order.getUrl())
                .addHeader("User-Agent", super.httpUserAgent)
                .addHeader("Accept-Encoding", "gzip")
        ;
        Request request = requestBuilder.build();
        Response response;
        ResponseBody body = null;
        OutputStream out = null;
        InputStream in = null;
        byte[] buffer = new byte[4096];
        try {
            response = this.client.newCall(request).execute();
            body = response.body();
            if (!response.isSuccessful() || body == null) {
                if (body != null) body.close();
                if (BuildConfig.DEBUG) Log.w(TAG, "Download of " + order.getUrl() + " failed - HTTP " + response.code() + " " + response.message());
                return new Delivery(order, response.code(), null, null);
            }
            tmp = File.createTempFile("html", ".htm");
            out = new BufferedOutputStream(new FileOutputStream(tmp));
            String contentEncoding = response.header("Content-Encoding");
            if ("gzip".equals(contentEncoding) || "x-gzip".equals(contentEncoding)) {
                in = new GZIPInputStream(body.byteStream());
            } else {
                in = body.byteStream();
            }
            while (!isCancelled() && !super.stopRequested) {
                int read = in.read(buffer);
                if (read <= 0) break;
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While loading " + order.getUrl() + ": " +  e.toString());
        } finally {
            Util.close(out, in, body);
        }
        if (tmp == null || !tmp.isFile() || tmp.length() == 0L) {
            return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        }

        String videoId = null;

        BufferedReader reader = null;
        String line = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(tmp)));
            for (;;) {
                line = reader.readLine();
                if (line == null) break;
                int i = line.indexOf(KEY);
                if (i < 0) continue;
                i = line.indexOf(KEY2, i + KEY.length());
                if (i < 0) continue;
                int j = line.indexOf('"', i + KEY2.length());
                if (j < 0) continue;
                videoId = line.substring(i + KEY2.length(), j);
                break;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While parsing line \"" + line + "\" of " + order.getUrl() + ": " + e.toString());
        } finally {
            Util.close(reader);
        }

        tmp.delete();

        if (videoId == null) {
            return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, null, null);
        }

        Order order2 = new Order(order.getWish(), Uri.parse(PREFIX + videoId));
        order2.setDestinationFolder(order.getDestinationFolder());
        order2.setDestinationFilename(videoId);

        return super.load(order2, progressBefore, progressPerOrder);
    }
}

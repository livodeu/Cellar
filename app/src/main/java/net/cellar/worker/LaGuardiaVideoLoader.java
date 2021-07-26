/*
 * LaGuardiaVideoLoader.java
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
public class LaGuardiaVideoLoader extends YtVideoLoader {

    private static final String TAG = "LaGuardiaVideoLoader";

    private static final String KEY = "<div id=\"youtube-";

    public LaGuardiaVideoLoader(int id, @NonNull Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, client, loaderListener);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @NonNull
    @Override
    protected Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        /*
        1.: load HTML page like "https://www.theguardian.com/global/video/2021/jan/19/biden-and-harris-hold-vigil-for-400000-covid-19-dead-as-bells-toll-across-us-video"
        2.: look for sth. like "<div id="youtube-nmehcVJm3Y0" data-asset-id="nmehcVJm3Y0" class="youtube-media-atom__iframe"></div>"
            the end of the id value after 'youtube-' is the youtube video id
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
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(tmp)));
            for (;;) {
                String line = reader.readLine();
                if (line == null) break;
                int i = line.indexOf(KEY);
                if (i < 0) continue;
                int j = line.indexOf('"', i + KEY.length());
                if (j < 0) continue;
                videoId = line.substring(i + KEY.length(), j);
                break;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While parsing " + order.getUrl() + ": " + e.toString());
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

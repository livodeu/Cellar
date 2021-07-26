/*
 * SnooVideoLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 *
 */
public class SnooVideoLoader extends Loader {

    private static final String TAG = "RedditVideoLoader";

    private OkHttpClient client;

    /**
     * Constructor.
     * @param id download id
     * @param client OkHttpClient
     * @param loaderListener LoaderListener
     */
    public SnooVideoLoader(int id, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, loaderListener);
        this.client = client;
    }

    /** {@inheritDoc} */
    @Override
    protected void cleanup() {
        this.client = null;
    }

    /** {@inheritDoc}
     * @return*/
    @Override
    @NonNull
    Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        Request.Builder requestBuilder;
        /*

        Expected url similar to: https://v.redd.it/39f1kp3grhb61/DASH_720.mp4?source=fallback
        https://v.redd.it/39f1kp3grhb61 redirects (HTTP 301) to https://www.reddit.com/r/ContagiousLaughter/comments/kxts7c/my_son_thoroughly_enjoying_his_evening_bath/

        final url should be:
        https://ds.redditsave.com/download.php?permalink=
        https://reddit.com/r/ContagiousLaughter/comments/kxts7c/my_son_thoroughly_enjoying_his_evening_bath/
        &video_url=https://v.redd.it/39f1kp3grhb61/DASH_720.mp4?source=fallback&audio_url=https://v.redd.it/39f1kp3grhb61/DASH_audio.mp4?source=fallback

        */

        /*

        1.)

        GET https://v.redd.it/fvcvyhic0ok61

        ->

        HTTP/1.1 302 Moved
        Location: https://www.reddit.com/video/fvcvyhic0ok61

        2.)

        GET https://www.reddit.com/video/fvcvyhic0ok61

        ->

        HTTP/1.1 301 Moved Permanently
        location: https://www.reddit.com/r/NatureIsFuckingLit/comments/lwa5h8/a_school_of_fish_following_a_duck/

        3.)

        GET https://www.reddit.com/r/NatureIsFuckingLit/comments/lwa5h8/a_school_of_fish_following_a_duck/

        ->

        HTTP/1.1 503 Service Temporarily Unavailable


        momentan:
        https://ds.redditsave.com/download.php?permalink=https://www.reddit.com/r/NatureIsFuckingLit/comments/lwa5h8/a_school_of_fish_following_a_duck/&video_url=https://v.redd.it/fvcvyhic0ok61

        korrekt:
        https://ds.redditsave.com/download.php?permalink=https://    reddit.com/r/NatureIsFuckingLit/comments/lwa5h8/a_school_of_fish_following_a_duck/&video_url=https://v.redd.it/fvcvyhic0ok61/DASH_1080.mp4?source=fallback&audio_url=false
         */

        File destinationDir = new File(order.getDestinationFolder());
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        if (order.getDestinationFilename() == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }

        int lastUnderscore = order.getUrl().lastIndexOf('_');
        int lastDot = order.getUrl().lastIndexOf('.');
        int lastSlash = order.getUrl().lastIndexOf('/');
        String initialUrl, audioUrl;
        if (lastDot > 0 && lastUnderscore > 0) {
            // https://v.redd.it/39f1kp3grhb61/DASH_720.mp4?source=fallback -> initialUrl = https://v.redd.it/39f1kp3grhb61
            initialUrl = order.getUrl().substring(0, lastSlash);
            // https://v.redd.it/39f1kp3grhb61/DASH_720.mp4?source=fallback -> audioUrl = https://v.redd.it/39f1kp3grhb61/DASH_audio.mp4?source=fallback
            audioUrl = order.getUrl().substring(0, lastUnderscore + 1) + "audio" + order.getUrl().substring(lastDot);
        } else {
            initialUrl = order.getUrl();
            audioUrl = null;
        }
        String downloadUrl = null;
        String contentDisposition = null;

        // first, a HEAD…
        requestBuilder = new Request.Builder()
                .url(initialUrl)
                .head();
        ResponseBody headBody = null;

        String destinationFilename = order.getDestinationFilename();
        try {
            Response headResponse = this.client.newCall(requestBuilder.build()).execute();
            int rc = headResponse.code();
            if (rc == 200) {
                String location = headResponse.request().url().toString();
                if (BuildConfig.DEBUG) Log.i(TAG, "HEAD '" + order.getUrl() + "' ==> '" + location + "'");
                contentDisposition = headResponse.header("Content-Disposition");
                if (location.startsWith("https://") || location.startsWith("http://")) {
                    String lps = Uri.parse(location).getLastPathSegment();
                    if (BuildConfig.DEBUG) Log.i(TAG, "lps: '" + lps + "'");
                    if (!TextUtils.isEmpty(lps)) destinationFilename = lps + ".mp4";
                    downloadUrl = "https://ds.redditsave.com/download.php?permalink=" + location + "&video_url=" + order.getUrl();
                    if (audioUrl != null) downloadUrl += "&audio_url=" + audioUrl;
                    if (BuildConfig.DEBUG) Log.i(TAG, "Download url: '" + downloadUrl + "'");
                }
            } else if (BuildConfig.DEBUG) {
                Log.e(TAG, "HEAD " + initialUrl + " returned " + rc);
            }
            headBody = headResponse.body();
        } catch (ConnectException | NoRouteToHostException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While HEADing " + order.getUrl() + ": " + e.toString());
            File destinationFile = new File(destinationDir, destinationFilename);
            Util.deleteFile(destinationFile);
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While HEADing " + order.getUrl() + ": " + e.toString());
            File destinationFile = new File(destinationDir, destinationFilename);
            Util.deleteFile(destinationFile);
            return new Delivery(order, LoaderService.ERROR_OTHER, destinationFile, null);
        } finally {
            Util.close(headBody);
        }

        if (downloadUrl == null) {
            return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, new File(destinationDir, order.getDestinationFilename()), null);
        }

        requestBuilder = new Request.Builder()
                .url(downloadUrl)
                .addHeader("User-Agent", "")
        ;
        File destinationFile = new File(destinationDir, destinationFilename);
        boolean destinationFileExistedBefore = destinationFile.isFile();

        if (contentDisposition != null) {
            // https://tools.ietf.org/html/rfc2616#section-19.5.1
            String replacementFilename = parseContentDisposition(contentDisposition);
            if (replacementFilename != null) {
                destinationFile = new File(destinationDir, replacementFilename);
                destinationFileExistedBefore = destinationFile.isFile();
                order.setDestinationFilename(replacementFilename);
            }
        }

        String alt = Util.suggestAlternativeFilename(destinationFile);
        if (alt != null) {
            destinationFile = new File(destinationDir, alt);
        }
        publishProgress(Progress.resourcename(destinationFile.getName()));
        Request request = requestBuilder.build();
        Response response;
        ResponseBody body = null;
        OutputStream out = null;
        InputStream in = null;

        try {
            response = this.client.newCall(request).execute();
            body = response.body();
            MediaType mediaType = body != null ? body.contentType() : null;
            if (!response.isSuccessful() || body == null) {
                if (body != null) body.close();
                if (BuildConfig.DEBUG) Log.i(TAG, "Download of " + order.getUrl() + " failed - HTTP " + response.code() + " " + response.message());
                return new Delivery(order, response.code(), destinationFile, mediaType != null ? mediaType.toString() : null);
            }
            final long contentLength = body.contentLength();
            if (contentLength > 0L) {
                long freeSpace = destinationDir.getFreeSpace();
                if (contentLength > freeSpace) {
                    body.close();
                    return new Delivery(order, LoaderService.ERROR_LACKING_SPACE, destinationFile, null);
                }
            } else {
                publishProgress(Progress.noprogress());
            }
            out = new BufferedOutputStream(new FileOutputStream(destinationFile));
            in = body.byteStream();
            long totalBytes = 0L;
            final byte[] buffer = new byte[4096];
            Progress progress = null;
            while (!isCancelled() && !super.stopRequested) {
                int read = in.read(buffer);
                if (read <= 0) break;
                out.write(buffer, 0, read);
                if (contentLength <= 0L) continue;
                // publish progress
                totalBytes += read;
                progress = Progress.completing(progressBefore + (float) totalBytes / (float) contentLength * progressPerOrder, progress);
                publishProgress(progress);
            }
            Util.close(out, in, body);
            if (isCancelled()) {
                if (!destinationFileExistedBefore && !isDeferred()) Util.deleteFile(destinationFile);
                return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, null);
            }
            return new Delivery(order, response.code(), destinationFile, "video/mp4");
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        }
        Util.close(out, in, body);
        if (isCancelled()) {
            if (!destinationFileExistedBefore && !isDeferred()) Util.deleteFile(destinationFile);
            return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, null);
        }
        return new Delivery(order, -1, destinationFile, null);
    }
}

/*
 * YtVideoLoader.java
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
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 *
 */
public class YtVideoLoader extends Streamer {

    private static final String TAG = "YtVideoLoader";

    public static final String PREFIX = "https://www.youtube.com/watch?v=";

    protected OkHttpClient client;

    public YtVideoLoader(int id, @NonNull Context ctx, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, ctx, loaderListener);
        this.client = client;
    }

    /** {@inheritDoc} */
    @Override
    protected void cleanup() {
        this.client = null;
        super.cleanup();
        NewPipe.init(null);
    }

    @NonNull
    @Override
    protected Delivery load(@NonNull final Order order, float progressBefore, float progressPerOrder) {
        final File destinationDir = new File(order.getDestinationFolder());
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        if (order.getDestinationFilename() == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }
        // setup temporary destination file - we hope to receive a more meaningful name shortly…
        String destinationFilename = order.getDestinationFilename();
        File destinationFile = new File(destinationDir, destinationFilename);
        //
        NewPipe.init(new YtDownloader(this.client, super.httpUserAgent));
        try {
            final YoutubeStreamExtractor yse = new YoutubeStreamExtractor(ServiceList.YouTube, YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(order.getUrl()));
            yse.fetchPage();
            StreamType streamType = yse.getStreamType();

            // try to get a video name
            try {
                String name = yse.getName().trim();
                if (name.length() > 0) {
                    destinationFilename = name.replace(File.separatorChar, '_').trim();
                    destinationFile = new File(destinationDir, destinationFilename);
                    String alt = Util.suggestAlternativeFilename(destinationFile);
                    if (alt != null) {
                        destinationFile = new File(destinationDir, alt);
                    }
                }
            } catch (Exception ignored) {
            }

            final VideoStream videoPick;
            final int videoFormatId;
            final String hls;
            if (streamType == StreamType.LIVE_STREAM) {
                videoPick = null;
                videoFormatId = Integer.MAX_VALUE;
                hls = yse.getHlsUrl();
                Log.i(TAG, "HLS: '" + hls + "'");
            } else {
                hls = null;
                final List<VideoStream> videos = yse.getVideoStreams();
                if (videos.isEmpty()) videos.addAll(yse.getVideoOnlyStreams());
                final List<AudioStream> audios = yse.getAudioStreams();
                if (videos.isEmpty()) {
                    if (BuildConfig.DEBUG) {
                        String errMsg = yse.getErrorMessage();
                        Log.w(TAG, "No video streams found at " + order.getUrl() + (errMsg != null ? " - error msg: " + errMsg : ""));
                    }
                    return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, destinationFile, null);
                }
                // Sort videos so that the best one is the first one
                Collections.sort(videos, (o1, o2) -> {
                    String r1 = o1.resolution;
                    String r2 = o2.resolution;
                    if (r1 == null) {
                        if (r2 == null) return 0;
                        else return -1;
                    }
                    if (r2 == null) return 1;
                    int l1 = r1.length();
                    int l2 = r2.length();
                    if (l1 == 0 && l2 == 0) return 0;
                    if (r1.charAt(l1 - 1) == 'p' && r2.charAt(l2 - 1) == 'p') {
                        try {
                            int i1 = Integer.parseInt(r1.substring(0, l1 - 1));
                            int i2 = Integer.parseInt(r2.substring(0, l2 - 1));
                            return Integer.compare(i2, i1);
                        } catch (Exception e) {
                            if (BuildConfig.DEBUG) Log.e(TAG, "While comparing '" + r1 + "' to '" + r2 + "'");
                        }
                    }
                    return r2.compareTo(r1);
                });
                videoPick = videos.get(0);
                if (videoPick.isVideoOnly && !audios.isEmpty()) {
                    if ("MPEG-4".equals(videoPick.getFormat().getName())) {
                        // if the video is mpeg-4, prefer m4a audio
                        Collections.sort(audios, (o1, o2) -> {
                            if (o1.getFormat().id == 0x100 && o2.getFormat().id != 0x100) return -1;
                            if (o2.getFormat().id == 0x100 && o1.getFormat().id != 0x100) return 1;
                            return Integer.compare(o2.average_bitrate, o1.average_bitrate);
                        });
                    } else {
                        Collections.sort(audios, (o1, o2) -> Integer.compare(o2.average_bitrate, o1.average_bitrate));
                    }
                }
                if (BuildConfig.DEBUG) {
                    for (VideoStream video : videos) {
                        Log.i(TAG, "Video stream '" + video.resolution + "', videoOnly: " + video.isVideoOnly + ", format: " + video.getFormat().getName());
                    }
                    for (AudioStream audio : audios) {
                        Log.i(TAG, "Audio stream  " + audio.getBitrate() + " bps " + audio.getCodec());
                    }
                }
                videoFormatId = videoPick.getFormatId();
            }
            final Order videoOrder;
            if (hls != null) {
                videoOrder = new Order(order.getWish(), Uri.parse(hls));
                if (BuildConfig.DEBUG) Log.i(TAG, "Video stream is HLS from " + hls);
            } else {
                videoOrder = new Order(order.getWish(), Uri.parse(videoPick.getUrl()));
                if (BuildConfig.DEBUG) Log.i(TAG, "Video stream is " + videoPick.getFormat() + (!videoPick.isVideoOnly ? ", including audio" : ""));
            }
            videoOrder.setDestinationFolder(order.getDestinationFolder());

            if (videoFormatId == MediaFormat.MPEG_4.id) {
                destinationFilename += "." + MediaFormat.MPEG_4.suffix;
                videoOrder.setMime(MediaFormat.MPEG_4.mimeType);
            } else if (videoFormatId == MediaFormat.WEBM.id) {
                destinationFilename += "." + MediaFormat.WEBM.suffix;
                videoOrder.setMime(MediaFormat.WEBM.mimeType);
            } else if (videoFormatId == MediaFormat.v3GPP.id) {
                destinationFilename += "." + MediaFormat.v3GPP.suffix;
                videoOrder.setMime(MediaFormat.v3GPP.mimeType);
            }
            videoOrder.setDestinationFilename(destinationFilename);

            // pass the new Order to the Streamer
            return super.load(videoOrder, progressBefore, progressPerOrder);
        } catch (ContentNotAvailableException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            Util.deleteFile(destinationFile);
            return new Delivery(order, 404, destinationFile, null, e, null);
        } catch (ReCaptchaException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            Util.deleteFile(destinationFile);
            return new Delivery(order, LoaderService.ERROR_YOUTUBE_CAPTCHA, destinationFile, null, e, null);
        } catch (ConnectException | SocketTimeoutException | NoRouteToHostException | UnknownHostException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            Util.deleteFile(destinationFile);
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, e, null);
        } catch (UnknownServiceException e) {
            Util.deleteFile(destinationFile);
            return new Delivery(order, e.toString().contains("CLEARTEXT") ? LoaderService.ERROR_CLEARTEXT_NOT_PERMITTED : LoaderService.ERROR_OTHER, destinationFile, null, e, null);
        } catch (SSLPeerUnverifiedException e) {
            Util.deleteFile(destinationFile);
            return new Delivery(order, LoaderService.ERROR_SSL_PEER_UNVERIFIED, destinationFile, null, e, null);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        }
        if (!isDeferred()) Util.deleteFile(destinationFile);
        if (isCancelled()) {
            return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, null);
        }
        return new Delivery(order, LoaderService.ERROR_OTHER, destinationFile, null);
    }

    /**
     *
     */
    private static class YtDownloader extends org.schabi.newpipe.extractor.downloader.Downloader {

        @NonNull private final OkHttpClient client;
        @NonNull private final String httpUserAgent;

        private YtDownloader(@NonNull OkHttpClient client, @NonNull String httpUserAgent) {
            super();
            this.client = client;
            this.httpUserAgent = httpUserAgent;
        }

        /** {@inheritDoc} */
        @Override
        public Response execute(@NonNull Request request) throws IOException, ReCaptchaException {
            final String httpMethod = request.httpMethod();
            final String url = request.url();
            final Map<String, List<String>> headers = request.headers();
            final byte[] dataToSend = request.dataToSend();

            RequestBody requestBody = null;
            if (dataToSend != null) {
                //noinspection deprecation
                requestBody = RequestBody.create(null, dataToSend);
            }

            final okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                    .method(httpMethod, requestBody).url(url)
                    .addHeader("User-Agent", this.httpUserAgent);

            for (Map.Entry<String, List<String>> pair : headers.entrySet()) {
                final String headerName = pair.getKey();
                final List<String> headerValueList = pair.getValue();

                if (headerValueList.size() > 1) {
                    requestBuilder.removeHeader(headerName);
                    for (String headerValue : headerValueList) {
                        requestBuilder.addHeader(headerName, headerValue);
                    }
                } else if (headerValueList.size() == 1) {
                    requestBuilder.header(headerName, headerValueList.get(0));
                }

            }

            final okhttp3.Response response = this.client.newCall(requestBuilder.build()).execute();

            if (response.code() == 429) {
                response.close();
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            final ResponseBody body = response.body();
            String responseBodyToReturn = null;

            if (body != null) {
                responseBodyToReturn = body.string();
            }

            final String latestUrl = response.request().url().toString();
            return new Response(response.code(), response.message(), response.headers().toMultimap(), responseBodyToReturn, latestUrl);
        }
    }
}

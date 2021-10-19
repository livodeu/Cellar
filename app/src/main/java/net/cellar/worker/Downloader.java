/*
 * Downloader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.util.Log;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.Ancestry;
import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.model.Credential;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.queue.QueueManager;
import net.cellar.supp.DebugUtil;
import net.cellar.supp.UriUtil;
import net.cellar.supp.Util;

import org.jetbrains.annotations.TestOnly;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.Arrays;
import java.util.Date;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * See
 * <ul>
 * <li><a href="https://tools.ietf.org/html/rfc7230#section-2.7.2">Scheme definition</a></li>
 * </ul>
 */
public class Downloader extends Loader {
    private static final String TAG = "Downloader";
    private final byte[] buffer = new byte[4096];
    protected boolean ignoreListener = false;
    private OkHttpClient client;
    private static String fakeContentDisposition;

    @TestOnly
    public static void setFakeContentDisposition(@NonNull String fcd) {
        if (!BuildConfig.DEBUG) return;
        fakeContentDisposition = "filename=\"" + fcd + "\"";
    }

    /**
     * Constructor.
     * @param id download id
     * @param client OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public Downloader(int id, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, loaderListener);
        this.client = client;
    }

    /** {@inheritDoc} */
    @Override
    protected void cleanup() {
        this.client = null;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @NonNull
    protected Delivery load(@NonNull Order order, @FloatRange(from = 0, to = 1) final float progressBefore, @FloatRange(from = 0, to = 1) final float progressPerOrder) {
        if (BuildConfig.DEBUG) Log.i(TAG, "load(" + order + ") - proxy: " + (this.client != null ? this.client.proxy() : "<null>"));
        final File destinationDir = new File(order.getDestinationFolder());
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        if (order.getDestinationFilename() == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }
        File destinationFile = new File(destinationDir, order.getDestinationFilename());
        boolean destinationFileExistedBefore = destinationFile.isFile();

        final String referer = order.getReferer();
        final String host = order.getUri().getHost();

        final Credential credentialForHost = UriUtil.getCredential(order.getUri());

        final long resourceLength;
        final Date resourceLastModified;
        final String contentDisposition;
        Request.Builder requestBuilder;

        // first, a HEAD…
        requestBuilder = new Request.Builder()
                .url(order.getUrl())
                .head();
        if (referer != null) requestBuilder.addHeader("Referer", referer);
        if (credentialForHost != null) {
            String user = credentialForHost.getUserid();
            CharSequence pwd = credentialForHost.getPassword();
            requestBuilder.addHeader("Authorization", Credentials.basic(user != null ? user : "", pwd != null ? pwd.toString() : ""));
        }
        ResponseBody headBody = null;
        try {
            final Response headResponse = this.client.newCall(requestBuilder.build()).execute();
            contentDisposition = fakeContentDisposition != null ? fakeContentDisposition : headResponse.header("Content-Disposition");
            resourceLength = Util.parseLong(headResponse.header("Content-Length"), -1L);
            resourceLastModified = Util.parseDate(headResponse.header("Last-Modified"), DF, null);
            headBody = headResponse.body();
            // https://tools.ietf.org/html/rfc2616#section-10.4.6
            // 405 is "Method Not Allowed" - we'll try a GET then anyway even if the server does not like HEAD…
            if (!headResponse.isSuccessful() && headResponse.code() != HttpURLConnection.HTTP_BAD_METHOD) {
                Util.close(headBody);
                if (headResponse.code() == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    final Delivery.AuthenticateInfo authenticateInfo;
                    try {
                        //noinspection ConstantConditions
                        authenticateInfo = Delivery.AuthenticateInfo.parseWwwAuthenticate(headResponse.header("WWW-Authenticate"));
                    } catch (NullPointerException | IllegalArgumentException e) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "While parsing WWW-Authenticate: " + e.toString());
                        return new Delivery(order, HttpURLConnection.HTTP_NOT_IMPLEMENTED, destinationFile, null);
                    }
                    return new Delivery(order, headResponse.code(), destinationFile, null, authenticateInfo);
                }
                return new Delivery(order, headResponse.code(), destinationFile, null);
            }
            if (resourceLength > 0L) {
                long freeSpace = destinationDir.getFreeSpace();
                if (resourceLength > freeSpace) {
                    Util.close(headBody);
                    return new Delivery(order, LoaderService.ERROR_LACKING_SPACE, destinationFile, null, new ResourceTooLargeException(resourceLength), null);
                }
            } else {
                publishProgress(Progress.noprogress());
            }
        } catch (ConnectException | SocketTimeoutException | NoRouteToHostException | UnknownHostException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "HEAD " + order.getUrl() + ": " + e.toString());
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, e, null);
        } catch (SSLHandshakeException e) {
            // e.g. javax.net.ssl.SSLHandshakeException: Chain validation failed
            if (BuildConfig.DEBUG) Log.e(TAG, "HEAD " + order.getUrl() + ": " + e.toString());
            return new Delivery(order, LoaderService.ERROR_SSL_HANDSHAKE, destinationFile, null, e, null);
        } catch (UnknownServiceException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "HEAD " + order.getUrl() + ": " + e.toString());
            return new Delivery(order, e.toString().contains("CLEARTEXT") ? LoaderService.ERROR_CLEARTEXT_NOT_PERMITTED : LoaderService.ERROR_OTHER, destinationFile, null, e, null);
        } catch (SSLPeerUnverifiedException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "HEAD " + order.getUrl() + ": " + e.toString());
            return new Delivery(order, LoaderService.ERROR_SSL_PEER_UNVERIFIED, destinationFile, null, e, null);
        } catch (InterruptedIOException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "HEAD " + order.getUrl() + ": " + e.toString());
            // apparently, headBody is always null here so no need to close it
            return new Delivery(order, LoaderService.ERROR_OTHER, destinationFile, null, e, null);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "HEAD " + order.getUrl() + ": " + e.toString(), e);
            Util.close(headBody);
            return new Delivery(order, LoaderService.ERROR_OTHER, destinationFile, null, e, null);
        } finally {
            Util.close(headBody);
        }
        if (contentDisposition != null) {
            // https://tools.ietf.org/html/rfc2616#section-19.5.1
            String replacementFilename = parseContentDisposition(contentDisposition);
            if (replacementFilename != null) {
                destinationFile = new File(destinationDir, replacementFilename);
                destinationFileExistedBefore = destinationFile.isFile();
                order.setDestinationFilename(replacementFilename);
                QueueManager.getInstance().setFileName(order.getUrl(), replacementFilename);
                publishProgress(Progress.resourcename(replacementFilename));
                if (BuildConfig.DEBUG) Log.i(TAG, "The file name will be changed to \"" + replacementFilename + "\"");
            }
        }

        // determine whether we already have a part of that resource we are trying to load
        Ancestry ancestry = Ancestry.getInstance();
        final boolean partiallyDownloaded = destinationFile.isFile()
                && destinationFile.length() > 0L
                && destinationFile.length() < resourceLength
                // if Ancestry has a record, then it must match the host (Ancestry might not know the file if the process had been killed during the latest download attempt because the Ancestry record is created in LoaderService.done())
                && (!ancestry.knowsFile(destinationFile) || (host != null && host.equals(ancestry.getHost(destinationFile))))
                // a file has been partially downloaded if it has not been modified after the remote resource has been modified
                && resourceLastModified != null
                // the remote resource must be older than or of the same age as the local file
                && resourceLastModified.getTime() <= destinationFile.lastModified();


        if (DebugUtil.TEST) {
            publishProgress(Progress.msg("Resuming: " + partiallyDownloaded, false));
        }

        if (this.stopRequested) {
            return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, null);
        }

        // second, the real GET…
        requestBuilder = new Request.Builder()
                .url(order.getUrl())
                .addHeader("Accept-Encoding", "gzip")
                ;
        if (referer != null) requestBuilder.addHeader("Referer", referer);
        if (credentialForHost != null) {
            String user = credentialForHost.getUserid();
            CharSequence pwd = credentialForHost.getPassword();
            requestBuilder.addHeader("Authorization", Credentials.basic(user != null ? user : "", pwd != null ? pwd.toString() : ""));
        }

        // determine starting point - usually at byte 0
        final long startByteCount;
        if (partiallyDownloaded) {
            startByteCount = destinationFile.length();
            // https://tools.ietf.org/html/rfc2616#section-14.35
            if (BuildConfig.DEBUG) Log.i(TAG, "The resource " + order.getUrl() + " had been downloaded partially (" + startByteCount + " bytes out of " + resourceLength + ")");
            requestBuilder.addHeader("Range", "bytes=" + startByteCount + "-");
        } else {
            startByteCount = 0L;
            if (destinationFile.length() > 0L) requestBuilder.addHeader("If-Modified-Since", DF.format(new Date(destinationFile.lastModified())));
        }

        final Request request = requestBuilder.build();
        final Response response;
        ResponseBody body = null;
        OutputStream out = null;
        InputStream in = null;
        long totalBytesFromThisDownload = 0L;
        try {
            response = this.client.newCall(request).execute();
            body = response.body();
            final MediaType mediaType = body != null ? body.contentType() : null;
            if (!response.isSuccessful() || body == null) {
                if (body != null) body.close();
                if (BuildConfig.DEBUG) Log.w(TAG, "Download of " + order.getUrl() + " failed - HTTP " + response.code() + " " + response.message());
                return new Delivery(order, response.code(), destinationFile, mediaType != null ? mediaType.toString() : null);
            }
            if (!this.ignoreListener && super.refListener != null) {
                LoaderListener l = super.refListener.get();
                if (l != null) l.contentlength(super.id, resourceLength);
            }
            // create (or append to) destination file
            out = new BufferedOutputStream(new FileOutputStream(destinationFile, partiallyDownloaded && response.code() == 206));
            //
            String contentEncoding = response.header("Content-Encoding");
            final boolean gzip = "gzip".equals(contentEncoding) || "x-gzip".equals(contentEncoding);
            final boolean deflate = "deflate".equals(contentEncoding);  // <- should not happen unless we had given "deflate" in the "Accept-Encoding" request header
            InputStream bodyByteStream = body.byteStream();
            if (gzip && !(bodyByteStream instanceof GZIPInputStream)) {
                in = new CountingGZIPInputStream(bodyByteStream);
            } else if (deflate && !(bodyByteStream instanceof InflaterInputStream)) {
                in = new CountingInflaterInputStream(bodyByteStream, new Inflater(true));
            } else {
                in = bodyByteStream;
            }
            if (BuildConfig.DEBUG && (resourceLength <= 0L || this.ignoreListener)) Log.w(TAG, "No progress will be reported!");
            Progress progress = null;
            @FloatRange(from = 0, to = 1) float latestProgressReport = 0f;
            while (!isCancelled() && !super.stopRequested) {
                int read = in.read(this.buffer);    // <- if the connection is lost, we get an Exception here
                if (read <= 0) break;
                out.write(this.buffer, 0, read);
                // we cannot publish the progress if we don't know the resource length
                if (resourceLength <= 0L) continue;
                // publish progress
                if (in instanceof CountingGZIPInputStream) totalBytesFromThisDownload = ((CountingGZIPInputStream) in).getTotal();
                else if (in instanceof CountingInflaterInputStream) totalBytesFromThisDownload = ((CountingInflaterInputStream) in).getTotal();
                else totalBytesFromThisDownload += read;
                if (!this.ignoreListener) {
                    // number of bytes downloaded is number of bytes from previous attempt plus number of bytes from current attempt
                    long totalBytes = startByteCount + totalBytesFromThisDownload;
                    // this can be used to test partial downloads: if (BuildConfig.DEBUG && total > 10_000L && !destinationFileExistedBefore) throw new SSLException("TESTTESTTEST");
                    @FloatRange(from = 0, to = 1) float progressValue = progressBefore + (float) totalBytes / (float) resourceLength * progressPerOrder;
                    if (progressValue > 1) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "Progress > 1, calculated as such: " + progressValue + " = " + progressBefore + " + " + totalBytes + " / " + resourceLength + " * " + progressPerOrder);
                        continue;
                    }
                    progress = Progress.completing(progressValue, progress);
                    if (progressValue > 0.95f || progressValue - latestProgressReport >= 0.01f) {
                        // publish progress only if close to the end or if a minimum delta compared to the previous value has been reached
                        publishProgress(progress);
                        latestProgressReport = progress.getCompletion();
                    }
                }
            }
            Util.close(out, in, body);
            Arrays.fill(this.buffer, (byte)0);
            if (isCancelled()) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Download " + super.id + (isDeferred() ? " deferred" : " cancelled (not deferred)"));
                if (destinationFile.isFile() && !destinationFileExistedBefore && !isDeferred()) destinationFile.delete();
                return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, mediaType != null ? mediaType.toString() : null);
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "Downloaded " + order.getUrl() + " - HTTP " + response.code() + " " + response.message() + " - media type: '" + mediaType + "', total: " + totalBytesFromThisDownload);
            return new Delivery(order, response.code(), destinationFile, mediaType != null ? mediaType.toString() : null);
        } catch (SSLException | InterruptedIOException e) {
            // SSLException: we are here usually if the network connection collapsed during the download ("javax.net.ssl.SSLException: Read error: … I/O error during system call, Software caused connection abort")
            // InterruptedIOException: we are here when the user has cancelled/deferred the download (that means, cancel() has been called)
            if (BuildConfig.DEBUG) Log.e(TAG, "While downloading from " + order.getUrl() + ": " + e.toString());
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While downloading from " + order.getUrl() + ": " + e.toString(), e);
        }
        Util.close(out, in, body);
        if (isCancelled()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Download " + super.id + (isDeferred() ? " deferred" : " cancelled (not deferred)"));
            if (destinationFile.isFile() && !destinationFileExistedBefore && !isDeferred()) destinationFile.delete();
            return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, null);
        }
        return new Delivery(order, totalBytesFromThisDownload > 0L ? LoaderService.ERROR_INTERRUPTED : LoaderService.ERROR_OTHER, destinationFile, null);
    }

    /**
     * A GZIPInputStream that keeps track of the number of bytes read.
     */
    private static class CountingGZIPInputStream extends GZIPInputStream {

        private long total;

        private CountingGZIPInputStream(InputStream in) throws IOException {
            super(in, 1024);
        }

        /** {@inheritDoc} */
        @Override
        protected void fill() throws IOException {
            super.fill();
            this.total += super.len;
        }

        private long getTotal() {
            return this.total;
        }
    }

    /**
     * An InflaterInputStream that keeps track of the number of bytes read.
     */
    private static class CountingInflaterInputStream extends InflaterInputStream {

        private long total;

        public CountingInflaterInputStream(InputStream in, Inflater inf) {
            super(in, inf);
        }

        /** {@inheritDoc} */
        @Override
        protected void fill() throws IOException {
            super.fill();
            this.total += super.len;
        }

        private long getTotal() {
            return this.total;
        }
    }

}

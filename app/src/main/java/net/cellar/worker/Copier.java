/*
 * Copier.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.R;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Util;
import net.cellar.supp.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

/**
 * Copies the data represented by the given uri to the app's downloads directory.<br>
 * Works for content: and file: schemes.
 */
public class Copier extends Loader {

    private static final String TAG = "Copier";
    private Reference<Context> refctx;

    /**
     * Constructor.
     * @param id download id
     * @param ctx Context
     * @param loaderListener Listener (optional)
     */
    public Copier(int id, @NonNull Context ctx, @Nullable LoaderListener loaderListener) {
        super(id, loaderListener);
        this.refctx = new WeakReference<>(ctx);
    }

    /** {@inheritDoc} */
    @Override
    protected void cleanup() {
        this.refctx = null;
    }

    /**
     * Copies the data represented by the {@link Order#getUri() uri} to the downloads directory.<br>
     * Works for content: and file: schemes.
     * @param order Order
     * @return Delivery
     */
    @NonNull
    protected Delivery load(@NonNull Order order, @FloatRange(from = 0, to = 1) final float progressBefore, @FloatRange(from = 0, to = 1) final float progressPerOrder) {
        final Context context = this.refctx.get();
        if (context == null) return new Delivery(order, LoaderService.ERROR_CONTEXT_GONE, null, null);
        final Uri sourceUri = order.getUri();
        int rc = 200;
        InputStream in = null;
        OutputStream out = null;
        File destinationFile = null;
        boolean destinationExisted = false;
        try {
            File destinationDir = new File(order.getDestinationFolder());
            if (BuildConfig.DEBUG) Log.i(TAG, "Copying from \"" + order.getUrl() + "\"");
            destinationFile = new File(destinationDir, order.getDestinationFilename());
            String alt = Util.suggestAlternativeFilename(destinationFile);
            if (alt != null) {
                order.setDestinationFilename(alt);
                destinationFile = new File(destinationDir, order.getDestinationFilename());
            }
            // avoid copying our own files…
            String sourceHost = sourceUri.getHost();    // "net.cellar.fileprovider", as defined in the manifest
            if (sourceHost != null && sourceHost.startsWith(BuildConfig.APPLICATION_ID)) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Destination file is \"" + destinationFile + "\" from host \"" + sourceHost + "\"");
                return new Delivery(order, LoaderService.ERROR_COPY_FROM_MYSELF, destinationFile, null);
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "Destination file is \"" + destinationFile + "\"");
            destinationExisted = destinationFile.isFile();
            final long size = order.getFileSize();
            long freeSpace = destinationDir.getFreeSpace();
            if (size > freeSpace) {
                return new Delivery(order, LoaderService.ERROR_LACKING_SPACE, destinationFile, null);
            }
            //
            in = context.getContentResolver().openInputStream(sourceUri);
            if (in == null) throw new FileNotFoundException(context.getString(R.string.error_not_found, sourceUri.toString()));
            out = new BufferedOutputStream(new FileOutputStream(destinationFile));
            long total = 0L;
            byte[] buf = new byte[size > 0L ? (int) Math.min(size, 8192) : 8192];
            Progress progress = null;
            while (!isCancelled() && !super.stopRequested) {
                int read = in.read(buf);
                if (read <= 0) break;
                out.write(buf, 0, read);
                total += read;
                if (size > 0) {
                    progress = Progress.completing(progressBefore + (float) total / (float) size * progressPerOrder, progress);
                    publishProgress(progress);
                }
            }
            if (isCancelled()) {
                rc = isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED;
                if (destinationFile.isFile() && !destinationExisted && !isDeferred()) Util.deleteFile(destinationFile);
            }
        } catch (SecurityException e) {
            rc = 403;
            if (destinationFile != null && destinationFile.isFile() && !destinationExisted) Util.deleteFile(destinationFile);
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to copy \"" + sourceUri + "\": " + e.toString());
        } catch (FileNotFoundException e) {
            String msg = e.toString().toLowerCase(java.util.Locale.US);
            if (msg.contains("permission") && (msg.contains("denied") || msg.contains("denial"))) rc = 403; else rc = 404;
            if (destinationFile.isFile() && !destinationExisted) Util.deleteFile(destinationFile);
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to copy \"" + sourceUri + "\": " + e.toString());
        } catch (Throwable e) {
            rc = 500;
            if (destinationFile != null && destinationFile.isFile() && !destinationExisted) Util.deleteFile(destinationFile);
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to copy \"" + sourceUri + "\": " + e.toString(), e);
        } finally {
            Util.close(out, in);
        }
        return new Delivery(order, rc, destinationFile, order.getMime());
    }
}

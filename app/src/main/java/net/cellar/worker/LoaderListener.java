/*
 * LoaderListener.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import net.cellar.model.Delivery;

import java.util.Set;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

/**
 * Implemented to receive information about the progress of a download process.
 */
public interface LoaderListener {

    /**
     * The data is being buffered. Standby…
     * @param id download id
     * @param buffer buffer level in percent
     */
    default void buffering(int id, @FloatRange(from = 0, to = 100) float buffer) {
        // NOP
    }

    /**
     * The content length has been received.
     * @param id download id
     * @param contentLength content length header [bytes]
     */
    @WorkerThread
    default void contentlength(int id, long contentLength) {
        // NOP
    }

    /**
     * A download is done.
     * @param id download id
     * @param complete {@code true} if the download is complete, {@code false} if cancelled
     * @param deliveries Set of prepared Deliveries
     */
    @UiThread
    void done(int id, boolean complete, @NonNull Set<Delivery> deliveries);

    /**
     * It has been determined that a live stream is being recorded.
     * @param id download id
     */
    default void liveStreamDetected(int id) {
        // NOP
    }

    /**
     * A message may be displayed.
     * @param id download id
     * @param msg message
     * @param isError {@code true} if is is an error message
     */
    default void message(int id, @NonNull String msg, boolean isError) {
        // NOP
    }

    /**
     * No progress can be determined.
     * @param id download id
     */
    default void noprogress(int id) {
        // NOP
    }

    /**
     * A download has made some progress.
     * @param id download id
     * @param progress progress, 1 signals completion
     * @param remainingSeconds estimated number of seconds remaining
     */
    default void progress(int id, @FloatRange(to = 1) float progress, int remainingSeconds) {
        // NOP
    }

    /**
     * A download has made some progress.
     * This method is called when video or audio content is being streamed.
     * @param id download id
     * @param ms elapsed time in milliseconds
     * @param msTotal if &gt; 0, length of the medi<b>um</b> in milliseconds
     * @param remainingSeconds estimated number of seconds remaining
     */
    default void progressAbsolute(int id, float ms, long msTotal, int remainingSeconds) {
        // NOP
    }

    /**
     * A new and better resource name has been determined.
     * @param id downlod id
     * @param resourceName the new resource name
     */
    default void receivedResourceName(int id, @NonNull String resourceName) {
        // NOP
    }
}

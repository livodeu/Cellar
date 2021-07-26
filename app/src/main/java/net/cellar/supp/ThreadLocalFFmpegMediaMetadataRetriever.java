/*
 * ThreadLocalFFmpegMediaMetadataRetriever.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import androidx.annotation.WorkerThread;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import wseemann.media.FFmpegMediaMetadataRetriever;

public class ThreadLocalFFmpegMediaMetadataRetriever extends ThreadLocal<FFmpegMediaMetadataRetriever> {

    public final Set<Reference<Thread>> initialised = new HashSet<>(2);
    private final Set<FFmpegMediaMetadataRetriever> instances = new HashSet<>(2);

    public void cleanup() {
        synchronized (this.instances) {
            try {
                for (FFmpegMediaMetadataRetriever mmr : this.instances) {
                    mmr.release();
                }
                this.instances.clear();
            } catch (Exception ignored) {
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected FFmpegMediaMetadataRetriever initialValue() {
        synchronized (initialised) {
            this.initialised.add(new WeakReference<>(Thread.currentThread()));
        }
        FFmpegMediaMetadataRetriever mmr = new FFmpegMediaMetadataRetriever();
        synchronized (this.instances) {
            this.instances.add(mmr);
        }
        return mmr;
    }

    /** {@inheritDoc} */
    @Override
    @WorkerThread
    public void remove() {
        FFmpegMediaMetadataRetriever mmr = get();
        if (mmr != null) {
            mmr.release();
        }
        super.remove();
    }

}

/*
 * RedirectLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.model.Delivery;
import net.cellar.model.Order;

import okhttp3.OkHttpClient;

/**
 * Modifies a url before downloading.
 */
abstract public class RedirectLoader extends Downloader {

    /**
     * Constructor.
     *
     * @param id             download id
     * @param client         OkHttpClient
     * @param loaderListener Listener (optional)
     */
    public RedirectLoader(int id, @NonNull OkHttpClient client, @Nullable LoaderListener loaderListener) {
        super(id, client, loaderListener);
    }

    @NonNull
    @Override
    protected final Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        String to = redirect(order.getUrl());
        if (BuildConfig.DEBUG) net.cellar.supp.Log.i(getClass().getSimpleName(), "Redirecting \"" + order.getUrl() + "\" to \"" + to + "\"");
        final Order redirectedOrder = new Order(Uri.parse(to));
        redirectedOrder.setDestinationFolder(order.getDestinationFolder());
        redirectedOrder.setDestinationFilename(order.getDestinationFilename());
        redirectedOrder.setMime(order.getMime());
        redirectedOrder.setReferer(order.getReferer());
        redirectedOrder.setGroup(order.getGroup());
        if (order.hasAdditionalAudioUrls()) {
            for (String au : order.getAdditionalAudioUrls()) {
                redirectedOrder.addAudioUrl(au);
            }
        }
        return super.load(redirectedOrder, progressBefore, progressPerOrder);
    }

    /**
     * Returns the modified url.
     * @param original original url
     * @return the url that should be used instead
     */
    @NonNull
    abstract public String redirect(@NonNull String original);
}

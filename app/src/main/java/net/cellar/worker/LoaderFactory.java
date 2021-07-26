/*
 * LoaderFactory.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import net.cellar.App;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Websites will never be supported if they require registration, including facegram, instarest, twitbook, pintter, etc.
 */
public final class LoaderFactory {

    @NonNull private final App app;

    /**
     * Constructor.
     * @param app App
     */
    public LoaderFactory(@NonNull App app) {
        super();
        this.app = app;
    }

    @NonNull
    public Loader create(final int id, @NonNull final Class<? extends Loader> clazz, @Nullable final LoaderListener listener) {
        if (clazz == Downloader.class) {
            return new Downloader(id, this.app.getOkHttpClient(), listener);
        }
        if (clazz == YtVideoLoader.class) {
            return new YtVideoLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == France24Loader.class) {
            return new France24Loader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == SnooVideoLoader.class) {
            return new SnooVideoLoader(id, this.app.getOkHttpClient(), listener);
        }
        if (clazz == ArdVideoLoader.class) {
            return new ArdVideoLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == ImgurLoader.class) {
            return new ImgurLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == HtmlShredderVideoLoader.class) {
            return new HtmlShredderVideoLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == HtmlShredderImageLoader.class) {
            return new HtmlShredderImageLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == VideoObjectContenturlLoader.class) {
            return new VideoObjectContenturlLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == MetaContenturlLoader.class) {
            return new MetaContenturlLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == MetacafeLoader.class) {
            return new MetacafeLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == LaGuardiaVideoLoader.class) {
            return new LaGuardiaVideoLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == NyLoader.class) {
            return new NyLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == NzzLoader.class) {
            return new NzzLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == OperavisionVideoLoader.class) {
            return new OperavisionVideoLoader(id, this.app, this.app.getOkHttpClient(), listener);
        }
        if (clazz == Copier.class) {
            return new Copier(id, this.app, listener);
        }
        if (clazz == SignalUpdateLoader.class) {
            return new SignalUpdateLoader(id, this.app.getOkHttpClient(), listener);
        }
        if (clazz == FrogPoxLoader.class) {
            return new FrogPoxLoader(id, this.app.getOkHttpClient(), listener);
        }
        throw new IllegalArgumentException("Unhandled class " + clazz);
    }
}

/*
 * UriPair.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.net.Uri;

import java.util.Objects;

import androidx.annotation.NonNull;

/**
 * A pair of a remote (https or http) and a local (content or file) uri which point to, essentially, the same data (in different locations of the universe, of course).
 */
public final class UriPair {

    /** a URI pointing to a local resource */
    @NonNull private final Uri local;
    /** a URI pointing to a remote resource */
    @NonNull private final Uri remote;

    /**
     * Constructor.
     * @param remote a remote Uri
     * @param local a local Uri
     */
    public UriPair(@NonNull Uri remote, @NonNull Uri local) {
        super();
        this.local = local;
        this.remote = remote;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UriPair)) return false;
        UriPair uriPair = (UriPair) o;
        return local.equals(uriPair.local) &&
                remote.equals(uriPair.remote);
    }

    @NonNull public Uri getLocal() {
        return local;
    }

    @NonNull public Uri getRemote() {
        return remote;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(local, remote);
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return "UriPair{" +
                "local=" + local +
                ", remote=" + remote +
                '}';
    }
}

/*
 * ResourceTooLargeException.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

/**
 * A resource is too large to be loaded.
 */
public class ResourceTooLargeException extends Exception {

    private final long size;

    ResourceTooLargeException(long size) {
        super("Resource is too large: " + size);
        this.size = size;
    }

    public long getSize() {
        return size;
    }
}

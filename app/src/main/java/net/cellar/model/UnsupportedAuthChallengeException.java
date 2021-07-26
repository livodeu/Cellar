/*
 * UnsupportedAuthChallengeException.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import java.io.IOException;

/**
 * An unsupported authorization challenge scheme has been received.
 */
public class UnsupportedAuthChallengeException extends IOException {

    public UnsupportedAuthChallengeException(String msg) {
        super(msg);
    }
}

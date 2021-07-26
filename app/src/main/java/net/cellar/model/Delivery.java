/*
 * Delivery.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Wraps an {@link Order} together with a return code, a file, a media type and possibly information about how to authenticate.
 */
public class Delivery {

    @NonNull private final Order order;
    private final int rc;
    @Nullable private final File file;
    @Nullable private final String mediaType;
    @Nullable private final Throwable e;
    @Nullable private final AuthenticateInfo authenticateInfo;

    /**
     * Constructor.
     * @param order Order that has been fulfilled
     * @param rc return code (HTTP-like or {@link net.cellar.LoaderService LoaderService} error constants &gt;= 1000)
     * @param file File
     * @param mediaType MIME type
     */
    public Delivery(@NonNull Order order, int rc, @Nullable File file, @Nullable String mediaType) {
        this(order, rc, file, mediaType, null, null);
    }

    /**
     * Constructor.
     * @param order Order that has been fulfilled
     * @param rc return code (HTTP-like or {@link net.cellar.LoaderService LoaderService} error constants &gt;= 1000)
     * @param file File
     * @param mediaType MIME type
     * @param authenticateInfo AuthenticateInfo originating in a HTTP 401 WWW-Authenticate response header
     */
    public Delivery(@NonNull Order order, int rc, @Nullable File file, @Nullable String mediaType, @Nullable AuthenticateInfo authenticateInfo) {
        this(order, rc, file, mediaType, null, authenticateInfo);
    }

    /**
     * Constructor.
     * @param order Order that has been fulfilled
     * @param rc return code (HTTP-like or {@link net.cellar.LoaderService LoaderService} error constants &gt;= 1000)
     * @param file File
     * @param mediaType MIME type
     * @param e Throwable
     */
    public Delivery(@NonNull Order order, int rc, @Nullable File file, @Nullable String mediaType, @Nullable Throwable e, @Nullable AuthenticateInfo authenticateInfo) {
        super();
        this.order = order;
        this.rc = rc;
        this.file = file;
        this.mediaType = mediaType;
        this.e = e;
        this.authenticateInfo = authenticateInfo;
    }

    @Nullable
    public AuthenticateInfo getAuthenticateInfo() {
        return authenticateInfo;
    }

    @Nullable public File getFile() {
        return file;
    }

    @Nullable public String getMediaType() {
        return mediaType;
    }

    @NonNull public Order getOrder() {
        return order;
    }

    public int getRc() {
        return rc;
    }

    @Nullable
    public Throwable getThrowable() {
        return e;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return "Delivery{" +
                "order=" + order +
                ", rc=" + rc +
                ", file=" + file +
                ", mediaType='" + mediaType + '\'' +
                ", e=" + e +
                ", " + authenticateInfo +
                '}';
    }

    /**
     * Wraps a scheme and a realm.<br>
     * See <ul>
     *     <li><a href="https://tools.ietf.org/html/rfc7617">https://tools.ietf.org/html/rfc7617</a></li>
     *     <li><a href="https://en.wikipedia.org/wiki/Basic_access_authentication">https://en.wikipedia.org/wiki/Basic_access_authentication</a></li>
     * </ul>
     */
    public static class AuthenticateInfo {
        @NonNull public final String scheme;
        @NonNull public final String realm;
        @Nullable public final String userid;

        /**
         * Parses a HTTP WWW-Authenticate header (which has probably been received along with a 401 status code).
         * @param wwwAuthenticate a HTTP WWW-Authenticate header value ("Basic realm=localhost")
         * @return AuthenticateInfo
         * @throws IllegalArgumentException if the header is malformed
         * @throws NullPointerException if {@code wwwAuthenticate} is {@code null}
         */
        @NonNull
        public static AuthenticateInfo parseWwwAuthenticate(@NonNull String wwwAuthenticate) throws IllegalArgumentException {
            int space = wwwAuthenticate.indexOf(' ');
            if (space <= 0) {
                throw new IllegalArgumentException("Malformed WWW-Authenticate \"" + wwwAuthenticate + "\"");
            }
            final String type = wwwAuthenticate.substring(0, space);
            String realm = wwwAuthenticate.substring(space + 1).trim();
            if (!realm.startsWith("realm=")) {
                throw new IllegalArgumentException("Malformed WWW-Authenticate \"" + wwwAuthenticate + "\"");
            }
            realm = realm.substring(6).trim();
            return new AuthenticateInfo(type, realm);
        }

        /**
         * Constructor.
         * @param scheme scheme, e.g. "Basic"
         * @param realm realm, e.g. "localhost"
         */
        public AuthenticateInfo(@NonNull String scheme, @NonNull String realm) {
            this(scheme, realm, null);
        }

        /**
         * Constructor.
         * @param scheme scheme, e.g. "Basic"
         * @param realm realm, e.g. "localhost"
         * @param userid user id (optional)
         */
        public AuthenticateInfo(@NonNull String scheme, @NonNull String realm, @Nullable String userid) {
            super();
            this.scheme = scheme;
            this.realm = realm;
            this.userid = userid;
        }

        /** {@inheritDoc} */
        @Override
        @NonNull
        public String toString() {
            return "AuthenticateInfo{" +
                    "scheme='" + scheme + '\'' +
                    ", realm='" + realm + '\'' +
                    '}';
        }
    }
}

/*
 * UriUtil.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import net.cellar.App;
import net.cellar.model.Credential;

import java.io.File;

public final class UriUtil {

    @Nullable
    public static String extractUrl(@Nullable final String s) {
        if (s == null) return null;
        final String sl = s.toLowerCase(java.util.Locale.US);
        for (String ss : App.SUPPORTED_PREFIXES) {
            int index = sl.indexOf(ss);
            if (index < 0) continue;
            String candidate = s.substring(index).trim();
            index = candidate.indexOf('"');
            if (index > 0) candidate = candidate.substring(0, index);
            return candidate;
        }
        return null;
    }

    @Nullable
    public static File fromUri(@Nullable Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            String s = uri.toString();
            return new File(s.substring(7));
        }
        return null;
    }

    /**
     * Attempts to extract a Credential from a Uri given as<br>
     * <pre>
     * scheme://user:password@host/path/resource<br>
     *    ↑      ↑             ↑
     * scheme, user, and      host
     * </pre>
     * must be present; otherwise {@code null} is returned.<br>
     * Note: The {@link Credential#getPassword() password} of the returned Credential may be empty (a.k.a. {@code null})!<br>
     * "http", "https", "ftp" and "sftp" are supported.
     * @param uri Uri
     * @return Credential or {@code null}
     */
    @Nullable
    public static Credential getCredential(@Nullable final Uri uri) {
        if (uri == null) return null;
        final String scheme = uri.getScheme();
        if (scheme == null) return null;
        final String host = uri.getHost();
        if (host == null) return null;
        final String userInfo = uri.getUserInfo();
        // See Uri.AbstractHierarchicalUri.parseUserInfo()
        if (userInfo == null) return null;
        int colon = userInfo.indexOf(':');
        final String user = colon > 0 ? userInfo.substring(0, colon) : userInfo;
        if (TextUtils.isEmpty(user)) return null;
        final String pwd = colon > 0 ? userInfo.substring(colon + 1) : null;
        switch (scheme) {
            case "ftp": return new Credential(host, user, pwd, Credential.TYPE_FTP);
            case "sftp": return new Credential(host, user, pwd, Credential.TYPE_SFTP);
            case "http":
            case "https": return new Credential(host, user, pwd, Credential.TYPE_HTTP_BASIC);
        }
        return null;
    }

    /**
     * Determines whether the given Uri points to a resource that is not on the device.
     * @param uri Uri
     * @return {@code true} / {@code false}
     */
    public static boolean isRemoteUri(@Nullable final Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        return isRemoteUrl(uri.toString());
    }

    /**
     * Checks whether the given String represents a uri pointing to a remote resource.
     * @param questionable potential uri
     * @return {@code true} if the given String seems to be a uri pointing to a remote resource
     */
    public static boolean isRemoteUrl(@Nullable final String questionable) {
        if (questionable == null) return false;
        final String sl = questionable.toLowerCase(java.util.Locale.US);
        for (String ss : App.SUPPORTED_REMOTE_PREFIXES) if (sl.startsWith(ss) && sl.length() > ss.length()) return true;
        return false;
    }

    /**
     * Tests whether the given uri scheme might be part of a uri that points to a resource that is stored on the device.
     * @param questionable uri scheme
     * @return {@code true} / {@code false}
     */
    public static boolean isSupportedLocalScheme(@Nullable final String questionable) {
        for (String scheme : App.SUPPORTED_LOCAL_SCHEMES) {
            if (scheme.equals(questionable)) return true;
        }
        return false;
    }

    /**
     * Tests whether the given uri scheme might be part of a uri that points to a resource that is stored in the interweb.
     * @param questionable uri scheme
     * @return {@code true} / {@code false}
     */
    public static boolean isSupportedRemoteScheme(@Nullable final String questionable) {
        for (String scheme : App.SUPPORTED_REMOTE_SCHEMES) {
            if (scheme.equals(questionable)) return true;
        }
        return false;
    }

    /**
     * Checks whether the given String represents a uri.
     * @param questionable potential uri
     * @return {@code true} if the given String seems to be a uri
     */
    public static boolean isUrl(@Nullable final String questionable) {
        if (questionable == null) return false;
        final String sl = questionable.toLowerCase(java.util.Locale.US);
        for (String ss : App.SUPPORTED_PREFIXES) if (sl.startsWith(ss)) return true;
        return false;
    }

    private UriUtil() {
    }
}

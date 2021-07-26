/*
 * Vcard.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.text.TextUtils;

import androidx.annotation.NonNull;

/**
 *
 *  For test data, see <a href="https://github.com/mangstadt/ez-vcard/tree/master/src/test/resources/ezvcard/io/text">here</a>.
 */
public class Vcard {

    private final String email;
    private final String fn;
    private final String tel;
    private String url;
    private String adr;

    /**
     * Constructor.
     * @param fn full name
     * @param email email address
     * @param tel telephone number
     */
    public Vcard(String fn, String email, String tel) {
        super();
        this.fn = fn;
        this.email = email;
        this.tel = tel;
    }

    public void setAdr(String adr) {
        this.adr = adr;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(128).append("BEGIN:VCARD\nVERSION:4.0\n");
        if (!TextUtils.isEmpty(this.fn)) sb.append("FN:").append(this.fn).append('\n');
        if (!TextUtils.isEmpty(this.email)) sb.append("EMAIL:").append(this.email).append('\n');
        if (!TextUtils.isEmpty(this.tel)) sb.append("TEL;:").append(this.tel).append('\n');
        if (!TextUtils.isEmpty(this.url)) sb.append("URL:").append(this.url).append('\n');
        if (!TextUtils.isEmpty(this.adr)) sb.append("ADR:").append(this.adr).append('\n');
        sb.append("END:VCARD");
        return sb.toString();
    }
}

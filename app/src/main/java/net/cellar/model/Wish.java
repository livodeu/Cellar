/*
 * Wish.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import net.cellar.supp.Replacer;
import net.cellar.supp.UriHandler;
import net.cellar.supp.Util;

import java.util.Collection;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * A Wish is the result of parsing an Intent.<br>
 * An Intent can hold information in various fields - these are extracted and then stored in an instance of this class.
 */
public final class Wish implements Parcelable {

    public static final Creator<Wish> CREATOR = new Creator<Wish>() {
        @Override
        public Wish createFromParcel(Parcel in) {
            return new Wish(in);
        }

        @Override
        public Wish[] newArray(int size) {
            return new Wish[size];
        }
    };
    @VisibleForTesting
    public static final boolean UNAMP_ENABLED = true;
    /** separates individual attributes in the {@link #toString() String} representation */
    private static final char SEP = '⁑';
    /** replaces references to amp resources with the original ones */
    private static final Replacer UNAMPER = new Replacer(new String[] {".+?/amp/s/", ".+?/amp/"}, "https://", Replacer.MODE_ONLY_FIRST, false);

    /**
     * Checks whether a given Collection of Recipes contains a given Wish.
     * @param wishes Collection of Recipes to search
     * @param r Wish to find
     * @return true / false
     */
    public static boolean contains(@Nullable final Collection<Wish> wishes, @Nullable final Wish r) {
        if (wishes == null || r == null) return false;
        for (Wish wish : wishes) {
            if (r.equals(wish)) return true;
        }
        return false;
    }

    /**
     * Parses a String representation of a Wish.<br>
     * <b>This method may not be modified without adjusting {@link Wish#toString()}!</b>
     * @param s String representation of a Wish
     * @return Wish
     * @throws NullPointerException if {@code s} is {@code null}
     */
    @NonNull
    public static Wish fromString(@NonNull String s) {
        final StringTokenizer st = new StringTokenizer(s, String.valueOf(SEP));
        Uri uri = null;
        String mime = null;
        CharSequence title = null;
        CharSequence referer = null;
        long timestamp = 0L;
        boolean held = false;
        CharSequence fileName = null;
        UriHandler uriHandler = null;
        for (int i = 0; st.hasMoreTokens(); i++) {
            String t = st.nextToken();
            if (t == null || "null".equals(t)) continue;
            switch (i) {
                case 0: uri = Uri.parse(t); break;
                case 1: mime = t; break;
                case 2: title = t; break;
                case 3: referer = t; break;
                case 4: timestamp = Util.parseLong(t, 0L); break;
                case 5: held = Util.parseInt(t, 0) == 1; break;
                case 6: fileName = t; break;
                case 7: uriHandler = UriHandler.fromString(t);
            }
        }
        if (uri == null) throw new NullPointerException("Null Uri!");
        final Wish wish = new Wish(uri, title);
        wish.setMime(mime);
        wish.setReferer(referer);
        wish.setTimestamp(timestamp);
        wish.setHeld(held);
        wish.setFileName(fileName);
        wish.setUriHandler(uriHandler);
        return wish;
    }

    /**
     * Invokes toString() on the given Object provided it does not contain any <i>dangerous</i> chars in which case {@code null} is returned.<br>
     * Chars are dangerous if they cannot be serialized/deserialized without casualties.<br>
     * Actually, considering the way it is used here, this method should almost always return {@code o.toString()} if o is non-{@code null}.
     * @param o Object to, ahem, stringify (or stringificate?)
     * @return String representation of the given Object or {@code null}
     */
    @Nullable
    private static String safe(@Nullable Object o) {
        if (o == null) return null;
        final String s = o.toString();
        return (s.indexOf(SEP) < 0 && s.indexOf('\n') < 0) ? s : null;
    }

    @NonNull
    private static Uri unamp(@NonNull Uri uri) {
        if (!UNAMP_ENABLED) return uri;
        final String s = uri.toString().toLowerCase(java.util.Locale.US);
        if (s.startsWith("https://www.google.com/amp/") || s.startsWith("https://www.bing.com/amp/")) {
            uri = Uri.parse(UNAMPER.replace(s));
        }
        return uri;
    }

    /** the Uri to load from */
    @NonNull private Uri uri;
    /** the MIME type of the data that {@link #uri} points to */
    private String mime;
    /** a descriptive title */
    private CharSequence title;
    /** a special prescription for how to deal with the Uri (optional) */
    private UriHandler uriHandler;
    private long timestamp;
    private CharSequence referer;
    private boolean held;
    /** the name of the local file - usually {@code null} */
    private CharSequence fileName;

    /**
     * Constructor.<br>
     * <b>This constructor may not be modified without adjusting {@link #writeToParcel(Parcel, int)}!</b>
     * @param in Parcel
     */
    private Wish(final Parcel in) {
        super();
        String uris = in.readString();
        this.uri = Uri.parse(uris);
        this.mime = in.readString(); if (this.mime != null && this.mime.length() == 0) this.mime = null;
        this.title = in.readString(); if (this.title != null && this.title.length() == 0) this.title = null;
        this.referer = in.readString(); if (this.referer != null && this.referer.length() == 0) this.referer = null;
        this.timestamp = in.readLong();
        this.held = in.readInt() > 0;
        this.fileName = in.readString(); if (this.fileName != null && this.fileName.length() == 0) this.fileName = null;
        String urihs = in.readString();
        this.uriHandler = urihs != null ? UriHandler.fromString(urihs) : null;
    }

    /**
     * Constructor.
     * @param uri uri
     */
    public Wish(@NonNull Uri uri) {
        super();
        this.uri = unamp(uri);
    }

    /**
     * Constructor.
     * @param uri uri
     * @param title title
     */
    public Wish(@NonNull Uri uri, @Nullable CharSequence title) {
        this(uri);
        this.title = title;
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Wish wish = (Wish) o;
        return Objects.equals(this.uri, wish.uri);
    }

    /**
     * Copies all attributes from the given Wish to {@code this} one.
     * @param source Wish
     */
    public void fill(final Wish source) {
        if (source == null) return;
        this.uri = source.uri;
        this.mime = source.mime;
        this.title = source.title;
        this.timestamp = source.timestamp;
        this.held = source.held;
        this.referer = source.referer;
        this.fileName = source.fileName;
        this.uriHandler = source.uriHandler;
    }

    /**
     * Returns the name of the local file, if set.<br>
     * This is usually {@code null}, but may have been set if the host supplied a specific file name.
     * @return the file name or {@code null}
     */
    @Nullable
    public CharSequence getFileName() {
        return fileName;
    }

    public String getMime() {
        return mime;
    }

    public CharSequence getReferer() {
        return referer;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public CharSequence getTitle() {
        return title;
    }

    @NonNull
    public Uri getUri() {
        return uri;
    }

    public UriHandler getUriHandler() {
        return uriHandler;
    }

    public boolean hasTitle() {
        return !TextUtils.isEmpty(this.title);
    }

    public boolean hasUri() {
        return !this.uri.equals(Uri.EMPTY);
    }

    public boolean hasUriHandler() {
        return this.uriHandler != null;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(this.uri);
    }

    public boolean isHeld() {
        return this.held;
    }

    public void setFileName(@Nullable CharSequence fileName) {
        this.fileName = fileName;
    }

    public boolean hasFileName() {
        return !TextUtils.isEmpty(this.fileName);
    }

    /**
     * Sets the flag that controls deferment.
     * @param held {@code true} or {@code false}
     */
    public void setHeld(boolean held) {
        this.held = held;
    }

    public void setMime(String mime) {
        this.mime = safe(mime);
    }

    public void setReferer(CharSequence referer) {
        this.referer = referer;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setTitle(CharSequence title) {
        this.title = safe(title);
    }

    /**
     * Sets the Uri.
     * @param uri Uri (required)
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    public void setUri(Uri uri) {
        if (uri == null) throw new NullPointerException("Null Uri passed!");
        this.uri = unamp(uri);
    }

    public void setUriHandler(@Nullable UriHandler uriHandler) {
        this.uriHandler = uriHandler;
    }

    /** {@inheritDoc}<br>
     * <b>This method may not be modified without adjusting {@link #fromString(String)}!</b>
     */
    @Override
    @NonNull
    public String toString() {
        return uri.toString() + SEP + mime + SEP + title + SEP + referer + SEP + timestamp + SEP + (held ? "1" : "0") + SEP + fileName + SEP + uriHandler;
    }

    /** {@inheritDoc}<br>
     * <b>This method may not be modified without adjusting the constructor {@link #Wish(Parcel)}!</b>
     */
    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeString(uri.toString());
        dest.writeString(mime != null ? mime : "");
        dest.writeString(title != null ? title.toString() : "");
        dest.writeString(referer != null ? referer.toString() : "");
        dest.writeLong(timestamp);
        dest.writeInt(held ? 1 : 0);
        dest.writeString(fileName != null ? fileName.toString() : null);
        dest.writeString(uriHandler != null ? uriHandler.toString() : null);
    }
}

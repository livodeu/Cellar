/*
 * Order.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.supp.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 *
 */
public class Order implements Parcelable {

    public static final @NonNull Creator<Order> CREATOR = new Creator<Order>() {

        public Order createFromParcel(Parcel source) {
            return new Order(source);
        }

        public Order[] newArray(int size) {
            return new Order[size];
        }
    };

    /** if {@link #getGroup()} returns this value, the Order does not belong to a group */
    public static final long NO_ORDER_GROUP = 0L;

    /**
     * Removes user id and password from an Order.
     * @param order Order
     * @return Order
     */
    @NonNull
    public static Order stripCredentials(@NonNull final Order order) {
        final Wish wish = order.getWish();
        final Uri bad = Uri.parse(order.getUrl());
        String encodedAuthority = bad.getEncodedAuthority();
        if (encodedAuthority == null) return order;
        encodedAuthority = encodedAuthority.substring(encodedAuthority.lastIndexOf('@') + 1);
        Uri strippedUri = bad.buildUpon().encodedAuthority(encodedAuthority).build();
        final Order stripped = wish != null ? new Order(wish, strippedUri) : new Order(strippedUri);
        stripped.destinationFolder = order.destinationFolder;
        stripped.destinationFilename = order.destinationFilename;
        stripped.fileSize = order.fileSize;
        stripped.mime = order.mime;
        stripped.referer = order.referer;
        stripped.group = order.group;
        if (order.additionalAudioUrls != null) {
            stripped.additionalAudioUrls = new ArrayList<>(order.additionalAudioUrls);
        }
        return stripped;
    }

    /**
     * Replaces "http://" with "https://". Does not do anything if the scheme is something other than http.
     * @param http Order with a HTTP url
     * @return Order with a HTTPS url
     */
    @NonNull
    public static Order toHttps(@NonNull final Order http) {
        Wish httpWish = http.getWish();
        String httpUrl = http.getUrl();
        if (!httpUrl.startsWith("http://")) return http;
        String httpsUrl = "https" + httpUrl.substring(4);
        final Order https;
        if (httpWish != null) {
            https = new Order(httpWish, Uri.parse(httpsUrl));
        } else {
            https = new Order(Uri.parse(httpsUrl));
        }
        https.destinationFolder = http.destinationFolder;
        https.destinationFilename = http.destinationFilename;
        https.fileSize = http.fileSize;
        https.mime = http.mime;
        https.referer = http.referer;
        https.group = http.group;

        if (http.additionalAudioUrls != null) {
            https.additionalAudioUrls = new ArrayList<>(http.additionalAudioUrls);
        }
        return https;
    }

    @Nullable private final Wish wish;
    @NonNull private final Uri uri;
    /** result of uri{@link #uri#toString()} */
    @NonNull private final String url;
    private String destinationFolder;
    private String destinationFilename;
    private long fileSize;
    private String mime;
    /** Referer header to add */
    private String referer;
    @Nullable private List<String> additionalAudioUrls;
    /** an optional group id (that several orders belong to) - meant to be passed to a {@link android.app.Notification.Builder#setGroup(String) Notification} */
    private long group = NO_ORDER_GROUP;

    /**
     * Constructor.
     * @param uri Uri
     */
    public Order(@NonNull Uri uri) {
        super();
        this.wish = null;
        this.uri = uri;
        this.url = uri.toString();
    }

    /**
     * Constructor.
     * @param wish Wish
     */
    public Order(@NonNull Wish wish) {
        super();
        this.wish = wish;
        this.uri = wish.getUri();
        this.url = this.uri.toString();
        this.referer = wish.getReferer() != null ? wish.getReferer().toString() : null;
    }

    /**
     * Constructor.
     * @param wish Wish
     * @param uri Uri
     */
    public Order(@Nullable Wish wish, @NonNull Uri uri) {
        super();
        this.wish = wish;
        this.uri = uri;
        if (BuildConfig.DEBUG && wish != null && wish.getUri().equals(uri)) Log.e(getClass().getSimpleName(), "Got Wish and Uri with same contents!");
        this.url = this.uri.toString();
        this.referer = wish != null && wish.getReferer() != null ? wish.getReferer().toString() : null;
    }

    /**
     * Constructor.
     * @param in Parcel
     */
    public Order(@NonNull final Parcel in) {
        super();
        String uris = in.readString();
        this.uri = Uri.parse(uris);
        //if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "Order(in): uri=\""+uri + "\" ("+uri.getClass() + ")");
        this.url = Objects.requireNonNull(in.readString());
        this.destinationFolder = in.readString();
        this.destinationFilename = in.readString();
        this.fileSize = in.readLong();
        this.mime = in.readString();
        this.referer = in.readString();
        this.group = in.readLong();
        String wishs = in.readString();
        this.wish = wishs != null ? Wish.fromString(wishs) : null;
        if (this.additionalAudioUrls == null) this.additionalAudioUrls = new ArrayList<>();
        in.readStringList(this.additionalAudioUrls);
    }

    /*
    @NonNull
    @Override
    public Order clone() throws CloneNotSupportedException {
        final Order klon = (Order)super.clone();
        klon.destinationFilename = destinationFilename;
        klon.destinationFolder = destinationFolder;
        klon.mime = mime;
        klon.referer = referer;
        klon.group = group;
        if (additionalAudioUrls != null) klon.additionalAudioUrls = new ArrayList<>(additionalAudioUrls);
        return klon;
    }*/

    public void addAudioUrl(@Nullable String url) {
        if (url == null) return;
        if (this.additionalAudioUrls == null) this.additionalAudioUrls = new ArrayList<>(4);
        this.additionalAudioUrls.add(url);
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
        if (!(o instanceof Order)) return false;
        Order order = (Order) o;
        return this.uri.equals(order.uri) &&
                Objects.equals(this.destinationFolder, order.destinationFolder) &&
                Objects.equals(this.destinationFilename, order.destinationFilename);
    }

    @NonNull
    public List<String> getAdditionalAudioUrls() {
        if (this.additionalAudioUrls == null) return new ArrayList<>(0);
        return this.additionalAudioUrls;
    }

    public String getDestinationFilename() {
        return this.destinationFilename;
    }

    public String getDestinationFolder() {
        return this.destinationFolder;
    }

    /**
     * Returns the size of the source data.
     * @return number of bytes of the source data or 0 if unknown
     */
    public long getFileSize() {
        return this.fileSize;
    }

    /**
     * Returns the (optional) group id.
     * @return group id or {@link #NO_ORDER_GROUP}
     */
    public long getGroup() {
        return this.group;
    }

    public String getMime() {
        return this.mime;
    }

    public String getReferer() {
        return referer;
    }

    @NonNull
    public Uri getUri() {
        return this.uri;
    }

    @NonNull
    public String getUrl() {
        return this.url;
    }

    @Nullable
    public Wish getWish() {
        return this.wish;
    }

    public boolean hasAdditionalAudioUrls() {
        return this.additionalAudioUrls != null && !this.additionalAudioUrls.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(this.uri, this.destinationFolder, this.destinationFilename);
    }

    /**
     * Sets the destination file.
     * @param destinationFolder absolute path of the destination folder
     * @param destinationFilename destination file name
     */
    public void setDestination(String destinationFolder, CharSequence destinationFilename) {
        this.destinationFolder = destinationFolder;
        this.destinationFilename = (destinationFilename != null ? destinationFilename.toString() : null);
    }

    public void setDestinationFilename(CharSequence destinationFilename) {
        this.destinationFilename = (destinationFilename != null ? destinationFilename.toString() : null);
    }

    public void setDestinationFolder(@NonNull String destinationFolder) {
        this.destinationFolder = destinationFolder;
    }

    /**
     * Sets the size of the source data.
     * @param fileSize number of bytes of the source data
     */
    public void setFileSize(@IntRange(from = 1) long fileSize) {
        this.fileSize = fileSize;
    }

    /**
     * Sets the order group,
     * which is an id (that several orders belong to) that is meant to be passed to a {@link android.app.Notification.Builder#setGroup(String) Notification}
     * @param group order group id or {@link #NO_ORDER_GROUP}
     */
    public void setGroup(long group) {
        this.group = group;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return "Order{" +
                "url='" + this.url + '\'' +
                ", destinationFolder='" + this.destinationFolder + '\'' +
                ", destinationFilename='" + this.destinationFilename + '\'' +
                ", mime='" + this.mime + '\'' +
                ", referer='" + this.referer + '\'' +
                ", group='" + this.group + '\'' +
                //", origin=" + this.origin +
                '}';
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(final Parcel dest, int flags) {
        //if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "writeToParcel(): uri=\""+uri+"\" (" + uri.getClass() +")");
        dest.writeString(uri.toString());
        dest.writeString(url);
        dest.writeString(destinationFolder);
        dest.writeString(destinationFilename);
        dest.writeLong(fileSize);
        dest.writeString(mime);
        dest.writeString(referer);
        dest.writeLong(group);
        dest.writeString(wish != null ? wish.toString() : null);
        if (additionalAudioUrls == null) additionalAudioUrls = new ArrayList<>(0);
        dest.writeStringList(additionalAudioUrls);
    }
}

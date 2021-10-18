/*
 * UriHandler.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import net.cellar.BuildConfig;
import net.cellar.worker.ArdVideoLoader;
import net.cellar.worker.FrogPoxLoader;
import net.cellar.worker.France24Loader;
import net.cellar.worker.HtmlShredderImageLoader;
import net.cellar.worker.HtmlShredderVideoLoader;
import net.cellar.worker.ImgurLoader;
import net.cellar.worker.LaGuardiaVideoLoader;
import net.cellar.worker.Loader;
import net.cellar.worker.MetaContenturlLoader;
import net.cellar.worker.NyLoader;
import net.cellar.worker.NzzLoader;
import net.cellar.worker.OperavisionVideoLoader;
import net.cellar.worker.SignalUpdateLoader;
import net.cellar.worker.SnooVideoLoader;
import net.cellar.worker.VideoObjectContenturlLoader;
import net.cellar.worker.YtVideoLoader;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

/**
 * Contains info how to deal with a uri.<br>
 * If {@link #hasLoader()} returns {@code true}, then the uri will be dealt with in a special way,
 * defined by the {@link #getLoaderClass() loader implementation}.<br>
 * Otherwise, the url will be dealt with in the standard way and
 * just the {@link #getUri() return uri} and {@link #getTitle() title} must be checked whether they have been set or modified here.
 */
public final class UriHandler implements Parcelable {

    public static final @NonNull Creator<UriHandler> CREATOR = new Creator<UriHandler>() {
        public UriHandler createFromParcel(Parcel source) {
            return new UriHandler(source);
        }

        public UriHandler[] newArray(int size) {
            return new UriHandler[size];
        }
    };

    /**
     * These should match:
     * <ul>
     * <li>https://www.ardmediathek.de/rbb/video/abendschau/nacktesenatoren/rbb-fernsehen/Ykasd8chHHAScsafhhas8lFHASHfuas/</li>
     * <li>https://www.ardmediathek.de/video/swr-retro-abendschau/edles-wuerfelhusten-design/swrfernsehen-de/Y3JpZDovL3N3ci5kZS9hZXgvbzExNjEwMTA/</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_ARD = Pattern.compile("https?://(www\\.)?ardmediathek\\.de/((\\w{1,4}/)?)video/.+");
    /**
     * This should match:
     * <ul>
     * <li>https://arstechnica.com/video/watch/the-pathetic-paper-plane-an-e-mobile-you-can-fly-on</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_ARSE = Pattern.compile("https?://(www\\.)?(arstechnica)\\.com/video/watch/.+");
    /**
     * These should match:
     * <ul>
     * <li>https://www.filmothek.bundesarchiv.de/video/123456</li>
     * <li>https://www.filmothek.bundesarchiv.de/video/987654?topic=mksdlm3s9d9me&start=00%3A01%3A04.01&end=00%3A02%3A47.07</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_BA = Pattern.compile("https?://www\\.filmothek\\.bundesarchiv\\.de/video/\\d{1,6}.*");
    /**
     * Something like this should match:
     * <ul>
     * <li>https://www.&lt;curdlenasty&gt;.com/video/watch/retardedyankeerantsaboutwahteverlikeyouknowlike</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_CN = Pattern.compile("https?://(www\\.)?((newyorker)|(vogue)|(wired))\\.com/video/watch/.+");

    /**
     * This should match:
     * <ul>
     * <li>https://www.dropbox.com/s/asd90dsai9sadjk/file.jpg?dl=0</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_POX = Pattern.compile("https?://www\\.dropbox\\.com/.*\\\\?dl=0");

    /**
     * This should match:
     * <ul>
     * <li>https://www.france24.com/en/tv-shows/up-to-moon/20210204-rocket-transport-reborn</li>
     * </ul>
     */
    public static final Pattern PATTERN_FRANCE24 = Pattern.compile("https?://(www\\.)?france24\\.com/\\w{1,2}/tv-shows/.+");
    /**
     * This should match:
     * <ul>
     * <li>https://gfycat.com/harmfulidioticuspresident</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_GFYCAT = Pattern.compile("https?://gfycat\\.com/\\w+");
    /**
     * These should match:
     * <ul>
     * <li>https://www.theguardian.com/&lt;category&gt;/video/&lt;year&gt;/&lt;mon&gt;/&lt;dd&gt;/some-video-title-is-given-here</li>
     * <li>https://www.theguardian.com/world/video/2011/dec/22/watch-this-video</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_GUARDIAN = Pattern.compile("https?://(www\\.)?theguardian\\.com/.+/video/.+");
    /**
     * These should match:
     * <ul>
     * <li>https://imgur.com/gallery/XDbj49Q</li>
     * <li>https://i.imgur.com/4kYsf7E.gifv</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_IMGUR = Pattern.compile("https?://(\\w\\.)?imgur\\.com/(gallery/)?((\\w+?\\.+\\w{1,})|(\\w{2,}))");
    /**
     * This should match:
     * <ul>
     * <li>https://www.loc.gov/item/00694289/</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_LOCGOV = Pattern.compile("https?://(www\\.)?loc\\.gov/item/.+");
    /**
     * This should match:
     * <ul>
     * <li>https://www.nzz.ch/video/nzz-format/video-title-here-xy.1234567</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_NZZ = Pattern.compile("https?://www\\.?nzz\\.ch/video/.+");
    /**
     * These should match:
     * <ul>
     * <li>https://operavision.eu/en/library/performances/operas/der-konische-opa-berlin</li>
     * <li>https://operavision.eu/fr/bibliotheque/spectacles/operas/der-kosmische-opa-berlin</li>
     * <li>https://operavision.eu/de/bibliothek/auffuehrungen/opern/der-komische-opa-berlin</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_OPERAVISION = Pattern.compile("https?://operavision\\.eu/((en/library/performances)|(fr/bibliotheque/spectacles)|(de/bibliothek/auffuehrungen))/.*");
    /**
     * This should match:
     * <ul>
     * <li>https://postimg.cc/N5kyc21w</li>
     * </ul>
     * This should NOT match:
     * <ul>
     * <li>https://postimg.cc/gallery/r0VKmc8</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_POSTIMG = Pattern.compile("https?://postimg\\.cc/\\w+");
    /**
     * These should match:
     * <ul>
     * <li>https://v.redd.it/39f1kp3grhb61/DASH_720.mp4?source=fallback</li>
     * <li>https://v.redd.it/fvcvyhic0ok61</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_RED = Pattern.compile("https?://v\\.redd\\.it/.+");

    /**
     * <ul>
     * <li>https://updates.signal.org/android/latest.json</li>
     * </ul>
     */
    public static final Pattern PATTERN_SIGNALUP = Pattern.compile("https?://updates\\.signal.org/android/latest\\.json");

    /**
     * These should match:
     * <ul>
     * <li>https://www.youtube.com/v/XAB1CabDcdEe?hl=bb_FF&amp;version=3&amp;rel=0&amp;autoplay=1</li>
     * <li>https://www.youtube.com/embed/AB1CabDcdEe?hl=aa_EE&version=3&rel=0&autoplay=1</li>
     * <li>https://www.youtube.com/watch?v=AB1CabDcdEe</li>
     * </ul>
     */
    public static final Pattern PATTERN_YOUTUBE = Pattern.compile("https?://(www\\.)?youtube\\.com/((v/)|(embed/)|(watch\\?v=))\\w{2,}.*");

    /**
     * This should match:
     * https://www.youtube.com/redirect?event=playlist_description&redir_token=whatever&q=https%3A%2F%2Fwww.host.net%2Fpath1%2Fpath2%2Ffile.txt
     */
    @VisibleForTesting
    public static final Pattern PATTERN_YOUTUBE_REDIR = Pattern.compile("https?://(www\\.)?youtube\\.com/redirect\\?\\w{2,}.*");

    /**
     * This should match:
     * <ul>
     * <li>https://youtu.be/AB1CabDcdEe</li>
     * </ul>
     */
    @VisibleForTesting
    public static final Pattern PATTERN_YOUTU_BE = Pattern.compile("https?://youtu\\.be/\\w+.+");

    /**
     * This should match https://zvideox.net/watch/videoid/…
     */
    @VisibleForTesting
    public static final Pattern PATTERN_ZVIDEOX = Pattern.compile("https?://zvideox\\.net/watch/.+");

    /** separates individual elements */
    private static final String SEP = "⁜";
    private static final String TAG = "UriHandler";

    /**
     * Creates an UriHandler based on the given Uri.
     * @param uri original Uri to be checked
     * @return UriHandler
     */
    @Nullable
    public static UriHandler checkUri(@NonNull Uri uri) {
        String uris = uri.toString();
        if (uris.contains(SEP)) return null;
        String host = uri.getHost();
        if (host == null) return null;
        if (host.endsWith(".")) {
            // "thatfilehoster.com." -> "thatfilehoster.com"
            String fixedhost = host.substring(0, host.length() - 1);
            uri = Uri.parse(uris.replace(host, fixedhost));
            host = fixedhost;
            uris = uri.toString();
        }
        final List<String> ps = uri.getPathSegments();
        final int nPathSegments = ps != null ? ps.size() : 0;

        // scheme://host/path?query#fragment

        if (PATTERN_YOUTU_BE.matcher(uris).matches()) {
            return new UriHandler(Uri.parse(YtVideoLoader.PREFIX + uri.getLastPathSegment()), YtVideoLoader.class, null);
        } else if (PATTERN_YOUTUBE.matcher(uris).matches()) {
            String v = uri.getQueryParameter("v");
            if (TextUtils.isEmpty(v)) {
                // like https://www.youtube.com/v/XAB1CabDcdEe?hl=bb_FF&amp;version=3&amp;rel=0&amp;autoplay=1, https://www.youtube.com/embed/AB1CabDcdEe?hl=aa_EE&version=3&rel=0&autoplay=1
                v = ps != null ? ps.get(1) : null;
            }
            if (TextUtils.isEmpty(v)) return new UriHandler(uri, null, null);
            return new UriHandler(Uri.parse(YtVideoLoader.PREFIX + v), YtVideoLoader.class, null);
        } else if (PATTERN_YOUTUBE_REDIR.matcher(uris).matches()) {
            // https://www.youtube.com/redirect?event=playlist_description&redir_token=whatever&q=https%3A%2F%2Fwww.host.net%2Fpath%2Ffile.txt
            String q = uri.getQueryParameter("q");
            if (q != null && (q.startsWith("http://") || q.startsWith("https://"))) {
                return new UriHandler(Uri.parse(q), null, null);
            }
        } else if (PATTERN_ZVIDEOX.matcher(uris).matches()) {
            if (ps != null && ps.size() >= 2) {
                return new UriHandler(Uri.parse(YtVideoLoader.PREFIX + ps.get(1)), YtVideoLoader.class, null);
            }
        } else if (PATTERN_IMGUR.matcher(uris).matches()) {
             return new UriHandler(uri, ImgurLoader.class, null);
        } else if (PATTERN_GFYCAT.matcher(uris).matches()) {
            return new UriHandler(uri, HtmlShredderVideoLoader.class, uri.getLastPathSegment());
        } else if (PATTERN_OPERAVISION.matcher(uris).matches()) {
            return new UriHandler(uri, OperavisionVideoLoader.class, uri.getLastPathSegment());
        } else if (PATTERN_GUARDIAN.matcher(uris).matches()) {
            return new UriHandler(uri, LaGuardiaVideoLoader.class, uri.getLastPathSegment());
        } else if (PATTERN_POSTIMG.matcher(uris).matches()) {
            return new UriHandler(uri, HtmlShredderImageLoader.class, null);
        } else if (PATTERN_RED.matcher(uris).matches()) {
            return new UriHandler(uri, SnooVideoLoader.class, null);
        } else if (PATTERN_NZZ.matcher(uris).matches()) {
            String title = uri.getLastPathSegment();
            return new UriHandler(uri, NzzLoader.class, title != null ? Util.removeExtension(title) : null);
        } else if (PATTERN_LOCGOV.matcher(uris).matches()) {
            return new UriHandler(uri, HtmlShredderVideoLoader.class, null);
        } else if (PATTERN_CN.matcher(uris).matches()) {
            return new UriHandler(uri, NyLoader.class, uri.getLastPathSegment());
        } else if (PATTERN_ARSE.matcher(uris).matches()) {
            return new UriHandler(uri, MetaContenturlLoader.class, uri.getLastPathSegment());
        } else if (PATTERN_FRANCE24.matcher(uris).matches()) {
            return new UriHandler(uri, France24Loader.class, uri.getLastPathSegment());
        } else if (PATTERN_BA.matcher(uris).matches()) {
            return new UriHandler(uri, HtmlShredderVideoLoader.class, null);
        } else if (PATTERN_POX.matcher(uris).matches()) {
            return new UriHandler(uri, FrogPoxLoader.class, null);
        } else if (PATTERN_ARD.matcher(uris).matches()) {
            return new UriHandler(uri, ArdVideoLoader.class, null);
        } else if (PATTERN_SIGNALUP.matcher(uris).matches()) {
            return new UriHandler(uri, SignalUpdateLoader.class, null);
        } else {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                md.update(host.getBytes());
                String hosthash = Util.asHex(md.digest()).toString();
                if ("8e7ff80271b43826397405c39f6607330949d224".equals(hosthash) && nPathSegments == 2 && "watch".equals(ps.get(0))) {
                    return new UriHandler(uri, VideoObjectContenturlLoader.class, uri.getLastPathSegment());
                } else if ("430a1da0deda2d7471b22309180d6735f6605870".equals(hosthash) && nPathSegments == 1) {
                    return new UriHandler(uri, MetaContenturlLoader.class, uri.getLastPathSegment());
                }
            } catch (NoSuchAlgorithmException ignored) {
            }
        }
        // uri should be handled in a standard way
        return null;
    }

    /**
     * @param s String representation of an instance of this class
     * @return UriHandler
     */
    @Nullable
    public static UriHandler fromString(String s) {
        if (s == null) return null;
        StringTokenizer st = new StringTokenizer(s, SEP);
        if (st.countTokens() != 3) return null;
        Uri uri = null;
        Class<? extends Loader> clazz = null;
        CharSequence title = null;
        try {
            for (int i = 0; st.hasMoreTokens(); i++) {
                String t = st.nextToken();
                if ("null".equals(t)) t = null;
                if (i == 0) {
                    uri = (t != null ? Uri.parse(t) : null);
                } else if (i == 1) {
                    //noinspection unchecked
                    clazz = t != null && t.length() > 0 ? (Class<? extends Loader>) Class.forName(t) : null;
                } else if (i == 2) {
                    title = t;
                }
            }
            if (uri == null) throw new IllegalArgumentException("Null uri!");
            return new UriHandler(uri, clazz, title);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While unpacking \"" + s + "\": " + e.toString(), e);
        }
        return null;
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
        return (!s.contains(SEP) && s.indexOf('\n') < 0) ? s : null;
    }

    /** the Uri to deal with */
    @NonNull private final Uri uri;
    /** the Loader class that should deal with the Uri */
    @Nullable private final Class<? extends Loader> loader;
    /** an optional title */
    @Nullable private final CharSequence title;

    /**
     * Constructor.
     * @param uri the Uri to deal with
     * @param loader the Loader class that should deal with the Uri
     * @param title an optional title
     */
    private UriHandler(@NonNull Uri uri, @Nullable Class<? extends Loader> loader, @Nullable CharSequence title) {
        super();
        this.uri = uri;
        this.loader = loader;
        this.title = safe(title);
    }

    /**
     * Constructor.
     * @param in Parcel
     */
    public UriHandler(Parcel in) {
        super();
        String s = in.readString();
        StringTokenizer st = new StringTokenizer(s, SEP);
        Uri uri = null;
        Class<? extends Loader> clazz = null;
        CharSequence title = null;
        try {
            for (int i = 0; st.hasMoreTokens(); i++) {
                String t = st.nextToken();
                if ("null".equals(t)) t = null;
                if (i == 0) {
                    uri = (t != null ? Uri.parse(t) : null);
                } else if (i == 1) {
                    //noinspection unchecked
                    clazz = t != null ? (Class<? extends Loader>) Class.forName(t) : null;
                } else if (i == 2) {
                    title = t;
                }
            }
            if (uri == null) throw new IllegalArgumentException("Null uri!");
            this.uri = uri;
            this.loader = clazz;
            this.title = title;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While unpacking \"" + s + "\": " + e.toString());
            throw new RuntimeException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(toString());
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return Class that will handle the download of the data that the uri refers to
     */
    @Nullable public Class<? extends Loader> getLoaderClass() {
        return this.loader;
    }

    @Nullable public CharSequence getTitle() {
        return this.title;
    }

    /**
     * The Uri that this Urihandler deals with - may be the same as the one given originally, may also be different.
     * @return Uri
     */
    @NonNull public Uri getUri() {
        return this.uri;
    }

    /**
     * @return true if a {@link Loader} has been identified that can download the data that the intent refers to
     */
    public boolean hasLoader() {
        return this.loader != null;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return this.uri + SEP + (this.loader != null ? this.loader.getName() : "") + SEP + this.title;
    }
}

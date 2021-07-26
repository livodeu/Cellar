/*
 * PlaylistItem.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model.pl;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import net.cellar.BuildConfig;
import net.cellar.R;
import net.cellar.supp.Util;

import java.util.Objects;
import java.util.StringTokenizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Representation of a "#EXT-X-STREAM-INF:" entry in a playlist.
 */
public class PlaylistItem {

    /** for debugging only */
    private static int NEXTID = 1;
    public static final String EXTXSTREAMINF = "#EXT-X-STREAM-INF:";
    private static final String TAG = "Item";
    private static final String[] AUDIO_CODECS = new String[] {"mp4a"};
    private static final String[] VIDEO_CODECS = new String[] {"avc1"};

    private final int id;
    @NonNull private Uri uri;
    /**
     * <pre>
     * The value is a decimal-integer of bits per second.  It represents
     * the peak segment bit rate of the Variant Stream.
     * </pre>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.3.4.2">https://tools.ietf.org/html/rfc8216#section-4.3.4.2</a>
     */
    private int bandWidth;
    /**
     * <pre>
     * The value is a decimal-integer of bits per second.  It represents
     * the average segment bit rate of the Variant Stream.
     * </pre>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.3.4.2">https://tools.ietf.org/html/rfc8216#section-4.3.4.2</a>
     */
    private int avgBandWidth;
    private int width;
    private int height;
    /**
     * <pre>
     * The value is a decimal-floating-point describing the maximum frame
     * rate for all the video in the Variant Stream, rounded to three
     * decimal places.
     * </pre>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.3.4.2">https://tools.ietf.org/html/rfc8216#section-4.3.4.2</a>
     */
    private float frameRate;
    private boolean supplyingVideo;
    private boolean supplyingAudio;
    private boolean mediaSegment;
    @Nullable private String extXMediaAudioGroupId;
    /**
     * <pre>
     * The value is a quoted-string.  It MUST match the value of the
     * GROUP-ID attribute of an EXT-X-MEDIA tag elsewhere in the Master
     * Playlist whose TYPE attribute is SUBTITLES.  It indicates the set
     * of subtitle Renditions that can be used when playing the
     * presentation.
     * </pre>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.3.4.2">https://tools.ietf.org/html/rfc8216#section-4.3.4.2</a>
     */
    @Nullable private String subtitles;
    /**
     * <pre>
     * The value is a quoted-string containing a comma-separated list of
     * formats, where each format specifies a media sample type that is
     * present in one or more Renditions specified by the Variant Stream.
     * Valid format identifiers are those in the ISO Base Media File
     * Format Name Space defined by "The 'Codecs' and 'Profiles'
     * Parameters for "Bucket" Media Types" [RFC6381].
     * </pre>
     * Example: "avc1.4D401E,mp4a.40.2"<br>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.3.4.2">https://tools.ietf.org/html/rfc8216#section-4.3.4.2</a>
     */
    @Nullable private String codecs;

    @Nullable
    public static PlaylistItem parseExtXStreamInf(@Nullable String extXStreamInf, @Nullable String url) {
        if (url == null || url.length() == 0) return null;
        // #EXT-X-STREAM-INF:BANDWIDTH=994144,AVERAGE-BANDWIDTH=967319,RESOLUTION=640x360,FRAME-RATE=25.000,CODECS="avc1.4D401E,mp4a.40.2",SUBTITLES="subtitles"
        // #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=638359,AVERAGE-BANDWIDTH=548359,AUDIO="A1.1+A2.1+A3.1-1266820252",SUBTITLES="T1-1266820252",FRAME-RATE=25.000,RESOLUTION=480x270
        if (extXStreamInf == null || !extXStreamInf.startsWith(EXTXSTREAMINF)) {
            PlaylistItem item = new PlaylistItem(Uri.parse(url));
            item.mediaSegment = true;
            return item;
        }
        final PlaylistItem item = new PlaylistItem(Uri.parse(url));
        String content = extXStreamInf.substring(EXTXSTREAMINF.length());
        StringTokenizer st = new StringTokenizer(content, ",", false);
        while (st.hasMoreTokens()) {
            StringBuilder part = new StringBuilder(st.nextToken());
            // example: CODECS="avc1.4d401f,mp4a.40.2,mp9v.123.3"
            if (part.toString().indexOf('"') > 0 && !part.toString().endsWith("\"")) {
                // the comma was part of the value
                while (st.hasMoreTokens()) {
                    // concatenate the remainder of the value to part
                    String nextpart = st.nextToken();
                    part.append(",").append(nextpart);
                    if (nextpart.endsWith("\"")) break;
                }
            }
            int eq = part.toString().indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            String val = part.substring(eq + 1).trim();
            switch (key) {
                case "RESOLUTION":
                    item.setResolution(val);
                    break;
                case "AVERAGE-BANDWIDTH":
                    item.setAvgBandWidth(val);
                    break;
                case "BANDWIDTH":
                    item.setBandWidth(val);
                    break;
                case "FRAME-RATE":
                    item.setFrameRate(val);
                    break;
                case "AUDIO":
                    if (val.startsWith("\"")) val = val.substring(1);
                    if (val.endsWith("\"")) val = val.substring(0, val.length() - 1);
                    item.setExtXMediaAudioGroupId(val);
                    break;
                case "SUBTITLES":
                    item.setSubtitles(val);
                    break;
                case "CODECS":
                    if (val.startsWith("\"")) val = val.substring(1);
                    if (val.endsWith("\"")) val = val.substring(0, val.length() - 1);
                    item.setCodecs(val);
                    final String[] codecs = val.split(",");
                    for (String codec : codecs) {
                        for (String ac : AUDIO_CODECS) {if (codec.startsWith(ac)) item.setAudio(); break;}
                        for (String vc : VIDEO_CODECS) {if (codec.startsWith(vc)) item.setVideo(); break;}
                    }
                    break;
            }
        }
        return item;
    }

    /**
     * Constructor.
     * @param uri Uri
     */
    public PlaylistItem(@NonNull Uri uri) {
        super();
        this.id = NEXTID++;
        this.uri = uri;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlaylistItem)) return false;
        PlaylistItem that = (PlaylistItem) o;
        return avgBandWidth == that.avgBandWidth &&
                width == that.width &&
                height == that.height &&
                Float.compare(that.frameRate, frameRate) == 0 &&
                uri.equals(that.uri) &&
                Objects.equals(extXMediaAudioGroupId, that.extXMediaAudioGroupId);
    }

    /**
     * <pre>
     AUDIO

     The value is a quoted-string.  It MUST match the value of the
     GROUP-ID attribute of an EXT-X-MEDIA tag elsewhere in the Master
     Playlist whose TYPE attribute is AUDIO.  It indicates the set of
     audio Renditions that SHOULD be used when playing the
     presentation.  See Section 4.3.4.2.1.

     The AUDIO attribute is OPTIONAL.
     </pre>
     */
    @Nullable
    public String getExtXMediaAudioGroupId() {
        return extXMediaAudioGroupId;
    }

    /**
     * @return Uri (<em>this might be a relative url!</em>)
     */
    @NonNull
    public Uri getUri() {
        return uri;
    }

    /**
     * A URI in a Playlist, whether it is a URI line or part of a tag, MAY be relative.<br>
     * Any relative URI is considered to be relative to the URI of the Playlist that contains it.<br>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.1">https://tools.ietf.org/html/rfc8216#section-4.1</a>
     * @return true / false
     */
    public boolean hasRelativeUri() {
        return uri.isRelative();
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(uri, avgBandWidth, width, height, frameRate, extXMediaAudioGroupId);
    }

    public boolean isMediaSegment() {
        return mediaSegment;
    }

    /**
     * Attempts to suggest a file extension based on the {@link #codecs} value.<br>
     * For example, a codecs value of "avc1.64001f, mp4a.40.2" would make this method return ".mp4".<br>
     * (Possible codec values are <a href="https://mp4ra.org/#/codecs">here</a>.)
     * @return file extension, including a leading '.', or {@code null}
     */
    @Nullable
    public String suggestFileExtension()  {
        if (codecs == null) {
            String lps = uri.getLastPathSegment();
            if (lps != null) {
                int dot = lps.lastIndexOf('.');
                if (dot > 0) return lps.substring(dot);
            }
            return null;
        }
        final String[] parts = codecs.split(",");
        boolean video = false, audio = false;
        for (String part : parts) {
            final String trimmedPart = part.trim();
            if (trimmedPart.startsWith("avc") || trimmedPart.startsWith("hev") || trimmedPart.startsWith("hvc") || trimmedPart.startsWith("vp0")
                    || trimmedPart.startsWith("mj") || trimmedPart.equals("s263") || trimmedPart.equals("vc-1")) {
                video = true;
            } else if (trimmedPart.equals("mp4a") || trimmedPart.startsWith("ac-") || trimmedPart.equals("alac") || trimmedPart.equals("alaw")
                    || trimmedPart.equals("ulaw") || trimmedPart.equals("Opus")) {
                audio = true;
            }
        }
        if (video) {
            return audio ? ".mp4" : ".m4v";
        }
        if (audio) return ".m4a";
        return null;
    }

    /**
     * Applies the given prefix to the uri if it is lacking a scheme.
     * @param prefix prefix to prepend
     */
    public void makeUriAbsolute(@NonNull String prefix) {
        if (this.uri.isAbsolute()) return;
        if (prefix.endsWith("/")) {
            this.uri = Uri.parse(prefix + this.uri.toString());
        } else {
            this.uri = Uri.parse(prefix + '/' + this.uri.toString());
        }
    }

    /**
     * Sets the {@link #supplyingAudio} flag to {@code true}.
     */
    private void setAudio() {
        this.supplyingAudio = true;
    }

    /**
     * Sets the avg. bandwidth value found in an <a href="https://datatracker.ietf.org/doc/html/rfc8216#section-4.3.4.2">"#EXT-X-STREAM-INF"</a> line.
     * @param bandWidth average bandwidth in bits per second
     */
    private void setAvgBandWidth(String bandWidth) {
        this.avgBandWidth = Util.parseInt(bandWidth, 0);
    }

    /**
     * Sets the bandwidth value found in an <a href="https://datatracker.ietf.org/doc/html/rfc8216#section-4.3.4.2">"#EXT-X-STREAM-INF"</a> line.
     * @param bandWidth bandwidth in bits per second, e.g. "4524312"
     */
    private void setBandWidth(String bandWidth) {
        this.bandWidth = Util.parseInt(bandWidth, 0);
    }

    /**
     * Sets the codecs information found in an <a href="https://datatracker.ietf.org/doc/html/rfc8216#section-4.3.4.2">"#EXT-X-STREAM-INF"</a> line.
     * @param codecs codecs, possibly comma-separated, e.g. "avc1.77.30, mp4a.40.2"
     */
    private void setCodecs(@Nullable String codecs) {
        this.codecs = codecs;
    }

    private void setExtXMediaAudioGroupId(@Nullable String extXMediaAudioGroupId) {
        this.extXMediaAudioGroupId = extXMediaAudioGroupId;
    }

    /**
     * Sets the frame rate information found in an <a href="https://datatracker.ietf.org/doc/html/rfc8216#section-4.3.4.2">"#EXT-X-STREAM-INF"</a> line.
     * @param frameRate frame rate
     */
    private void setFrameRate(String frameRate) {
        this.frameRate = Util.parseFloat(frameRate, 0f);
    }

    /**
     * Sets the (image) resolution value found in a "#EXT-X-STREAM-INF" line.
     * @param resolution resolution in pixels given as width'x'height, e.g. "1280x720"
     */
    private void setResolution(@Nullable String resolution) {
        if (resolution == null) {
            width = height = 0;
        } else {
            int x = resolution.toLowerCase(java.util.Locale.US).indexOf('x');
            if (x <= 0) {
                width = height = 0;
            }
            try {
                width = Integer.parseInt(resolution.substring(0, x));
                height = Integer.parseInt(resolution.substring(x + 1));
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, "While parsing resolution: " + e.toString());
                width = height = 0;
            }
        }
    }

    private void setSubtitles(@Nullable String subtitles) {
        this.subtitles = subtitles;
    }

    /**
     * Sets the {@link #supplyingVideo} flag to {@code true}.
     */
    private void setVideo() {
        this.supplyingVideo = true;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return "Item{" +
                "id=" + id +
                ", uri=" + uri +
                ", bandWidth=" + bandWidth +
                ", width=" + width +
                ", height=" + height +
                ", frameRate=" + frameRate +
                ", supplyingAudio=" + supplyingAudio +
                ", supplyingVideo=" + supplyingVideo +
                ", extXMediaAudioGroupId=" + extXMediaAudioGroupId +
                '}';
    }

    /**
     * Returns a String that can be displayed to the user (if ctx is non-null).
     * @param ctx Context (can be null but shouldn't)
     * @return a String that can be displayed to the user
     */
    @NonNull
    public String toUserString(@Nullable Context ctx) {
        if (ctx == null) return toString();
        final String suffix;
        if (supplyingAudio || extXMediaAudioGroupId != null) {
            if (supplyingVideo) {
                suffix = null;
            } else {
                suffix = ' ' + ctx.getString(R.string.label_playlist_audio);
            }
        } else if (supplyingVideo) {
            suffix = ' ' + ctx.getString(R.string.label_playlist_video);
        } else {
            suffix = null;
        }
        // #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=638359,AVERAGE-BANDWIDTH=548359,AUDIO="A1.1+A2.1+A3.1-510682780",SUBTITLES="T1-510682780",FRAME-RATE=25.000,RESOLUTION=480x270
        final int bw = avgBandWidth > 0 ? avgBandWidth : bandWidth;
        if (frameRate < 1) {
            if (width <= 0 || height <= 0) {
                if (bw > 0) {
                    // this is probably audio-only (like #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=56000,CODECS="mp4a.40.2")
                    return ctx.getString(R.string.label_playlistitem_bps_nofps_nosize, Math.round(bw / 1000f)) + (suffix != null ? suffix : "");
                } else {
                    return uri.toString();
                }
            }
            if (bw < 1) {
                return ctx.getString(R.string.label_playlistitem_nobps_nofps, width, height) + (suffix != null ? suffix : "");
            }
            return ctx.getString(R.string.label_playlistitem_bps_nofps, width, height, Math.round(bw / 1000f)) + (suffix != null ? suffix : "");
        }
        if (bw < 1) {
            return ctx.getString(R.string.label_playlistitem_fps_nobps, width, height, Math.round(frameRate)) + (suffix != null ? suffix : "");
        }
        return ctx.getString(R.string.label_playlistitem, width, height, Math.round(frameRate), Math.round(bw / 1000f)) + (suffix != null ? suffix : "");
    }
}

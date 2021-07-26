/*
 * M3UPlaylist.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model.pl;

import android.net.Uri;

import net.cellar.BuildConfig;
import net.cellar.supp.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Content-Type: application/x-mpegURL or application/vnd.apple.mpegurl<br>
 * <a href="https://tools.ietf.org/html/rfc8216">https://tools.ietf.org/html/rfc8216</a><br>
 * Examples:<br>
 * <hr>
 * <pre>
 #EXTM3U
 #EXT-X-VERSION:3
 #EXT-X-INDEPENDENT-SEGMENTS
 #EXT-X-STREAM-INF:BANDWIDTH=1910510,AVERAGE-BANDWIDTH=1826410,RESOLUTION=1024x576,FRAME-RATE=25.000,CODECS="avc1.4D401F,mp4a.40.2",SUBTITLES="subtitles"
 index_42.m3u8
 #EXT-X-STREAM-INF:BANDWIDTH=994144,AVERAGE-BANDWIDTH=967319,RESOLUTION=640x360,FRAME-RATE=25.000,CODECS="avc1.4D401E,mp4a.40.2",SUBTITLES="subtitles"
 index_43.m3u8
 #EXT-X-STREAM-INF:BANDWIDTH=737180,AVERAGE-BANDWIDTH=726410,RESOLUTION=512x288,FRAME-RATE=25.000,CODECS="avc1.4D4015,mp4a.40.2",SUBTITLES="subtitles"
 index_44.m3u8
 #EXT-X-STREAM-INF:BANDWIDTH=4806326,AVERAGE-BANDWIDTH=4541245,RESOLUTION=1280x720,FRAME-RATE=25.000,CODECS="avc1.4D401F,mp4a.40.2",SUBTITLES="subtitles"
 index_45.m3u8
 #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitles",NAME="English",DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,LANGUAGE="eng",URI="index_7_0.m3u8"
 </pre>
 * <hr>
 * <pre>
 #EXTM3U


 #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-510682780",NAME="TV Ton",LANGUAGE="deu",DEFAULT=YES,URI="https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/5/5.m3u8"
 #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-510682780",NAME="Originalton",LANGUAGE="mul",URI="https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/6/6.m3u8"
 #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-510682780",NAME="Audio-Deskription",LANGUAGE="deu",URI="https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/7/7.m3u8"

 #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-1266820252",NAME="TV Ton",LANGUAGE="deu",DEFAULT=YES,URI="https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/5/5.m3u8"
 #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-1266820252",NAME="Originalton",LANGUAGE="mul",URI="https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/6/6.m3u8"
 #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-1266820252",NAME="Audio-Deskription",LANGUAGE="deu",URI="https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/7/7.m3u8"

 #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="T1-510682780",NAME="Untertitel deutsch",CODECS="wvtt",LANGUAGE="deu",DEFAULT=YES,AUTOSELECT=YES,URI="https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/8/8.m3u8"

 #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="T1-1266820252",NAME="Untertitel deutsch",CODECS="wvtt",LANGUAGE="deu",DEFAULT=YES,AUTOSELECT=YES,URI="https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/8/8.m3u8"

 #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=638359,AVERAGE-BANDWIDTH=548359,AUDIO="A1.1+A2.1+A3.1-510682780",SUBTITLES="T1-510682780",FRAME-RATE=25.000,RESOLUTION=480x270
 https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/1/1.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=638359,AVERAGE-BANDWIDTH=548359,AUDIO="A1.1+A2.1+A3.1-1266820252",SUBTITLES="T1-1266820252",FRAME-RATE=25.000,RESOLUTION=480x270
 https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/1/1.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=1140619,AVERAGE-BANDWIDTH=936619,AUDIO="A1.1+A2.1+A3.1-510682780",SUBTITLES="T1-510682780",FRAME-RATE=25.000,RESOLUTION=640x360
 https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/2/2.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=1140619,AVERAGE-BANDWIDTH=936619,AUDIO="A1.1+A2.1+A3.1-1266820252",SUBTITLES="T1-1266820252",FRAME-RATE=25.000,RESOLUTION=640x360
 https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/2/2.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=2224446,AVERAGE-BANDWIDTH=1774446,AUDIO="A1.1+A2.1+A3.1-510682780",SUBTITLES="T1-510682780",FRAME-RATE=25.000,RESOLUTION=960x540
 https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/3/3.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.4d401f,mp4a.40.2",BANDWIDTH=2224446,AVERAGE-BANDWIDTH=1774446,AUDIO="A1.1+A2.1+A3.1-1266820252",SUBTITLES="T1-1266820252",FRAME-RATE=25.000,RESOLUTION=960x540
 https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/3/3.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",BANDWIDTH=4471402,AVERAGE-BANDWIDTH=3511402,AUDIO="A1.1+A2.1+A3.1-510682780",SUBTITLES="T1-510682780",FRAME-RATE=50.000,RESOLUTION=1280x720
 https://zdf-hls-15.akamaized.net/hls/live/2016498/de/0f8cd85d141c27ece11d2287d7a07bbf/4/4.m3u8
 #EXT-X-STREAM-INF:CODECS="avc1.640028,mp4a.40.2",BANDWIDTH=4471402,AVERAGE-BANDWIDTH=3511402,AUDIO="A1.1+A2.1+A3.1-1266820252",SUBTITLES="T1-1266820252",FRAME-RATE=50.000,RESOLUTION=1280x720
 https://zdf-hls-15.akamaized.net/hls/live/2016498-b/de/0f8cd85d141c27ece11d2287d7a07bbf/4/4.m3u8
 </pre>
 * <hr>
 * <pre>
 #EXTM3U
 #EXT-X-TARGETDURATION:10
 #EXT-X-ALLOW-CACHE:YES
 #EXT-X-PLAYLIST-TYPE:VOD
 #EXT-X-VERSION:3
 #EXT-X-MEDIA-SEQUENCE:1
 #EXTINF:10.000,
 https://hdvodsrforigin-f.akamaihd.net/i/vod/myschool/2020/12/myschool_20201201_151628_23050020_v_webcast_h264_,q40,q10,q20,q30,q50,q60,.mp4.csmil/segment1_3_av.ts
 #EXTINF:10.000,
 https://hdvodsrforigin-f.akamaihd.net/i/vod/myschool/2020/12/myschool_20201201_151628_23050020_v_webcast_h264_,q40,q10,q20,q30,q50,q60,.mp4.csmil/segment2_3_av.ts
 #EXTINF:10.000,
 https://hdvodsrforigin-f.akamaihd.net/i/vod/myschool/2020/12/myschool_20201201_151628_23050020_v_webcast_h264_,q40,q10,q20,q30,q50,q60,.mp4.csmil/segment3_3_av.ts
 ...
 #EXT-X-ENDLIST
 </pre>
 */
public class M3UPlaylist extends Playlist {

    private static final String TAG = "M3UPlaylist";
    /** #EXT-X-MEDIA elements */
    private final List<ExtXMedia> extXMedia = new ArrayList<>();
    private boolean encrypted;
    /** true if no EXT tag appears at all - the playlist consists of urls only */
    private boolean plain = true;
    private String extXStreamInf;

    /**
     * Constructor.
     * @param source Uri
     */
    public M3UPlaylist(@NonNull Uri source) {
        super(source);
    }

    /**
     * Adds an #EXT-X-MEDIA element.
     * @param extXMedia #EXT-X-MEDIA element
     */
    private void addExtXMedia(@Nullable ExtXMedia extXMedia) {
        if (extXMedia == null) return;
        this.extXMedia.add(extXMedia);
    }

    /**
     * @param groupId the group-id to match (can be null)
     * @return the default ExtXMedia
     */
    @Nullable
    public ExtXMedia getDefaultExtXMedia(@Nullable final String groupId) {
        for (ExtXMedia extXMedia : this.extXMedia) {
            if ((groupId == null || groupId.equals(extXMedia.getGroupId())) && extXMedia.isDeflt()) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Default EXT-X-MEDIA for GROUP-ID \"" + groupId + "\" is " + extXMedia);
                return extXMedia;
            }
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "Did not get default EXT-X-MEDIA for GROUP-ID " + groupId + "!");
        return null;
    }

    @NonNull
    public List<ExtXMedia> getExtXMedia() {
        return this.extXMedia;
    }

    @NonNull
    public List<ExtXMedia> getExtXMediaByGroupId(final String groupId) {
        final List<ExtXMedia> list = new ArrayList<>(3);
        if (groupId == null) return list;
        for (ExtXMedia extXMedia : this.extXMedia) {
            if (groupId.equals(extXMedia.getGroupId())) list.add(extXMedia);
        }
        return list;
    }

    public boolean isEncrypted() {
        return this.encrypted;
    }

    /**
     * Returns whether this is a plain list file without #EXT tags.
     * @return true / false
     */
    public boolean isPlain() {
        return this.plain;
    }

    /**
     * {@inheritDoc}
     * A playlist is valid if it has got at least one item and is <em>either</em><br>
     * a) a media playlist <em>or</em><br>
     * b) a master playlist <em>or</em><br>
     * c) neither<br>
     * <hr>
     * <pre>
     * "A Media Segment tag MUST NOT appear in a Master Playlist.<br>
     * Clients MUST fail to parse Playlists that contain both Media Segment tags and Master Playlist tag"
     * </pre>
     * <hr>
     * Media Segment tags: "#EXTINF", "#EXT-X-BYTERANGE", etc.<br>
     * Master Playlist tag: "#EXT-X-MEDIA", "#EXT-X-STREAM-INF", etc.<br>
     *
     * @return true / false
     */
    @Override
    public boolean isValid() {
        return hasItems() && (!isMediaSegmentTagFound() || !isMasterPlaylistTagFound());
    }

    /** {@inheritDoc} */
    @Override
    public void parseLine(@NonNull final String line) {
        if (line.length() == 0 || line.startsWith("[")) return;
        if (line.startsWith("#EXT-X-")) this.plain = false;
        if (line.startsWith("#EXTINF")
                || line.startsWith("#EXT-X-BYTERANGE")
                || line.startsWith("#EXT-X-DISCONTINUITY")
                || line.startsWith("#EXT-X-MAP")
                || line.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                || line.startsWith("#EXT-X-DATERANGE")
                || line.startsWith("#EXT-X-TARGETDURATION")
                || line.startsWith("#EXT-X-MEDIA-SEQUENCE")
                || line.startsWith("#EXT-X-ENDLIST")
                || line.startsWith("#EXT-X-PLAYLIST-TYPE")) {
            super.mediaSegmentTagFound = true;
            return;
        } else if (line.startsWith("#EXT-X-SESSION-DATA")) {
            super.masterPlaylistTagFound = true;
            return;
        } else if (line.startsWith("#EXT-X-KEY")) {
            this.encrypted = true;
            super.mediaSegmentTagFound = true;
            return;
        } else if (line.startsWith("#EXT-X-SESSION-KEY")) {
            this.encrypted = true;
            super.masterPlaylistTagFound = true;
            return;
        } else if (line.startsWith("#EXT-X-MEDIA:")) {
            super.masterPlaylistTagFound = true;
            // #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-123456789",NAME="Henry",LANGUAGE="swa",DEFAULT=YES,URI="https://host.net/hls/live/path/file.m3u8"
            M3UPlaylist.ExtXMedia extXMedia = M3UPlaylist.ExtXMedia.parse(line);
            addExtXMedia(extXMedia);
            return;
        } else if (line.startsWith(PlaylistItem.EXTXSTREAMINF)) {
            super.masterPlaylistTagFound = true;
            this.extXStreamInf = line;
            return;
        }
        if (line.startsWith("#")) {
            return;
        }
        final String lline = line.toLowerCase(java.util.Locale.US);
        PlaylistItem item = PlaylistItem.parseExtXStreamInf(this.extXStreamInf, lline);
        if (item != null) {
            addItem(item);
        } else {
            addItem(new PlaylistItem(Uri.parse(lline)));
        }
        this.extXStreamInf = null;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return "M3UPlaylist\n" +
                "source=" + source +
                "\nitems=" + items +
                "\nextxmedia=" + extXMedia
                ;
    }

    /**
     * An #EXT-X-MEDIA element.<br>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.3.4.1">https://tools.ietf.org/html/rfc8216#section-4.3.4.1</a><br>
     * Example:<br>
     * <pre>
     * #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="A1.1+A2.1+A3.1-123456789",NAME="Henry",LANGUAGE="swa",DEFAULT=YES,URI="https://host.net/hls/live/path/file.m3u8"
     * </pre>
     */
    public static class ExtXMedia {

        private static final String TAG = "ExtXMedia";

        @NonNull
        static ExtXMedia parse(@NonNull final String line) {
            final ExtXMedia extXMedia = new ExtXMedia();
            for (int pos = 13; pos < line.length();) {
                int nextComma = line.indexOf(',', pos);
                String keyvalue = nextComma >= 0 ? line.substring(pos, nextComma) : line.substring(pos);
                int eq = keyvalue.indexOf('=');
                if (eq > 0) {
                    String key = keyvalue.substring(0, eq).trim();
                    String val = keyvalue.substring(eq + 1).trim();
                    //TODO handle the case of a comma between quotation marks
                    if (val.length() > 1) {
                        if (val.charAt(0) == '"') val = val.substring(1);
                        if (val.charAt(val.length() - 1) == '"') val = val.substring(0, val.length() - 1);
                    }
                    switch (key) {
                        case "TYPE":
                            extXMedia.type = val;
                            break;
                        case "GROUP-ID":
                            extXMedia.groupId = val;
                            break;
                        case "NAME":
                            extXMedia.name = val;
                            break;
                        case "LANGUAGE":
                            extXMedia.language = val;
                            break;
                        case "DEFAULT":
                            extXMedia.deflt = ("YES".equalsIgnoreCase(val));
                            break;
                        case "URI":
                            extXMedia.uri = val;
                            break;
                        default:
                            if (BuildConfig.DEBUG) Log.w(TAG, "Unhandled element '" + key + "' in EXT-X-MEDIA");
                    }
                }
                if (nextComma < 0) break;
                pos = nextComma + 1;
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "Parsed: " + extXMedia);
            return extXMedia;
        }
        private String type;
        private String groupId;
        private String language;
        private String name;
        private boolean deflt;
        private String uri;

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ExtXMedia)) return false;
            ExtXMedia extXMedia = (ExtXMedia) o;
            return Objects.equals(uri, extXMedia.uri);
        }

        public String getGroupId() {
            return groupId;
        }

        public String getLanguage() {
            return language;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getUri() {
            return uri;
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return Objects.hash(uri);
        }

        public boolean isDeflt() {
            return deflt;
        }



        /** {@inheritDoc} */
        @Override
        @NonNull
        public String toString() {
            return "ExtXMedia{" +
                    "type='" + type + '\'' +
                    ", groupId='" + groupId + '\'' +
                    ", language='" + language + '\'' +
                    ", name='" + name + '\'' +
                    ", deflt=" + deflt +
                    ", uri='" + uri + '\'' +
                    '}';
        }
    }
}

/*
 * Playlist.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model.pl;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * <a href="https://en.wikipedia.org/wiki/Category:Playlist_file_formats">https://en.wikipedia.org/wiki/Category:Playlist_file_formats</a>
 */
public abstract class Playlist {

    @NonNull
    protected final List<PlaylistItem> items = new ArrayList<>();
    @NonNull
    protected final Uri source;

    /** if true: the current interpretation for this app is that the playlist entries represent <em>alternatives</em> of which one should be picked */
    protected boolean masterPlaylistTagFound;
    /** if true: the current interpretation for this app is that the playlist entries represent <em>successive items</em> which should be played one after the other */
    protected boolean mediaSegmentTagFound;

    /**
     * Determines whether the given uri indicates that it points to a playlist.
     * @param uri uri to check
     * @return {@code true} if the given uri ends with a known playlist extension
     * @throws NullPointerException if {@code uri} is {@code null}
     */
    public static boolean isPlaylist(@NonNull final String uri) {
        final String luri = uri.toLowerCase(java.util.Locale.US);
        return luri.endsWith("m3u8") || luri.endsWith(".m3u") || luri.endsWith(".ram") || luri.endsWith(".pls");
    }

    /**
     * Constructor.
     * @param source Uri
     */
    protected Playlist(@NonNull Uri source) {
        super();
        this.source = source;
    }

    /**
     * Adds a PlaylistItem.
     * @param item PlaylistItem
     */
    protected final void addItem(@NonNull PlaylistItem item) {
        this.items.add(item);
    }

    public final int getCount() {
        return this.items.size();
    }

    @Nullable
    public final PlaylistItem getFirstItem() {
        return this.items.size() > 0 ? this.items.get(0) : null;
    }

    @NonNull
    public List<PlaylistItem> getItems() {
        return items;
    }

    /**
     * If all items bear the same file extension, the extension is returned. Otherwise {@code null}.
     * @return file extension (including leading .)<ul><li>if this is a media playlist<br>AND</li><li>if all its items have the same file extension</li></ul>
     */
    @Nullable
    public final String getMediaPlaylistUniqueMediaType() {
        if (this.items.isEmpty() || !isMediaPlaylist()) return null;
        final int n = this.items.size();
        PlaylistItem firstItem = this.items.get(0);
        final String extension = firstItem.suggestFileExtension();
        if (extension == null) return null;
        for (int i = 1; i < n; i++) {
            PlaylistItem item = this.items.get(i);
            if (!extension.equals(item.suggestFileExtension())) return null;
        }
        return extension;
    }

    /**
     * Returns the items found in the playlist <em>excluding those that have equal {@link PlaylistItem#toUserString(Context) user labels}</em>.<br>
     * <b>The items may contain relative urls like "live/stream1.m3u8"!</b>
     * @return List of items found in the playlist
     */
    @NonNull
    public final List<PlaylistItem> getUniqueItems(@NonNull final Context ctx) {
        final List<PlaylistItem> unique = new ArrayList<>(this.items.size());
        final Set<String> added = new HashSet<>(this.items.size());
        for (PlaylistItem item : this.items) {
            String label = item.toUserString(ctx);
            if (added.contains(label)) continue;
            unique.add(item);
            added.add(label);
        }
        return unique;
    }

    /**
     * Determines whether this Playlist has one or more PlaylistItems.
     * @return {@code true} if there is at least one item
     */
    protected final boolean hasItems() {
        return !this.items.isEmpty();
    }

    /**
     * A Playlist is a Media Playlist if all URI lines in the Playlist identify Media Segments.<br>
     * A Playlist is a Master Playlist if all URI lines in the Playlist identify Media Playlists.<br>
     * A Playlist MUST be either a Media Playlist or a Master Playlist; all other Playlists are invalid.<br>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.1">https://tools.ietf.org/html/rfc8216#section-4.1</a>
     * @return true / false
     */
    public final boolean isMasterPlaylist() {
        for (PlaylistItem item : this.items) {
            if (item.isMediaSegment()) return false;
        }
        return true;
    }

    protected final boolean isMasterPlaylistTagFound() {
        return this.masterPlaylistTagFound;
    }

    /**
     * A Playlist is a Media Playlist if all URI lines in the Playlist identify Media Segments.<br>
     * A Playlist is a Master Playlist if all URI lines in the Playlist identify Media Playlists.<br>
     * A Playlist MUST be either a Media Playlist or a Master Playlist; all other Playlists are invalid.<br>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.1">https://tools.ietf.org/html/rfc8216#section-4.1</a>
     * @return true / false
     */
    public final boolean isMediaPlaylist() {
        for (PlaylistItem item : this.items) {
            if (!item.isMediaSegment()) return false;
        }
        return true;
    }

    protected final boolean isMediaSegmentTagFound() {
        return this.mediaSegmentTagFound;
    }

    /**
     * @return true if the playlist has been parsed without error
     */
    public boolean isValid() {
        return !this.items.isEmpty();
    }

    /**
     * A URI in a Playlist, whether it is a URI line or part of a tag, MAY be relative.<br>
     * Any relative URI is considered to be relative to the URI of the Playlist that contains it.<br>
     * See <a href="https://tools.ietf.org/html/rfc8216#section-4.1">https://tools.ietf.org/html/rfc8216#section-4.1</a>
     * <br>
     * Example:<br>
     * Playlist uri "http://sip-live.hds.adaptive.level3.net/hls-live/ruv-ras2/_definst_/live.m3u8";<br>
     * Playlist file loaded from above uri contains line "live/stream1.m3u8";<br>
     * that relative uri must be translated to "http://sip-live.hds.adaptive.level3.net/hls-live/ruv-ras2/_definst_/live/stream1.m3u8"
     */
    public final void makeRelativeUrisAbsolute() {
        // scheme://authority/path?query#fragment
        Uri.Builder b = new Uri.Builder()
                .scheme(this.source.getScheme())
                .authority(this.source.getAuthority())
                ;
        final List<String> ps = this.source.getPathSegments();
        for (int i = 0; i < ps.size() - 1; i++) {
            b.appendPath(ps.get(i));
        }
        Uri prefix = b.build();
        String prefixs = prefix.toString();
        for (PlaylistItem item : this.items) {
            if (!item.hasRelativeUri()) continue;
            item.makeUriAbsolute(prefixs);
        }
    }

    /**
     * Parses the given input line.
     * @param line line to parse
     */
    abstract public void parseLine(@NonNull String line);

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "items=" + this.items +
                ", source=" + this.source +
                '}';
    }
}

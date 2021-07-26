/*
 * RamPlaylist.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model.pl;

import android.net.Uri;

import androidx.annotation.NonNull;

/**
 * Content-Type: audio/x-pn-realaudio<br>
 * <a href="https://en.wikipedia.org/wiki/RealAudio">https://en.wikipedia.org/wiki/RealAudio</a>
 * <br>
 * Examples:
 * <ul>
 * <li>
 * <pre>
 * http://freshgrass.streamguys1.com/folkalley-128mp3
 * </pre>
 * </li>
 * </ul>
 */
public class RamPlaylist extends Playlist {

    /**
     * Constructor.
     * @param source Uri
     */
    public RamPlaylist(@NonNull Uri source) {
        super(source);
    }

    /** {@inheritDoc} */
    @Override
    public void parseLine(@NonNull String line) {
        //the following line assumes that there are only absolute urls…
        if (!line.startsWith("http://") && !line.startsWith("https://")) return;
        addItem(new PlaylistItem(Uri.parse(line)));
    }
}

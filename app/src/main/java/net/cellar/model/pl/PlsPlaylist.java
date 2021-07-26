/*
 * PlsPlaylist.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model.pl;

import android.net.Uri;

import androidx.annotation.NonNull;

/**
 * <a href="https://en.wikipedia.org/wiki/PLS_(file_format)">https://en.wikipedia.org/wiki/PLS_(file_format)</a>
 * <br>
 * Examples:
 * <ul>
 * <li>
 * <pre>
 [playlist]
 NumberOfEvents=2

 File1=http://live-radio01.mediahubaustralia.com/2FMW/mp3/
 Title1=Classic FM NSW
 Length1=-1
 Version=2

 File2=http://live-radio02.mediahubaustralia.com/2FMW/mp3/
 Title2=Classic FM NSW
 Length2=-1
 Version=2
 * </pre>
 * </li>
 * </ul>
 */
public class PlsPlaylist extends Playlist {

    /**
     * Constructor.
     * @param source Uri
     */
    public PlsPlaylist(@NonNull Uri source) {
        super(source);
    }

    /** {@inheritDoc} */
    @Override
    public void parseLine(@NonNull String line) {
        line = line.toLowerCase(java.util.Locale.US);
        if (line.startsWith("file") && line.contains("=")) {
            // PLS playlist entry like "File1=http://ais-nzme.streamguys1.com/nz_011_aac"
            line = line.substring(line.indexOf('=') + 1).trim();
            //the following line assumes that there are only absolute urls…
            if (!line.startsWith("http://") && !line.startsWith("https://")) return;
            addItem(new PlaylistItem(Uri.parse(line)));
        } else if (line.startsWith("length") && line.contains("=") && line.endsWith("-1")) {
            // PLS playlist entry indicating an item with unknown length => therefore the items cannot be played one after another
            super.masterPlaylistTagFound = true;
        }
    }
}

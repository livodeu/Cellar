/*
 * DeferStandardTest.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import androidx.annotation.NonNull;

/**
 * Tests deferring/resuming a standard HTTPS download.
 */
public class DeferStandardTest extends DeferTest {

    @NonNull
    @Override
    String getFilename() {
        return "Voyage to the Planet of Prehistoric Women.mp4";
    }

    @NonNull
    @Override
    String getUrl() {
        return "https://archive.org/download/VoyageToThePlanetOfPrehistoricWomen_20130813/Voyage%20to%20the%20Planet%20of%20Prehistoric%20Women.mp4";
    }
}

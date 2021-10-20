/*
 * DeferTest.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.test.filters.MediumTest;

import net.cellar.model.Wish;
import net.cellar.queue.QueueManager;
import net.cellar.supp.Util;

import org.junit.After;

import java.io.File;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@MediumTest
public abstract class DeferTest extends LoadTest {

    @After
    public void cleanup() {
        Util.deleteFile(new File(App.getDownloadsDir(ctx), getFilename()));
    }

    @Override
    public void execute() {
        final String url = getUrl();
        final App app = (App)ctx.getApplicationContext();
        final File downloadsDir = App.getDownloadsDir(ctx);
        File expectedFile = new File(downloadsDir, getFilename());

        android.util.Log.i(getClass().getSimpleName(), "Loading \"" + url + "\"…");
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setComponent(new ComponentName(app, MainActivity.class));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        for (int i = 0; i < SECS_TO_START; i++) {
            try {
                Thread.sleep(1_000L);
                if (downloadStarted) break;
            } catch (Throwable ignored) {
            }
        }
        assertTrue("Download of \"" + Uri.parse(url).getLastPathSegment() + "\" not started!", this.downloadStarted);
        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(1_000L);
                if (expectedFile.length() > 1024L) break;
            } catch (Throwable ignored) {
            }
        }

        if (super.fileName != null) {
            expectedFile = new File(downloadsDir, super.fileName);
        }

        // check that file exists
        assertTrue("File \"" + getFilename() + "\" does not exist!", expectedFile.isFile());

        // defer download
        int deferred = app.deferAllLoaders();
        assertTrue("No download deferred! (1st time)",deferred >= 1);
        QueueManager queueManager = QueueManager.getInstance();
        assertNotNull(queueManager);
        final List<Wish> wishes = new ArrayList<>(queueManager.getWishes());
        assertNotNull(wishes);
        final int queued = wishes.size();
        assertTrue("No wishes queued!",queued >= 1);

        Uri toFind = Uri.parse(getUrl().substring(0, getUrl().lastIndexOf('/')) + "/" + Uri.encode(fileName != null ? fileName : getFilename()));
        int foundAt = -1;
        Wish matchingWish = null;
        for (int i = 0; i < queued; i++) {
            Wish wish = wishes.get(i);
            if (toFind.equals(wish.getUri())) {
                foundAt = i;
                matchingWish = wish;
                break;
            }
        }
        assertTrue("Deferred download \"" + toFind.getLastPathSegment() + "\" not found in queue!", foundAt >= 0);
        assertTrue("Deferred download is not held!", matchingWish.isHeld());
        try {
            Thread.sleep(3_000L);
        } catch (Throwable ignored) {
        }

        // resume download
        matchingWish.setHeld(false);
        assertFalse(matchingWish.isHeld());
        if (foundAt > 0) queueManager.moveUp(foundAt, foundAt);
        final Wish toResume = queueManager.getWishes().peek();
        assertNotNull(toResume);
        assertEquals("First queued item is not " + getUrl(), toResume, matchingWish);
        assertTrue("No wish taken from queue!", queueManager.nextPlease(true));
        assertEquals(queued - 1, queueManager.getWishes().size());
        try {
            Thread.sleep(5_000L);
        } catch (Throwable ignored) {
        }

        // defer again
        deferred = app.deferAllLoaders();
        assertTrue("No download deferred! (2nd time)",deferred >= 1);
        android.util.Log.i(getClass().getSimpleName(), "After the 2nd deferral, the queue contains " + queueManager.getWishes());

        // remove from queue
        assertTrue("Deferred download not removed from queue!", queueManager.remove(toResume));
        Deque<Wish> inTheEnd = queueManager.getWishes();
        android.util.Log.i(getClass().getSimpleName(), "In the end, the queue contains " + inTheEnd);
        assertFalse("Queue still contains " + toResume.getUri(), inTheEnd.contains(toResume));

        assertTrue("Failed to delete file \"" + getFilename() + "\"", expectedFile.delete());

        // give the QueueManager a moment to store its data
        try {
            Thread.sleep(1_000L);
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    abstract String getFilename();

    @NonNull
    @Override
    abstract String getUrl();
}

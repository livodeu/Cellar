/*
 * FileInfo.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.OpenableColumns;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;
import net.cellar.supp.Util;
import net.cellar.supp.Log;

/**
 * Retrieves display name and size based on a "content:" uri.
 */
public class FileInfo extends Thread {

    private static final String TAG = "FileInfo";

    private final Object sync = new Object();
    @NonNull private final ContentResolver cr;
    @NonNull private final Uri uri;
    @NonNull private final CancellationSignal cs;
    @GuardedBy("sync") private volatile String fileDisplayname;
    @GuardedBy("sync") private volatile long fileSize = -1L;

    /**
     * Constructor.
     * @param cr ContentResolver
     * @param uri "content:" uri, like, for example, "content://com.android.providers.downloads.documents/document/407"
     */
    public FileInfo(@NonNull ContentResolver cr, @NonNull Uri uri) {
        super();
        this.cr = cr;
        this.uri = uri;
        this.cs = new CancellationSignal();
    }

    @Nullable
    public String getFileDisplayname() {
        String displayName;
        synchronized (sync) {
            displayName = this.fileDisplayname;
        }
        return displayName;
    }

    @IntRange(from = -1)
    public long getFileSize() {
        long size;
        synchronized (sync) {
            size = this.fileSize;
        }
        return size;
    }

    /** {@inheritDoc} */
    @Override
    public void run() {
        Cursor cursor = null;
        try {
            cursor = this.cr.query(this.uri, null, null, null, null, this.cs);
            if (cursor == null) return;
            if (cursor.moveToFirst()) {
                synchronized (sync) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex > -1) this.fileSize = cursor.getLong(sizeIndex);
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex > -1) this.fileDisplayname = cursor.getString(nameIndex);
                }
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "Did not get info for " + uri);
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG,"While getting info for '" + this.uri + "': " + e.toString());
        } finally {
            Util.close(cursor);
        }
    }
}

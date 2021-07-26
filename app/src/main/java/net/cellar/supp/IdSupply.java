/*
 * IdSupply.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import androidx.annotation.IntRange;

import net.cellar.BackupService;

/**
 * All IDs should have their origins here (to avoid any conflict).
 */
public interface IdSupply {

    /** the first download id */
    int DOWNLOAD_ID_OFFSET = 1_000;
    /** notification id for {@link BackupService} */
    int NOTIFICATION_ID_BACKUP = 500;
    /** notification id for {@link net.cellar.ClipSpy} */
    int NOTIFICATION_ID_CLIPSPY = 34453;
    /** offset for notification ids regarding completed downloads */
    int NOTIFICATION_ID_DONE_OFFSET = 50_000;
    /** offset for notification ids regarding completed downloads minus 1 */
    int NOTIFICATION_ID_DONE_OFFSET_MINUS_1 = NOTIFICATION_ID_DONE_OFFSET - 1;
    /** notification id for the user being asked whether to enqueue a download */
    int NOTIFICATION_ID_ENQUEUE = 123_456;
    /** notification id for group summary */
    int NOTIFICATION_ID_GROUP_SUMMARY = 100_000;
    /** notification id for notifications from the {@link net.cellar.StoreActivity} */
    int NOTIFICATION_ID_STORE_ACTIVITY = 200_000;
    /** an email has been saved in a file */
    int NOTIFICATION_ID_MAIL_SAVED = 18_923;
    int NOTIFICATION_ID_MULTI = 9_124;
    /** offset for notification ids regarding download progress */
    int NOTIFICATION_ID_PROGRESS_OFFSET = 5_000;
    /** used for notifications about downloads that do not possess a download id */
    int NOTIFICATION_ID_FOR_DOWNLOADS_WITHOUT_ID = NOTIFICATION_ID_PROGRESS_OFFSET - 2;
    /** offset for notification ids regarding download progress minus 1 */
    int NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1 = NOTIFICATION_ID_PROGRESS_OFFSET - 1;
    /** What a terrible failure! */
    int NOTIFICATION_ID_WTF = 666;

    int NOTIFICATION_ID_INSTALL_OFFSET = 300_000;

    /**
     * Returns the matching <em>completion</em> notification id for a download id.<br>
     * This is not for active downloads, though!
     * @param downloadId download id
     * @return notification id
     */
    @IntRange(from = NOTIFICATION_ID_DONE_OFFSET + DOWNLOAD_ID_OFFSET, to = NOTIFICATION_ID_DONE_OFFSET + NOTIFICATION_ID_DONE_OFFSET_MINUS_1)
    static int completionNotificationId(@IntRange(from = DOWNLOAD_ID_OFFSET, to = NOTIFICATION_ID_DONE_OFFSET_MINUS_1) int downloadId) {
        return NOTIFICATION_ID_DONE_OFFSET + downloadId;
    }

    /**
     * Checks whether the given number is download id.
     * Download ids are smaller than {@link NOTIFICATION_ID_PROGRESS_OFFSET}.
     * @param id number to check
     * @return true / false
     */
    static boolean isDownloadId(int id) {
        return id >= DOWNLOAD_ID_OFFSET && id < NOTIFICATION_ID_PROGRESS_OFFSET;
    }

    /**
     * Returns the matching <em>progress</em> notification id for a download id.<br>
     * This is not for completed downloads, though!
     * @param downloadId download id
     * @return notification id
     */
    @IntRange(from = NOTIFICATION_ID_PROGRESS_OFFSET + DOWNLOAD_ID_OFFSET, to = NOTIFICATION_ID_PROGRESS_OFFSET + NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1)
    static int progressNotificationId(@IntRange(from = DOWNLOAD_ID_OFFSET, to = NOTIFICATION_ID_PROGRESS_OFFSET_MINUS_1) int downloadId) {
        return NOTIFICATION_ID_PROGRESS_OFFSET + downloadId;
    }
}

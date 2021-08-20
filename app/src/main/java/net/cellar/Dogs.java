/*
 * Dogs.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.exifinterface.media.ExifInterface;

import net.cellar.supp.Log;
import net.cellar.supp.MetadataReader;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import org.beyka.tiffbitmapfactory.IProgressListener;
import org.beyka.tiffbitmapfactory.TiffBitmapFactory;
import org.jetbrains.annotations.TestOnly;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import wseemann.media.FFmpegMediaMetadataRetriever;

/**
 * See
 * <ul>
 * <li><a href="https://developer.android.com/guide/topics/providers/create-document-provider">https://developer.android.com/guide/topics/providers/create-document-provider</a></li>
 * <li><a href="https://medium.com/androiddevelopers/building-a-documentsprovider-f7f2fb38e86a#.cq3fc969k">https://medium.com/androiddevelopers/building-a-documentsprovider-f7f2fb38e86a#.cq3fc969k</a></li>
 * <li><a href="https://github.com/android/storage-samples/tree/main/StorageProvider">https://github.com/android/storage-samples/tree/main/StorageProvider</a></li>
 * <li><a href="https://github.com/nextcloud/android/blob/master/src/main/java/com/owncloud/android/providers/DocumentsStorageProvider.java">https://github.com/nextcloud/android/blob/master/src/main/java/com/owncloud/android/providers/DocumentsStorageProvider.java</a></li>
 * <li><a href="https://programtalk.com/vs/LocalStorage/mobile/src/main/java/com/ianhanniballake/localstorage/LocalStorageProvider.java/">https://programtalk.com/vs/LocalStorage/mobile/src/main/java/com/ianhanniballake/localstorage/LocalStorageProvider.java/</a></li>
 * <li><a href="https://github.com/google/samba-documents-provider/blob/master/app/src/main/java/com/google/android/sambadocumentsprovider/provider/SambaDocumentsProvider.java">https://github.com/google/samba-documents-provider/blob/master/app/src/main/java/com/google/android/sambadocumentsprovider/provider/SambaDocumentsProvider.java</a></li>
 * </ul>
 *
 * Btw., the documents ui is here: <br>
 * https://android.googlesource.com/platform/packages/apps/DocumentsUI/ <br>
 * (git clone https://android.googlesource.com/platform/packages/apps/DocumentsUI)<br>
 *
 * When the view is refreshed by the user,
 * content://net.cellar.dogs/document/Cellar/children
 * is queried (DocumentsProvider.query())
 */
public final class Dogs extends DocumentsProvider {

    @VisibleForTesting
    public static final String ROOT_DOC = "Cellar";
    @VisibleForTesting
    public static final String ROOT_ID = "CELLAR_ID";
    private static final String AUTHORITY = BuildConfig.DOCSPROVIDER_AUTH;
    private final static String[] DEFAULT_DOC_PROJECTION =
            new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
            };
    private final static String[] DEFAULT_ROOT_PROJECTION =
            new String[] {
                    DocumentsContract.Root.COLUMN_ROOT_ID,
                    DocumentsContract.Root.COLUMN_ICON,
                    DocumentsContract.Root.COLUMN_TITLE,
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Root.COLUMN_SUMMARY
            };
    /** max. age for files returned in {@link #queryRecentDocuments(String, String[])} */
    private static final long MAXAGE_FOR_RECENTS = 14 * 24 * 3_600_000L;
    private static final String PREFIX_CUSTOM_THUMBNAILS = "specthumb";
    /** The quality level to pass to {@link Bitmap#compress(Bitmap.CompressFormat, int, OutputStream)} */
    @IntRange(from = 0, to = 100)
    private static final int QUALITY_LEVEL = 50;
    private static final String TAG = "Dogs";

    @NonNull
    public static Uri buildNotifyUri() {
        return DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOC);
    }

    /**
     * Searches a text file for a piece of text.
     * @param queryLower piece of text to find, in lower case
     * @param file file to search
     * @return {@code true} if the text has been found in the file
     */
    private static boolean containsText(@NonNull final String queryLower, @NonNull File file) {
        boolean match = false;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            for (; ; ) {
                String line = reader.readLine();
                if (line == null) break;
                if (line.toLowerCase().contains(queryLower)) {
                    match = true;
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            Util.close(reader);
        }
        return match;
    }

    /**
     * Deletes any remaining custom thumbnail files.
     * Normally, there shouldn't be any, if the file had been deleted correctly in {@link #openDocumentThumbnail(String, Point, CancellationSignal)}.
     */
    private static void deleteCustomThumbnails() {
        String tmpDir = System.getProperty("java.io.tmpdir", null);
        if (tmpDir == null) return;
        File tmp = new File(tmpDir);
        if (!tmp.isDirectory()) return;
        final File[] t = tmp.listFiles((dir, name) -> name.startsWith(PREFIX_CUSTOM_THUMBNAILS));
        if (t == null) return;
        Util.deleteFile(t);
    }

    /**
     * Stores the given bitmap in a temporary file and returns an AssetFileDescriptor in read-only mode for that file.<br>
     * The temporary file will be deleted before this method returns.<br>
     * <i>The bitmap will be recycled, too!</i>
     * @param bm Bitmap
     * @return AssetFileDescriptor
     * @throws NullPointerException if {@code bm} is {@code null}
     */
    @VisibleForTesting
    @Nullable
    public static AssetFileDescriptor makeAssetFileDescriptor(@NonNull Bitmap bm) {
        boolean ok = false;
        File tmp = null;
        OutputStream out = null;
        try {
            tmp = File.createTempFile(PREFIX_CUSTOM_THUMBNAILS, ".webp");
            out = new BufferedOutputStream(new FileOutputStream(tmp));
            ok = bm.compress(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP, QUALITY_LEVEL, out);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While storing a bitmap in a webp file: " + e.toString());
        } finally {
            Util.close(out);
        }
        if (BuildConfig.DEBUG) {
            if (ok) Log.i(TAG, "⤷ Created custom thumbnail of " + bm.getWidth() + "x" + bm.getHeight() + " pixels");
            else Log.e(TAG, "⤷ Failed to create custom thumbnail of " + bm.getWidth() + "x" + bm.getHeight() + " pixels");
        }
        bm.recycle();
        if (ok) {
            AssetFileDescriptor afd;
            try {
                afd = new AssetFileDescriptor(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY), 0, -1);
                Util.deleteFile(tmp);
                return afd;
            } catch (FileNotFoundException ignored) {
            }
        }
        Util.deleteFile(tmp);
        return null;
    }

    /**
     * Creates a big thumbnail picture for a picture file (one that is natively supported by the os).
     * @param documentId picture file path
     * @param sizeHint requested size
     * @return AssetFileDescriptor
     * @throws NullPointerException if any parameter is {@code null}
     */
    @Nullable
    private static AssetFileDescriptor makeBigPictureThumbnail(@NonNull final String documentId, @NonNull final Point sizeHint) throws FileNotFoundException {
        if (sizeHint.x <= 0 || sizeHint.y <= 0) return null;
        // let's see what size the original picture is
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(documentId, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        // if the original picture is not bigger than twice the requested size
        if (o.outWidth <= (sizeHint.x << 1)) {
            // then return the original picture
            return new AssetFileDescriptor(ParcelFileDescriptor.open(new File(documentId), ParcelFileDescriptor.MODE_READ_ONLY), 0, -1);
        }
        // otherwise create a suitably sized bitmap here, store it in a temp file and return that
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(o.outWidth / sizeHint.x, o.outHeight / sizeHint.y);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            o.inPreferredConfig = Bitmap.Config.HARDWARE;
        } else {
            o.inPreferredConfig = Bitmap.Config.RGB_565;
        }
        Bitmap bm = BitmapFactory.decodeFile(documentId, o);
        if (bm == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to decode " + documentId);
            return null;
        }
        // apply rotation if needed
        int rotation = Util.getImageOrientation(documentId);
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, false);
            bm.recycle();
            bm = rotated;
        }
        return makeAssetFileDescriptor(bm);
    }

    private final Object sync = new Object();
    /** the list of downloads that are being served */
    @GuardedBy("sync")
    private final List<File> downloads = new ArrayList<>();
    /** maps document ids (= absolute file paths) to mime types */
    private final LruCache<String, String> mimeCache = new LruCache<>(128);
    @Nullable private Handler handler;
    private Toast progressToast;
    private Uri rootsUri;
    /** gets notified whenever the contents change */
    private Uri notifyUri;
    private File dir;
    private Context debugContext;

    /** {@inheritDoc} */
    @Override
    public void deleteDocument(final String documentId) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "deleteDocument(\"" + documentId + "\")");
        final File file = new File(documentId);
        if (!file.exists()) {
            refresh();
            throw new FileNotFoundException(documentId);
        }
        if (!file.canWrite()) throw new UnsupportedOperationException("Cannot delete read-only " + documentId);
        final Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return;
        final App app = (App)ctx.getApplicationContext();
        if (app.isBeingDownloaded(file)) throw new UnsupportedOperationException("Cannot delete loading " + documentId);
        if (file.isDirectory()) {
            // actually, this should not happen as we did not intend to have directories…
            Util.deleteFileOrDirectory(file.listFiles());
            Util.deleteFileOrDirectory(file);
            refresh();
            return;
        }
        if (file.delete()) {
            try {
                revokeDocumentPermission(documentId);
            } catch (NullPointerException ignored) {
                // this will happen during tests because super doesn't use debugContext
            }
            app.getThumbsManager().removeThumbnail(file);
            this.mimeCache.remove(documentId);
            Ancestry.getInstance().remove(file);
            refresh();
            ctx.getContentResolver().notifyChange(this.notifyUri, null, false);
            //TODO does the document ui update the comment label under the root entry now?
            ctx.getContentResolver().notifyChange(this.rootsUri, null, false);
        } else if (BuildConfig.DEBUG) Log.e(TAG, "Failed to delete " + file);
    }

    /** {@inheritDoc} */
    @TargetApi(Build.VERSION_CODES.Q)
    @Nullable
    @Override
    public Bundle getDocumentMetadata(@NonNull String documentId) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "getDocumentMetadata(\"" + documentId + "\")");
        Bundle metadata = null;
        if (ExifInterface.isSupportedMimeType(getMime(documentId))) {
            InputStream in = null;
            try {
                //noinspection RedundantFileCreation
                in = new FileInputStream(new File(documentId)); // does not have to be a BufferedInputStream; ExifInterface constructs one by itself
                metadata = MetadataReader.getMetadata(in, null);
            } catch (FileNotFoundException e) {
                throw e;
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, "While getting meta data for " + documentId + ": " + e.toString());
            } finally {
                Util.close(in);
            }
        }
        return metadata;
    }

    /** {@inheritDoc} */
    @Override
    public String getDocumentType(final String documentId) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "getDocumentType(\"" + documentId + "\")");
        File file = new File(documentId);
        if (!file.exists()) throw new FileNotFoundException(documentId);
        return getMime(documentId);
    }

    /**
     * Determines the MIME type of a file.
     * @param path absolute path to file
     * @return mime type
     */
    @NonNull
    private synchronized String getMime(@NonNull final String path) {
        String mime = this.mimeCache.get(path);
        if (mime != null) return mime;
        mime = Util.getMime(new File(path));
        this.mimeCache.put(path, mime);
        return mime;
    }

    /**
     * Adds file information to a given cursor.
     * @param projection projection to use
     * @param result cursor to add the data to
     * @param file file to inspect
     * @param hasThumbnail {@code true} if a thumbnail icon is available for the given file
     * @param isCurrentTarget {@code true} if the file is currently the target of a download
     * @throws NullPointerException if any of the parameters are {@code null}
     */
    private void includeFile(@NonNull final String[] projection, @NonNull final MatrixCursor result, @NonNull final File file, final boolean hasThumbnail, final boolean isCurrentTarget) {
        final MatrixCursor.RowBuilder row = result.newRow();
        final String path = file.getAbsolutePath();
        final String mime = getMime(path);
        for (String column : projection) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName());
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    row.add(DocumentsContract.Document.COLUMN_SIZE, file.length());
                    break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                    row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
                    break;
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, path);
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime);
                    break;
                case DocumentsContract.Document.COLUMN_SUMMARY:
                    row.add(DocumentsContract.Document.COLUMN_SUMMARY, null);
                    break;
                case DocumentsContract.Document.COLUMN_ICON:
                    // openDocumentThumbnail() will usually be called if the flags below include FLAG_SUPPORTS_THUMBNAIL
                    row.add(DocumentsContract.Document.COLUMN_ICON, isCurrentTarget ? R.drawable.ic_file_download_black_24dp : null);
                    break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    int flags = 0;
                    if (!isCurrentTarget) {
                        if (hasThumbnail) flags |= DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;
                        if (file.canWrite()) flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags |= DocumentsContract.Document.FLAG_SUPPORTS_SETTINGS;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ExifInterface.isSupportedMimeType(mime)) flags |= DocumentsContract.Document.FLAG_SUPPORTS_METADATA;
                    }
                    row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
                    break;
                default:
                    if (BuildConfig.DEBUG) Log.e(TAG, "Unknown column \"" + column + "\"");
            }
        }
    }

    /**
     * Creates a big thumbnail picture for a video file.
     * @param documentId video file path
     * @param sizeHint requested size
     * @return AssetFileDescriptor
     * @throws NullPointerException if any parameter is {@code null}
     */
    @Nullable
    private AssetFileDescriptor makeBigMovieThumbnail(@NonNull final String documentId, @NonNull final Point sizeHint) {
        if (sizeHint.x <= 0 || sizeHint.y <= 0) return null;
        Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return null;
        FFmpegMediaMetadataRetriever mmr = App.MMRT.get();
        if (mmr == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "FFmpegMediaMetadataRetriever is null");
            return null;
        }
        Bitmap bm;
        try {
            mmr.setDataSource(ctx, Uri.fromFile(new File(documentId)));
            bm = mmr.getFrameAtTime(2_000_000L, FFmpegMediaMetadataRetriever.OPTION_CLOSEST);
            App.MMRT.remove();
            if (bm == null || bm.getWidth() <= 0 || bm.getHeight() <= 0) return null;
            float factor = (float) bm.getWidth() / (float) sizeHint.x;
            if (factor > 2f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bm, sizeHint.x, Math.round(sizeHint.y / factor), false);
                bm.recycle();
                bm = scaled;
            }
            return makeAssetFileDescriptor(bm);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Creates a big thumbnail picture for a tiff file.
     * @param documentId tiff file path
     * @param sizeHint requested size
     * @return AssetFileDescriptor
     * @throws NullPointerException if any parameter is {@code null}
     */
    @Nullable
    private AssetFileDescriptor makeBigTiffThumbnail(@NonNull final String documentId, @NonNull final Point sizeHint) {
        if (sizeHint.x <= 0 || sizeHint.y <= 0) return null;
        Bitmap bm;
        try {
            final File file = new File(documentId);
            final TiffBitmapFactory.Options o = new TiffBitmapFactory.Options();
            o.inJustDecodeBounds = true;
            TiffBitmapFactory.decodeFile(file, o);
            o.inJustDecodeBounds = false;
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inAvailableMemory = 1_024_576 << 8L;
            o.inPreferredConfig = TiffBitmapFactory.ImageConfig.RGB_565;
            o.inSampleSize = Math.max(o.outWidth / sizeHint.x, o.outHeight / sizeHint.y);
            Context ctx = debugContext != null ? debugContext : getContext();
            IProgressListener progressListener = (o.outWidth / o.inSampleSize) > 800 && ctx != null ? new IProgressListener() {
                private boolean toastShown;
                @Override
                public void reportProgress(long processedPixels, long totalPixels) {
                    if (Dogs.this.handler == null || totalPixels <= 0L) return;
                    float progress = (float)processedPixels / (float)totalPixels;
                    if (progress > 0f && !this.toastShown) {
                        Dogs.this.handler.post(() -> {
                            this.toastShown = true;
                            Dogs.this.progressToast.show();
                        });
                    } else if (progress < 0.95f) {
                        Dogs.this.handler.post(() -> Dogs.this.progressToast.setText(ctx.getString(R.string.label_progress, Math.round(100f * progress))));
                    } else {
                        Dogs.this.handler.post(() -> Dogs.this.progressToast.cancel());
                    }
                }

            } : null;
            bm = TiffBitmapFactory.decodeFile(file, o, progressListener);
            if (bm == null) return null;
            return makeAssetFileDescriptor(bm);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to create thumbnail for " + documentId + ": " + e.toString());
        }
        return null;
    }

    /** {@inheritDoc} */
    @SuppressLint("ShowToast")
    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;
        this.handler = new Handler();
        this.progressToast = Toast.makeText(ctx, ctx.getString(R.string.label_progress, 0), Toast.LENGTH_LONG);
        // the rootsUri would be used for notifications when the roots change - see refresh()
        this.rootsUri = DocumentsContract.buildRootsUri(AUTHORITY);
        this.dir = App.getDownloadsDir(ctx);
        this.notifyUri = buildNotifyUri();
        new Thread() {
            @Override
            public void run() {
                refresh();
                deleteCustomThumbnails();
            }
        }.start();
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, @Nullable CancellationSignal signal) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "openDocument(\"" + documentId + "\", \"" + mode + "\", " + signal + ")");
        if (!"r".equals(mode)) throw new UnsupportedOperationException(documentId + " does not support mode " + mode);
        File file = new File(documentId);
        if (!file.isFile()) throw new FileNotFoundException(documentId);
        final Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) throw new FileNotFoundException(documentId);
        // don't let anyone fool around with a file that is currently being written to
        if (((App)ctx.getApplicationContext()).isBeingDownloaded(file)) throw new UnsupportedOperationException("Cannot read " + documentId);
        //
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** {@inheritDoc}<br><br>
     * <em>This will be called by different threads!</em>
     */
    @Override
    @AnyThread
    public AssetFileDescriptor openDocumentThumbnail(final String documentId, final Point sizeHint, @Nullable CancellationSignal signal) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "openDocumentThumbnail(\"" + documentId + "\", " + sizeHint + ", …)");
        if (documentId == null || sizeHint == null) throw new FileNotFoundException(documentId);
        final Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) throw new FileNotFoundException(documentId);
        int stdWidth = ThumbsManager.getSuggestedThumbnailWidth(ctx);
        // is a rather large thumbnail requested? (requested size more than twice the standard size)
        if (sizeHint.x > (stdWidth << 1)) {
            final AssetFileDescriptor fd;
            if (Util.isPicture(documentId)) {
                fd = makeBigPictureThumbnail(documentId, sizeHint);
            } else if (Util.isTiffPicture(documentId)) {
                fd = makeBigTiffThumbnail(documentId, sizeHint);
            } else if (Util.isMovie(documentId)) {
                fd = makeBigMovieThumbnail(documentId, sizeHint);
            } else {
                fd = null;
            }
            if (fd != null) return fd;
        }
        return ((App)ctx.getApplicationContext()).getThumbsManager().openIconFile(new File(documentId));
    }

    /** {@inheritDoc} */
    @Override
    @TargetApi(26)
    public Cursor queryChildDocuments(final String parentDocumentId, @Nullable String[] projection, @Nullable Bundle queryArgs) throws FileNotFoundException {
        String sortColumn = OpenableColumns.DISPLAY_NAME;
        if (Build.VERSION.SDK_INT < 26) return queryChildDocuments(parentDocumentId, projection, sortColumn);
        if (queryArgs != null) {
            Object qasc = queryArgs.get(ContentResolver.QUERY_ARG_SORT_COLUMNS);
            if (qasc instanceof String[]) {
                String[] sortColumns = (String[])qasc;
                if (BuildConfig.DEBUG) Log.i(TAG, "queryChildDocuments(\"" + parentDocumentId + "\", " + Arrays.toString(projection) + ", \"" + Arrays.toString(sortColumns) + "\")");
                if (sortColumns.length > 0) sortColumn = sortColumns[0];
            }
        }
        return queryChildDocuments(parentDocumentId, projection, sortColumn);
    }

    /** {@inheritDoc} */
    @Override
    public Cursor queryChildDocuments(final String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "queryChildDocuments(\"" + parentDocumentId + "\", " + Arrays.toString(projection) + ", \"" + sortOrder + "\")");
        if (!ROOT_DOC.equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        final Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return new MatrixCursor(DEFAULT_DOC_PROJECTION, 0);
        final MatrixCursor result;
        synchronized (sync) {
            result = new MatrixCursor(DEFAULT_DOC_PROJECTION, this.downloads.size());
            if (projection == null) projection = DEFAULT_DOC_PROJECTION;
            if (sortOrder != null) {
                int space = sortOrder.indexOf(' ');
                final String column;
                if (space > 0) column = sortOrder.substring(0, space);
                else column = sortOrder;
                boolean ascending = !sortOrder.toUpperCase().endsWith(" DESC");
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                        if (ascending) Collections.sort(this.downloads, (o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));
                        else Collections.sort(this.downloads, (o1, o2) -> o2.getName().compareToIgnoreCase(o1.getName()));
                        break;
                    case DocumentsContract.Document.COLUMN_SIZE:
                        if (ascending) Collections.sort(this.downloads, Comparator.comparingLong(File::length));
                        else Collections.sort(this.downloads, (o1, o2) -> Long.compare(o2.length(), o1.length()));
                        break;
                    case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                        if (ascending) Collections.sort(this.downloads, Comparator.comparingLong(File::lastModified));
                        else Collections.sort(this.downloads, (o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
                        break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:
                        // this does not seem to be called ever…
                        Collections.sort(this.downloads, new MimeTypeComparator(ascending));
                        break;
                    default:
                        if (BuildConfig.DEBUG) Log.w(TAG, "Did not understand sort column '" + column + "'");
                }
            }
            final App app = (App)ctx.getApplicationContext();
            if (app != null) {
                final ThumbsManager tm = app.getThumbsManager();
                try {
                    tm.wakeUp();
                    for (File file : this.downloads) {
                        if (file == null) continue;
                        includeFile(projection, result, file, tm.hasIconFile(file), app.isBeingDownloaded(file));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        result.setNotificationUri(ctx.getContentResolver(), this.notifyUri);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Cursor queryDocument(final String documentId, @Nullable String[] projection) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "queryDocument(\"" + documentId + "\", " + Arrays.toString(projection) + ")");
        if (documentId == null) throw new FileNotFoundException();
        Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return null;
        if (projection == null) projection = DEFAULT_DOC_PROJECTION;
        MatrixCursor result;
        synchronized (sync) {
            result = new MatrixCursor(DEFAULT_DOC_PROJECTION, this.downloads.size());
        }
        if (ROOT_DOC.equals(documentId)) {
            MatrixCursor.RowBuilder row = result.newRow();
            for (String column : projection) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, ctx.getString(R.string.label_document_provider));
                        break;
                    case DocumentsContract.Document.COLUMN_SIZE:
                        row.add(DocumentsContract.Document.COLUMN_SIZE, null);
                        break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:
                        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
                        break;
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOC);
                        break;
                    case DocumentsContract.Document.COLUMN_ICON:
                        row.add(DocumentsContract.Document.COLUMN_ICON, R.mipmap.ic_launcher);
                        break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED);
                        break;

                }
            }
        } else {
            App app = (App)ctx.getApplicationContext();
            ThumbsManager tm = app.getThumbsManager();
            File file = new File(documentId);
            includeFile(projection, result, file, tm.hasCachedThumbnail(file), app.isBeingDownloaded(file));
            if (!file.isFile()) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Not found: " + file);
                throw new FileNotFoundException(documentId);
            }
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Cursor queryRecentDocuments(String rootId, @Nullable String[] projection) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "queryRecentDocuments(\"" + rootId + "\", " + Arrays.toString(projection) + ")") ;
        if (!ROOT_ID.equals(rootId)) throw new FileNotFoundException(rootId);
        Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) throw new FileNotFoundException(rootId);
        if (projection == null) projection = DEFAULT_DOC_PROJECTION;
        final MatrixCursor result;
        synchronized (sync) {
            Collections.sort(this.downloads, (o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
            final int n = Math.min(this.downloads.size(), 64);
            result = new MatrixCursor(projection, n);
            final App app = (App) ctx.getApplicationContext();
            final ThumbsManager tm = app.getThumbsManager();
            final long now = System.currentTimeMillis();
            int counter = 0;
            for (File file : this.downloads) {
                if (file == null) continue;
                if (now - file.lastModified() > MAXAGE_FOR_RECENTS) break;
                includeFile(projection, result, file, tm.hasCachedThumbnail(file), app.isBeingDownloaded(file));
                counter++;
                if (counter == n) break;
            }
        }
        return result;
    }

    /** {@inheritDoc} <br>
     * The projection parameter seems to be always {@code null}.
     */
    @Override
    public synchronized Cursor queryRoots(@Nullable String[] projection) {
        final MatrixCursor result = new MatrixCursor(DEFAULT_ROOT_PROJECTION, 1);
        final Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return result;
        final int n;
        synchronized (sync) {
            n = this.downloads.size();
        }
        final boolean empty = (n == 0);
        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher);
        row.add(DocumentsContract.Root.COLUMN_TITLE, BuildConfig.DEBUG ? ctx.getString(R.string.label_document_provider) + "-Ⓓ" : ctx.getString(R.string.label_document_provider));
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC);
        row.add(DocumentsContract.Root.COLUMN_FLAGS, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && empty
                ? DocumentsContract.Root.FLAG_EMPTY | DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_RECENTS | DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
                : DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_RECENTS | DocumentsContract.Root.FLAG_SUPPORTS_SEARCH);
        if (empty) {
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, ctx.getResources().getString(R.string.label_root_summary_empty));
        } else {
            long total = 0L;
            synchronized (sync) {
                for (File file : this.downloads) {
                    if (file == null) continue;
                    total += file.length();
                }
            }
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, ctx.getResources().getQuantityString(R.plurals.label_root_summary, n, n, UiUtil.formatBytes(total)));
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Cursor querySearchDocuments(String rootId, String query, @Nullable String[] projection) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "querySearchDocuments(\"" + rootId + "\", \"" + query + "\", " + Arrays.toString(projection) + ")") ;
        if (!ROOT_ID.equals(rootId) || query == null) throw new FileNotFoundException(rootId);
        if (projection == null) projection = DEFAULT_DOC_PROJECTION;
        Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return new MatrixCursor(projection, 0);
        final String queryLower = query.trim().toLowerCase();
        final App app = (App)ctx.getApplicationContext();
        final ThumbsManager tm = app.getThumbsManager();
        final MatrixCursor result = new MatrixCursor(projection);
        synchronized (sync) {
            for (File file : this.downloads) {
                if (file == null) continue;
                final boolean loading = app.isBeingDownloaded(file);
                boolean match = false;
                if (file.getName().toLowerCase().contains(queryLower)) {
                    // match if the file name matches
                    match = true;
                } else if (!loading) {
                    // don't try to look into files that are being loaded right now
                    final String mime = getMime(file.getAbsolutePath());
                    if (mime.startsWith("text/") && file.length() <= 1_000_000L) {
                        // match if the file content matches
                        match = containsText(queryLower, file);
                    } else if (ExifInterface.isSupportedMimeType(mime)) {
                        // match if an EXIF tag matches
                        InputStream in = null;
                        try {
                            in = new FileInputStream(file);
                            Bundle metadata = MetadataReader.getMetadata(in, MetadataReader.FOR_SEARCHES);
                            @SuppressLint("InlinedApi")
                            Bundle exifData = metadata.getBundle(DocumentsContract.METADATA_EXIF);
                            if (MetadataReader.match(queryLower, exifData)) match = true;
                        } catch (Exception ignored) {
                        } finally {
                            Util.close(in);
                        }
                    } else if ("application/zip".equals(mime)) {
                        // match if a zip file entry name matches
                        ZipFile zipFile = new ZipFile(file);
                        try {
                            final List<FileHeader> fileHeaderList = zipFile.getFileHeaders();
                            for (FileHeader fileHeader : fileHeaderList) {
                                String relativePath = fileHeader.getFileName();
                                if (relativePath != null && relativePath.toLowerCase().contains(queryLower)) {
                                    match = true;
                                    break;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (match) includeFile(projection, result, file, tm.hasCachedThumbnail(file), loading);
            }
        }
        return result;
    }

    @VisibleForTesting
    public synchronized void refresh() {
        this.mimeCache.evictAll();
        synchronized (sync) {
            boolean wasEmpty = this.downloads.isEmpty();
            this.downloads.clear();
            if (this.dir == null) return;
            File[] rf = this.dir.isDirectory() ? this.dir.listFiles() : new File[0];
            if (rf != null) {
                List<File> fileList = Arrays.asList(rf);
                Collections.sort(fileList, (o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
                this.downloads.addAll(fileList);
            }
            if (!this.downloads.isEmpty() && wasEmpty) {
                // for cases when DocumentsContract.Root.FLAG_EMPTY has been added:
                // when the first download is added, context.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null, false); must be called
                Context ctx = debugContext != null ? debugContext : getContext();
                if (ctx != null) ctx.getContentResolver().notifyChange(this.rootsUri, null, false);
            }
        }
    }

    /** {@inheritDoc}<br><br>
     * The uri parameter is "content://net.cellar.dogs/document/Cellar"
     */
    @Override
    public boolean refresh(Uri uri, @Nullable Bundle extras, @Nullable CancellationSignal cancellationSignal) {
        if (BuildConfig.DEBUG) Log.i(TAG, "refresh(\"" + uri + "\", " + extras + ", " + cancellationSignal + ")");
        refresh();
        Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx != null) ctx.getContentResolver().notifyChange(uri, null, false);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    @RequiresApi(21)
    public String renameDocument(final String documentId, String displayName) throws FileNotFoundException {
        if (BuildConfig.DEBUG) Log.i(TAG, "renameDocument(\"" + documentId + "\", \"" + displayName + "\")");
        if (documentId == null) throw new FileNotFoundException();
        final File file = new File(documentId);
        if (!file.isFile()) throw new FileNotFoundException(documentId);
        if (!file.canWrite()) {
            // return null to indicate that nothing has happened
            return null;
        }
        if (TextUtils.isEmpty(displayName)) {
            // return null to indicate that nothing has happened
            return null;
        }

        if (displayName.indexOf(File.separatorChar) >= 0) throw new UnsupportedOperationException("Name must not contain " + File.separatorChar);
        displayName = displayName.trim();
        if (displayName.length() == 0) {
            // return null to indicate that nothing has happened
            return null;
        }
        int displayNameDot = displayName.lastIndexOf('.');
        final String displayNameWithoutExtension = Util.sanitizeFilename(displayNameDot > 0 ? displayName.substring(0, displayNameDot) : displayName).toString();
        final Context ctx = debugContext != null ? debugContext : getContext();
        if (ctx == null) return null;
        final String fileName = file.getName();
        final String parent = file.getParent();
        int fileNameDot = fileName.lastIndexOf('.');
        String fileExtensionWithDot = fileNameDot > 0 ? fileName.substring(fileNameDot).toLowerCase() : null;
        File renamed = new File(parent, displayNameWithoutExtension + fileExtensionWithDot);
        if (file.equals(renamed)) {
            // things like this seem to be avoided by the documents ui
            return null;
        }
        String alt = Util.suggestAlternativeFilename(renamed);
        if (alt != null) {
            renamed = new File(parent, alt);
        }
        if (file.renameTo(renamed)) {
            this.mimeCache.remove(documentId);
            try {
                revokeDocumentPermission(documentId);
            } catch (NullPointerException ignored) {
                // this will happen during tests
            }
            refresh();
            ((App)ctx.getApplicationContext()).getThumbsManager().renameThumbnail(file, renamed);
            Ancestry.getInstance().transfer(file, renamed);
            ctx.getContentResolver().notifyChange(this.notifyUri, null, false);
            if (BuildConfig.DEBUG) Log.i(TAG, "Renamed \"" + documentId + "\" to \"" + renamed + "\"");
            return renamed.getAbsolutePath();
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "Cannot rename \"" + documentId + "\" to \"" + renamed + "\"");
        }
        return null;
    }

    /**
     * Accepts a Context in test surroundings.
     * @param ctx Context
     */
    @VisibleForTesting
    @TestOnly
    public void setDebugContext(Context ctx) {
        if (!BuildConfig.DEBUG) return;
        this.debugContext = ctx;
        if (this.debugContext == null) return;
        // the rootsUri would be used for notifications when the roots change - see refresh()
        this.rootsUri = DocumentsContract.buildRootsUri(AUTHORITY);
        this.dir = App.getDownloadsDir(this.debugContext);
        this.notifyUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, ROOT_DOC);
        refresh();
        deleteCustomThumbnails();
    }

    /**
     * Compares two files by their mime types.
     * As a matter of fact, this is probably never used because the documents ui does the sorting by mime type itself.
     */
    private class MimeTypeComparator implements Comparator<File> {

        private final boolean asc;

        private MimeTypeComparator(boolean asc) {
            this.asc = asc;
        }

        @Override
        public int compare(File o1, File o2) {
            String m1 = getMime(o1.getAbsolutePath());
            String m2 = getMime(o2.getAbsolutePath());
            return this.asc ? m1.compareToIgnoreCase(m2) : m2.compareToIgnoreCase(m1);
        }

    }
}

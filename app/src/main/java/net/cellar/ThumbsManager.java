/*
 * ThumbsManager.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;
import androidx.exifinterface.media.ExifInterface;

import net.cellar.supp.Log;
import net.cellar.supp.Util;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import org.beyka.tiffbitmapfactory.TiffBitmapFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wseemann.media.FFmpegMediaMetadataRetriever;

import static android.provider.DocumentsContract.buildDocumentUri;

/**
 * Manages thumbnails for downloaded files.
 */
public class ThumbsManager {

    /** bitmaps are restricted to this {@link Bitmap#getAllocationByteCount() allocation byte count} to ward off crazy data */
    public static final int MAX_ALLOCATION_COUNT = 24_000_000;
    public static final int MIN_THUMBNAIL_SIZE = 16;
    /** the format to store the thumbnail pictures in  - {@link #EXTENSION} should match this */
    private static final Bitmap.CompressFormat CFORMAT = Bitmap.CompressFormat.JPEG;
    /** if the {@link ThumbCreator} returns this, the result shall be ignored because creation is going on in the background */
    private static final Bitmap DUMMY = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    /** the file extension matching {@link #CFORMAT} */
    private static final String EXTENSION = ".jpg";
    /** name of the text file that contains the files that the creation of a thumbnail failed for (located in the cache folder) */
    private static final String FAILED_FILE = "failed";
    private static final char FAILED_FILE_SEP = ' ';
    /** the max. length of bitmap data acceptable from {@link FFmpegMediaMetadataRetriever#getEmbeddedPicture()} [bytes] */
    private static final int MAX_ARTWORK_LENGTH = 10_000_000;
    /** max. width and max. height of a source bitmap [pixels] */
    private static final int MAX_SOURCE_BMP_SIZE = 10_000;
    /** min. width and max. height of a source bitmap [pixels] */
    private static final int MIN_SOURCE_BMP_SIZE = 64;
    @RequiresApi(Build.VERSION_CODES.O)
    private static final BitmapFactory.Options OPTS_HW = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new BitmapFactory.Options() : null;
    private static final BitmapFactory.Options OPTS_RGB_565 = new BitmapFactory.Options();
    /** delay after which the cached thumbnail files will be stored after a modification of {@link #thumbsCache} */
    private static final long STORE_DELAY = 10_000L;
    private static final String TAG = "ThumbsManager";
    private static final TiffBitmapFactory.Options TIF_OPTS = new TiffBitmapFactory.Options();

    static {
        try {
            FFmpegMediaMetadataRetriever.IN_PREFERRED_CONFIG = Bitmap.Config.RGB_565;
        } catch (UnsatisfiedLinkError ule) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Some FFmpegMediaMetadataRetriever component is missing: " + ule);
            System.exit(-3);
        }
        OPTS_RGB_565.inPreferredConfig = Bitmap.Config.RGB_565;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            OPTS_HW.inPreferredConfig = Bitmap.Config.HARDWARE;
        }
        TIF_OPTS.inAvailableMemory = 1_024_576L;
        TIF_OPTS.inThrowException = false;
        TIF_OPTS.inPreferredConfig = TiffBitmapFactory.ImageConfig.RGB_565;
    }

    /**
     * Creates a thumbnail for the given image, video or audio file.
     * @param mmr FFmpegMediaMetadataRetriever
     * @param ctx Context
     * @param file File
     * @param w requested width
     * @return Bitmap
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @Nullable
    private static Bitmap createThumbnail(@NonNull final FFmpegMediaMetadataRetriever mmr, @NonNull Context ctx, @NonNull final File file, @IntRange(from = MIN_THUMBNAIL_SIZE) final int w) {
        // this method would have to be synchronized if used from different threads with one singular FFmpegMediaMetadataRetriever
        final String name = file.getName().toLowerCase(java.util.Locale.US);
        Bitmap bitmap = null;
        int rotation = 0;
        try {
            if (Util.isPicture(name)) {
                // case 1: simple picture supported by Android natively
                bitmap = decodeImage(file, w);
                if (bitmap != null) rotation = Util.getImageOrientation(file);
                if (BuildConfig.DEBUG && bitmap == null) Log.w(TAG, "Failed to decode " + file);
            } else if (Util.isTiffPicture(name)) {
                // case 2: TIFF picture
                bitmap = TiffBitmapFactory.decodeFile(file, TIF_OPTS);
                if (bitmap != null) rotation = Util.getImageOrientation(file);
                if (BuildConfig.DEBUG && bitmap == null) Log.w(TAG, "TiffBitmapFactory failed to decode " + file);
            } else if (name.endsWith(".epub")) {
                // case 3: EPUB file
                bitmap = extractEpub(file);
            } else if (name.endsWith(".apk")) {
                // case 4: APK file
                bitmap = extractApk(file);
            } else if (name.endsWith(".pdf")) {
                // case 5: PDF file
                bitmap = extractPdf(file, w);
            } else if (name.endsWith(".ttf")) {
                // case 6: TTF file
                bitmap = extractTtf(file, w);
            } else {
                // case 7: let FFmpegMediaMetadataRetriever try it
                if (Util.isMovie(name)) {
                    mmr.setDataSource(file.getAbsolutePath());
                    bitmap = mmr.getFrameAtTime(2_000_000L, FFmpegMediaMetadataRetriever.OPTION_CLOSEST);
                    if (bitmap == null) bitmap = mmr.getFrameAtTime(4_000_000L, FFmpegMediaMetadataRetriever.OPTION_CLOSEST);
                    if (BuildConfig.DEBUG && bitmap == null) Log.w(TAG, "FFmpegMediaMetadataRetriever failed to extract a thumbnail from movie " + file);
                } else if (Util.isAudio(name)) {
                    mmr.setDataSource(ctx, Uri.fromFile(file));
                    byte[] artwork = mmr.getEmbeddedPicture();
                    bitmap = artwork != null && artwork.length > 0 && artwork.length <= MAX_ARTWORK_LENGTH ? BitmapFactory.decodeByteArray(artwork, 0, artwork.length, OPTS_RGB_565) : null;
                    if (BuildConfig.DEBUG && bitmap == null) Log.w(TAG, "FFmpegMediaMetadataRetriever failed to extract a thumbnail from audio " + file);
                }
            }
            if (bitmap == null && ExifInterface.isSupportedMimeType(Util.getMime(file))) {
                // case 8: let ExifInterface try it
                bitmap = new ExifInterface(file).getThumbnailBitmap();
                if (BuildConfig.DEBUG && bitmap == null) Log.w(TAG, "ExifInterface failed to extract a thumbnail from " + file);
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to create thumbnail for " + file + ": " + e.toString(), e);
        }
        if (bitmap == null) return null;
        if (!isSourceBitmapValid(bitmap)) {
            // allocation byte count: 141955200 (120x295740 px)
            if (BuildConfig.DEBUG) Log.w(TAG, "Discarding " + bitmap.getWidth() + "x" + bitmap.getHeight() + " px bitmap for '" + name + "' with allocation byte count of " + bitmap.getAllocationByteCount());
            bitmap.recycle();
            return null;
        }

        // cut a square from the center
        final int bmw = bitmap.getWidth();
        final int bmh = bitmap.getHeight();
        if (bmw <= 1 || bmh <= 1) {
            return null;
        }
        Matrix m = null;
        if (rotation != 0) {
            m = new Matrix();
            m.preRotate(rotation, bmw >> 1, bmh >> 1);
        }
        if (bmw != bmh) {
            // not a square bitmap
            int squareSize = Math.min(bmw, bmh);
            if (squareSize % 2 == 1) squareSize--;
            Bitmap crop;
            if (w != squareSize) {
                if (m == null) m = new Matrix();
                float scale = (float) w / (float) squareSize;
                m.postScale(scale, scale);
            }
            if (bmw > bmh) {
                int xoffset = (bmw - bmh) >> 1;
                crop = Bitmap.createBitmap(bitmap, xoffset, 0, squareSize, squareSize, m, false);
            } else {
                int yoffset = (bmh - bmw) >> 1;
                crop = Bitmap.createBitmap(bitmap, 0, yoffset, squareSize, squareSize, m, false);
            }
            bitmap.recycle();
            bitmap = crop;
        } else if (bmw != w) {
            // already square but too small or too large
            if (m == null) m = new Matrix();
            float scale = w / (float) bmw;
            m.postScale(scale, scale);
            Bitmap scaled = Bitmap.createBitmap(bitmap, 0, 0, bmw, bmh, m, false);
            bitmap.recycle();
            bitmap = scaled;
        } else if (rotation != 0) {
            // perfectly sized, but needs rotation
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bmw, bmh, m, false);
            bitmap.recycle();
            bitmap = rotated;
        }
        return bitmap;
    }


    /**
     * Decodes an image file.
     * @param file File to decode
     * @param requestedSize requested size
     * @return Bitmap or {@code null}
     */
    @Nullable
    private static Bitmap decodeImage(final File file, final int requestedSize) {
        FileInputStream in = null;
        Bitmap bm = null;
        try {
            in = new FileInputStream(file);
            BitmapFactory.Options optsForDecodeBounds = new BitmapFactory.Options();
            optsForDecodeBounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, optsForDecodeBounds);
            int w = optsForDecodeBounds.outWidth;
            int h = optsForDecodeBounds.outHeight;
            Util.close(in);
            in = new FileInputStream(file);
            if (w > requestedSize || h > requestedSize) {
                BitmapFactory.Options optsForDecoding = new BitmapFactory.Options();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    optsForDecoding.inPreferredConfig = Bitmap.Config.HARDWARE;
                } else {
                    optsForDecoding.inPreferredConfig = Bitmap.Config.RGB_565;
                }
                optsForDecoding.inSampleSize = Math.max(1, Math.min(w, h) / requestedSize);
                bm = BitmapFactory.decodeStream(in, null, optsForDecoding);
                //if (BuildConfig.DEBUG && bm != null) Log.i(TAG, "Created a " + bm.getWidth() + "x" + bm.getHeight() + "-px thumb for " + file.getName() + " (" + w + "x" + h + ") - sample size was " + o565.inSampleSize);
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bm = BitmapFactory.decodeStream(in, null, OPTS_HW);
                } else {
                    bm = BitmapFactory.decodeStream(in, null, OPTS_RGB_565);
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While decoding bitmap from \"" + file + "\": " + e.toString());
        } finally {
            Util.close(in);
        }
        return bm;
    }

    /**
     * Attempts to extract a bitmap from an apk file.
     * @param apkFile apk file
     * @return Bitmap or {@code null}
     */
    @Nullable
    private static Bitmap extractApk(final File apkFile) {
        if (apkFile == null || !apkFile.isFile()) return null;
        ZipFile zipFile = new ZipFile(apkFile);
        if (!zipFile.isValidZipFile()) return null;
        Bitmap bm = null;
        File tmpDir = null;
        try {
            final List<FileHeader> fileHeaderList = zipFile.getFileHeaders();
            final String dir = System.getProperty("java.io.tmpdir");
            if (dir == null) return null;
            File cacheDir = new File(dir);
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return null;
            tmpDir = new File(cacheDir, "tmp" + System.currentTimeMillis());
            if (!tmpDir.mkdirs()) return null;
            int widestMipSoFar = MIN_THUMBNAIL_SIZE - 1, highestMipSoFar = MIN_THUMBNAIL_SIZE - 1;
            int widestBitSoFar = MIN_THUMBNAIL_SIZE - 1, highestBitSoFar = MIN_THUMBNAIL_SIZE - 1;
            Bitmap bestMip = null, bestBit = null;
            for (FileHeader fileHeader : fileHeaderList) {
                final String relativePath = fileHeader.getFileName();
                if (!relativePath.endsWith(".png") || relativePath.endsWith(".9.png")) continue;
                boolean mip = relativePath.startsWith("res/mipmap-");
                boolean bit = relativePath.startsWith("res/drawable-");
                if (!mip && !bit) continue;
                // if we already have a mipmap, skip all res/drawable- files
                if (bestMip != null && bit) continue;
                //
                File extracted = null;
                bm = null;
                try {
                    zipFile.extractFile(fileHeader, tmpDir.getAbsolutePath());
                    extracted = new File(tmpDir, relativePath);
                    if (!extracted.isFile()) continue;
                    bm = BitmapFactory.decodeFile(extracted.getAbsolutePath(), OPTS_RGB_565);
                } catch (Exception ignored) {
                } finally {
                    Util.deleteFile(extracted);
                }
                if (bm == null) continue;
                // prefer res/mipmap over res/drawable
                if (mip) {
                    if (bm.getWidth() > widestMipSoFar && bm.getHeight() > highestMipSoFar) {
                        bestMip = bm;
                        widestMipSoFar = bm.getWidth();
                        highestMipSoFar = bm.getHeight();
                    } else {
                        bm.recycle();
                    }
                } else {
                    if (bm.getWidth() > widestBitSoFar && bm.getHeight() > highestBitSoFar) {
                        bestBit = bm;
                        widestBitSoFar = bm.getWidth();
                        highestBitSoFar = bm.getHeight();
                    } else {
                        bm.recycle();
                    }
                }
            }
            // prefer mipmap over bitmap
            if (bestMip != null) bm = bestMip; else bm = bestBit;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While extracting bitmap from \"" + apkFile + "\": " + e.toString());
        }
        if (tmpDir != null) Util.deleteDirectory(tmpDir);
        return bm;
    }

    /**
     * Attempts to extract a cover picture from an epub file.
     * @param epubFile epub file
     * @return Bitmap or {@code null}
     */
    @Nullable
    private static Bitmap extractEpub(@Nullable final File epubFile) {
        if (epubFile == null || !epubFile.isFile()) return null;
        final ZipFile zipFile = new ZipFile(epubFile);
        Bitmap bm = null;
        try {
            final List<FileHeader> fileHeaderList = zipFile.getFileHeaders();
            for (FileHeader fileHeader : fileHeaderList) {
                String relativePath = fileHeader.getFileName();
                if (relativePath.contains("cover") && Util.isPicture(relativePath)) {
                    File extracted = null;
                    try {
                        String dir = System.getProperty("java.io.tmpdir");
                        zipFile.extractFile(fileHeader, dir);
                        extracted = new File(dir, fileHeader.getFileName());
                        bm = BitmapFactory.decodeFile(extracted.getAbsolutePath(), OPTS_RGB_565);
                    } catch (Exception ignored) {
                    } finally {
                        Util.deleteFile(extracted);
                    }
                    if (bm != null) break;
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While extracting bitmap from \"" + epubFile + "\": " + e.toString());
        }
        return bm;
    }

    /**
     * Attempts to extract a picture from a pdf file.
     * @param pdfFile pdf file
     * @return Bitmap or {@code null}
     */
    @Nullable
    @RequiresApi(21)
    private static Bitmap extractPdf(final File pdfFile, @IntRange(from = ThumbsManager.MIN_THUMBNAIL_SIZE) int size) {
        if (pdfFile == null || !pdfFile.isFile()) return null;
        long t = System.currentTimeMillis();
        PdfRenderer renderer = null;
        ParcelFileDescriptor pfd = null;
        Bitmap bm = null;
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            if (renderer.getPageCount() > 0) {
                bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                PdfRenderer.Page page = renderer.openPage(0);
                if (page != null) {
                    Matrix m;
                    int pageWidth = page.getWidth();
                    int pageHeight = page.getHeight();
                    if (pageWidth != pageHeight && pageHeight > 0) {
                        m = new Matrix();
                        float scaleX = (float)size / page.getWidth();
                        float scaleY = (float)size / page.getHeight();
                        float translateX = 0f, translateY = 0f;
                        float pageRatio = (float)page.getWidth() / (float)page.getHeight();
                        if (pageRatio < 1f) {
                            // page is higher than wide (portrait)
                            scaleX *= pageRatio;
                            translateX = size / 2f - pageWidth * scaleX / 2f;
                        } else {
                            // page is wider than high (landscape)
                            scaleY /= pageRatio;
                            translateY = size / 2f - pageHeight * scaleY / 2f;
                        }
                        //if (BuildConfig.DEBUG) Log.i(TAG, "Page ratio: " + pageRatio + "; scale " + scaleX + ", " + scaleY + ", trans " +translateX + ", " +  translateY);
                        m.postScale(scaleX, scaleY);
                        m.postTranslate(translateX, translateY);
                    } else {
                        m = null;
                    }
                    page.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While decoding bitmap from \"" + pdfFile + "\": " + e.toString());
        } finally {
            if (renderer != null) renderer.close();
            else Util.close(pfd);   // usually the ParcelFileDescriptor is closed by the PdfRenderer
        }
        if (BuildConfig.DEBUG && bm != null) Log.i(TAG, "extractPdf(" + pdfFile + ", " + size + ") took " + (System.currentTimeMillis() - t) + " ms for a " + bm.getWidth() + " x " + bm.getHeight() + "-px bitmap");
        return bm;
    }

    /**
     * Attempts to build an icon for a font file.
     * @param ttfFile font file
     * @return Bitmap or {@code null}
     */
    @Nullable
    private static Bitmap extractTtf(File ttfFile, @IntRange(from = ThumbsManager.MIN_THUMBNAIL_SIZE) int size) {
        if (ttfFile == null || !ttfFile.isFile()) return null;
        Typeface tf = Typeface.createFromFile(ttfFile);
        if (tf == null) return null;
        return Util.makeCharBitmap("abc", size / 2f, size, size, Color.BLACK, Color.WHITE, null, tf);
    }

    /**
     * @param ctx Context
     * @return thumbnail width in pixels
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    @IntRange(from = ThumbsManager.MIN_THUMBNAIL_SIZE)
    public static int getSuggestedThumbnailWidth(@NonNull Context ctx) {
        return ctx.getResources().getDimensionPixelSize(R.dimen.logo_size);
    }

    /**
     * Checks a potential source bitmap for size and byte allocation restrictions.
     * @param bm Bitmap to check
     * @return {@code true} if the bitmap is considered to be valid.
     */
    private static boolean isSourceBitmapValid(@NonNull final Bitmap bm) {
        return bm.getWidth() >= MIN_SOURCE_BMP_SIZE && bm.getHeight() >= MIN_SOURCE_BMP_SIZE
                && bm.getWidth() <= MAX_SOURCE_BMP_SIZE && bm.getHeight() <= MAX_SOURCE_BMP_SIZE
                && bm.getAllocationByteCount() <= MAX_ALLOCATION_COUNT;
    }

    /**
     * Checks whether a thumbnail can probably be created for the given file.
     * @param file file to check
     * @return {@code true} if the given file is probably supported
     */
    public static boolean probablySupported(@Nullable File file) {
        if (file == null) return false;
        final String name = file.getName();
        if (Util.isPicture(name) || Util.isTiffPicture(name) || Util.isMovie(name) || Util.isAudio(name)
                || name.endsWith(".epub") || name.endsWith(".pdf") || name.endsWith(".apk") || name.endsWith(".ttf"))
            return true;
        String mime = Util.getMime(file);
        return ExifInterface.isSupportedMimeType(mime);
    }

    /** Files that the creation of a thumbnail failed for */
    @NonNull private final Set<File> failedThumbs = Collections.synchronizedSet(new HashSet<>());

    /*
     * 38 download files with 30 thumbnail files
     *
     * lrucache size            hits        misses
     * 6                        0           68
     * 28                       46          20
     * 38                       52          14
     * 48                       52          14
     */
    /** Maps file names to thumbnail bitmaps */
    @NonNull private final LruCache<String, Bitmap> thumbsCache = new LruCache<>(128);
    /** These receive a notification when the thumbnail pictures have been loaded */
    @NonNull private final Set<Reference<OnThumbLoadedListener>> listeners = new HashSet<>(2);
    @NonNull private final Set<ThumbsManager.ThumbCreator> thumbCreators = Collections.synchronizedSet(new HashSet<>());
    @NonNull private final Handler handler = new Handler();
    /** used to control access to the {@link #iconDir icons' directory} */
    @NonNull private final Object syncdir = new Object();
    /** the {@link #SUBDIR} subfolder within the app's cache folder */
    @GuardedBy("syncdir")
    @NonNull private final File iconDir;
    /** the folder that the downloads are stored in */
    @NonNull private final File downloadsDir;
    /** the app's cache folder */
    @NonNull private final File cacheDir;
    private ExecutorService executor;
    /** {@code true} if the memory cache has been cleared */
    private volatile boolean needsReloading;
    /** Loads the thumbnail pictures asynchronously */
    @Nullable private Thread loader;
    /** Stores the thumbnail pictures asynchronously */
    @Nullable private Thread storer;
    @NonNull private final Runnable storerInvoker = this::store;

    /**
     * Constructor.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    ThumbsManager(@NonNull Context ctx) {
        super();
        this.cacheDir = ctx.getCacheDir();
        this.downloadsDir = App.getDownloadsDir(ctx);
        this.iconDir = Util.getFilePath(ctx, App.FilePath.ICONS, true);
        load();
    }

    /**
     * Adds an OnThumbLoadedListener.
     * @param listener OnThumbLoadedListener
     */
    void addListener(@Nullable final OnThumbLoadedListener listener) {
        if (listener == null) return;
        synchronized (this.listeners) {
            for (Reference<OnThumbLoadedListener> existing : this.listeners) {
                OnThumbLoadedListener l = existing.get();
                if (l == listener) {
                    if (BuildConfig.DEBUG) Log.w(TAG, listener + " had already been added as a listener!");
                    return;
                }
            }
            this.listeners.add(new WeakReference<>(listener));
        }
    }

    /**
     * Adds a thumbnail for the given download file.
     * @param file download file
     * @param thumbnail bitmap
     * @param storingNeeded {@code true} if the cache contents should be written to persistent memory
     */
    void addThumbnail(@Nullable File file, @Nullable Bitmap thumbnail, boolean storingNeeded) {
        if (file == null || thumbnail == null) {
            return;
        }
        synchronized (this.thumbsCache) {
            this.thumbsCache.put(file.getName(), thumbnail);
        }
        if (!storingNeeded) return;
        this.handler.removeCallbacks(this.storerInvoker);
        this.handler.postDelayed(this.storerInvoker, STORE_DELAY);
    }

    /**
     * Adds a thumbnail for the given download file.
     * @param file download file
     * @param thumbnail bitmap
     */
    public void addThumbnail(@Nullable File file, @Nullable Bitmap thumbnail) {
        addThumbnail(file, thumbnail, true);
    }

    /**
     * Removes thumbnail files for which no matching download exists (any more).<br>
     * {@link #removeThumbnail(File)} should have been called whenever a download had been deleted,
     * but if not, this method takes care of that.
     * @param downloads array of download files
     */
    private void checkObsolete(final File[] downloads) {
        if (downloads == null) return;
        Map<String, Bitmap> snapshot;
        synchronized (this.thumbsCache) {
            snapshot = this.thumbsCache.snapshot();
        }
        final Set<File> iconFilesToDelete = new HashSet<>(1);
        final Set<String> iconFileNames = snapshot.keySet();
        for (String iconFileName : iconFileNames) {
            boolean found = false;
            for (File download : downloads) {
                if (download.getName().equals(iconFileName)) {
                    found = true;
                }
            }
            if (!found) {
                if (BuildConfig.DEBUG) Log.w(TAG, "No matching download found for thumbnail '" + iconFileName + "' - deleting thumbnail");
                iconFilesToDelete.add(makeIconFile(iconFileName));
            }
        }
        if (!iconFilesToDelete.isEmpty()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Found " + downloads.length + " download(s) and " + iconFileNames.size() + " thumbnail(s) of which " + iconFilesToDelete.size() + " is/are obsolete.");
            synchronized (syncdir) {
                for (File iconFileToDelete : iconFilesToDelete) {
                    if (!iconFileToDelete.isFile()) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "Not a file: " + iconFileToDelete);
                        continue;
                    }
                    Util.deleteFile(iconFileToDelete);
                }
            }
        }
    }

    void clearCache() {
        if (this.needsReloading) return;
        this.handler.removeCallbacks(this.storerInvoker);
        synchronized (this.thumbsCache) {
            this.thumbsCache.evictAll();
        }
        this.needsReloading = true;
    }

    /**
     * Finds out whether all {@link ThumbCreator ThumbCreators} have finished.
     * If so, clears the collection of ThumbCreators (because they are not needed anymore).
     */
    public void clearCreators() {
        if (this.thumbCreators.isEmpty()) return;
        for (ThumbCreator tc : this.thumbCreators) {
            if (tc.getStatus() != AsyncTask.Status.FINISHED) return;
        }
        this.thumbCreators.clear();
    }

    /**
     * Thumbnail creation for the given file has failed.
     * @param file File
     */
    @AnyThread
    void failed(@Nullable File file) {
        if (file == null) return;
        this.failedThumbs.add(file);
    }

    /**
     * Returns the icon file for a given download file if the icon file exists.
     * @param downloadFile download file
     * @return icon file for the given download file or {@code null}
     * @throws NullPointerException if {@code downloadFile} is {@code null}
     */
    @Nullable
    public File getExistingIconFile(@NonNull File downloadFile) {
        File iconFile = makeIconFile(downloadFile.getName());
        boolean isFile;
        synchronized (syncdir) {
            isFile = iconFile.isFile();
        }
        return isFile ? iconFile : null;
    }

    /**
     * Returns a previously created thumbnail for the given download file.
     * @param file download file
     * @return Bitmap
     */
    @Nullable
    @UiThread
    Bitmap getThumbnail(@Nullable File file) {
        if (file == null) return null;
        final String fileName = file.getName();
        if (this.needsReloading) load();
        Bitmap bm;
        synchronized (this.thumbsCache) {
            bm = this.thumbsCache.get(fileName);
        }
        if (bm == null && thumbnailExistsAsFile(file)) {
            if (Build.VERSION.SDK_INT >= 26) {
                bm = BitmapFactory.decodeFile(makeIconFile(fileName).getAbsolutePath(), OPTS_HW);
            } else {
                bm = BitmapFactory.decodeFile(makeIconFile(fileName).getAbsolutePath(), OPTS_RGB_565);
            }
            if (bm != null) {
                addThumbnail(file, bm, false);
            } else if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to load thumbnail for \"" + fileName + "\"!");
            }
        }
        return bm;
    }

    /**
     * Determines whether a cached thumbnail file for the given download file exists.
     * @param file download file
     * @return true / false
     */
    boolean hasCachedThumbnail(@Nullable File file) {
        boolean has;
        if (this.needsReloading) load();
        synchronized (this.thumbsCache) {
            has = file != null && this.thumbsCache.get(file.getName()) != null;
        }
        return has;
    }

    /**
     * Tells whether thumbnail creation has failed.
     * @param file download file to check
     * @return {@code true} if thumbnail creation has failed for the given file
     */
    boolean hasFailed(@Nullable File file) {
        return file != null && this.failedThumbs.contains(file);
    }

    /**
     *
     * @param downloadFile download file
     * @return {@code true} if an icon file exists
     * @throws NullPointerException if {@code downloadFile} is {@code null}
     */
    public boolean hasIconFile(@NonNull File downloadFile) {
        File iconFile = makeIconFile(downloadFile.getName());
        boolean isFile;
        synchronized (syncdir) {
            isFile = iconFile.isFile();
        }
        return isFile;
    }

    /**
     * Loads thumbnail images from persistent memory. May take a few hundred ms.
     */
    private void load() {
        if (this.loader != null && this.loader.isAlive()) return;
        this.needsReloading = false;
        this.loader = new Thread() {
            @Override
            public void run() {
                final File[] files;
                synchronized (ThumbsManager.this.syncdir) {
                    if (!ThumbsManager.this.iconDir.isDirectory()) return;
                    files = ThumbsManager.this.iconDir.listFiles();
                }
                boolean atLeastOneLoaded = false;
                if (files != null && files.length > 0) {
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        options.inPreferredConfig = Bitmap.Config.HARDWARE;
                    } else {
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                    }
                    options.inTempStorage = new byte[16_384];
                    for (File file : files) {
                        if (!file.getName().endsWith(EXTENSION)) continue;
                        Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                        if (bm == null) {
                            if (BuildConfig.DEBUG) Log.e(TAG, "Failed to decode " + file);
                            continue;
                        }
                        String fileName = file.getName();
                        String fileNameWithoutExtension = fileName.substring(0, fileName.length() - EXTENSION.length());
                        atLeastOneLoaded = true;
                        synchronized (ThumbsManager.this.thumbsCache) {
                            ThumbsManager.this.thumbsCache.put(fileNameWithoutExtension, bm);
                        }
                    }
                }
                //
                ThumbsManager.this.failedThumbs.clear();
                File failedFile = new File(ThumbsManager.this.cacheDir, FAILED_FILE);
                if (!failedFile.isFile()) return;
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(failedFile)));
                    for (; ; ) {
                        String line = reader.readLine();
                        if (line == null) break;
                        int space = line.lastIndexOf(FAILED_FILE_SEP);
                        if (space <= 0) continue;
                        long lastModified = Long.parseLong(line.substring(space + 1));
                        File f = new File(line.substring(0, space));
                        if (!f.isFile() || f.lastModified() != lastModified) {
                            if (BuildConfig.DEBUG) Log.i(TAG, "Discarding failure \"" + line + "\"");
                            continue;
                        }
                        failed(f);
                    }
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "While loading list of failed files: " + e.toString());
                } finally {
                    Util.close(reader);
                }
                if (atLeastOneLoaded) ThumbsManager.this.handler.post(() -> notifyListeners());
                File[] downloads = ThumbsManager.this.downloadsDir.listFiles();
                checkObsolete(downloads);

            }
        };
        this.loader.start();
    }

    /**
     * Creates the thumbnail file for a download file.
     * @param name download file name
     * @return thumbnail file (not necessarily existent)
     */
    @NonNull
    public File makeIconFile(@NonNull String name) {
        return new File(this.iconDir, name + EXTENSION);
    }

    /**
     * Notifies listeners that one or more thumbnails have been loaded from persistent memory.
     */
    @UiThread
    private void notifyListeners() {
        int nl;
        synchronized (this.listeners) {
            nl = this.listeners.size();
        }
        if (nl == 0) return;
        final Set<Reference<OnThumbLoadedListener>> abandoned = new HashSet<>(nl);
        synchronized (this.listeners) {
            for (Reference<OnThumbLoadedListener> ref : this.listeners) {
                OnThumbLoadedListener l = ref.get();
                if (l == null) {
                    abandoned.add(ref);
                    continue;
                }
                l.thumbnailsLoaded();
            }
            this.listeners.removeAll(abandoned);
        }
    }

    /**
     * Returns an AssetFileDescriptor pointing to a thumbnail icon file.
     * @param downloadFile download file
     * @return AssetFileDescriptor
     * @throws FileNotFoundException if the icon file does not exist
     */
    @NonNull
    public AssetFileDescriptor openIconFile(@NonNull File downloadFile) throws FileNotFoundException {
        File iconFile = getExistingIconFile(downloadFile);
        if (iconFile == null) throw new FileNotFoundException(downloadFile.getAbsolutePath());
        return new AssetFileDescriptor(ParcelFileDescriptor.open(iconFile, ParcelFileDescriptor.MODE_READ_ONLY), 0L, -1L);
    }

    /**
     * Refreshes the thumbnail data.
     * @param ctx Context
     * @param downloads download files to inspect, this may be null to inspect all downloads
     */
    @UiThread
    void refresh(@NonNull final Context ctx, @Nullable File... downloads) {
        if (downloads == null || downloads.length == 0) downloads = App.getDownloadsDir(ctx).listFiles();
        if (downloads == null) return;
        final int thumbnailWidth = getSuggestedThumbnailWidth(ctx);
        final App app = (App)ctx.getApplicationContext();
        for (File download : downloads) {
            if (app.isBeingDownloaded(download)) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Skipping thumbnail creation for " + download + " because it is being downloaded.");
                continue;
            }
            if (hasFailed(download)) {
                continue;
            }
            File iconFile = makeIconFile(download.getName());
            if (iconFile.isFile() && iconFile.length() > 0L) continue;
            final ThumbCreator tc = new ThumbCreator(ctx, thumbnailWidth, download);
            if (this.thumbCreators.contains(tc)) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Skipping thumbnail creation for " + download + " because that is already happening.");
                continue;
            }
            this.thumbCreators.add(tc);
            if (this.executor == null) this.executor = Executors.newCachedThreadPool();
            tc.executeOnExecutor(this.executor);
        }
    }

    /**
     * Removes an OnThumbLoadedListener.
     * @param listener OnThumbLoadedListener to remove
     */
    void removeListener(@Nullable final OnThumbLoadedListener listener) {
        if (listener == null) return;
        synchronized (this.listeners) {
            Reference<OnThumbLoadedListener> toRemove = null;
            for (Reference<OnThumbLoadedListener> existing : this.listeners) {
                OnThumbLoadedListener l = existing.get();
                if (l == listener) {
                    toRemove = existing;
                    break;
                }
            }
            if (toRemove != null) this.listeners.remove(toRemove);
        }

    }

    /**
     * Deletes a thumbnail. Should be called when the given file has been deleted.
     * @param file download file that the thumbnail must be deleted for (does not have to exist)
     */
    void removeThumbnail(@Nullable File file) {
        if (file == null) return;
        final String fileName = file.getName();
        // remove from cache
        synchronized (this.thumbsCache) {
            this.thumbsCache.remove(fileName);
        }
        // remove from persistent memory
        synchronized (this.syncdir) {
            Util.deleteFile(makeIconFile(fileName));
        }
    }

    /**
     * Renames a thumbnail file as a consequence of renaming a download file.
     * @param oldFile old download file which does not exist as such any more
     * @param newFile new (= renamed) download file
     */
    void renameThumbnail(@Nullable File oldFile, @Nullable File newFile) {
        if (oldFile == null || newFile == null) return;
        final String oldFileName = oldFile.getName();
        final String newFileName = newFile.getName();
        synchronized (this.thumbsCache) {
            Bitmap thumb = this.thumbsCache.remove(oldFileName);
            if (thumb != null) this.thumbsCache.put(newFileName, thumb);
        }
        synchronized (this.syncdir) {
            File oldIconFile = makeIconFile(oldFileName);
            if (oldIconFile.isFile()) {
                File newIconFile = makeIconFile(newFileName);
                boolean ok = oldIconFile.renameTo(newIconFile);
                if (BuildConfig.DEBUG && !ok) Log.e(TAG, "Failed to rename thumbnail file \"" + oldIconFile + "\" to \"" + newIconFile + "\"");
                if (!ok) Util.deleteFile(oldIconFile);
            }
        }
    }

    /**
     * Allows the UiActivity to make itself known to the non-finished ThumbCreators so it can be notified about new thumbnails.
     * Useful if ThumbCreators have been launched when no UiActivity was active.
     * @param listener OnThumbCreatedListener
     */
    public void setOnThumbCreatedListener(@Nullable final OnThumbCreatedListener listener) {
        for (ThumbCreator tc : this.thumbCreators) {
            if (listener != null && tc.getStatus() == AsyncTask.Status.FINISHED) continue;
            tc.setListener(listener);
        }
    }

    /**
     * Stores the cached thumbnails in persistent memory.
     */
    private void store() {
        if (this.storer != null && this.storer.isAlive()) {
            if (BuildConfig.DEBUG) Log.e(TAG, "A previous storer is still active");
            return;
        }
        synchronized (ThumbsManager.this.syncdir) {
            if (!this.iconDir.isDirectory()) return;
        }
        Map<String, Bitmap> snapshot;
        synchronized (this.thumbsCache) {
            snapshot = this.thumbsCache.snapshot();
        }
        final Set<Map.Entry<String, Bitmap>> entries = snapshot.entrySet();
        this.storer = new Thread() {
            @Override
            public void run() {
                int counter = 0;
                synchronized (ThumbsManager.this.syncdir) {
                    for (Map.Entry<String, Bitmap> entry : entries) {
                        File iconFile = makeIconFile(entry.getKey());
                        // existing thumbnail files will not be overwritten
                        if (iconFile.isFile()) {
                            continue;
                        }
                        //
                        OutputStream out = null;
                        boolean ok = false;
                        try {
                            out = new FileOutputStream(iconFile);
                            entry.getValue().compress(CFORMAT, 50, out);
                            counter++;
                            ok = true;
                        } catch (Exception e) {
                            if (BuildConfig.DEBUG) Log.e(TAG, "While compressing \"" + iconFile + "\": " + e.toString());
                        } finally {
                            Util.close(out);
                        }
                        if (!ok) Util.deleteFile(iconFile);
                    }
                    // store names and timestamps of files that thumbnails cannot be created for
                    File failedFile = new File(ThumbsManager.this.cacheDir, FAILED_FILE);
                    BufferedWriter writer = null;
                    try {
                        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(failedFile)));
                        for (File f : ThumbsManager.this.failedThumbs) {
                            writer.write(f.getAbsolutePath() + FAILED_FILE_SEP + f.lastModified());
                            writer.newLine();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        Util.close(writer);
                    }
                }
                if (BuildConfig.DEBUG) Log.i(TAG, "Stored " + counter + " thumbnails in persistent memory and " + failedThumbs.size() + " entry/ies for failed thumbnails");
            }
        };
        this.storer.setPriority(Thread.NORM_PRIORITY - 1);
        this.storer.start();
    }

    /**
     * Determines whether a thumbnail file for the given download file exists in persistent memory.
     * @param downloadFile download file
     * @return {@code true} if a thumbnail file for the given download file exists in persistent memory
     */
    public boolean thumbnailExistsAsFile(@NonNull File downloadFile) {
        File iconFile = makeIconFile(downloadFile.getName());
        boolean isFile;
        synchronized (syncdir) {
            isFile = iconFile.isFile();
        }
        return isFile;
    }

    /**
     * Makes sure that the thumbnails are loaded.
     */
    synchronized void wakeUp() {
        if (this.needsReloading) load();
    }

    public interface OnThumbCreatedListener {

        /**
         * A thumbnail picture for the given file has been created.
         * @param file File
         */
        @UiThread
        void thumbCreated(@NonNull File file);
    }

    public interface OnThumbLoadedListener {
        /**
         * At least one thumbnail picture for a file has been loaded from "disk" (🖬).
         */
        @UiThread
        void thumbnailsLoaded();
    }

    /**
     * Creates a thumbnail image for one file.<br>
     * A ThumbCreator equals another if their {@link #file} attributes are equal.
     */
    static class ThumbCreator extends AsyncTask<Void, Float, Bitmap> {

        @IntRange(from = MIN_THUMBNAIL_SIZE) private final int width;
        @NonNull private final File file;
        private App app;
        @Nullable private OnThumbCreatedListener listener;

        /**
         * Constructor.
         * @param ctx Context
         * @param width width
         * @param file File to create a thumbnail for
         * @throws NullPointerException if {@code ctx} is {@code null}
         */
        private ThumbCreator(@NonNull Context ctx, @IntRange(from = MIN_THUMBNAIL_SIZE) int width, @NonNull File file) {
            super();
            if (BuildConfig.DEBUG) Log.i(ThumbCreator.class.getSimpleName(), "ThumbCreator created for " + file);
            this.app = (App)ctx.getApplicationContext();
            this.width = width;
            this.file = file;
            if (ctx instanceof OnThumbCreatedListener) this.listener = (OnThumbCreatedListener)ctx;
        }

        /** {@inheritDoc} */
        @Nullable
        @Override
        protected Bitmap doInBackground(Void... ignored) {
            if (BuildConfig.DEBUG) Log.i(ThumbCreator.class.getSimpleName(), "ThumbCreator running for \"" + this.file + "\"");
            if (!this.file.isFile() || this.file.length() == 0L || this.app.getThumbsManager().hasCachedThumbnail(this.file)) {
                return null;
            }
            if (this.app.isBeingDownloaded(this.file)) {
                if (BuildConfig.DEBUG) Log.e(ThumbCreator.class.getSimpleName(), "ThumbCreator cannot process \"" + file + "\" because it is being downloaded!");
                return null;
            }
            if (this.app.getThumbsManager().hasFailed(this.file)) {
                return null;
            }
            if (probablySupported(this.file)) {
                FFmpegMediaMetadataRetriever mmr = App.MMRT.get();
                if (mmr == null) return null;
                Bitmap bitmap = createThumbnail(mmr, this.app, this.file, this.width);
                App.MMRT.remove();
                return bitmap;
            }
            return null;
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ThumbCreator that = (ThumbCreator) o;
            return Objects.equals(this.file, that.file);
        }

        /** {@inheritDoc} */
        @Override
        protected void finalize() {
            this.listener = null;
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return Objects.hash(this.file);
        }

        /** {@inheritDoc} */
        @Override
        protected void onCancelled(@Nullable Bitmap ignored) {
            this.app.getThumbsManager().thumbCreators.remove(this);
            // make sure to null the listener!
            this.listener = null;
            this.app = null;
        }

        /** {@inheritDoc} */
        @Override
        protected void onPostExecute(@Nullable Bitmap thumb) {
            final ThumbsManager t = this.app.getThumbsManager();
            t.thumbCreators.remove(this);
            if (thumb == DUMMY) {
                // make sure to null the listener!
                this.listener = null;
                this.app = null;
                return;
            }
            if (thumb != null) {
                t.addThumbnail(this.file, thumb);
                if (this.listener != null) this.listener.thumbCreated(this.file);
                this.app.getContentResolver().notifyChange(buildDocumentUri(BuildConfig.DOCSPROVIDER_AUTH, this.file.getAbsolutePath()), null, false);
            } else {
                // mark this thumbnail creation as failed if
                // a) the thumbnail does not exist,
                // b) the creation had not failed before and
                // c) the file is not currently being loaded
                if (!t.hasCachedThumbnail(this.file) && !t.thumbnailExistsAsFile(this.file) && !t.hasFailed(this.file) && !this.app.isBeingDownloaded(this.file)) {
                    if (BuildConfig.DEBUG && probablySupported(file)) Log.w(ThumbCreator.class.getSimpleName(), "Did not create a thumbnail for " + file);
                    t.failed(this.file);
                }
            }
            // make sure to null the listener!
            this.listener = null;
            this.app = null;
        }

        /**
         * Allows to set an OnThumbCreatedListener subsequently (when this ThumbCreator had been started without).
         * @param listener OnThumbCreatedListener
         */
        @UiThread
        void setListener(@Nullable OnThumbCreatedListener listener) {
            this.listener = listener;
        }
    }

}

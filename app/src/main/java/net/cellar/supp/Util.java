/*
 * Util.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ApplicationErrorReport;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.material.snackbar.Snackbar;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.MainActivity;
import net.cellar.R;
import net.cellar.StoreActivity;
import net.cellar.UiActivity;

import org.xmlpull.v1.XmlPullParser;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.CharBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Static utility methods.
 */
public final class Util {

    /** see {@link android.os.FileUtils android.os.FileUtils.isValidFatFilenameChar(char)/isValidExtFilenameChar(char)} */
    public static final char[] ILLEGAL_FILE_CHARS = new char[] { 0x00, '"', '*', '/', ',', '<', '>', '?', '\\', '|', 0x7F};
    /** supported extensions of audio files */
    private static final String[] AUDIOS = new String[] {".aac", ".ac3", ".ape", ".dts", ".flac", ".m4a", ".mka", ".mp3", ".ogg", ".ra", ".shn", ".wma"};
    /** BitmapFactory.Options with the {@link BitmapFactory.Options#inJustDecodeBounds inJustDecodeBounds} flag set to {@code true}*/
    private static final BitmapFactory.Options BITMAP_FACTORY_OPTIONS_JUST_DECODE = new BitmapFactory.Options();
    private static final CharSequence EMPTY_CHARSEQUENCE = new StringBuilder(0);
    @Size(16) private static final char[] HEX = new char[] {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    /** supported extensions of movie files */
    private static final String[] MOVIES = new String[] {".flv", ".m2ts", ".mkv", ".mov", ".mpg", ".mp4", ".nsv", ".ogv", ".rv", ".vob", ".webm", ".wmv"};
    /** supported extensions of picture files (excludes tiff files) */
    private static final String[] PICTURES =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new String[] {".bmp", ".gif", ".heic", ".heif", ".ico", ".jpeg", ".jpg", ".png", ".svg", ".webp"}
                    : new String[] {".bmp", ".gif", ".ico", ".jpeg", ".jpg", ".png", ".svg", ".webp"};
    private static final String TAG = "Util";
    /** possible extensions of TIFF picture files */
    private static final String[] TIFF = new String[] {".dng", ".tif", ".tiff"};

    static {
        BITMAP_FACTORY_OPTIONS_JUST_DECODE.inJustDecodeBounds = true;
    }

    /**
     * Adds a file name extension to a given file based on the given MIME type.<br>
     * For example, if mime is "text/plain", then ".txt" would be added to the file name.<br>
     * It should have been made sure beforehand that the file did not carry an extension in the first place…
     * @param file file to add the extension to
     * @param mime MIME type to base the extension on
     * @return the file as it exists afterwards, be it renamed or not
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @NonNull
    public static File addExtensionFromMimeType(@NonNull final File file, @Nullable String mime) {
        if (mime == null) return file;
        int semicolon = mime.indexOf(';'); // it might be sth. like "text/html; charset=utf-8"
        if (semicolon >= 0) mime = mime.substring(0, semicolon).trim();
        String suggestedTag = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (TextUtils.isEmpty(suggestedTag)) return file;
        final String folder = file.getParent();
        File renamed = new File(folder, file.getName() + '.' + suggestedTag);
        String alt = suggestAlternativeFilename(renamed);
        if (alt != null) {
            renamed = new File(folder, alt);
        }
        return file.renameTo(renamed) ? renamed : file;
    }

    /**
     * Converts some bytes into their hexadecimal representation.<br>
     * Is about 10 times faster than {@link Integer#toHexString(int)}.
     * @param data input data
     * @return hexadecimal representation
     */
    @NonNull
    public static CharSequence asHex(@Nullable final byte[] data) {
        if (data == null) return EMPTY_CHARSEQUENCE;
        final char[] cs = new char[data.length << 1];
        int j = 0;
        for (byte b : data) {
            int i = ((int)b) & 0xff;
            if (i < 0x10) {
                cs[j++] = '0';
                cs[j++] = HEX[i];
            } else {
                cs[j++] = HEX[(i & 0xf0) >> 4];
                cs[j++] = HEX[(i & 0x0f)];
            }
        }
        return CharBuffer.wrap(cs);
    }

    /**
     * Closes some Closeables.
     * @param cc Closeable
     */
    public static void close(@Nullable Closeable... cc) {
        if (cc == null) return;
        for (Closeable c : cc) {
            if (c == null) continue;
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Closes some AutoCloseables.
     * @param cc AutoCloseable
     */
    public static void close(AutoCloseable... cc) {
        if (cc == null) return;
        for (AutoCloseable c : cc) {
            if (c == null) continue;
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Copies data from an InputStream to an OutputStream.<br>
     * The streams will be closed upon completion.
     * @param in InputStream
     * @param out OutputStream
     * @param bufferSize buffer size to use
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if either stream is {@code null}
     */
    public static void copy(@NonNull final InputStream in, @NonNull final OutputStream out, @IntRange(from = 1) int bufferSize) throws IOException {
        final byte[] buf = new byte[bufferSize];
        try {
            for (; ; ) {
                int read = in.read(buf);
                if (read < 0) break;
                out.write(buf, 0, read);
            }
        } finally {
            try {
                if (out instanceof FileOutputStream) Os.fsync(((FileOutputStream) out).getFD());
            } catch (ErrnoException e) {
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            }
            close(out, in);
        }
    }

    /**
     * Deletes the given directory. Does not do anything if that isn't a directory.
     * @param dir directory to delete
     */
    public static void deleteDirectory(@NonNull final File dir) {
        if (!dir.isDirectory()) return;
        File[] contents = dir.listFiles();
        if (contents != null && contents.length > 0) {
            deleteFileOrDirectory(contents);
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /**
     * Deletes a file. No questions asked, no answers given.<br>
     * <em>Will not delete a directory!</em>
     * @param file File to delete
     */
    public static void deleteFile(@Nullable File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /**
     * Deletes files. No questions asked, no answers given.<br>
     * <em>Will not delete directories!</em>
     * @param files Files to delete
     */
    public static void deleteFile(@Nullable final File... files) {
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    public static void deleteFileOrDirectory(@Nullable final File... files) {
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.exists()) continue;
            if (file.isDirectory()) {
                deleteDirectory(file);
                continue;
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Returns the app's ABI,
     * for example, "arm64-v8a" or "x86".
     * @param ctx Context
     * @return ABI or null
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    @Nullable
    public static String getAbi(@NonNull Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0);
            @SuppressLint("DiscouragedPrivateApi")
            java.lang.reflect.Field fabi = ai.getClass().getDeclaredField("primaryCpuAbi");
            return (String) fabi.get(ai);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        }
        return null;
    }

    /**
     * Returns a file's extension (a.k.a. tag) <em>in upper case</em>.
     * @param file file
     * @return file extension
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @Nullable
    public static String getExtensionUcase(@NonNull File file) {
        final String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;
        return fileName.substring(dot + 1).toUpperCase();
    }

    /**
     * Returns a path that is defined in {@link R.xml#filepaths}.<br>
     * Example to return the contents of "&lt;files-path path="downloads/" name="downloads" /&gt;":<br>
     * <code>
     *     File downloadsFolder = getFilePath(ctx, App.FilePath.DOWNLOADS, false)
     * </code>
     * @param ctx Context
     * @param nameToFind name attribute to match
     * @param create {@code true} to create the directory if it does not exist
     * @return File object representing a directory (which possibly does not exist if {@code create} was {@code false})
     * @throws NullPointerException if {@code ctx} or {@code filePath} are {@code null}
     */
    @NonNull
    public static File getFilePath(@NonNull final Context ctx, @NonNull final App.FilePath filePath, final boolean create) {
        final XmlResourceParser xpp = ctx.getResources().getXml(R.xml.filepaths);
        try {
            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                eventType = xpp.next();
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = xpp.getName();
                    boolean files = "files-path".equals(tagName);
                    boolean cache = "cache-path".equals(tagName);
                    if (files || cache) {
                        int na = xpp.getAttributeCount();
                        String attrPathValue = null;
                        String attrNameValue = null;
                        for (int i = 0; i < na; i++) {
                            String attrName = xpp.getAttributeName(i);
                            String attrValue = xpp.getAttributeValue(i);
                            if ("name".equals(attrName)) attrNameValue = attrValue;
                            else if ("path".equals(attrName)) attrPathValue = attrValue;
                        }
                        if (filePath.getName().equals(attrNameValue) && attrPathValue != null) {
                            final File dir = new File(files ? ctx.getFilesDir() : ctx.getCacheDir(), attrPathValue);
                            if (create && !dir.isDirectory()) {
                                if (!dir.mkdirs()) throw new FileNotFoundException("Cannot create " + dir);
                            }
                            return dir;
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        }
        throw new IllegalArgumentException("Cannot find " + filePath);
    }

    /**
     * Calculates a hash of a given String.
     * @param of String to calculate the hash of
     * @param messageDigest message digest to use
     * @return hash value as hex code
     */
    @Nullable
    public static String getHash(@NonNull String of, @NonNull String messageDigest) {
        try {
            MessageDigest md = MessageDigest.getInstance(messageDigest);
            md.update(of.getBytes());
            return asHex(md.digest()).toString();
        } catch (NoSuchAlgorithmException ignored) {
        }
        return null;
    }

    /**
     * Attempts to extract the offending host and port values from a network-related Throwable.
     * @param e Throwable
     * @param defaultValue default value to return
     * @return "host:port" or "host" or {@code null}
     */
    @Nullable
    public static String getHostAndPort(@Nullable final Throwable e, @Nullable final String defaultValue) {
        String host = null;
        if (e instanceof ConnectException) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Failed to connect to ")) {
                host = msg.substring(21).trim();
                int slash = host.lastIndexOf('/');
                if (slash > 0) host = host.substring(0, slash);
            }
        } else if (e instanceof SocketTimeoutException) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("failed to connect to /")) {
                int s = msg.indexOf(' ', 23);
                if (s > 22) {
                    String port = null;
                    int p0 = msg.indexOf("(port ", s + 1);
                    if (p0 > s) {
                        int p1 = msg.indexOf(")", p0 + 6);
                        if (p1 > p0) port = msg.substring(p0 + 6, p1);
                    }
                    host = msg.substring(22, s).trim();
                    if (port != null) host = host + ":" + port;
                }
            }
        } else if (e instanceof SSLPeerUnverifiedException) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Hostname ")) {
                int s = msg.indexOf(' ', 9);
                if (s > 9) {
                    host = msg.substring(9, s).trim();
                }
            }
        }
        if (BuildConfig.DEBUG && host == null && e != null) {
            Log.w(TAG, "Failed to extract host from \"" + e.getMessage() + "\"");
        }
        return host != null ? host : defaultValue;
    }

    /**
     * Returns the image orientation as given in the exif information.
     * @param file image file to inspect
     * @return 0, 90, 180 or 270
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @IntRange(from = 0, to = 270)
    public static int getImageOrientation(@NonNull File file) {
        return getImageOrientation(file.getAbsolutePath());
    }

    /**
     * Returns the image orientation as given in the exif information.
     * @param path absolute path to image file to inspect
     * @return 0, 90, 180 or 270
     * @throws NullPointerException if {@code path} is {@code null}
     */
    @IntRange(from = 0, to = 270)
    public static int getImageOrientation(@NonNull String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
            }
        } catch (IOException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While trying to get orientation of " + path + ": " + e.toString());
        }
        return 0;
    }

    /**
     * Returns the size of an image contained in a file.
     * If there is an error during decoding, the returned values will be -1, apparently.
     * @param file image file
     * @return image file dimensions (width, height)
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @Size(2)
    @NonNull
    public static int[] getImageSize(@NonNull File file) {
        int w, h;
        synchronized (BITMAP_FACTORY_OPTIONS_JUST_DECODE) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), BITMAP_FACTORY_OPTIONS_JUST_DECODE);
            w = BITMAP_FACTORY_OPTIONS_JUST_DECODE.outWidth;
            h = BITMAP_FACTORY_OPTIONS_JUST_DECODE.outHeight;
        }
        return new int[] {w, h};
    }

    /**
     * Returns a MIME type based on a given file extension.<br>
     * If there are any stupid warnings: Whether this method can return {@code null} depends entirely on the default value…<br>
     * ("//noinspection ConstantConditions" may help if a non-null return value is expected)
     * @param tag file extension/tag without leading .
     * @param defaultValue the default value to return if a MIME type could not be determined
     * @return MIME type; defaults to {@code defaultValue}
     */
    @NonNull
    public static String getMime(@Nullable String tag, @NonNull String defaultValue) {
        if (tag == null) return defaultValue;
        String mime = null;
        if (!TextUtils.isEmpty(tag)) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(tag);
            if (mime == null) {
                switch (tag) {
                    case "ac3":
                        mime = "audio/vnd.dolby.dd-raw";
                        break;
                    case "ape":
                        mime = "audio/x-ape";
                        break;
                    case "dts":
                        mime = "audio/vnd.dts";
                        break;
                    case "flc":
                        mime = "video/x-fli";
                        break;
                    case "flv":
                        mime = "video/x-flv";
                        break;
                    case "gz":
                        mime = "application/gzip";
                        break;
                    case "m2t":
                    case "m2ts":
                    case "mts":
                    case "ts":
                    case "tsv":
                        mime = "video/MP2T";
                        break;
                    case "m3u8":
                        // this can be video or audio…
                        mime = "application/vnd.apple.mpegurl";
                        break;
                    case "nsv":
                        mime = "application/x-winamp";
                        break;
                    case "pls":
                        mime = "audio/x-scpls";
                        break;
                    case "ram":
                        mime = "audio/x-pn-realaudio";
                        break;
                    case "shn":
                        mime = "application/x-shorten";
                        break;
                    case "tar":
                        mime = "application/x-tar";
                        break;
                    case "ttf":
                        mime = "application/x-font-ttf";
                        break;
                    case "vob":
                        mime = "video/dvd";
                        break;
                }
            }
        }
        if (mime == null) mime = defaultValue;
        return mime;
    }

    /**
     * Attempts to determine the MIME type based on a given file's tag.
     * Handles directories, too, in which case {@link android.provider.DocumentsContract.Document#MIME_TYPE_DIR MIME_TYPE_DIR} is returned.
     * @param file file to determine the MIME type for
     * @return MIME type or, if not determined, {@link App#MIME_DEFAULT}
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @NonNull
    public static String getMime(@NonNull File file) {
        if (file.isDirectory()) return android.provider.DocumentsContract.Document.MIME_TYPE_DIR;
        final String mime;
        final String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            final String tag = fileName.substring(dot + 1).toLowerCase(Locale.US);
            mime = getMime(tag, App.MIME_DEFAULT);
        } else {
            mime = App.MIME_DEFAULT;
        }
        return mime;
    }

    /**
     * Extracts the MIME type from a given Uri.
     * @param uri Uri
     * @return MIME type
     */
    @Nullable
    public static String getMime(@Nullable Uri uri) {
        if (uri == null) return null;
        String lps = uri.getLastPathSegment();
        if (lps == null) return null;
        int lastDot = lps.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= lps.length() - 1) return null;
        String tag = lps.substring(lastDot + 1).trim();
        if (tag.length() == 0) return null;
        tag = tag.toLowerCase(Locale.US);
        return getMime(tag, App.MIME_DEFAULT);
    }

    /**
     * Returns information about what component launched the given Activity.
     * Will return {@code null} on devices running API 21 or lower.
     * @param activity Activity
     * @return referrer or {@code null}
     */
    @Nullable
    public static Uri getReferrer(@Nullable Activity activity) {
        if (activity == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                return activity.getReferrer();
            } else {
                return null;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        }
        return null;
    }

    /**
     * Determines whether the given file name belongs to an audio file.
     * @param fileName file name
     * @return {@code true} / {@code false}
     * @throws NullPointerException if {@code fileName} is {@code null}
     */
    public static boolean isAudio(@NonNull final String fileName) {
        for (String p : AUDIOS) {
            if (fileName.endsWith(p)) return true;
        }
        return false;
    }

    /**
     * Determines whether the given file name belongs to a movie.
     * @param fileName file name
     * @return {@code true} / {@code false}
     * @throws NullPointerException if {@code fileName} is {@code null}
     */
    public static boolean isMovie(@NonNull final String fileName) {
        for (String p : MOVIES) {
            if (fileName.endsWith(p)) return true;
        }
        return false;
    }

    /**
     * Determines whether the given file name belongs to a picture.<br>
     * <em>Will return {@code false} for TIFF pictures!</em>
     * @param fileName file name
     * @return {@code true} / {@code false}
     * @throws NullPointerException if {@code fileName} is {@code null}
     */
    public static boolean isPicture(@NonNull final String fileName) {
        for (String p : PICTURES) {
            if (fileName.endsWith(p)) return true;
        }
        return false;
    }

    /**
     * Determines whether the given file name belongs to a TIFF picture.
     * @param fileName file name
     * @return {@code true} / {@code false}
     */
    public static boolean isTiffPicture(@NonNull final String fileName) {
        for (String p : TIFF) {
            if (fileName.endsWith(p)) return true;
        }
        return false;
    }

    /**
     * Inspects the first four bytes of the given file to see whether it's a zip file.
     * @param file file to inspect
     * @return {@code true} / {@code false}
     */
    public static boolean isZip(@Nullable File file) {
        if (file == null || !file.isFile() || file.length() < 4L) return false;
        RandomAccessFile raf = null;
        boolean zip = false;
        try {
            byte[] buf = new byte[4];
            raf = new RandomAccessFile(file, "r");
            if (raf.read(buf) == 4) {
                int[] i = new int[4];
                for (int j = 0; j < 4; j++) i[j] = ((int) buf[j]) & 0xff;
                if (i[0] == 'P' && i[1] == 'K' && i[2] < 0x10 && i[3] < 0x10) zip = true;
            }
        } catch (Exception ignored) {
        } finally {
            close(raf);
        }
        return zip;
    }

    /**
     * Creates a bitmap that contains a text.<br>
     * Usage:<br>
     * <code>
     * Util.makeCharBitmap("\uD83D\uDD2B", 0f, iconSize, iconSize, Color.BLACK, Color.TRANSPARENT, new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_ATOP))
     * </code>
     * @param s text
     * @param textSize text size (set to 0 to maximise the text)
     * @param wx width
     * @param wy height
     * @param color text color
     * @param background background color
     * @param cf ColorFilter (optional)
     * @return Bitmap
     * @throws NullPointerException if {@code s} is {@code null}
     */
    @NonNull
    public static Bitmap makeCharBitmap(final String s, float textSize, final int wx, final int wy, @ColorInt int color, @ColorInt int background, @Nullable ColorFilter cf) {
        return makeCharBitmap(s, textSize, false, wx, wy, color, background, cf, null);
    }

    /**
     * Creates a bitmap that contains a text.
     * @param s text
     * @param textSize text size (set to 0 to maximise the text)
     * @param wx width
     * @param wy height
     * @param color text color
     * @param background background color
     * @param cf ColorFilter (optional)
     * @param tf Typeface to use
     * @return Bitmap
     * @throws NullPointerException if {@code s} is {@code null}
     */
    @NonNull
    public static Bitmap makeCharBitmap(final String s, float textSize, final int wx, final int wy, @ColorInt int color, @ColorInt int background, @Nullable ColorFilter cf, Typeface tf) {
        return makeCharBitmap(s, textSize, false, wx, wy, color, background, cf, tf);
    }

    /**
     * Creates a bitmap that contains a text.<br>
     * Usage:<br>
     * <code>
     * Util.makeCharBitmap("\uD83D\uDD2B", 0f, iconSize, iconSize, Color.BLACK, Color.TRANSPARENT, new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_ATOP))
     * </code>
     * @param s text
     * @param textSize text size (set to 0 to maximise the text)
     * @param bold true for bold text
     * @param wx width
     * @param wy height
     * @param color text color
     * @param background background color
     * @param cf ColorFilter (optional)
     * @param tf Typeface (optional)
     * @return Bitmap
     * @throws NullPointerException if {@code s} is {@code null}
     */
    @NonNull
    private static Bitmap makeCharBitmap(final String s, float textSize, boolean bold, final int wx, final int wy, @ColorInt int color, @ColorInt int background, @Nullable ColorFilter cf, Typeface tf) {
        final Bitmap bitmap = Bitmap.createBitmap(wx, wy, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(background);
        final Canvas canvas = new Canvas();
        final int l = s.length();
        final Rect bounds = new Rect();
        final Paint paint = new Paint();
        paint.setColor(color);
        paint.setSubpixelText(true);
        paint.setHinting(Paint.HINTING_ON);
        paint.setAntiAlias(true);
        if (bold) paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        if (tf != null) paint.setTypeface(tf);
        if (textSize > 0f) {
            paint.setTextSize(textSize);
        } else {
            textSize = 8f;
            for (; ; textSize += 1f) {
                paint.setTextSize(textSize);
                paint.getTextBounds(s, 0, l, bounds);
                if (bounds.width() > wx || bounds.height() > wy) {
                    paint.setTextSize(textSize - 1f);
                    break;
                }
            }
        }
        paint.setFilterBitmap(true);
        if (cf != null) paint.setColorFilter(cf);
        paint.getTextBounds(s, 0, l, bounds);
        canvas.setBitmap(bitmap);
        float px = wx / 2f;
        float py = -bounds.top + (wy / 2f) - (bounds.height() / 2f);
        canvas.drawText(s, px, py, paint);
        return bitmap;
    }

    /**
     * Creates an ApplicationErrorReport based on the information contained in the parameters.
     * @param ctx Context
     * @param e Throwable
     * @return ApplicationErrorReport
     * @throws NullPointerException if any of the parameters is {@code null}
     */
    @NonNull
    public static ApplicationErrorReport makeErrorReport(@NonNull Context ctx, @NonNull Throwable e) {
        final ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = report.processName = ctx.getApplicationContext().getPackageName();
        report.time = System.currentTimeMillis();
        report.type = ApplicationErrorReport.TYPE_CRASH;
        report.systemApp = false;
        final ApplicationErrorReport.CrashInfo crash = new ApplicationErrorReport.CrashInfo();
        crash.exceptionClassName = e.getClass().getSimpleName();
        crash.exceptionMessage = e.getMessage();
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        crash.stackTrace = writer.toString();
        final StackTraceElement stack = e.getStackTrace()[0];
        crash.throwClassName = stack.getClassName();
        crash.throwFileName = stack.getFileName();
        crash.throwLineNumber = stack.getLineNumber();
        crash.throwMethodName = stack.getMethodName();
        report.crashInfo = crash;
        return report;
    }

    /**
     * Parses a String that possibly represents a Date value.
     * @param s String containing a date value
     * @param df DateFormat to use
     * @param defaultValue default value to apply if {@code s} cannot be parsed
     * @return Date or {@code null}
     */
    @Nullable
    public static Date parseDate(@Nullable String s, @Nullable java.text.DateFormat df, @Nullable Date defaultValue) {
        if (s == null || df == null) return defaultValue;
        try {
            return df.parse(s);
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * Parses a String that possibly represents a double value.
     * @param s String containing a double value
     * @param defaultValue default value to apply if {@code s} cannot be parsed
     * @return double
     */
    public static double parseDouble(@Nullable String s, double defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * Parses a String that possibly represents a float value.
     * @param s String containing a float value
     * @param defaultValue default value to apply if {@code s} cannot be parsed
     * @return float
     */
    public static float parseFloat(@Nullable String s, float defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Float.parseFloat(s);
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * Parses a String that possibly represents an integer value.
     * @param s String containing an integer value
     * @param defaultValue default value to apply if {@code s} cannot be parsed
     * @return int
     */
    public static int parseInt(@Nullable String s, int defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * Parses a String that possibly represents a long value.
     * @param s String containing a long value
     * @param defaultValue default value to apply if {@code s} cannot be parsed
     * @return long
     */
    public static long parseLong(@Nullable String s, long defaultValue) {
        if (s == null) return defaultValue;
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
        }
        return defaultValue;
    }

    /**
     * Removes the filename extension. E.g. returns "example" when "example.txt" is given.
     * @param filename file name
     * @return file name without extension
     * @throws NullPointerException if {@code filename} is {@code null}
     */
    @NonNull
    public static String removeExtension(@NonNull String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return filename;
        return filename.substring(0, dot);
    }

    /**
     * Removes illegal chars from a file name.
     * Considers chars to be illegal if mentioned in {@link android.os.FileUtils} for fat or ext4 filesystems.<br>
     * <b>Do not pass paths because slashes will be removed!</b>
     * @param filename file name to remove illegal chars from
     * @return file name with no illegal chars
     * @throws NullPointerException if {@code filename} is {@code null}
     */
    @NonNull
    public static CharSequence sanitizeFilename(@NonNull final CharSequence filename) {
        char[] replacement = null;
        final int l = filename.length();
        for (int i = 0; i < l; i++) {
            char c = filename.charAt(i);
            boolean illegal = false;
            for (char illegalFileChar : ILLEGAL_FILE_CHARS) {
                if (illegalFileChar == c) {
                    if (replacement == null) {
                        replacement = new char[l];
                        for (int j = 0; j < i; j++) replacement[j] = filename.charAt(j);
                    }
                    replacement[i] = '-';
                    illegal = true;
                    break;
                }
            }
            if (!illegal && replacement != null) replacement[i] = c;
        }
        return replacement != null ? new String(replacement) : filename;
    }

    /**
     * Sends (= shares) a file.<br>
     * Only on devices running Android as new as {@link Build.VERSION_CODES#N N} (API 24), this app will be excluded from the possible destinations.<br>
     * Fails silently if the Context or File parameters are {@code null}.
     * @param ctx Context (required)
     * @param file File to send (required)
     * @param authority The authority of a {@link FileProvider} defined in a {@code <provider>} element in the manifest (required)
     * @param mime Internet media type of the given file
     */
    public static void send(@Nullable final Context ctx, @Nullable final File file, @NonNull final String authority, @Nullable final String mime) {
        if (ctx == null || file == null) return;

        final Uri uri = FileProvider.getUriForFile(ctx, authority, file);

        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mime != null ? mime : getMime(file));
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + ctx.getPackageName()));
        shareIntent.putExtra(Intent.EXTRA_TITLE, file.getName());
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        final Intent chooserIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            Intent callback = new Intent(ctx, UiActivity.SharedResultReceiver.class);
            callback.putExtra(UiActivity.SharedResultReceiver.EXTRA_FILE, file.getName());
            @SuppressLint("InlinedApi")
            int pFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, callback, pFlags);
            chooserIntent = Intent.createChooser(shareIntent, null, pi.getIntentSender());
        } else {
            chooserIntent = Intent.createChooser(shareIntent, null);
        }
        if (!(ctx instanceof Activity)) chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // https://developer.android.com/reference/android/content/Intent#EXTRA_EXCLUDE_COMPONENTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[] {new ComponentName(ctx, MainActivity.class)});
        }
        ComponentName cn = chooserIntent.resolveActivity(ctx.getPackageManager());
        if (cn == null) {
            CoordinatorLayout cl = (ctx instanceof CoordinatorLayoutHolder ? ((CoordinatorLayoutHolder) ctx).getCoordinatorLayout() : null);
            if (mime != null) {
                if (cl != null) Snackbar.make(cl, ctx.getString(R.string.msg_no_applications_for, mime), Snackbar.LENGTH_LONG).show();
                else Toast.makeText(ctx.getApplicationContext(), ctx.getString(R.string.msg_no_applications_for, mime), Toast.LENGTH_LONG).show();
            } else {
                if (cl != null) Snackbar.make(cl, R.string.msg_no_applications, Snackbar.LENGTH_LONG).show();
                else Toast.makeText(ctx.getApplicationContext(), R.string.msg_no_applications, Toast.LENGTH_LONG).show();
            }
            return;
        }
        ctx.grantUriPermission(cn.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(chooserIntent);
    }

    /**
     * Sends several files.
     * @param ctx Context
     * @param files files to send
     * @param authority if non-null, FileProvider will be used
     * @param chooserTitle resource id for title
     * @throws NullPointerException if {@code ctx} of {@code files} are {@code null}
     */
    public static void sendMulti(@NonNull final Context ctx, @NonNull final Collection<File> files, @Nullable final String authority) {
        if (files.isEmpty()) return;
        final Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.setType("*/*");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final ArrayList<Uri> data = new ArrayList<>(files.size());
        if (authority != null) {
            for (File file : files) {if (file == null) continue; data.add(FileProvider.getUriForFile(ctx, authority, file));}
        } else {
            for (File file : files) {if (file == null) continue; data.add(Uri.fromFile(file));}
        }
        shareIntent.putExtra(Intent.EXTRA_STREAM, data);
        Intent chooserIntent = Intent.createChooser(shareIntent, null);
        if (!(ctx instanceof Activity)) chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // https://developer.android.com/reference/android/content/Intent#EXTRA_EXCLUDE_COMPONENTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ComponentName[] excluded = new ComponentName[] {new ComponentName(ctx, MainActivity.class), new ComponentName(ctx, StoreActivity.class)};
            chooserIntent.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded);
        }
        if (authority != null) {
            ComponentName cn = chooserIntent.resolveActivity(ctx.getPackageManager());
            String pack = cn.getPackageName();
            for (Uri uri : data) ctx.grantUriPermission(pack, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        ctx.startActivity(chooserIntent);
    }

    /**
     * If the given file already exists, suggests a different file name for another copy of that file.<br>
     * For example, this method suggests "master.m3u8.1.mp4" if a the file named "master.m3u8.mp4" already exists.
     * @param file File to check
     * @return {@code null} if the given file does not exist (yet); a file name otherwise
     * @throws NullPointerException if {@code file} is {@code null}
     */
    @Nullable
    public static String suggestAlternativeFilename(@NonNull File file) {
        if (!file.exists()) return null;
        final String parent = file.getParent();
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        final String fileExtensionWithDot = dot > 0 ? name.substring(dot).toLowerCase() : "";
        final String displayNameWithoutExtension = dot > 0 ? name.substring(0, dot) : name;
        int add = 0;
        File alternative;
        do {
            alternative = new File(parent, displayNameWithoutExtension + "." + (++add) + fileExtensionWithDot);
        } while (alternative.exists());
        return alternative.getName();
    }

    /**
     * Trims a CharSequence.
     * @param s CharSequence to trim
     * @param chopMeOff leading/trailing char to remove
     * @return CharSequence
     * @throws NullPointerException if {@code s} is {@code null}
     */
    @NonNull
    public static CharSequence trim(@NonNull final CharSequence s, final char chopMeOff) {
        final int n = s.length();
        if (n == 0) return s;
        int start = 0, end = n - 1;
        while (start < n && s.charAt(start) == chopMeOff) start++;
        while (end > start && s.charAt(end) == chopMeOff) end--;
        if (start == 0 && end == n - 1) return s;
        return s.subSequence(start, end + 1);
    }

    private Util() {
    }
}

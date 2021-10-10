/*
 * Inspector.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.text.TextUtils;
import android.view.Window;
import android.webkit.MimeTypeMap;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.R;
import net.cellar.auth.EncryptionHelper;
import net.cellar.supp.DebugUtil;
import net.cellar.supp.Log;
import net.cellar.supp.Util;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import org.jetbrains.annotations.TestOnly;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import wseemann.media.FFmpegMediaMetadataRetriever;


/**
 * https://github.com/landley/toybox/blob/master/toys/posix/file.c
 */
public class Inspector extends AsyncTask<File, Float, Map<File, String>> implements SharedPreferences.OnSharedPreferenceChangeListener {

    /** String Set: names of files that will be ignored by Inspector */
    @VisibleForTesting public static final String PREF_INSPECTOR_IGNORED = "pref_inspector_ignored";
    private static final String KEY = "pref_toybox";
    private static final String P1 = "file";
    private static final String PROG = "/system/bin/toybox";
    private static final String TAG = "Inspector";
    private static final int TOYBOX_AVAILABLE = 1;
    private static final int TOYBOX_UNAVAILABLE = -1;
    private static final int TOYBOX_UNKNOWN = 0;
    @Size(16) private static final int[] WMV = new int[] {0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11, 0xa6, 0xd9, 0x00, 0xaa, 0x00, 0x62, 0xce, 0x6c};
    @Size(16) private static final int[] WTV = new int[] {0xb7, 0xd8, 0x00, 0x20, 0x37, 0x49, 0xda, 0x11, 0xa6, 0x4e, 0x00, 0x07, 0xe9, 0x5e, 0xad, 0x8d};
    /** See https://en.wikipedia.org/wiki/List_of_XML_schemas */
    @Size(9) private static final String[] XML_SCHEMAS = new String[] {
            "atom", "gpx", "kml", "math", "playlist",
            "rss", "schema", "svg", "xmi"
    };
    @Size(9) private static final String[] XML_EXTENSIONS = new String[] {
            ".atom", ".gpx", ".kml", ".mathml", ".xspf",
            ".rss", ".xsd", ".svg", ".xmi"
    };


    /**
     * https://en.wikipedia.org/wiki/Magic_number_(programming)
     * https://en.wikipedia.org/wiki/List_of_file_signatures
     * https://www.filesignatures.net/index.php?page=all
     * https://mark0.net/soft-trid-deflist.html
     * @param f File to inspect
     */
    @VisibleForTesting
    public static String inspectFile(@NonNull File f, @NonNull Map<File, String> suggestions) {
        String extension = null;
        String[] alt = null;
        RandomAccessFile raf = null;
        try {
            byte[] b = new byte[16];
            raf = new RandomAccessFile(f, "r");
            final int read = raf.read(b);
            final int[] i = new int[read];
            for (int j = 0; j < read; j++) i[j] = ((int)b[j]) & 0xff;

            if (read >= WMV.length) {
                boolean datsWmv = true;
                for (int k = 0; k < read; k++) {
                    if (i[k] != WMV[k]) {
                        datsWmv = false; break;
                    }
                }
                if (datsWmv) {
                    FFmpegMediaMetadataRetriever mmr = new FFmpegMediaMetadataRetriever();
                    mmr.setDataSource(f.getAbsolutePath());
                    FFmpegMediaMetadataRetriever.Metadata md = mmr.getMetadata();
                    boolean asfHasVideo = false;
                    HashMap<String, String> mda = md.getAll();
                    mmr.release();
                    for (String key : mda.keySet()) {
                        if ("video_codec".equals(key) && !TextUtils.isEmpty(mda.get(key))) {
                            asfHasVideo = true;
                            break;
                        }
                    }
                    extension = asfHasVideo ? ".wmv" : ".wma";
                    alt = new String[]{".asf"};
                }
            }
            if (extension == null && read >= WTV.length) {
                boolean datsWtv = true;
                for (int k = 0; k < read; k++) {
                    if (i[k] != WTV[k]) {
                        datsWtv = false; break;
                    }
                }
                if (datsWtv) extension = ".wtv";
            }
            if (extension == null && read >= 16) {
                if (isTheSame(i, 0, "audfprintpeakV00")) extension = ".afpk";
                else if (i[0] == 0&& i[1] == 0 && i[2] == 0x27 && i[3] == 0x0a) {
                    boolean allnullfrom4to15 = true;
                    for (int j = 4; j <= 15; j++) if (i[j] != 0) {allnullfrom4to15 = false; break;}
                    if (allnullfrom4to15) extension = ".shp";
                }
            }
            if (extension == null && read >= 15) {
                if (isTheSame(i, 0, "<!DOCTYPE html>") || isTheSame(i, 0, "<!doctype html>")) {
                    extension = ".htm";
                    alt = new String[] {".html"};
                } else if (isTheSame(i, 0, "BEGIN:VCALENDAR")) {
                    extension = ".ics";
                }
            }
            if (extension == null && read >= 14) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // the first 4 bytes must match the file size (e.g. 0x04 0xd5 0x07 0x00 for file size of 513.284 == 0x0007d504)
                    //TODO this looks awfully complicated - can't we have something simpler here?
                    CharSequence filelength = Util.trim(Long.toHexString(Long.reverseBytes(f.length())), '0');
                    CharSequence first4Bytes = Util.trim(EncryptionHelper.asHex(b, 4), '0');
                    if (TextUtils.equals(filelength, first4Bytes)) {
                        if (i[4] == 0x11 && i[5] == 0xaf && i[12] == 0x08 && i[13] == 0) extension = ".fli";
                        else if (i[4] == 0x12 && i[5] == 0xaf && i[12] == 0x08 && i[13] == 0) extension = ".flc";
                    }
                }
            }
            if (extension == null && read >= 13) {
                if (isTheSame(i, 0, "[Flatpak Ref]")) extension = ".flatpakref";
            }
            if (extension == null && read >= 12) {
                if (isTheSame(i, 4, "ftypisom")) {extension = ".mp4"; alt = new String[] {".3gp5"};}
                else if (isTheSame(i, 4, "ftypM4A ") || isTheSame(i, 4, "ftypm4a ")) extension = ".m4a";
                else if (isTheSame(i, 4, "ftypMP42") || isTheSame(i, 4, "ftypmp42")) {extension = ".m4v"; alt = new String[] {".mp4"};}
                else if (isTheSame(i, 4, "ftypQT  ") || isTheSame(i, 4, "ftypqt  ")) extension = ".mov";
                else if (isTheSame(i, 4, "ftyp3gp ")) {extension = ".3gp"; alt = new String[] {".3gg", ".3g2"};}
                else if (isTheSame(i, 0, "d8:announce")) extension = ".torrent";
                else if (isTheSame(i, 0, "RIFF")) {
                    if (i[8] == 'W' && i[9] == 'A' && i[10] == 'V' && i[11] == 'E') extension = ".wav";
                    else if (i[8] == 'A' && i[9] == 'V' && i[10] == 'I' && i[11] == ' ') extension = ".avi";
                    else if (i[8] == 'C' && i[9] == 'D' && i[10] == 'D' && i[11] == 'A') extension = ".cda";
                    else if (i[8] == 'W' && i[9] == 'E' && i[10] == 'B' && i[11] == 'P') extension = ".webp";
                    else if (isTheSame(i, 8, "sfbk")) extension = ".sf2";
                }
            }
            if (extension == null && read >= 11) {
                if (isTheSame(i, 0, "BEGIN:VCARD")) extension = ".vcf";
            }
            if (extension == null && read >= 10) {
                if (i[0] == 0x00 && i[1] == 0x00 && i[2] == 0x01 && i[3] == 0x00 && i[4] > 0x00 && (i[9] == 0 || i[9] == 255)) extension = ".ico";
                else if (i[0] == 0x00 && i[1] == 0x00 && i[2] == 0x02 && i[3] == 0x00 && i[4] > 0x00 && (i[9] == 0 || i[9] == 255)) extension = ".cur";
            }
            if (extension == null && read >= 8) {
                if (i[0] == '.' && i[1] == 'R' && i[2] == 'M' && i[3] == 'F' && i[4] == 0 && i[5] == 0 && i[6] == 0 && i[7] == 0x12) extension = ".ra";
                if (i[0] == 0x89 && i[1] == 'H' && i[2] == 'D' && i[3] == 'F' && i[4] == 0x0d && i[5] == 0x0a && i[6] == 0x1a && i[7] == 0x0a) {extension = ".hdf"; alt = new String[] {".h4", ".hdf4", ".h5", ".hdf5", ".he2", ".he5"};}
            }
            if (extension == null && read >= 7) {
                if (isTheSame(i, 0, "#EXTM3U")) {extension = ".m3u"; alt = new String[] {".m3u8"};}
            }
            if (extension == null && read >= 6) {
                if (isTheSame(i, 0, "<?xml ")) {
                    String schema = new XmlInspecter().inspect(f);
                    if (schema != null) {
                        for (int j = 0; j < XML_SCHEMAS.length; j++) {
                            if (XML_SCHEMAS[j].equals(schema)) {
                                extension = XML_EXTENSIONS[j];
                                break;
                            }
                        }
                        if (extension == null) {
                            extension = ".xml";
                        }
                    }
                }
                if (extension == null) {
                    if (i[0] == 0x37 && i[1] == 0x7a && i[2] == 0xbc && i[3] == 0xaf && i[4] == 0x27 && i[5] == 0x1c) extension = ".7z";
                    else if (i[0] == 0xfd && i[1] == 0x37 && i[2] == 0x7a && i[3] == 0x58 && i[4] == 0x5a && i[5] == 0) extension = ".xz";
                }
            }
            if (extension == null && read >= 5) {
                if (isTheSame(i, 0, "%PDF-")) extension = ".pdf";
                else if (i[0] == '.' && i[1] == 'r' && i[2] == 'a' && i[3] == 0xfd && i[4] == 0) extension = ".ra";
            }
            if (extension == null && read >= 4) {
                if (i[0] == 0x89 && isTheSame(i,1, "PNG")) extension = ".png";
                else if (isTheSame(i, 0, "fLaC")) extension = ".flac";
                else if (isTheSame(i, 0, "MThd"))  {extension = ".mid"; alt = new String[] {".midi"};}
                else if (i[0] == 0 && i[1] == 0 && i[2] == 1 && i[3] == 0xba) {extension = ".mpg"; alt = new String[] {".mpeg", ".vob", ".m2p"};}
                else if (i[0] == 0x1a && i[1] == 0x45 && i[2] == 0xdf && i[3] == 0xa3) {extension = ".mkv"; alt = new String[] {".mka", ".mks", ".mk3d", ".webm"};}
                else if (isTheSame(i, 0, "NSVf")) extension = ".nsv";
                else if (isTheSame(i, 0, "wOFF")) extension = ".woff";
                else if (isTheSame(i, 0, "wOF2")) extension = ".woff2";
                else if (isTheSame(i, 0, "ISc(")) extension = ".cab";
                else if (isTheSame(i, 0, "KCMS")) extension = ".icm";
                else if (isTheSame(i, 0, "FLV") && i[3] == 0x01) extension = ".flv";
                else if (i[0] == 0xed && i[1] == 0xab && i[2] == 0xee && i[3] == 0xdb) extension = ".rpm";
                else if (i[0] == 0x61 && i[1] == 0x6a && i[2] == 0x6b && i[3] == 0x67) extension = ".shn";
                else if (isTheSame(i, 0, "%!PS")) extension = ".ps";
                else if (i[0] == 0xc5 && i[1] == 0xd0 && i[2] == 0xd3 && i[3] == 0xc6) extension = ".eps";
                else if (isTheSame(i, 0, "8BPS")) extension = ".psd";
                else if (isTheSame(i, 0, "OggS")) extension = ".opus";
                else if ((i[0] == 0xa1 && i[1] == 0xb2 && i[2] == 0xc3 && i[3] == 0xd4) || (i[0] == 0xd4 && i[1] == 0xc3 && i[2] == 0xb2 && i[3] == 0xa1)) extension = ".pcap";
                else if (i[0] == 0x2e && i[1] == 0x73 && i[2] == 0x6e && i[3] == 0x64) extension = ".au";   // Sun/NeXT audio data
                else if ((i[0] == 'I' && i[1] == 'I' && i[2] == '*' && i[3] == 0)
                        || (i[0] == 'M' && i[1] == 'M' && i[2] == 0 && i[3] == '*')) {
                    extension = ".tif"; alt = new String[] {".tiff", ".dng", ".cr2", ".crw", ".arw", ".nef"};
                }
            }
            if (extension == null && read >= 3) {
                if (isTheSame(i, 0, "ID3")) extension = ".mp3";
                else if (i[0] == 0xf2 && i[1] == 0x5a && i[2] == 0x68) extension = ".bz2";
                else if (isTheSame(i, 0, "#!/")) extension = ".sh";
            }
            if (extension == null && read >= 2) {
                if (i[0] == 0x0b && i[1] == 0x77) extension = ".ac3";
                else if (i[0] == 0xff && (i[1] == 0xfb || i[1] == 0xf3 || i[1] == 0xf2)) extension = ".mp3";
                else if (i[0] == 0xff && i[1] == 0xf1) extension = ".aac";
                else if (isTheSame(i, 0, "PK")) {
                    inspectZip(f, suggestions);
                }
            }
            if (extension == null && f.length() >= 0x8006L) {
                raf.seek(0x8001L);
                if (raf.read(b, 0, 5) == 5) {
                    int[] ii = new int[5];
                    for (int j = 0; j < 5; j++) ii[j] = ((int)b[j]) & 0xff;
                    if (ii[0] == 'C' && ii[1] == 'D' && ii[2] == '0' && ii[3] == '0' && ii[4] == '1') extension = ".iso";
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While inspecting " + f + ": " + e.toString());
        } finally {
            Util.close(raf);
        }
        if (extension == null) return null;
        String fileName = f.getName().toLowerCase();
        if (!fileName.endsWith(extension)) {
            if (alt != null) {
                for (String altExtension : alt) {
                    if (fileName.endsWith(altExtension)) return altExtension;
                }
            }
            suggestions.put(f, extension);
        }
        return extension;
    }

    @VisibleForTesting
    public static String inspectViaToybox(@NonNull File file, @NonNull Map<File, String> suggestions) {
        BufferedReader in = null;
        BufferedReader err = null;
        String extension = null;
        String[] alt = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[] {PROG, P1, file.getAbsolutePath()});

            in = new BufferedReader(new InputStreamReader(new BufferedInputStream(p.getInputStream())));
            err = new BufferedReader(new InputStreamReader(new BufferedInputStream(p.getErrorStream())));
            while (extension == null) {
                String line = in.readLine();
                if (line == null) break;
                int colon = line.lastIndexOf(':');
                if (colon < 0) continue;
                final String content = line.substring(colon + 1).trim();
                if (BuildConfig.DEBUG && !"data".equals(content)) Log.i(TAG, line);
                if (content.startsWith("PNG image data")) extension = ".png";
                else if (content.startsWith("JPEG image data")) {extension = ".jpg"; alt = new String[] {".jpeg"};}
                else if (content.startsWith("GIF image data")) extension = ".gif";
                else if (content.startsWith("Ogg data, theora video")) {extension = ".ogv"; alt = new String[] {".ogg"};}
                else if (content.startsWith("Ogg data, vorbis audio")) {extension = ".ogg"; alt = new String[] {".oga"};}
                else if (content.startsWith("Ogg data")) {extension = ".ogg"; alt = new String[] {".oga", ".ogv"};}
                else if ("data".equals(content)) extension = inspectFile(file, suggestions);
                else if ("ASCII text".equals(content)) {
                    extension = inspectFile(file, suggestions);
                    if (extension == null) {extension = ".txt"; alt = new String[] {".csv", ".yml"};}
                }
                else if (content.startsWith("Zip archive")) extension = inspectZip(file, suggestions);
                else if ("TrueType font".equals(content)) extension = ".ttf";
                else if ("/bin/sh script".equals(content)) extension = ".sh";
            }
            for (;;) {
                String line = err.readLine();
                if (line == null) break;
                if (BuildConfig.DEBUG) Log.e(TAG, line);
            }
            if (isAlive(p)) {
                p.waitFor();
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While inspecting \"" + file + "\": " + e.toString());
        } finally {
            Util.close(in, err);
        }
        boolean matchesalt = false;
        if (extension != null) {
            String fileName = file.getName().toLowerCase();
            if (!fileName.endsWith(extension)) {
                if (alt != null) {
                    for (String a : alt) {
                        if (fileName.endsWith(a)) {
                            matchesalt = true;
                            if (DebugUtil.TEST) return a;
                            break;
                        }
                    }
                }
                if (!matchesalt) suggestions.put(file, extension);
            }
        }
        return extension;
    }

    /**
     * Inspects the given zip file.
     * @param f zip file
     * @param suggestions map to add the file extension to
     */
    private static String inspectZip(@NonNull final File f, @NonNull final Map<File, String> suggestions) {
        if (!f.isFile()) return null;
        final ZipFile zipFile = new ZipFile(f);
        String extension = null;
        try {
            final List<FileHeader> headers = zipFile.getFileHeaders();
            for (FileHeader h : headers) {
                final String name = h.getFileName();
                if (name == null) continue;
                if (name.endsWith(".opf")) extension = ".epub";
                else if ("AndroidManifest.xml".equals(name) || "classes.dex".equals(name)) extension = ".apk";
                else if ("debian-binary".equals(name)) extension = ".deb";
                else if ("mimetype".equals(name)) {
                    // OpenDocument files should contain a file named "mimetype" which just contains guess what
                    File extracted = null;
                    BufferedReader reader = null;
                    try {
                        String dir = System.getProperty("java.io.tmpdir");
                        zipFile.extractFile(h, dir);
                        extracted = new File(dir, name);
                        reader = new BufferedReader(new InputStreamReader(new FileInputStream(extracted)));
                        String line = reader.readLine();
                        String ext = line != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(line.trim()) : null;
                        if (ext != null) extension = '.' + ext;
                    } finally {
                        Util.close(reader);
                        Util.deleteFile(extracted);
                    }
                }
                if (extension != null) break;
            }
            if (extension == null) extension = ".zip";
            if (!f.getName().toLowerCase().endsWith(extension)) {
                suggestions.put(f, extension);
            }
        } catch (IOException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While inspecting " + f + ": " + e.toString());
        }
        return extension;
    }

    /**
     * Tests whether the given process is alive.
     * @param p Process
     * @return true / false
     */
    private static boolean isAlive(@Nullable Process p) {
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /**
     * Compares the input data from position start with a given char sequence.
     * @param data input data
     * @param start start index
     * @param sequence char sequence to compare to
     * @return {@code true} if the data equals the char sequence
     */
    private static boolean isTheSame(@NonNull final int[] data, @IntRange(from = 0) final int start, @NonNull final CharSequence sequence) {
        if (data.length < sequence.length()) return false;
        try {
            for (int i = start; i < start + sequence.length(); i++) {
                char c = sequence.charAt(i - start);
                if (data[i] != c) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "isTheSame(" + Arrays.toString(data) + ", " + start + ", \"" + sequence + "\"): " + e.toString(), e);
        }
        return false;
    }

    /**
     * Modifies a file extension.
     * @param f File to rename
     * @param extension extension to append to the file name
     * @return true if the file has been renamed
     */
    private static boolean replaceExtension(@NonNull File f, @NonNull String extension) {
        int dot = f.getName().lastIndexOf('.');
        String newName = dot >= 0 ? f.getName().substring(0, dot) + extension : f.getName() + extension;
        File target;
        target = new File(f.getParent(), newName);
        String alt = Util.suggestAlternativeFilename(target);
        if (alt != null) target = new File(f.getParent(), alt);
        boolean ok = f.renameTo(target);
        if (BuildConfig.DEBUG && !ok) Log.e(TAG, "Failed to rename " + f + " to " + target);
        return ok;
    }

    /**
     * Finds out whether "toybox file" can be run on the device.
     * @return true / false
     */
    @VisibleForTesting
    public static boolean testToybox() {
        boolean ok = false;
        BufferedReader in = null;
        BufferedReader err = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[] {PROG});
            in = new BufferedReader(new InputStreamReader(new BufferedInputStream(p.getInputStream())));
            err = new BufferedReader(new InputStreamReader(new BufferedInputStream(p.getErrorStream())));
            while (!ok) {
                String line = in.readLine();
                if (line == null) break;
                //if (BuildConfig.DEBUG) Log.i(TAG, line);
                String[] cmds = line.split(" ");
                for (String cmd : cmds) {
                    if (P1.equals(cmd)) {
                        ok = true;
                        break;
                    }
                }
            }
            for (; ; ) {
                String line = err.readLine();
                if (line == null) break;
                if (BuildConfig.DEBUG) Log.e(TAG, line);
            }
            if (isAlive(p)) {
                p.waitFor();
            }
        } catch (Exception e) {
            ok = false;
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        } finally {
            Util.close(in, err);
        }
        return ok;
    }

    /** Non-null under normal circumstances - null under test conditions */
    private final Reference<Activity> refa;
    /** Set of names of files that will be ignored */
    @NonNull private final Set<String> ignoreUs;
    private Listener listener;
    /** only used in tests */
    @SuppressLint("StaticFieldLeak")
    private Context ctx;

    /**
     * Constructor.
     * @param activity Activity
     * @param listener Listener
     */
    public Inspector(@NonNull Activity activity, @NonNull Listener listener) {
        super();
        this.refa = new WeakReference<>(activity);
        this.listener = listener;
        this.ignoreUs = init(activity);
    }

    @TestOnly
    public Inspector(@NonNull Context ctx, @NonNull Listener listener) {
        super();
        if (!DebugUtil.TEST) throw new AssertionError("Test-Only!");
        this.refa = null;
        this.ctx = ctx;
        this.listener = listener;
        this.ignoreUs = init(ctx);
    }

    /**
     * Removes entries from the ignore Set whose files do not exist any more.
     * @param ctx Context
     */
    @VisibleForTesting
    public int cleanIgnoreSet(@NonNull Context ctx) {
        int n;
        synchronized (ignoreUs) {
            if (this.ignoreUs.isEmpty()) {
                return 0;
            }
            final File dir = App.getDownloadsDir(ctx);
            final Set<String> toRemove = new HashSet<>();
            for (String name : this.ignoreUs) {
                File file = new File(dir, name);
                if (!file.isFile()) {
                    toRemove.add(name);
                    if (BuildConfig.DEBUG) Log.i(TAG, "Removing \"" + name + "\" from the ignore list.");
                }
            }
            n = toRemove.size();
            if (n == 0) {
                return 0;
            }
            if (!this.ignoreUs.removeAll(toRemove)) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to remove any of " + toRemove + " from ignore set!");
                return 0;
            }
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            SharedPreferences.Editor ed = prefs.edit();
            if (this.ignoreUs.isEmpty()) {
                ed.remove(PREF_INSPECTOR_IGNORED);
            } else {
                ed.putStringSet(PREF_INSPECTOR_IGNORED, this.ignoreUs);
            }
            ed.apply();
        }
        return n;
    }

    /** {@inheritDoc} */
    @Override
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public Map<File, String> doInBackground(File... files) {
        final App app;
        if (DebugUtil.TEST) {
            if (ctx == null) return null;
            app = (App) ctx.getApplicationContext();
        } else {
            Activity activity = this.refa.get();
            if (activity == null) return null;
            app = (App) activity.getApplicationContext();
        }
        cleanIgnoreSet(app);
        if (files == null || files.length == 0) {
            files = App.getDownloadsDir(app).listFiles();
            if (files == null || files.length == 0) return null;
        }
        final Map<File, String> suggestions = new HashMap<>(files.length);
        int toyboxAvailable = PreferenceManager.getDefaultSharedPreferences(app).getInt(KEY, TOYBOX_UNKNOWN);
        if (toyboxAvailable == TOYBOX_UNKNOWN) {
            toyboxAvailable = testToybox() ? TOYBOX_AVAILABLE : TOYBOX_UNAVAILABLE;
            SharedPreferences.Editor ed = PreferenceManager.getDefaultSharedPreferences(app).edit();
            ed.putInt(KEY, toyboxAvailable);
            ed.apply();
        }

        // for devices that do not have toybox
        if (toyboxAvailable == TOYBOX_UNAVAILABLE) {
            for (File file : files) {
                if (isCancelled()) break;
                synchronized (ignoreUs) {
                    if (this.ignoreUs.contains(file.getName())) continue;
                }
                if (app.isBeingDownloaded(file) || file.length() == 0L || !file.isFile()) continue;
                inspectFile(file, suggestions);
            }
            return suggestions;
        }

        // for devices that do have toybox
        for (File file : files) {
            if (isCancelled()) break;
            synchronized (ignoreUs) {
                if (this.ignoreUs.contains(file.getName())) continue;
            }
            if (app.isBeingDownloaded(file) || file.length() == 0L || !file.isFile()) continue;
            inspectViaToybox(file, suggestions);
        }
        return suggestions;
    }

    /**
     * Completes initialisation.
     * @param ctx Context
     * @return Set of names of files to ignore
     */
    @NonNull
    private Set<String> init(@NonNull Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.registerOnSharedPreferenceChangeListener(this);
        //noinspection ConstantConditions
        return prefs.getStringSet(PREF_INSPECTOR_IGNORED, new HashSet<>(0));
    }

    /** {@inheritDoc} */
    @Override
    protected void onCancelled(Map<File, String> suggestions) {
        Context c = this.refa != null ? this.refa.get() : null;
        if (c == null) c = ctx; // <- test only
        if (c != null) {
            PreferenceManager.getDefaultSharedPreferences(c).unregisterOnSharedPreferenceChangeListener(this);
            if (this.refa != null) this.refa.clear();
        }
        this.ctx = null;
        this.listener = null;
    }

    /** {@inheritDoc} */
    @Override
    protected void onPostExecute(@Nullable final Map<File, String> suggestions) {
        this.ctx = null;
        Activity activity = this.refa != null ? this.refa.get() : null;
        if (activity == null) {this.listener = null; return;}
        PreferenceManager.getDefaultSharedPreferences(activity).unregisterOnSharedPreferenceChangeListener(this);
        if (activity.isFinishing() || suggestions == null || suggestions.isEmpty()) {this.listener = null; return;}
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.msg_confirmation)
                .setNegativeButton(R.string.label_no, (dialog, which) -> {this.listener = null; dialog.cancel();})
                ;
        final Set<Map.Entry<File, String>> entries = suggestions.entrySet();
        for (Map.Entry<File, String> entry : entries) {
            File file = entry.getKey();
            if (!file.isFile()) continue;
            builder
                    .setMessage(activity.getString(R.string.msg_rename, file.getName(), entry.getValue()))
                    .setNeutralButton(R.string.label_ignore, (dialog, which) -> {
                        synchronized (ignoreUs) {
                            this.ignoreUs.add(file.getName());
                        }
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putStringSet(PREF_INSPECTOR_IGNORED, this.ignoreUs);
                        ed.apply();
                        dialog.dismiss();
                    })
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (replaceExtension(file, entry.getValue())) {
                            if (this.listener != null) this.listener.renamed();
                        }
                        this.listener = null;
                        dialog.dismiss();
                    })
                    .setOnCancelListener(dialog -> this.listener = null)
                    .setOnDismissListener(dialog -> this.listener = null)
            ;
            AlertDialog ad = builder.create();
            Window dialogWindow = ad.getWindow();
            if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
            ad.show();
        }
        suggestions.clear();
        this.refa.clear();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PREF_INSPECTOR_IGNORED.equals(key)) {
            synchronized (ignoreUs) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Reloading file names to ignore.");
                this.ignoreUs.clear();
                //noinspection ConstantConditions
                this.ignoreUs.addAll(prefs.getStringSet(PREF_INSPECTOR_IGNORED, new HashSet<>(0)));
            }
        }
    }

    public interface Listener {
        /** a file has been renamed */
        void renamed();
    }

    /**
     * Returns the name of the root node of an XML document.
     */
    private static class XmlInspecter extends DefaultHandler {
        private static SAXParserFactory SAXPARSER_FACTORY = null;
        private StringBuilder builder;
        private String schema = "xml";

        /**
         * Inspects an xml file.
         * @param file File to inspect
         * @return name of the root node
         */
        @Nullable
        String inspect(@NonNull File file) {
            if (SAXPARSER_FACTORY == null) SAXPARSER_FACTORY = SAXParserFactory.newInstance();
            SAXParser parser;
            InputStreamReader reader = null;
            try {
                parser = SAXPARSER_FACTORY.newSAXParser();
                reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
                parser.parse(new InputSource(reader), this);
            } catch (SAXException s) {
                this.schema = s.getMessage();
            } catch (Exception e) {
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                this.schema = null;
            } finally {
                Util.close(reader);
            }
            return this.schema;
        }

        /** {@inheritDoc} */
        @Override
        public void characters(final char[] ch, int start, int length) {
            if (BuildConfig.DEBUG) Log.i(XmlInspecter.class.getSimpleName(), "characters(" + ch.length + ", " + start + ", " + length + ")");
            // limit the length to sensible values to avoid nasty input data
            if (this.builder.length() > 4096) return;
            //
            this.builder.append(ch, start, length);
        }

        /** {@inheritDoc} */
        @Override
        public void startDocument() {
            this.builder = new StringBuilder(128);
        }

        /** {@inheritDoc} */
        @Override
        public void startElement(String uri, String localName, String name, final Attributes attributes) throws SAXException {
            if (!TextUtils.isEmpty(localName)) {
                // The Exception is abused here to return the result. Good grief.
                throw new SAXException(localName.toLowerCase(java.util.Locale.US));
            }
        }
    }
}

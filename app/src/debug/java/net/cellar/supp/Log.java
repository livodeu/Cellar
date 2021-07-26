/*
 * Log.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.AnyThread;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

public final class Log {

    private static final File FILE;
    private static final String FILENAME = "cellar.log";
    private static final Handler HANDLER;
    private static final List<String> LINES;
    private static final Runnable SAVER = Log::save;
    private static final String SUBDIR = "logs";
    private static final long MAX_LENGTH = 50_000_000L;
    private static final long MAIN_THREAD;

    static {
        if (BuildConfig.DEBUG) {
            String tmp = System.getProperty("java.io.tmpdir");
            assert tmp != null;
            File dir = new File(tmp, SUBDIR);
            if (!dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            MAIN_THREAD = Thread.currentThread().getId();
            LINES = new ArrayList<>(64);
            FILE = new File(dir, FILENAME);
            try {
                Looper.prepare();
            } catch (Exception e) {
                android.util.Log.i(Log.class.getSimpleName(), e.toString());
            }
            HANDLER = new Handler();
        } else {
            MAIN_THREAD = 0L;
            LINES = null;
            FILE = null;
            HANDLER = null;
        }
    }

    @AnyThread
    private static void addLine(final int level, final String tag, final String msg) {
        final char l;
        switch (level) {
            case android.util.Log.VERBOSE: l = 'V'; break;
            case android.util.Log.DEBUG: l = 'D'; break;
            case android.util.Log.WARN: l = 'W'; break;
            case android.util.Log.ERROR: l = 'E'; break;
            case android.util.Log.ASSERT: l = 'A'; break;
            default: l = 'I';
        }
        boolean onMain = Thread.currentThread().getId() == MAIN_THREAD;
        synchronized (LINES) {
            LINES.add(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new java.util.Date()) + (onMain ? " MAIN " : " WORK ") + l + '/' + tag + ' ' + msg);
        }
        HANDLER.removeCallbacks(SAVER);
        HANDLER.postDelayed(SAVER, 3_000L);
    }

    @AnyThread
    public static void e(String tag, String msg, @NonNull Throwable t) {
        if (!BuildConfig.DEBUG) return;
        addLine(android.util.Log.ERROR, tag, msg + '\n' + android.util.Log.getStackTraceString(t));
        android.util.Log.e(tag, msg, t);
    }

    @AnyThread
    public static void e(String tag, String msg) {
        if (!BuildConfig.DEBUG) return;
        addLine(android.util.Log.ERROR, tag, msg);
        android.util.Log.e(tag, msg);
    }

    @IntRange(from = 0)
    public static long getSize() {
        return FILE != null ? FILE.length() : 0L;
    }

    @AnyThread
    public static void i(String tag, String msg) {
        if (!BuildConfig.DEBUG) return;
        addLine(android.util.Log.INFO, tag, msg);
        android.util.Log.i(tag, msg);
    }

    public static void k(String tag, java.io.InputStream in) {
        new Thread() {
            @Override
            public void run() {
                java.io.BufferedReader reader = null;
                try {
                    reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(in)));
                    for(;;) {
                        String line = reader.readLine();
                        if (line == null) break;
                        android.util.Log.v(tag, line);
                    }
                } catch (Throwable ignored) {
                } finally {
                    Util.close(reader);
                }
            }
        }.start();
    }

    private static void save() {
        BufferedWriter writer = null;
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null) return;
            File dir = new File(tmp, SUBDIR);
            if (!dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(FILE, FILE.length() < MAX_LENGTH)));
            synchronized (LINES) {
                for (String line : LINES) {
                    writer.write(line);
                    writer.newLine();
                }
                LINES.clear();
            }
        } catch (java.io.IOException e) {
            android.util.Log.e(Log.class.getSimpleName(), e.toString());
        } finally {
            Util.close(writer);
        }
    }

    /**
     * Shares the log file.
     * @param ctx Context
     * @return null or an error message
     */
    @Nullable
    public static String share(@NonNull Context ctx) {
        if (FILE == null || !FILE.isFile()) return "The log file does not exist!";
        Util.send(ctx, FILE, BuildConfig.FILEPROVIDER_AUTH, "text/plain");
        return null;
    }

    @AnyThread
    public static void w(String tag, String msg) {
        if (!BuildConfig.DEBUG) return;
        addLine(android.util.Log.WARN, tag, msg);
        android.util.Log.w(tag, msg);
    }

    @AnyThread
    public static void w(String tag, String msg, @NonNull Throwable t) {
        if (!BuildConfig.DEBUG) return;
        addLine(android.util.Log.WARN, tag, msg + '\n' + android.util.Log.getStackTraceString(t));
        android.util.Log.e(tag, msg, t);
    }

    private Log() {}
}

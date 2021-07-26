package net.cellar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.speech.tts.TextToSpeech;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import net.cellar.supp.Util;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes test results to a CSV file.
 * Also mutters something (unless running on an emulator).
 */
public class LoggingListener extends RunListener implements TextToSpeech.OnInitListener {

    private static final String[] COLUMNS = new String[] {"Date", "Test", "API", "Success"};
    private static final String CSV_FILE = "testlog.csv";
    @SuppressLint("SimpleDateFormat")
    private static final DateFormat DF = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
    private static final char SEPARATOR = ',';

    /**
     * Generates a text representing a failed test.
     * @param appendTo StringBuilder to append to
     * @param failure Failure
     * @return CharSequence
     */
    @NonNull
    private static CharSequence makeFailText(@Nullable StringBuilder appendTo, @NonNull Failure failure) {
        final StringBuilder sb = appendTo != null ? appendTo : new StringBuilder(32);
        sb.append("\"").append(failure.getDescription().getMethodName()).append("\" failed ");
        String msg = failure.getMessage();
        if (msg != null && msg.length() > 0) {
            sb.append("with message \"")
                    .append(failure.getMessage())
                    .append("\".");
        } else {
            sb.append("without a message.");
        }
        return sb;
    }

    /**
     * Creates a CSV line.
     * @param description Description
     * @param success true / false
     * @return String
     */
    private static String makeReport(@NonNull final Description description, boolean success) {
        return DF.format(new java.util.Date())
                + SEPARATOR
                + description.getClassName() + '.' + description.getMethodName()
                + SEPARATOR
                + Build.VERSION.SDK_INT
                + SEPARATOR
                + success;
    }

    @NonNull
    private static CharSequence makeSkippedText(@Nullable StringBuilder appendTo, @NonNull Failure failure) {
        final StringBuilder sb = appendTo != null ? appendTo : new StringBuilder(32);
        sb.append("\"").append(failure.getDescription().getMethodName()).append("\" has been skipped ");
        String msg = failure.getMessage();
        if (msg != null && msg.length() > 0) {
            sb.append("because \"")
                    .append(failure.getMessage())
                    .append("\".");
        } else {
            sb.append("without a message.");
        }
        //android.util.Log.i(LoggingListener.class.getSimpleName(), sb.toString());
        return sb;
    }

    /**
     * Generates a text representing a successful test.
     * @param appendTo StringBuilder to append to
     * @param description Description
     * @return CharSequence
     */
    @NonNull
    private static CharSequence makeSuccessText(@Nullable StringBuilder appendTo, @NonNull Description description) {
        StringBuilder sb = appendTo != null ? appendTo : new StringBuilder(32);
        return sb.append("\"").append(description.getMethodName()).append("\" succeeded.");
    }
    private final File csv;
    private final TextToSpeech tts;
    private final List<Failure> failures = new ArrayList<>();
    private final List<Description> successes = new ArrayList<>();
    private final List<Failure> badAssumptions = new ArrayList<>();
    private boolean canSpeak = false;
    private OutputStream out = null;

    /**
     * Constructor.
     */
    public LoggingListener() {
        super();
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (ctx == null) {
            android.util.Log.e(getClass().getSimpleName(), "Null Context!");
            csv = null; tts = null;
            return;
        }
        File downloadsDir = App.getDownloadsDir(ctx);
        if (!downloadsDir.isDirectory()) {
            if (!downloadsDir.mkdirs()) {
                csv = null; tts = null;
                return;
            }
        }
        boolean needsTitle = false;
        csv = new File(downloadsDir, CSV_FILE);
        if (csv.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            csv.setWritable(true);
        } else {
            needsTitle = true;
        }
        try {
            out = new FileOutputStream(csv, true);
            if (needsTitle) {
                for (int i = 0; i < COLUMNS.length - 1; i++) {
                    out.write(COLUMNS[i].getBytes());
                    out.write(SEPARATOR);
                }
                out.write(COLUMNS[COLUMNS.length - 1].getBytes());
                out.write('\n');
            }
        } catch (IOException e) {
            android.util.Log.e(getClass().getSimpleName(), e.toString());
        }
        tts = new TextToSpeech(ctx, this);
    }

    private void destroy() {
        Util.close(out);
        out = null;
        if (csv != null && csv.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            csv.setWritable(false);
        }
    }

    /**
     * Writes a line to the csv file.
     * @param report line to write
     */
    private void log(String report) {
        if (out == null || report == null) return;
        try {
            out.write(report.getBytes());
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            android.util.Log.e(getClass().getSimpleName(), e.toString());
        }
    }

    @Override
    public void onInit(int status) {
        canSpeak = (status == TextToSpeech.SUCCESS);
        if (canSpeak) {
            tts.setLanguage(Locale.ENGLISH);
            tts.setSpeechRate(0.75f);
            tts.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        } else {
            android.util.Log.e(getClass().getSimpleName(), "TTS failed to initialise properly: code " + status);
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    private void speak(CharSequence cs) {
        if (!canSpeak || cs == null) return;
        final int l = cs.length();
        // un-camel for tts - the lady would have a bit of trouble otherwise
        final StringBuilder makeup = new StringBuilder(l + 8);
        for (int i = 0; i < l; i++) {
            char c = cs.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && Character.isLowerCase(cs.charAt(i - 1))) {
                    makeup.append(' ');
                }
            }
            makeup.append(c);
        }
        tts.speak(makeup, TextToSpeech.QUEUE_ADD, null, String.valueOf(System.currentTimeMillis()));
    }

    /** {@inheritDoc} */
    @Override
    public void testAssumptionFailure(Failure failure) {
        android.util.Log.w(getClass().getSimpleName(), "test assumption not met: " + failure);
        if (failure == null) return;
        badAssumptions.add(failure);
        speak(makeSkippedText(null, failure));
    }

    /** {@inheritDoc} */
    @Override
    public void testFailure(Failure failure) {
        android.util.Log.w(getClass().getSimpleName(), "test failure: " + failure);
        if (failure == null) return;
        failures.add(failure);
        speak(makeFailText(null, failure));
    }

    /** {@inheritDoc} */
    @Override
    public void testFinished(Description description) {
        android.util.Log.i(getClass().getSimpleName(), "test finished: " + description);
        if (description == null) return;
        boolean failed = false;
        boolean ignored = false;
        for (Failure failure : failures) {
            if (description.equals(failure.getDescription())) {
                failed = true;
                break;
            }
        }
        for (Failure failure : badAssumptions) {
            if (description.equals(failure.getDescription())) {
                ignored = true;
                break;
            }
        }
        if (!failed && !ignored) {
            successes.add(description);
            speak(makeSuccessText(null, description));
        }
        if (!ignored) log(makeReport(description, !failed));
    }

    /** {@inheritDoc} */
    @Override
    public void testIgnored(Description description) {
        android.util.Log.i(getClass().getSimpleName(), "test ignored: " + description);
    }

    /** {@inheritDoc} */
    @Override
    public void testRunFinished(Result result) {
        final StringBuilder msg = new StringBuilder(64);
        msg.append("Test run finished.");
        List<Failure> failures = result.getFailures();
        if (failures != null && !failures.isEmpty()) {
            msg.append(' ');
            for (Failure failure : failures) {
                makeFailText(msg, failure);
            }
            int s = successes.size();
            if (s != 1) msg.append(' ').append(s).append(" tests succeeded."); else msg.append(" One test succeeded.");
        } else {
            int n = result.getRunCount();   // runcount will include those whose assumptions were not met, but not those that were ignored
            n -= badAssumptions.size();
            if (n <= 0) msg.append(" No test has been executed.");
            else if (n != 1) msg.append(" All ").append(n).append(" tests succeeded."); else msg.append(" The test succeeded.");
        }
        int ignored = result.getIgnoreCount();
        if (ignored == 1) msg.append(" One test has been ignored.");
        else if (ignored > 1) msg.append(" ").append(ignored).append(" tests have been ignored.");
        if (result.wasSuccessful()) android.util.Log.i(getClass().getSimpleName(), msg.toString());
        else android.util.Log.w(getClass().getSimpleName(), msg.toString());
        speak(msg);
        destroy();
    }

    /** {@inheritDoc} */
    @Override
    public void testRunStarted(@Nullable Description description) {
        android.util.Log.i(getClass().getSimpleName(), "test run started: " + description);
    }

    /** {@inheritDoc} */
    @Override
    public void testStarted(Description description) {
        android.util.Log.i(getClass().getSimpleName(), "test started: " + description);
    }
}

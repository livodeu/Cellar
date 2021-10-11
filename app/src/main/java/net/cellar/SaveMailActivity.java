/*
 * SaveMailActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.supp.DebugUtil;
import net.cellar.supp.IdSupply;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SaveMailActivity extends Activity {

    private static final String TAG = "SaveMailActivity";

    /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] addresses = getIntent().getStringArrayExtra(Intent.EXTRA_EMAIL);
        String subject = getIntent().getStringExtra(Intent.EXTRA_SUBJECT);
        String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        String html = getIntent().getStringExtra(Intent.EXTRA_HTML_TEXT);

        final File dir = App.getDownloadsDir(this);
        String txtFilename = null;
        String htmlFilename;

        if (!TextUtils.isEmpty(subject)) {
            txtFilename = subject;
        }
        if (TextUtils.isEmpty(txtFilename)) {
            if (addresses != null && addresses.length > 0) {
                txtFilename = addresses[0];
            }
        }
        if (TextUtils.isEmpty(txtFilename)) {
            txtFilename = text;
        }

        if (TextUtils.isEmpty(txtFilename)) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Wasn't able to create a file name!");
            finish();
            return;
        }
        if (text == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Received no text!");
            finish();
            return;
        }

        final List<Uri> failures = new ArrayList<>();
        final List<String> successes = new ArrayList<>();

        assert txtFilename != null;
        if (txtFilename.length() > 128) txtFilename = txtFilename.substring(0, 128);

        txtFilename = txtFilename.replace('/', '_').trim();
        htmlFilename = txtFilename;

        if (!txtFilename.endsWith(".txt")) txtFilename = txtFilename + ".txt";
        if (!htmlFilename.endsWith(".htm")) htmlFilename = htmlFilename + ".htm";

        File txtFile = new File(dir, txtFilename);
        String alt = Util.suggestAlternativeFilename(txtFile);
        if (alt != null) txtFile = new File(dir, alt);

        // save attachments
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.containsKey(Intent.EXTRA_STREAM)) {
            Object stream = extras.get(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) {
                String storedfilename = saveUri(dir, (Uri)stream);
                if (storedfilename != null) successes.add(storedfilename); else failures.add((Uri)stream);
            } else if (stream instanceof List) {
                List<?> urilist = (List<?>)stream;
                for (Object o : urilist) {
                    if (!(o instanceof Uri)) continue;
                    String storedfilename = saveUri(dir, (Uri)o);
                    if (storedfilename != null) successes.add(storedfilename); else failures.add((Uri)o);
                }
            }
        }

        // save mail text in a text file
        OutputStream out = null;
        boolean textSaved;
        try {
            out = new FileOutputStream(txtFile);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STARTED));
            setResult(RESULT_OK);
            textSaved = true;
        } catch (IOException e) {
            textSaved = false;
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        } finally {
            Util.close(out);
        }
        if (!textSaved && txtFile.isFile()) Util.deleteFile(txtFile);

        // write HTML text, too, if it was supplied
        if (html != null && html.length() > 0) {
            boolean htmlSaved = false;
            File htmlFile = new File(dir, htmlFilename);
            alt = Util.suggestAlternativeFilename(htmlFile);
            if (alt != null) htmlFile = new File(dir, alt);
            try {
                out = new FileOutputStream(htmlFile);
                out.write(html.getBytes(StandardCharsets.UTF_8));
                htmlSaved = true;
             } catch (IOException e) {
                if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            } finally {
                Util.close(out);
            }
            if (!htmlSaved && htmlFile.isFile()) Util.deleteFile(htmlFile);
        }

        // show a notification
        App app = (App)getApplicationContext();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            Intent contentIntent = new Intent(this, UiActivity.class);
            contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            final Notification.Builder builder = new Notification.Builder(this);
            final StringBuilder msg = new StringBuilder(32);

            if (textSaved) {
                msg.append(getString(R.string.msg_downloaded_mail, txtFile.getName()));
            } else {
                msg.append(getString(R.string.msg_downloaded_mail_fail));
            }
            if (!successes.isEmpty()) {
                for (String success : successes) {
                    msg.append('\n').append(getString(R.string.msg_downloaded_mail_att, success));
                }
            }
            if (!failures.isEmpty()) {
                for (Uri failure : failures) {
                    msg.append('\n').append(getString(R.string.msg_downloaded_mail_att_fail, failure));
                }
            }

            boolean hooray = textSaved || !successes.isEmpty();
            builder
                    .setContentText(msg)
                    .setSmallIcon(hooray ? R.drawable.ic_n_download_done : R.drawable.ic_n_error)
                    .setContentTitle(hooray ? getString(R.string.msg_download_complete) : getString(R.string.msg_download_failed))
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setContentIntent(PendingIntent.getActivity(this, 1, contentIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_CANCEL_CURRENT))
            ;
            if (msg.length() > getResources().getInteger(R.integer.notification_text_maxlength)) {
                // do not set summary text because that would end up in the place of the notification's subtext
                builder.setStyle(new Notification.BigTextStyle().bigText(msg));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(app.getNc().getId());
            }
            if (!hooray) {
                builder.setColor(getResources().getColor(R.color.colorAccent));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setColorized(true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setAllowSystemGeneratedContextualActions(false);
            }
            nm.notify(IdSupply.NOTIFICATION_ID_MAIL_SAVED, builder.build());
        }
        finish();
    }

    /**
     * Saves the file that the given Uri points to in the given directory.
     * @param directory directory to store the file in
     * @param uri Uri that points to a file
     * @return the name of the file that has been stored or {@code null}
     */
    @Nullable
    private String saveUri(@NonNull File directory, @NonNull Uri uri) {
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(uri, null, null, null, null, null);
        String fileName = null;
        if (cursor != null && cursor.moveToFirst()) {
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (sizeIndex > -1) {
                long fileSize = cursor.getLong(sizeIndex);
                if (fileSize > directory.getFreeSpace()) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Could not save " + uri + "! Not enough space (need " + fileSize + ")");
                    return null;
                }
            }
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex > -1) fileName = cursor.getString(nameIndex);
        }
        if (fileName == null) {
            fileName = uri.getLastPathSegment();
        }
        Util.close(cursor);
        if (fileName == null || fileName.length() == 0) {
            return null;
        }
        fileName = fileName.replace('/', '_').trim();
        InputStream in = null;
        OutputStream out = null;
        boolean ok;
        File destFile = new File(directory, fileName);
        String alt = Util.suggestAlternativeFilename(destFile);
        if (alt != null) {
            fileName = alt;
            destFile = new File(directory, fileName);
        }
        try {
            in = cr.openInputStream(uri);
            if (in == null) throw new FileNotFoundException("Failed to open " + uri);
            out = new BufferedOutputStream(new FileOutputStream(destFile));
            byte[] buf = new byte[4096];
            for (;;) {
                int read = in.read(buf);
                if (read < 0) break;
                out.write(buf, 0, read);
            }
            ok = true;
        } catch (Exception e) {
            ok = false;
            if (BuildConfig.DEBUG) Log.e(TAG, "While reading \"" + uri + "\": " + e.toString());
        } finally {
            Util.close(out, in);
        }
        if (!ok && destFile.isFile()) Util.deleteFile(destFile);
        return ok ? fileName : null;
    }
}

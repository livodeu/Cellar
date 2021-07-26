/*
 * StoreActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.annotation.SuppressLint;
import android.app.ApplicationErrorReport;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.CalendarContract;
import android.text.Html;
import android.text.TextUtils;
import android.util.StringBuilderPrinter;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import net.cellar.supp.DebugUtil;
import net.cellar.supp.IdSupply;
import net.cellar.supp.Log;
import net.cellar.model.Vcalendar;
import net.cellar.model.Vcard;
import net.cellar.supp.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;

/**
 * Handles data that is received directly and does not have to be downloaded.
 */
public final class StoreActivity extends AppCompatActivity {

    private static final String TAG = "StoreActivity";

    /**
     * Writes the contents of {@code text} to a file. UTF-8 is assumed.
     * @param text text to write
     * @param dest destination file
     * @throws IOException if an I/O error occurs
     */
    private static void writeTextToFile(@NonNull final String text, @NonNull final File dest) throws IOException {
        OutputStream out = null;
        boolean destExistedBefore = dest.isFile();
        try {
            out = new FileOutputStream(dest);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            Util.close(out);
        } catch (IOException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
            Util.close(out);
            if (!destExistedBefore) Util.deleteFile(dest);
            throw e;
        }
    }

    /** the {@link Intent#getType() Intent MIME type} */
    private String mime;
    private CharSequence title;
    private String plainText;
    private String htmlText;

    /**
     * See also <a href="https://developer.android.com/guide/components/intents-common?hl=en#java">https://developer.android.com/guide/components/intents-common?hl=en#java</a>
     * @param intent Intent
     */
    private void handleIntent(Intent intent) {

        if (BuildConfig.DEBUG) DebugUtil.logIntent(TAG, intent);

        final Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_n_download_done)
                .setAutoCancel(true)
                ;
        try {
            if (intent == null || (!isVcalendar(intent) && !isVcard(intent) && !isNote(intent) && !isMail(intent) && !isBugReport(intent) && !isGeo(intent))) {
                throw new RuntimeException(getString(R.string.error_cant_handle_that));
            }

            assert (plainText != null || htmlText != null);
            assert mime != null;
            assert title != null;

            if (BuildConfig.DEBUG) Log.i(TAG, plainText != null ? plainText : htmlText);
            String tag = MimeTypeMap.getSingleton().getExtensionFromMimeType(this.mime);
            File dir = App.getDownloadsDir(this);
            File dest = new File(dir, App.generateFilename(this.title, null, tag));
            String alt = Util.suggestAlternativeFilename(dest);
            if (alt != null) dest = new File(dir, alt);
            writeTextToFile(this.htmlText != null ? this.htmlText : this.plainText, dest);
            Intent contentIntent = new Intent(this, UiActivity.class);
            contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            builder
                    .setContentTitle(getString(R.string.msg_download_stored))
                    .setContentText(getString(R.string.msg_downloaded_file, dest.getName()))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(PendingIntent.getActivity(this, 1, contentIntent, 0))
            ;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(((App)getApplicationContext()).getNc().getId());
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "While dealing with " + intent + ": " + e);
            builder
                    .setSmallIcon(R.drawable.ic_n_error)
                    .setContentTitle(getString(R.string.error_store_failed))
                    .setContentText(!TextUtils.isEmpty(e.getMessage()) ? e.getMessage() : e.toString())
                    .setCategory(Notification.CATEGORY_ERROR)
                    .setColor(Color.RED)
            ;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setColorized(true).setChannelId(((App)getApplicationContext()).getNc().getId());
            }
        }
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(IdSupply.NOTIFICATION_ID_STORE_ACTIVITY, builder.build());
        if (DebugUtil.TEST) sendBroadcast(new Intent(App.ACTION_DOWNLOAD_STARTED));
    }

    /**
     * Checks whether the given Intent represents a usable bug report intent.
     * @param intent Intent
     * @return true / false
     */
    private boolean isBugReport(@NonNull Intent intent) {
        if (!Intent.ACTION_APP_ERROR.equals(intent.getAction())) return false;
        Bundle extras = intent.getExtras();
        if (extras == null) return false;
        Parcelable br = extras.getParcelable(Intent.EXTRA_BUG_REPORT);
        if (br == null) return false;
        if (br instanceof ApplicationErrorReport) {
            ApplicationErrorReport aer = (ApplicationErrorReport)br;
            StringBuilder sb = new StringBuilder(2048);
            aer.dump(new StringBuilderPrinter(sb), "");
            this.plainText = sb.toString();
            this.title = aer.packageName;
            if (this.title == null) this.title = aer.processName;
            if (this.title == null) this.title = ApplicationErrorReport.class.getSimpleName();
        } else {
            this.plainText = br.toString();
            this.title = br.getClass().getSimpleName();
        }
        this.mime = "text/plain";
        return true;
    }

    /**
     * Determines whether the given Intent has a geo: scheme.
     * See also <a href="https://en.wikipedia.org/wiki/Keyhole_Markup_Language">https://en.wikipedia.org/wiki/Keyhole_Markup_Language</a>.
     * @param intent Intent
     * @return true / false
     */
    private boolean isGeo(@NonNull Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (!"geo".equals(scheme)) return false;
        String content = uri.getSchemeSpecificPart(); // 51.81891,7.178675?z=10
        int comma = content.indexOf(',');
        if (comma <= 0) return false;
        double lat = Util.parseDouble(content.substring(0, comma).trim(), 0.0);
        int q = content.indexOf('?', comma + 1);
        if (q < 0) return false;
        double lon = Util.parseDouble(content.substring(comma + 1, q), 0.0);

        this.title = intent.getStringExtra(Intent.EXTRA_TITLE);
        if (TextUtils.isEmpty(this.title)) this.title = intent.getStringExtra(Intent.EXTRA_SUBJECT);

        final StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n<Placemark>\n");
        if (!TextUtils.isEmpty(this.title)) {
            sb.append("\t<name><![CDATA[").append(this.title).append("]]></name>\n");
        }
        sb.append("\t<Point>\n\t\t<coordinates>").append(lon).append(',').append(lat).append("</coordinates>\n\t</Point>\n");
        sb.append("</Placemark>\n</Document>\n</kml>");
        this.plainText = sb.toString();
        if (TextUtils.isEmpty(this.title)) {
            try {
                this.title = Location.convert(lat, Location.FORMAT_SECONDS) + '°' + (lat > 0 ? 'N' : 'S') + "," + Location.convert(lon, Location.FORMAT_SECONDS) + '°' + (lon > 0 ? 'E' : 'W');
            } catch (Exception ignored) {
                this.title = uri.toString();
            }
        }
        this.mime = "application/vnd.google-earth.kml+xml";
        return true;
    }

    /**
     * Checks whether the given Intent represents a usable "Send mail to" intent.
     * It's usable if it contains a text.
     * The title is taken from the mail subject or, if that's empty, from the beginning of the text.
     * @param intent Intent
     * @return true / false
     */
    @SuppressLint("NewApi")
    private boolean isMail(@NonNull Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SENDTO.equals(action)) return false;
        Bundle e = intent.getExtras();
        CharSequence text = e != null ? e.getCharSequence(Intent.EXTRA_TEXT) : null;
        this.plainText = text != null ? text.toString() : null;
        this.htmlText  = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
        this.title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (!TextUtils.isEmpty(this.htmlText)) {
            if (TextUtils.isEmpty(this.title)) this.title = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ? Html.fromHtml(this.htmlText, 0) : Html.fromHtml(this.htmlText);
            this.mime = "text/html";
        } else if (!TextUtils.isEmpty(this.plainText)) {
            if (TextUtils.isEmpty(this.title)) this.title = this.plainText;
            this.mime = "text/plain";
        } else {
            return false;
        }
        if (this.title.length() > 32) {
            this.title = this.title.toString().substring(0, 32).trim();
        }
        return TextUtils.getTrimmedLength(this.title) > 0;
    }

    /**
     * Checks whether the given Intent represents a usable "Create note" intent.
     * @param intent Intent
     * @return true / false
     */
    private boolean isNote(@NonNull Intent intent) {
        if (!"com.google.android.gms.actions.CREATE_NOTE".equals(intent.getAction())) return false;
        this.plainText = intent.getStringExtra("com.google.android.gms.actions.extra.TEXT");
        if (TextUtils.isEmpty(this.plainText)) return false;
        this.title = intent.getStringExtra("com.google.android.gms.actions.extra.NAME");
        if (TextUtils.isEmpty(this.title)) return false;
        this.mime = "text/plain";
        return true;
    }

    /**
     * Checks whether the given Intent is a usable "Add Calendar entry" intent.
     * @param intent Intent
     * @return true / false
     */
    private boolean isVcalendar(@NonNull Intent intent) {
        if (!Intent.ACTION_INSERT.equals(intent.getAction())) return false;
        Bundle extras = intent.getExtras();
        if (extras == null) return false;
        Uri uri = intent.getData();
        boolean calendarUri = CalendarContract.Events.CONTENT_URI.equals(uri)
                || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && CalendarContract.Events.ENTERPRISE_CONTENT_URI.equals(uri));
        if (!calendarUri) return false;
        //
        String summary = extras.getString(CalendarContract.Events.TITLE);
        String desc = extras.getString(CalendarContract.Events.DESCRIPTION);
        String location = extras.getString("eventLocation");
        Object beginTime = extras.get(CalendarContract.EXTRA_EVENT_BEGIN_TIME);
        Object endTime = extras.get(CalendarContract.EXTRA_EVENT_END_TIME);
        long begin = beginTime instanceof Long ? (Long)beginTime : (beginTime instanceof String ? Long.parseLong((String)beginTime) : 0L);
        long end = endTime instanceof Long ? (Long)endTime : (endTime instanceof String ? Long.parseLong((String)endTime) : 0L);
        final Vcalendar vcalendar = new Vcalendar();
        vcalendar.setStart(begin);
        vcalendar.setEnd(end);
        vcalendar.setLocation(location);
        vcalendar.setDescription(desc);
        if (!TextUtils.isEmpty(summary)) {
            vcalendar.setSummary(summary);
            this.title = summary;
        } else if (!TextUtils.isEmpty(desc)) {
            this.title = desc;
        } else if (!TextUtils.isEmpty(location)) {
            this.title = location;
        } else if (begin > 0L) {
            this.title = DateFormat.getDateTimeInstance().format(new java.util.Date(begin));
        } else if (end > 0L) {
            this.title = DateFormat.getDateTimeInstance().format(new java.util.Date(end));
        }
        if (TextUtils.isEmpty(this.title)) return false;
        this.plainText = vcalendar.toString();
        this.mime = "text/calendar";
        return true;
    }

    /**
     * Checks whether the given Intent is a "Add contact entry" intent.
     * If so, {@link #title}, {@link #plainText} and {@link #mime} are set here and true is returned.
     * @param intent Intent
     * @return true / false
     */
    private boolean isVcard(@NonNull Intent intent) {
        if (!Intent.ACTION_INSERT.equals(intent.getAction())) return false;
        Bundle extras = intent.getExtras();
        if (!"vnd.android.cursor.dir/contact".equals(intent.getType())) return false;
        if (extras == null) return false;
        String name = extras.getString("name");
        if (TextUtils.isEmpty(name)) return false;
        String email = extras.getString("email");
        // TEL;HOME:023-692-1841
        String phone = extras.getString("phone");
        Vcard vcard = new Vcard(name, email, phone);
        String url = extras.getString("url");
        if (!TextUtils.isEmpty(url)) vcard.setUrl(url);
        // ADR;TYPE=home:;;Heidestrasse 17;Koeln;;51147;Germany
        String postal = extras.getString("postal");
        if (!TextUtils.isEmpty(postal)) vcard.setAdr(postal);
        this.title = name;
        this.plainText = vcard.toString();
        this.mime = "text/x-vcard";
        return true;
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        super.onResume();
        handleIntent(getIntent());
        finish();
    }
}

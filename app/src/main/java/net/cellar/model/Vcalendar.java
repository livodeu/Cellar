/*
 * Vcalendar.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.StringTokenizer;

/**
 * See <a href="https://en.wikipedia.org/wiki/ICalendar">here</a>.<br>
 * Example:
 * <pre>
 * BEGIN:VCALENDAR
 * VERSION:2.0
 * PRODID:-//hacksw/handcal//NONSGML v1.0//EN
 * BEGIN:VEVENT
 * UID:uid1@example.com
 * DTSTAMP:19970714T170000Z
 * ORGANIZER;CN=John Doe:MAILTO:john.doe@example.com
 * DTSTART:19970714T170000Z
 * DTEND:19970715T035959Z
 * SUMMARY:Bastille Day Party
 * GEO:48.85299;2.36885
 * END:VEVENT
 * END:VCALENDAR
 * </pre>
 */
public class Vcalendar {
    //                                                                19970714T170000Z
    private static final DateFormat DF = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);

    static {
        DF.setLenient(true);
    }

    private long date;
    private long start;
    private long end;
    private String summary;
    private String description;
    private String mailto;
    private String location;

    public long getEnd() {
        return end;
    }

    public long getStart() {
        return start;
    }

    public void setDescription(final String description) {
        if (description == null) {
            this.description = null;
            return;
        }
        // https://tools.ietf.org/html/rfc5545#section-3.8.1.5
        final StringTokenizer st = new StringTokenizer(description, "\n", true);
        int n = st.countTokens();
        final StringBuilder sb = new StringBuilder(description.length() + (n << 1));
        while (st.hasMoreTokens()) {
            String t = st.nextToken();
            if ("\n".equals(t)) sb.append("\\n");
            else sb.append(t);
        }
        this.description = sb.toString();
    }

    public void setEnd(long end) {
        this.end = end;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64).append("BEGIN:VCALENDAR\nVERSION:2.0\n");
        sb.append("BEGIN:EVENT\n");
        if (date > 0L) sb.append("DTSTAMP:").append(DF.format(new Date(date))).append('\n');
        if (start > 0L) sb.append("DTSTART:").append(DF.format(new Date(start))).append('\n');
        if (end > 0L) sb.append("DTEND:").append(DF.format(new Date(end))).append('\n');
        if (!TextUtils.isEmpty(summary)) sb.append("SUMMARY:").append(summary).append('\n');
        if (!TextUtils.isEmpty(description)) sb.append("DESCRIPTION:").append(description).append('\n');
        if (!TextUtils.isEmpty(location)) sb.append("LOCATION:").append(location).append('\n');
        if (!TextUtils.isEmpty(mailto)) sb.append("MAILTO:").append(mailto).append('\n');
        sb.append("END:VEVENT\nEND:VCALENDAR");
        return sb.toString();
    }
}

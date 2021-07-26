/*
 * UiUtil.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.DrawableRes;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.StringTokenizer;

/**
 * UI-related utility methods.
 */
public final class UiUtil {

    /** intl. label for hours */
    private static final String LABEL_H = " h";
    /** <a href="https://www.bipm.org/utils/common/pdf/si-brochure/SI-Brochure-9.pdf">https://www.bipm.org/utils/common/pdf/si-brochure/SI-Brochure-9.pdf, page 145, table 8</a> */
    private static final String LABEL_MIN = " min";
    /** intl. label for milliseconds */
    private static final String LABEL_MS = " ms";
    /** intl. label for seconds */
    private static final String LABEL_S = " s";
    /** a reusable Calendar - just needs to be updated via {@link Calendar#setTimeInMillis(long)} */
    private static final Calendar NOW = Calendar.getInstance();

    /**
     * Dismisses a number of dialogs.
     * @param dialogs dialogs to dismiss
     */
    public static void dismissDialog(@Nullable final AlertDialog... dialogs) {
        if (dialogs == null) return;
        for (AlertDialog dialog : dialogs) {
            if (dialog == null) continue;
            if (dialog.isShowing()) dialog.dismiss();
        }
    }

    /**
     * Formats a number of bytes. The unit labels are not translated (and they should not be).
     * @param bytes number of bytes to display
     * @return String
     */
    @NonNull
    public static String formatBytes(@IntRange(from = 0) final long bytes) {
        if (bytes < 1_000L) {
            return bytes + " B";
        } else if (bytes < 1_000_000L) {
            return Math.round(bytes / 1_000f) + " kB";
        } else if (bytes < 1_000_000_000L)  {
            return Math.round(bytes / 1_000_000f) + " MB";
        }
        return Math.round(bytes / 1_000_000_000f) + " GB";
    }

    /**
     * Formats a Date in such a fashion that only the time will be shown if the Date is from today.
     * @param date Date to format
     * @param style style (see {@link DateFormat})
     * @return String
     */
    @NonNull
    public static String formatDate(long date, @IntRange(from = 0, to = 3) int style) {
        synchronized (NOW) {
            NOW.setTimeInMillis(System.currentTimeMillis());
            final Calendar dateCalendar = Calendar.getInstance();
            dateCalendar.setTimeInMillis(date);
            if (NOW.get(Calendar.YEAR) == dateCalendar.get(Calendar.YEAR)
                    && NOW.get(Calendar.MONTH) == dateCalendar.get(Calendar.MONTH)
                    && NOW.get(Calendar.DAY_OF_MONTH) == dateCalendar.get(Calendar.DAY_OF_MONTH)) {
                return DateFormat.getTimeInstance(style).format(date);
            }
            return DateFormat.getDateInstance(style).format(date);
        }
    }

    /**
     * Formats something like "CN=Release Engineering,OU=Release Engineering,O=Godzilla Corporation,L=Kuopio,ST=Savonia,C=FI".
     * @param issuer the result of X509Certificate.getIssuerX500Principal().getName()
     * @param labelIn the word for "in" or "from", meaning: in (respectively from) a specified locality
     * @return formatted name
     */
    @NonNull
    public static String formatIssuer(@Nullable String issuer, @NonNull String labelIn) {
        if (issuer == null) return "";
        final StringBuilder sb = new StringBuilder(64);
        final String divider = ", ";
        String cn = null, ou = null, o = null, l = null, st = null, c = null;
        final StringTokenizer stringTokenizer = new StringTokenizer(issuer, ",");
        while (stringTokenizer.hasMoreTokens()) {
            String namePart = stringTokenizer.nextToken();
            int eq = namePart.indexOf('=');
            if (eq <= 0) continue;
            String key = namePart.substring(0, eq).toLowerCase(java.util.Locale.US);
            String value = namePart.substring(eq + 1);
            switch (key) {
                case "cn": cn = value; break;
                case "ou": ou = value; break;
                case "o": o = value; break;
                case "l": l = value; break;
                case "st": st = value; break;
                case "c": c = value; break;
            }
        }

        if (o != null) sb.append(o.trim());
        if (ou != null && !ou.equals(o)) sb.append(sb.length() > 0 ? divider : "").append(ou.trim());
        if (cn != null && !cn.equals(ou)) sb.append(sb.length() > 0 ? divider : "").append(cn);

        if (l != null || st != null || c != null) {
            sb.append(sb.length() > 0 ? " " : "").append(labelIn);
            if (l != null) sb.append(' ').append(l);
            else if (st != null) sb.append(' ').append(st);
            if (c != null) {
                final java.util.Locale[] locales = java.util.Locale.getAvailableLocales();
                for (java.util.Locale locale : locales) {
                    if (c.equalsIgnoreCase(locale.getCountry())) {
                        c = locale.getDisplayCountry();
                        break;
                    }
                }
                if (l != null || st != null) sb.append(" (").append(c).append(')');
                else sb.append(' ').append(c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Formats a milliseconds value, using SI units and units accepted for use with the SI. The unit labels are not translated.
     * @param ms milliseconds
     * @return String representation
     */
    @NonNull
    public static String formatMs(final float ms) {
        if (ms < 1_000f) return (int)ms + LABEL_MS;
        if (ms < 60_000f) return Math.round(ms / 1_000f) + LABEL_S;
        if (ms < 3_600_000f) {
            int m = (int)Math.floor(ms / 60_000f);
            int s = (int)Math.floor((ms - (m * 60_000)) / 1_000f);
            if (s < 10) return m + ":0" + s + LABEL_MIN;
            return m + ":" + s + LABEL_MIN;
        }
        int h = (int)Math.floor(ms / 3_600_000f);
        return h + LABEL_H + ' ' + Math.round((ms - h * 3_600_000) / 60_000f) + LABEL_MIN;
    }

    public static int getNotificationSmallIconSize(@NonNull Context ctx) {
        return Math.round(24f * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * Creates a notification action.
     * @param ctx Context
     * @param icon icon resource id
     * @param label string resource for notification label
     * @param pi PendingIntent wrapping the Intent to execute
     * @return notification action
     * @throws RuntimeException if {@code ctx} is {@code null}
     */
    @NonNull
    public static Notification.Action makeNotificationAction(@NonNull Context ctx, @DrawableRes int icon, @StringRes int label, @NonNull PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), pi).setContextual(true).setAllowGeneratedReplies(false).build();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), pi).setAllowGeneratedReplies(false).build();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), pi).build();
        }
        //noinspection deprecation
        return new Notification.Action.Builder(icon, ctx.getString(label), pi).build();
    }

    /**
     * Trims a CharSequence to a maximum length.
     * @param cs CharSequence to trim
     * @param maxLength max. length
     * @return original CharSequence or trimmed CharSequence as a String
     */
    @NonNull
    public static CharSequence trim(@Nullable final CharSequence cs, final int maxLength) {
        if (cs == null || maxLength <= 0) return "";
        if (cs.length() <= maxLength) return cs;
        if (TextUtils.getTrimmedLength(cs) <= maxLength) return cs.toString().trim();
        return cs.subSequence(0, maxLength - 1).toString().trim() + "…";
    }

    private UiUtil() {
    }
}

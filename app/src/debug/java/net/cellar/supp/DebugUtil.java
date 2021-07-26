/*
 * DebugUtil.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.cellar.BuildConfig;

import java.lang.reflect.Field;
import java.util.Set;

public final class DebugUtil {

	private static final String TAG = "DebugUtil";

	/** Determines whether this is a test build. */
	public static final boolean TEST;

	static {
		boolean found = false;
		try {
			Class.forName("androidx.test.espresso.Espresso");
			found = true;
		} catch (Throwable ignored) {
		}
		TEST = found;
	}

	/**
	 * Builds indents.
	 * @param level level
	 * @return StringBuilder
	 */
	@NonNull
	public static StringBuilder indent(@IntRange(from = 0) final int level) {
		final StringBuilder sb = new StringBuilder(level);
		for (int i = 0; i < level; i++) sb.append('\t');
		return sb;
	}

	public static void logBundle(final Bundle bundle) {
		if (bundle == null) return;
		final Set<String> keys = bundle.keySet();
		for (String key : keys) {
			Object value = bundle.get(key);
			if (value instanceof CharSequence) Log.i(TAG, key + "=\"" + value + "\" (" + value.getClass().getSimpleName() + ")");
			else Log.i(TAG, key + "=" + value + " (" + (value != null ? value.getClass().getSimpleName() : "<null>") + ")");
		}
	}

	/**
	 * Logs the given Intent. Doesn't do anything on non-debug versions.
	 * @param tag log tag
	 * @param intent Intent
	 * @throws NullPointerException if {@code intent} is {@code null}
	 */
	public static void logIntent(@NonNull String tag, @NonNull Intent intent, @Nullable Uri referrer) {
		if (!BuildConfig.DEBUG) return;
		final StringBuilder sb = new StringBuilder(384).append("\n");
		try {
			final Bundle extras = intent.getExtras();
			sb.append("\nIntent action: \"").append(intent.getAction()).append("\"");
			final Set<String> cats = intent.getCategories();
			if (cats != null && !cats.isEmpty()) {
				for (String cat : cats) {
					sb.append("\nCategory: \"").append(cat).append("\"");
				}
			}
			sb.append("\nIntent data: \"").append(intent.getData()).append("\"");
			if (intent.getType() != null) {
				sb.append("\nIntent type: \"").append(intent.getType()).append("\"");
			}
			if (extras != null) {
				final java.util.Set<String> keys = extras.keySet();
				for (String key : keys) {
					Object value = extras.get(key);
					sb.append("\nIntent extra \"").append(key).append("\" = \"").append(value).append("\" ").append((value != null ? "(" + value.getClass().getName() + ")" : "<null>"));
				}
			} else {
				sb.append("\nNo Intent extras.");
			}
			if (referrer != null) {
				sb.append("\nReferrer: ").append(referrer);
			}
			int flax = intent.getFlags();
			sb.append("\nFlags: 0x").append(Integer.toHexString(flax));
			if (flax != 0) {
				try {
					final Field[] fields = Intent.class.getDeclaredFields();
					for (Field field : fields) {
						if (field.getName().contains("FLAG")) {
							int val = field.getInt(Intent.class);
							if ((flax & val) > 0) {
								// https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_FORWARD_RESULT
								sb.append("\n- ").append(field.getName()).append(" (0x").append(Integer.toHexString(val)).append(")");
							}
						}
					}
				} catch (Exception ignored) {
				}
			}
			Log.i(tag, sb.toString());
		} catch (Exception e) {
			Log.e(TAG, e.toString() + "\n(already built '" + sb + "')", e);
		}
	}

	public static void logIntent(@NonNull String tag, @NonNull Intent intent) {
		logIntent(tag, intent, null);
	}

	private DebugUtil() {
	}
}

/*
 * Replacer.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.Size;

import net.cellar.BuildConfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Replacer {

    /** only the first matching Pattern should be applied */
    @Mode public static final int MODE_ONLY_FIRST = 1;
    /** all given Patterns should be applied, one after the other */
    @Mode public static final int MODE_ALL = 2;

    @Nullable private final Pattern[] patterns;
    @NonNull private final String with;
    @Mode private final int mode;
    private final boolean replaceAll;

    /**
     * Constructor.
     * @param rex regexes to match (either one or all, depending on {@code mode})
     * @param with replacement
     * @param mode one of the modes stating how to deal with the patterns (irrelevant, of course, if only one pattern is given)
     * @param replaceAll {@code true} to replace all occurrences per pattern, {@code false} to replace only the first occurrence per pattern
     */
    public Replacer(@Size(min = 1) @NonNull String[] rex, @NonNull String with, @Mode int mode, boolean replaceAll) {
        super();
        this.with = with;
        this.mode = mode;
        this.replaceAll = replaceAll;
        int n = rex.length;
        this.patterns = new Pattern[n];
        for (int i = 0; i < n; i++) {
            Pattern mightBeAPattern = null;
            try {
                mightBeAPattern = Pattern.compile(rex[i]);
            } catch (PatternSyntaxException pse) {
                if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), pse.toString());
            }
            this.patterns[i] = mightBeAPattern;
        }
    }

    /**
     * Replaces matches of {@link #patterns} in the given input with {@link #with}.
     * @param s input
     * @return input String with matches replaced with {@link #with}
     */
    public String replace(String s) {
        if (s == null) return null;
        if (this.patterns == null || this.patterns.length == 0) return s;
        for (Pattern p : this.patterns) {
            if (p == null) continue;
            Matcher m = p.matcher(s);
            boolean result = m.find();
            s =  this.replaceAll ? m.replaceAll(this.with) : m.replaceFirst(this.with);
            if (result && this.mode == MODE_ONLY_FIRST) break;
        }
        return s;
    }


    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MODE_ONLY_FIRST, MODE_ALL})
    public @interface Mode {}

}

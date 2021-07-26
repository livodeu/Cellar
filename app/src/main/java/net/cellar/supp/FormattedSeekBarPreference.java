/*
 * FormattedSeekBarPreference.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.supp;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

/**
 * A SeekBarPreference that displays its value formatted with a String resource.
 */
public class FormattedSeekBarPreference extends SeekBarPreference {

    private TextView textViewValue;
    @StringRes
    private int res;

    public FormattedSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public FormattedSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public FormattedSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FormattedSeekBarPreference(Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        if (this.res == 0) return;
        this.textViewValue = (TextView) view.findViewById(androidx.preference.R.id.seekbar_value);
        if (this.textViewValue == null) return;
        this.textViewValue.setPadding(24, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.textViewValue.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        }
        this.textViewValue.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        this.textViewValue.setVisibility(View.VISIBLE);
        Context ctx = getContext();
        if (ctx == null) return;
        this.textViewValue.setText(ctx.getString(this.res, getValue()));
    }

    /**
     * Sets the String resource.<br>
     * The resource must contain one "%1$d" placeholder for the value.
     * @param res String resource
     */
    public void setRes(@StringRes int res) {
        this.res = res;
    }

    /**
     * Displays the current value.
     * The String resource must have been set before via {@link #setRes(int)}.
     */
    public void showValue() {
        showValue(getValue());
    }

    /**
     * Displays the given value.
     * The String resource must have been set before via {@link #setRes(int)}.
     * @param value value to show
     */
    public void showValue(int value) {
        if (this.textViewValue == null || this.res == 0) return;
        Context ctx = getContext();
        if (ctx == null) return;
        this.textViewValue.setText(ctx.getString(this.res, value));
    }

}

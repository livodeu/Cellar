/*
 * NewDownloadActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import net.cellar.model.Wish;
import net.cellar.queue.QueueManager;
import net.cellar.supp.CoordinatorLayoutHolder;
import net.cellar.supp.Log;
import net.cellar.supp.SimpleTextWatcher;
import net.cellar.supp.Util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class NewDownloadActivity extends BaseActivity implements CoordinatorLayoutHolder, ServiceConnection {

    private static final String STATE_REFERER = "state.referer";
    private static final String STATE_URI = "state.uri";
    /** the user has entered text that is not a valid uri */
    @Validity private static final int VALIDITY_INVALID = 2;
    /** the user has not typed enough yet to determine the validity */
    @Validity private static final int VALIDITY_MAYBE = 1;
    /** the user has entered text that is a valid uri */
    @Validity private static final int VALIDITY_VALID = 0;
    private Toolbar toolbar;
    private CoordinatorLayout coordinatorLayout;
    private com.google.android.material.textfield.TextInputLayout layoutEditTextUrl;
    private com.google.android.material.textfield.TextInputEditText editTextUrl;
    private SwitchMaterial switchExtended;
    private View extensible;
    private com.google.android.material.textfield.TextInputLayout layoutEditTextReferer;
    private com.google.android.material.textfield.TextInputEditText editTextReferer;
    private CompoundButton switchLater;
    private FloatingActionButton fab;
    private ClipboardListener clipboardListener;
    private ClipSpy clipSpy;
    private boolean helpEnabled;

    private void accept() {
        Editable enteredUrl = this.editTextUrl.getText();
        if (enteredUrl == null) return;
        String url = enteredUrl.toString().trim();
        if (url.length() == 0) return;
        if (!url.contains("://")) {
            if (url.contains(":/")) url = url.replace(":/", "://");
            else url = "https://" + url;
        }
        Editable enteredReferer = this.editTextReferer.getText();
        String referer = enteredReferer != null ? enteredReferer.toString().trim() : "";
        if (referer.length() == 0 || !referer.contains("://")) referer = null;
        boolean now = this.switchLater.isChecked();
        Uri uri = Uri.parse(url);
        final Wish wish = new Wish(uri, uri.getLastPathSegment());
        wish.setMime(Util.getMime(uri));
        wish.setHeld(!now);
        wish.setReferer(referer);
        QueueManager.getInstance().add(wish);
        if (now) QueueManager.getInstance().nextPlease(true);
        clearClipboard();
        setResult(RESULT_OK);
        finish();
    }

    /** {@inheritDoc} */
    @Override
    protected void clipboardChanged() {
        final Uri clip = getUriFromClipboard((ClipboardManager)getSystemService(CLIPBOARD_SERVICE));
        if (clip == null) return;
        final String url = clip.toString();
        this.editTextUrl.setText(url);
        this.editTextUrl.setSelection(url.length(), url.length());
    }

    @NonNull
    @Override
    public CoordinatorLayout getCoordinatorLayout() {
        return this.coordinatorLayout;
    }

    /**
     * Toggles help display.
     */
    private void help() {
        this.helpEnabled = !this.helpEnabled;
        this.layoutEditTextUrl.setHelperTextEnabled(this.helpEnabled);
        this.layoutEditTextReferer.setHelperTextEnabled(this.helpEnabled);
        this.layoutEditTextUrl.setHelperText(this.helpEnabled ? getString(R.string.help_uri) : null);
        this.layoutEditTextReferer.setHelperText(this.helpEnabled ? getString(R.string.help_referer) : null);
        if (this.helpEnabled) {
            TextView textViewRefererHelper = this.layoutEditTextReferer.findViewById(com.google.android.material.R.id.textinput_helper_text);
            textViewRefererHelper.setOnClickListener(v1 -> {
                Intent intentViewWikipedia = new Intent(Intent.ACTION_VIEW);
                intentViewWikipedia.setData(Uri.parse(getString(R.string.help_referer_wp)));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && this.isInMultiWindowMode()) {
                    intentViewWikipedia.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                if (intentViewWikipedia.resolveActivity(getPackageManager()) != null) {
                    startActivity(intentViewWikipedia);
                } else {
                    Snackbar.make(this.coordinatorLayout, R.string.msg_no_applications, Snackbar.LENGTH_LONG).show();
                }
            });
        }

    }

    /**
     * Asks the user for confirmation before finishing.
     */
    private void maybeCancel() {
        final Snackbar sb = Snackbar.make(this.coordinatorLayout, R.string.action_download_ask_cancel, 7_000);
        sb.setAction(R.string.label_yes, v -> finish());
        sb.show();
    }

    /** {@inheritDoc} */
    @Override
    public void onBackPressed() {
        if (valid() == VALIDITY_VALID) {
            maybeCancel();
            return;
        }
        super.onBackPressed();
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setResult(RESULT_CANCELED);

        final AppCompatDelegate delegate = getDelegate();
        delegate.setContentView(R.layout.activity_newdownload);
        this.toolbar = delegate.findViewById(R.id.toolbar);
        delegate.setSupportActionBar(this.toolbar);
        androidx.appcompat.app.ActionBar ab = getSupportActionBar();
        if (ab != null) ab.setDisplayOptions(androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP | androidx.appcompat.app.ActionBar.DISPLAY_SHOW_TITLE);

        this.coordinatorLayout = delegate.findViewById(R.id.coordinator_layout);

        this.layoutEditTextUrl = delegate.findViewById(R.id.layoutEditTextUrl);
        this.editTextUrl = delegate.findViewById(R.id.editTextUrl);
        this.switchExtended = delegate.findViewById(R.id.switchExtended);
        this.extensible = delegate.findViewById(R.id.layoutReferer); // <- modify this if ever another option other than the Referer is added to the dialog
        this.layoutEditTextReferer = delegate.findViewById(R.id.layoutEditTextReferer);
        this.editTextReferer = delegate.findViewById(R.id.editTextReferer);
        this.switchLater = delegate.findViewById(R.id.switchHeld);

        this.fab = delegate.findViewById(R.id.fab);
        assert this.fab != null;
        this.fab.setOnClickListener(view -> accept());
        this.fab.setVisibility(View.GONE);

        this.extensible.setVisibility(this.switchExtended.isChecked() ? View.VISIBLE : View.GONE);
        this.switchExtended.setOnCheckedChangeListener((buttonView, isChecked) -> this.extensible.setVisibility(buttonView.isChecked() ? View.VISIBLE : View.GONE));

        // follow changes to the entered uri
        this.editTextUrl.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable e) {
                final int v = valid();
                NewDownloadActivity.this.fab.setVisibility(v == VALIDITY_VALID ? View.VISIBLE : View.GONE);
                boolean showAsValid = v != VALIDITY_INVALID;
                NewDownloadActivity.this.editTextUrl.setError(showAsValid ? null : getString(R.string.error_invalid_url), null);

                String urlHint = null;
                if (v == VALIDITY_VALID) {
                    // if a valid uri is entered that contains a host, enable automatic setting of the referer value
                    boolean enableMagicWand = false;
                    String s = e.toString().trim();
                    if (s.length() > 0) {
                        Uri uri = Uri.parse(s);
                        String host = uri.getHost();
                        if (host != null && host.trim().length() > 0) {
                            urlHint = getString(R.string.label_from_host, host.trim());
                            enableMagicWand = true;
                        }
                    }
                    NewDownloadActivity.this.layoutEditTextReferer.setEndIconDrawable(enableMagicWand ? R.drawable.ic_baseline_auto_fix_normal_24 : 0);
                } else {
                    NewDownloadActivity.this.layoutEditTextReferer.setEndIconDrawable(null);
                }
                NewDownloadActivity.this.layoutEditTextUrl.setHint(urlHint == null ? getString(R.string.label_uri) : urlHint);
            }
        });

        // when the end icon of the referer widget is clicked, set the download uri's host as referer
        this.layoutEditTextReferer.setEndIconOnClickListener(v12 -> {
            Editable enteredUrl = this.editTextUrl.getText();
            String url = enteredUrl != null ? enteredUrl.toString().trim() : "";
            if (url.length() == 0 || !url.contains("://")) return;
            Uri uri = Uri.parse(url);
            this.editTextReferer.setText(uri.getScheme() + "://" + uri.getHost());
        });

        this.clipboardListener = new ClipboardListener(this);

        // possibly pre-populate the uri widget with clipboard content
        Uri clip = getUriFromClipboard((ClipboardManager)getSystemService(CLIPBOARD_SERVICE));
        if (clip != null) this.editTextUrl.setText(clip.toString());

        // the switch that controls immediate vs. delayed loading
        this.switchLater.setOnCheckedChangeListener((buttonView, isChecked) -> buttonView.setText(isChecked ? getString(R.string.label_load_now) : getString(R.string.label_load_later)));

        if (savedInstanceState != null) {
            this.editTextUrl.setText(savedInstanceState.getCharSequence(STATE_URI));
            this.editTextReferer.setText(savedInstanceState.getCharSequence(STATE_REFERER));
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.newdownload, menu);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            if (valid() == VALIDITY_VALID) {
                maybeCancel();
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
        if (id == R.id.action_help) {
            help();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        clearClipboard();
        if (Build.VERSION.SDK_INT < 30) {
            this.clipboardListener.unregister();
            if (this.clipSpy != null) {
                this.clipSpy.setActive(true);
                try {
                    unbindService(this);
                    this.clipSpy = null;
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) Log.w(getClass().getSimpleName(), e.toString());
                }
            }
        }
        super.onPause();
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < 30
                && PreferenceManager.getDefaultSharedPreferences(this).getBoolean(App.PREF_CLIPSPY, App.PREF_CLIPSPY_DEFAULT) && this.clipSpy == null) {
            bindService(new Intent(this, ClipSpy.class), this, BuildConfig.DEBUG ? BIND_DEBUG_UNBIND : 0);
            this.clipboardListener.register();
        }
        Editable enteredUrl = this.editTextUrl.getText();
        if (enteredUrl == null || enteredUrl.length() == 0) {
            this.editTextUrl.requestFocus();
            super.handler.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(this.editTextUrl, InputMethodManager.SHOW_IMPLICIT), 500L);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (hasFocus) {
                this.clipboardListener.register();
                if (TextUtils.isEmpty(this.editTextUrl.getText())) {
                    // possibly pre-populate the uri widget with clipboard content
                    Uri clip = getUriFromClipboard((ClipboardManager) getSystemService(CLIPBOARD_SERVICE));
                    if (clip != null) this.editTextUrl.setText(clip.toString());
                }
            } else {
                this.clipboardListener.unregister();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putCharSequence(STATE_URI, this.editTextUrl.getText());
        outState.putCharSequence(STATE_REFERER, this.editTextReferer.getText());
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (service instanceof ClipSpy.ClipSpyBinder) {
            this.clipSpy = ((ClipSpy.ClipSpyBinder) service).getClipSpy();
            if (this.clipSpy != null) this.clipSpy.setActive(false);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceDisconnected(ComponentName name) {
        this.clipSpy = null;
    }

    /**
     * Determines whether the entered url might be(come) valid.
     * @return one of the Validity values
     */
    @Validity
    private int valid() {
        Editable enteredUrl = this.editTextUrl.getText();
        final String url = enteredUrl != null ? enteredUrl.toString().trim().toLowerCase(java.util.Locale.US) : "";
        if (url.length() == 0) return VALIDITY_MAYBE;
        for (String prefix : App.SUPPORTED_PREFIXES) {
            if (url.startsWith(prefix) && url.length() > prefix.length()) return VALIDITY_VALID;
        }
        for (String prefix : App.SUPPORTED_PREFIXES) {
            if (prefix.startsWith(url)) return VALIDITY_MAYBE;
        }
        return VALIDITY_INVALID;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({VALIDITY_VALID, VALIDITY_MAYBE, VALIDITY_INVALID})
    @interface Validity {}

}

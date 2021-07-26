/*
 * SettingsActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.transition.platform.MaterialFadeThrough;

import net.cellar.net.ProxyPicker;
import net.cellar.supp.DebugUtil;
import net.cellar.supp.FormattedSeekBarPreference;
import net.cellar.supp.Log;
import net.cellar.supp.SimpleTextWatcher;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;
import net.cellar.worker.Loader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 *
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    /**
     * Removes existing APKs (which remain in their folder after sharing).
     * @param ctx Context
     */
    static void clearSharedApkDirectory(Context ctx) {
        if (ctx == null) return;
        File dest = Util.getFilePath(ctx, App.FilePath.APK, false);
        if (!dest.isDirectory()) return;
        File[] old = dest.listFiles();
        if (old == null || old.length == 0) return;
        Util.deleteFile(old);
    }

    /**
     * Extracts a string or integer value from the resources.
     * @param ctx Context
     * @param name resource name
     * @param packageName package name
     * @return String or null
     */
    @Nullable
    private static String getResourceValue(@NonNull Context ctx, @NonNull final String name, @NonNull final String packageName) {
        int id;
        final Resources res = ctx.getResources();
        try {
            id = res.getIdentifier(name, "string", packageName);
            return res.getString(id);
        } catch (Resources.NotFoundException ignored) {
        }
        try {
            id = res.getIdentifier(name, "integer", packageName);
            return String.valueOf(res.getInteger(id));
        } catch (Resources.NotFoundException ignored) {
        }
        return null;
    }

    /**
     * Displays a help dialog.
     * @param activity Activity
     * @param rawRes raw resource id of the html file
     * @param webView WebView to use
     * @return AlertDialog
     */
    @NonNull
    private static AlertDialog showHelp(@NonNull final Activity activity, @RawRes int rawRes, @NonNull final WebView webView) {
        byte[] b = new byte[2048];
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        InputStream in = null;
        try {
            in = activity.getResources().openRawResource(rawRes);
            for (;;) {
                int read = in.read(b);
                if (read < 0) break;
                //noinspection ObjectAllocationInLoop
                sb.append(new String(b, 0, read, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        } finally {
            Util.close(in);
        }
        final String packageName = activity.getPackageName();
        final int bodyStart = TextUtils.indexOf(sb, "<body");
        final int bodyEnd = TextUtils.indexOf(sb, "</body>");
        for (int i = Math.max(0, bodyStart); i < bodyEnd;) {
            int b0 = TextUtils.indexOf(sb, '{', i);
            if (b0 < 0) break;
            int b1 = TextUtils.indexOf(sb, '}', b0 + 1);
            if (b1 < 0) break;
            CharSequence toReplace = sb.subSequence(b0 + 1, b1);
            String replacement = getResourceValue(activity, toReplace.toString(), packageName);
            if (replacement != null) {
                sb.replace(b0, b1 + 1, replacement);
                i = b0 + replacement.length();
            } else {
                i = b1 + 1;
            }
        }
        int currentNightMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        webView.setBackgroundColor(Configuration.UI_MODE_NIGHT_YES == currentNightMode ? Color.BLACK : Color.WHITE);
        webView.loadDataWithBaseURL("about:blank", sb.toString(), "text/html", "UTF-8", null);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.AppTheme_AlertDialog)
                .setIcon(R.drawable.ic_baseline_help_outline_24)
                .setView(webView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                ;
        AlertDialog ad = builder.create();
        ad.setCanceledOnTouchOutside(true);
        ad.setOnCancelListener(dialog -> {
            ViewParent p = webView.getParent();
            if (p instanceof ViewGroup) {
                ((ViewGroup)p).removeView(webView);
            }
        });
        ad.setOnDismissListener(dialog -> {
            ViewParent p = webView.getParent();
            if (p instanceof ViewGroup) {
                ((ViewGroup)p).removeView(webView);
            }
        });
        ad.show();
        return ad;
    }

    @VisibleForTesting
    public SettingsFragment settingsFragment;
    private CoordinatorLayout coordinatorLayout;
    private ImageButton helpButton;
    private WebView webViewForHelp;
    private AlertDialog helpDialog;

    /** {@inheritDoc} */
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (BuildConfig.DEBUG) DebugUtil.logIntent(TAG, getIntent());
        BaseActivity.setDarkMode(this);
        MaterialFadeThrough mf = new MaterialFadeThrough();
        BaseActivity.setAnimations(this, mf, mf);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        this.coordinatorLayout = getDelegate().findViewById(R.id.coordinator_layout);
        this.helpButton = getDelegate().findViewById(R.id.btn_hlp);
        //noinspection ConstantConditions
        this.helpButton.setOnClickListener(v -> helpDialog = showHelp(this, R.raw.help, this.webViewForHelp));

        this.webViewForHelp = new WebView(this);
        WebSettings ws = this.webViewForHelp.getSettings();
        ws.setBlockNetworkLoads(true);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setJavaScriptEnabled(true);
        this.webViewForHelp.setNetworkAvailable(false);
        this.webViewForHelp.setWebChromeClient(new NonLoggingWebChromeClient());
        this.webViewForHelp.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView wv, String url) {
                int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                String cmd = "document.body.className = '" + (Configuration.UI_MODE_NIGHT_YES == currentNightMode ? "night" : "day") + "';";
                wv.evaluateJavascript(cmd, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                }
                startActivity(intent);
                return true;
            }
        });

        this.settingsFragment = new SettingsFragment();
        try {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, this.settingsFragment)
                    .commitAllowingStateLoss();
        } catch (IllegalStateException e) {
                /*
                Once there was:
                java.lang.IllegalStateException: FragmentManager has been destroyed
                    at androidx.fragment.app.FragmentManager.enqueueAction(FragmentManager.java:1725)
                    at androidx.fragment.app.BackStackRecord.commitInternal(BackStackRecord.java:321)
                    at androidx.fragment.app.BackStackRecord.commit(BackStackRecord.java:286)
                    at org.turdus.SettingsActivity.launch(SettingsActivity.java:69)     <- .beginTransaction()
                 */
            if (BuildConfig.DEBUG) Log.e(TAG,"While launching: " +  e.toString(), e);
            // this should be caught in the App's UncaughtExceptionHandler…
            throw e;
        }
    }

    @Override
    protected void onPause() {
        if (helpDialog != null && helpDialog.isShowing()) {
            helpDialog.dismiss();
        }
        super.onPause();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        private final Handler handler = new Handler();
        private AlertDialog dialogBackup, dialogLicenses;
        /** if set to true, the backup dialog will be (re-)opened */
        private boolean reopenExportDialog;

        /** {@inheritDoc} */
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();

            PreferenceCategory netw = findPreference("netw");
            ListPreference prefProxyType = findPreference(App.PREF_PROXY_TYPE);
            EditTextPreference prefProxyServer = findPreference(App.PREF_PROXY_SERVER);
            EditTextPreference prefProxyRestrict = findPreference(App.PREF_PROXY_RESTRICT);

            Preference prefBlacklist = findPreference(App.PREF_BLACKLIST);

            SwitchPreferenceCompat prefNight = findPreference(App.PREF_NIGHT);
            FormattedSeekBarPreference prefNightFrom = findPreference(App.PREF_NIGHT_FROM);
            FormattedSeekBarPreference prefNightTo = findPreference(App.PREF_NIGHT_TO);

            Preference prefBackup = findPreference("pref_backup_zip");
            Preference prefRestore = findPreference("pref_restore_zip");

            Preference prefLicenses = findPreference("pref_licenses");

            Preference prefShareThis = findPreference("pref_share_this");

            if (prefShareThis != null) {
                prefShareThis.setOnPreferenceClickListener(preference -> {
                    Activity a = getActivity();
                    if (a == null) return false;
                    PackageManager pm = a.getPackageManager();
                    boolean ok = false;
                    File dest = null;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0);
                        String dir = ai.publicSourceDir;
                        File apkFile = new File(dir);
                        if (!apkFile.isFile()) {
                            String err = a.getString(R.string.error_not_found, apkFile.getName());
                            if (BuildConfig.DEBUG) Log.e(TAG, err);
                            if (a instanceof SettingsActivity) Snackbar.make(((SettingsActivity)a).coordinatorLayout, err, Snackbar.LENGTH_SHORT).show();
                            return false;
                        }
                        String abi = Util.getAbi(a);
                        File apkdir = Util.getFilePath(a, App.FilePath.APK, true);
                        dest = new File(apkdir, a.getString(R.string.app_name) + "-" + BuildConfig.VERSION_NAME + (abi != null ? ("-" + abi) : "") + ".apk");
                        Util.copy(new FileInputStream(apkFile), new FileOutputStream(dest), 65536);
                        ok = true;
                        Util.send(a, dest, BuildConfig.FILEPROVIDER_AUTH, "application/zip");
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                    } finally {
                        if (!ok && dest != null && dest.isFile()) Util.deleteFile(dest);
                    }
                    return true;
                });
            }

            if (prefLicenses != null) {
                prefLicenses.setOnPreferenceClickListener(preference -> {
                    Context ctx = getActivity();
                    if (ctx == null) return false;
                    try {
                        WebView webView = new WebView(ctx);
                        webView.setVerticalScrollBarEnabled(false);
                        webView.setInitialScale(0);
                        WebSettings ws = webView.getSettings();
                        ws.setBlockNetworkLoads(true);
                        ws.setLoadWithOverviewMode(true);
                        ws.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
                        ws.setBuiltInZoomControls(false);
                        webView.loadUrl("file:///android_res/raw/licenses.htm");
                        webView.setWebChromeClient(new WebChromeClient() {
                            @Override
                            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                                if (BuildConfig.DEBUG) {
                                    switch (consoleMessage.messageLevel()) {
                                        case ERROR:
                                        case WARNING: Log.w(TAG, consoleMessage.message()); break;
                                        default: Log.i(TAG, consoleMessage.message());
                                    }
                                }
                                return true;
                            }
                        });
                        webView.setScaleX(0f);
                        @SuppressLint("Recycle")
                        Animator ax = ObjectAnimator.ofFloat(webView, "scaleX", 0f, 1f);
                        ax.setDuration(500L);
                        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
                        builder
                                .setTitle(R.string.pref_licenses)
                                .setView(webView)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                        ;
                        this.dialogLicenses = builder.create();
                        Window dialogWindow = this.dialogLicenses.getWindow();
                        if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
                        this.dialogLicenses.show();
                        ax.start();
                     } catch (Exception e) {
                        if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
                    }
                    return true;
                });
            }

            if (prefBlacklist != null) {
                prefBlacklist.setOnPreferenceChangeListener((preference, newValue) -> {
                    Context ctx = getContext();
                    if (ctx == null) return true;
                    // set the newly selected url as preliminary summary
                    if (newValue instanceof String && ((String)newValue).length() > 0) {
                        preference.setSummary(newValue.toString());
                    } else {
                        preference.setSummary(ctx.getResources().getStringArray(R.array.entries_list_evil)[0]);
                    }
                    // update the blacklist
                    App app = (App)ctx.getApplicationContext();
                    this.handler.postDelayed(() -> Loader.updateEvilBlocker(getActivity(), app.getEvilBlocker(), (ok, count) -> {
                        Activity a = getActivity();
                        if (a == null) return;
                        String current = PreferenceManager.getDefaultSharedPreferences(a).getString(App.PREF_BLACKLIST, App.PREF_BLACKLIST_DEFAULT);
                        a.runOnUiThread(() -> {
                            if (TextUtils.isEmpty(current)) {
                                preference.setSummary(a.getResources().getStringArray(R.array.entries_list_evil)[0]);
                            } else {
                                long lastModified = ((App)a.getApplicationContext()).getEvilBlocker().lastModified();
                                preference.setSummary(getString(R.string.label_blacklist_age, current, lastModified > 0L ? UiUtil.formatDate(lastModified, DateFormat.SHORT) : "?"));
                            }
                        });
                    }), 500L);
                    return true;
                });
                String current = prefs.getString(App.PREF_BLACKLIST, App.PREF_BLACKLIST_DEFAULT);
                Activity a = getActivity();
                if (a != null) {
                    if (TextUtils.isEmpty(current)) {
                        prefBlacklist.setSummary(a.getResources().getStringArray(R.array.entries_list_evil)[0]);
                    } else {
                        long lastModified = ((App)a.getApplicationContext()).getEvilBlocker().lastModified();
                        prefBlacklist.setSummary(getString(R.string.label_blacklist_age, current, lastModified > 0 ? UiUtil.formatDate(lastModified, DateFormat.SHORT) : "?"));
                    }
                }
            }

            if (prefRestore != null) {
                prefRestore.setOnPreferenceClickListener(preference -> {
                    final Activity a = getActivity();
                    if (a == null) return true;
                    Intent intentImport = new Intent(a, ImportArchiveActivity.class);
                    intentImport.setAction(ImportArchiveActivity.ACTION_PICK_ARCHIVE);
                   // intentImport.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intentImport);
                    return true;
                });
            }

            if (prefBackup != null) {
                Context ctx = getContext();
                if (ctx != null) {
                    File dir = App.getDownloadsDir(ctx);
                    File[] files = dir.isDirectory() ? dir.listFiles() : null;
                    prefBackup.setVisible(files != null && files.length > 0);
                }
                prefBackup.setOnPreferenceClickListener(preference -> {
                    final Activity a = getActivity();
                    if (a == null) return true;
                    final File dir = Util.getFilePath(a, App.FilePath.BACKUPS, true);
                    final View v = getLayoutInflater().inflate(R.layout.export_zip, null);
                    final EditText editTextPwd = v.findViewById(R.id.editTextPassword);
                    final SwitchMaterial switchAes = v.findViewById(R.id.switchAes);
                    editTextPwd.addTextChangedListener(new SimpleTextWatcher() {
                        @Override
                        public void afterTextChanged(Editable s) {
                            final int l = s.length();
                            switchAes.setEnabled(l > 0);
                            dialogBackup.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(l > 0 ? View.VISIBLE : View.INVISIBLE);
                        }
                    });
                    AlertDialog.Builder builder = new AlertDialog.Builder(a)
                            .setTitle(R.string.action_export_zip)
                            .setView(v)
                            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
                            .setNeutralButton(getString(R.string.label_use_aes_whats_that) + ("en".equals(Locale.getDefault().getLanguage()) ? "" : " ₍ₑₙ₎"), (dialog, which) -> {
                                Intent help = new Intent(Intent.ACTION_VIEW);
                                help.setDataAndType(Uri.parse(getString(R.string.url_zip_encryption)), "text/plain");
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && a.isInMultiWindowMode()) {
                                    help.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
                                }
                                help.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                                // reopen this dialog when the user returns
                                this.reopenExportDialog = true;
                                startActivity(help);
                            })
                            .setPositiveButton(R.string.label_create, (dialog, which) -> {
                                dialog.dismiss();
                                App app = (App)a.getApplicationContext();
                                if (app.hasActiveLoaders()) {
                                    if (a instanceof SettingsActivity) Snackbar.make(((SettingsActivity)a).coordinatorLayout, R.string.msg_backup_notpossible_loading, Snackbar.LENGTH_SHORT).show();
                                    return;
                                }
                                File dest = new File(dir, "export" + BackupService.FILE_EXTENSION);
                                Editable cpwd = editTextPwd.getText();
                                char[] pwd;
                                if (cpwd != null) {
                                    int l = cpwd.length();
                                    pwd = new char[l];
                                    cpwd.getChars(0, l, pwd, 0);
                                } else {
                                    pwd = null;
                                }
                                Intent intent = new Intent(a, BackupService.class);
                                intent.setAction(BackupService.ACTION_ZIP);
                                intent.putExtra(BackupService.EXTRA_DEST, dest.getAbsolutePath());
                                if (pwd != null && pwd.length > 0) {
                                    intent.putExtra(BackupService.EXTRA_PWD, pwd);
                                    intent.putExtra(BackupService.EXTRA_AES, switchAes.isChecked());
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    a.startForegroundService(intent);
                                } else {
                                    a.startService(intent);
                                }
                                if (pwd != null) Arrays.fill(pwd, (char)0);

                            })
                            ;
                    this.dialogBackup = builder.create();
                    Window dialogWindow = this.dialogBackup.getWindow();
                    if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
                    this.dialogBackup.show();
                    Button aesButt = this.dialogBackup.getButton(DialogInterface.BUTTON_NEUTRAL);
                    aesButt.setVisibility(View.INVISIBLE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        aesButt.setOnLongClickListener(null);
                        aesButt.setTooltipText(getString(R.string.label_use_aes_whats_that_ttip));
                    }
                    return true;
                });
            }

            // **********************************
            Context ctx = getContext();
            App app = ctx != null ? (App)ctx.getApplicationContext() : null;
            final boolean loading = app != null && app.hasActiveLoaders();

            if (netw != null) {
                netw.setEnabled(!loading);
                netw.setSummary(loading ? getString(R.string.pref_cat_network_disabled) : null);
            }

            if (prefProxyType != null && prefProxyServer != null) {
                prefProxyType.setVisible(!loading);
                prefProxyServer.setVisible(!loading);
                prefProxyType.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!(newValue instanceof String)) return false;
                    boolean enabled = Proxy.Type.HTTP.toString().equals(newValue) || Proxy.Type.SOCKS.toString().equals(newValue);
                    prefProxyServer.setVisible(enabled);
                    if (prefProxyRestrict != null) prefProxyRestrict.setVisible(enabled);
                    preference.setSummary(Proxy.Type.DIRECT.toString().equals(newValue) ? getString(R.string.pref_proxy_type_direct) : newValue.toString());
                    return true;
                });
                prefProxyServer.setOnPreferenceChangeListener((preference, newValue) -> {
                    preference.setSummary(newValue != null ? newValue.toString().trim() : "N/A");
                    return true;
                });
                prefProxyServer.getOnPreferenceChangeListener().onPreferenceChange(prefProxyServer, prefs.getString(App.PREF_PROXY_SERVER, null));
                prefProxyType.getOnPreferenceChangeListener().onPreferenceChange(prefProxyType, prefs.getString(App.PREF_PROXY_TYPE, Proxy.Type.DIRECT.toString()));
            }

            if (prefProxyRestrict != null) {
                prefProxyRestrict.setOnBindEditTextListener(editText -> {
                    editText.setHint(R.string.pref_proxy_restrict_hint);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        editText.setTooltipText(getString(R.string.pref_proxy_restrict_hint));
                    }
                });
                prefProxyRestrict.setOnPreferenceChangeListener((preference, newValue) -> {
                    Set<String> hosts = ProxyPicker.getAffectedHosts(prefs);
                    if (hosts == null) prefProxyRestrict.setSummary(getString(R.string.pref_proxy_restrict_to, 0));
                    else prefProxyRestrict.setSummary(hosts.size() == 1 ? hosts.iterator().next() : getString(R.string.pref_proxy_restrict_to, hosts.size()));
                    return true;
                });
                prefProxyRestrict.getOnPreferenceChangeListener().onPreferenceChange(prefProxyType, prefs.getString(App.PREF_PROXY_RESTRICT, null));
            }

            if (prefNight != null) {
                prefNight.setOnPreferenceChangeListener((preference, newValue) -> {
                    final Activity a = getActivity();
                    if (!(a instanceof SettingsActivity)) return true;
                    this.handler.postDelayed(() -> BaseActivity.setDarkMode((SettingsActivity)a), 200L);
                    if (Boolean.TRUE.equals(newValue) && ((PowerManager)a.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode()) {
                        Snackbar.make(((SettingsActivity)a).coordinatorLayout, R.string.msg_nightmode_not_during_battery_saver, Snackbar.LENGTH_LONG).show();
                    }
                    return true;
                });
            }

            if (prefNightFrom != null) {
                prefNightFrom.setRes(R.string.pref_night_value);
                prefNightFrom.showValue();
                prefNightFrom.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!(newValue instanceof Integer)) return false;
                    int value = (Integer)newValue;
                    if (prefNightTo != null && value == prefNightTo.getValue()) return false;
                    ((FormattedSeekBarPreference)preference).showValue(value);
                    this.handler.postDelayed(() -> BaseActivity.setDarkMode((SettingsActivity)getActivity()), 200L);
                    return true;
                });
            }

            if (prefNightTo != null) {
                prefNightTo.setRes(R.string.pref_night_value);
                prefNightTo.showValue();
                prefNightTo.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (!(newValue instanceof Integer)) return false;
                    int value = (Integer)newValue;
                    if (prefNightFrom != null && value == prefNightFrom.getValue()) return false;
                    ((FormattedSeekBarPreference)preference).showValue(value);
                    this.handler.postDelayed(() -> BaseActivity.setDarkMode((SettingsActivity)getActivity()), 200L);
                    return true;
                });
            }
        }

        @Override
        public void onPause() {
            if (this.dialogLicenses != null && this.dialogLicenses.isShowing()) {
                this.dialogLicenses.dismiss();
                this.dialogLicenses = null;
            }
            super.onPause();
        }

        /** {@inheritDoc} */
        @Override
        public void onResume() {
            super.onResume();
            Context ctx = getContext();
            if (ctx == null) return;
            Preference prefBackup = findPreference("pref_backup_zip");
            if (prefBackup != null) {
                boolean loading = ((App)ctx.getApplicationContext()).hasActiveLoaders();
                prefBackup.setEnabled(!loading);
                prefBackup.setSummary(loading ? getString(R.string.msg_backup_notpossible_loading) : null);
                if (this.reopenExportDialog) {
                    this.reopenExportDialog = false;
                    if (!loading && prefBackup.getOnPreferenceClickListener() != null) prefBackup.getOnPreferenceClickListener().onPreferenceClick(prefBackup);
                }
            }
        }
    }

    /**
     * WebChromeClient that suppresses console messages (in release versions).
     */
    private static class NonLoggingWebChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            if (BuildConfig.DEBUG) {
                String src = consoleMessage.sourceId();
                Log.i(TAG, (!TextUtils.isEmpty(src) ? src + ": " : "") + consoleMessage.message());
            }
            return true;
        }

    }

}
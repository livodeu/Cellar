/*
 * NetworkChangedReceiver.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.net;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.ProxyInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.R;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Registers a {@link NetworkCallback} with the {@link ConnectivityManager}.<br>
 * Note: getSystemService(CONNECTIVITY_SERVICE) should be called from the application context
 * (see <a href="https://github.com/square/leakcanary/blob/main/plumber-android/src/main/java/leakcanary/AndroidLeakFixes.kt">leakcanary</a>)
 */
public final class NetworkChangedReceiver extends BroadcastReceiver implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static NetworkChangedReceiver instance;

    /**
     * Returns the singleton instance.
     *
     * @return NetworkChangedReceiver
     */
    @NonNull
    public static NetworkChangedReceiver getInstance() {
        if (instance == null) {
            instance = new NetworkChangedReceiver();
        }
        return instance;
    }

    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    private static String networkToString(@NonNull Context ctx, @Nullable Network network) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getNetworkInfo(network);
        return ni != null ? ni.toString() : "<null>";
    }

    /**
     * Displays an informational dialog giving an overview about the current network connection(s).
     *
     * @param a Activity
     */
    public static void showInfo(@NonNull Activity a) {
        final ConnectivityManager cm = (ConnectivityManager) a.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        final WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        final Network[] ns = cm.getAllNetworks();
        final StringBuilder sb = new StringBuilder(512).append("<html><head></head><body>");
        final int count = ns.length;
        int label = 1;
        Network active = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            active = cm.getActiveNetwork();
        }
        for (Network n : ns) {
            NetworkInfo ni = cm.getNetworkInfo(n);
            if (ni == null || !ni.isConnectedOrConnecting()) continue;
            NetworkCapabilities networkCapabilities = cm.getNetworkCapabilities(n);
            if (networkCapabilities == null) continue;
            boolean notRestricted = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            if (!notRestricted) continue;

            sb.append("<p>");
            if (n.equals(active)) {
                //                             apparently, Html.fromHtml() does not like ff alpha, so we have to remove that vvvvvvvvvvvvv
                sb.append("<p style=\"color:#").append(Integer.toHexString(a.getResources().getColor(R.color.colorPrimary) & ~0xff000000)).append("\">");
            }
            LinkProperties lp = cm.getLinkProperties(n);

            // Type
            String stn = ni.getSubtypeName();
            if (count > 1) sb.append(label++).append(": ");
            sb.append(ni.getTypeName());
            if (stn != null && stn.length() > 0) sb.append(" (").append(stn).append(")");
            //sb.append(" id: ").append(n.hashCode() / 11);
            if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiInfo ci = wm.getConnectionInfo();
                if (ci != null) {
                    int speed = ci.getLinkSpeed();
                    if (speed > 0) sb.append(", ").append(speed).append(' ').append(WifiInfo.LINK_SPEED_UNITS);
                    int freq = ci.getFrequency();
                    if (freq != -1) sb.append(", ").append(NumberFormat.getIntegerInstance().format(freq)).append(' ').append(WifiInfo.FREQUENCY_UNITS);
                    int dbm = ci.getRssi();
                    if (dbm != -127) sb.append(", \uD83D\uDCF6 ").append(WifiManager.calculateSignalLevel(dbm, 100)).append(" %");
                }
            }

            // addresses
            List<LinkAddress> las = lp != null ? lp.getLinkAddresses() : null;
            if (las != null && las.size() > 0) {
                sb.append("<br>").append(a.getString(R.string.label_network_address)).append(": ");
                final int nlas = las.size();
                for (int i = 0; i < nlas; i++) {
                    LinkAddress la = las.get(i);
                    if (la == null) continue;
                    sb.append(la);
                    if (i < nlas - 1) sb.append(' ').append(a.getString(R.string.label_or)).append(' ');
                }
            }

            // Domain
            String domains = lp != null ? lp.getDomains() : null;
            if (domains != null && domains.length() > 0) {
                sb.append("<br>Domain: ").append(a.getString(R.string.label_quoted, domains));
            }

            // DNS
            List<InetAddress> dnss = lp != null ? lp.getDnsServers() : null;
            if (dnss != null && dnss.size() > 0) {
                String dns0 = dnss.get(0).toString();
                if (dns0.startsWith("/")) dns0 = dns0.substring(1);
                sb.append("<br>DNS: ").append(dns0);
            }

            // Proxy
            ProxyInfo proxyInfo = lp != null ? lp.getHttpProxy() : null;
            if (proxyInfo != null) {
                sb.append("<br>Proxy: ").append(proxyInfo.getHost()).append(':').append(proxyInfo.getPort());
            }

            // https://piunikaweb.com/2018/05/22/android-p-to-fix-wifivpn-issue-introduced-by-oreo-8-1/
            boolean notMetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            sb.append("<br>").append(a.getString(R.string.label_network_metered)).append(": ").append(notMetered ? a.getString(R.string.label_no) : a.getString(R.string.label_yes));
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                boolean notRoaming = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
                sb.append("<br>").append("Roaming").append(": ").append(notRoaming ? a.getString(R.string.label_no) : a.getString(R.string.label_yes));
            }

            if (n.equals(active)) sb.append("</p>");
            sb.append("</p>\n");
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            // https://developer.android.com/training/basics/network-ops/data-saver#java
            sb.append("<p>").append(a.getString(R.string.label_network_metered_restrictions)).append(": ");
            int rbs = cm.getRestrictBackgroundStatus();
            switch (rbs) {
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED:
                    // Device is restricting metered network activity while application is running on background.
                    sb.append(a.getString(R.string.label_yes));
                    break;
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED:
                    // Device is not restricting metered network activity while application is running on background.
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED:
                    // Device is restricting metered network activity while application is running on background, but application is allowed to bypass it.
                    sb.append(a.getString(R.string.label_no));
                    break;
            }
            sb.append("</p>\n");
        }
        sb.append("</body></html>");
        @SuppressLint("InflateParams")
        View v = LayoutInflater.from(a).inflate(R.layout.info, null);
        TextView mtw = v.findViewById(R.id.textViewInfo);
        mtw.setText(Html.fromHtml(sb.toString()));
        Intent wirelessSettings = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        ResolveInfo riWirelessSettings = a.getPackageManager().resolveActivity(wirelessSettings, 0);
        CharSequence settingsLabel = riWirelessSettings != null ? a.getPackageManager().getApplicationLabel(riWirelessSettings.activityInfo.applicationInfo) : null;
        AlertDialog.Builder builder = new AlertDialog.Builder(a)
                .setIcon(R.drawable.ic_baseline_info_24)
                .setTitle(R.string.action_network_info)
                .setView(v)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
        if (riWirelessSettings != null) {
            builder.setNeutralButton(settingsLabel, (dialog, which) -> a.startActivity(wirelessSettings));
        }
        AlertDialog ad = builder.create();
        Window dialogWindow = ad.getWindow();
        if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
        ad.show();
    }
    private final Set<Reference<ConnectivityChangedListener>> listeners = new HashSet<>(2);
    private final Handler handler;
    private App app;
    private NetworkCallback networkCallback;
    @NonNull
    private NetworkInfo.State state = NetworkInfo.State.UNKNOWN;
    private boolean prefsChangeListenerAdded;
    private boolean networkCallbackRegistered = false;

    /**
     * Constructor.
     */
    @MainThread
    private NetworkChangedReceiver() {
        super();
        this.handler = new Handler();
    }

    /**
     * Adds a {@link ConnectivityChangedListener}.<br>
     * The listener's {@link ConnectivityChangedListener#onConnectivityChanged(NetworkInfo.State, NetworkInfo.State) onConnectivityChanged()} method will be called immediately.
     * (the old state will always be {@link android.net.NetworkInfo.State#UNKNOWN UNKNOWN} in that first call)
     *
     * @param listener ConnectivityChangedListener
     */
    public void addListener(@Nullable final ConnectivityChangedListener listener) {
        if (listener == null) return;
        if (listener instanceof Activity && ((Activity)listener).isDestroyed()) {
            if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), "Tried to add a destroyed Activity as ConnectivityChangedListener!");
            return;
        }
        synchronized (this.listeners) {
            for (Reference<ConnectivityChangedListener> refListener : this.listeners) {
                ConnectivityChangedListener l = refListener.get();
                if (l == null) continue;
                if (l == listener) {
                    if (BuildConfig.DEBUG) Log.w(getClass().getSimpleName(), "Tried to add " + listener + " as ConnectivityChangedListener more than once!");
                    return;
                }
            }
            this.listeners.add(new WeakReference<>(listener));
        }
        listener.onConnectivityChanged(NetworkInfo.State.UNKNOWN, this.state);
    }

    @NonNull
    public NetworkInfo.State getState() {
        // if we were not able to register a callback for connectivity changes, then always return CONNECTED
        if (!this.networkCallbackRegistered) {
            return NetworkInfo.State.CONNECTED;
        }
        //
        return this.state;
    }

    @RequiresApi(21)
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    @UiThread
    public void init(@NonNull final App app) {
        this.app = app;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (!this.prefsChangeListenerAdded) {
            prefs.registerOnSharedPreferenceChangeListener(this);
            this.prefsChangeListenerAdded = true;
        }
        final boolean allowMetered = prefs.getBoolean(App.PREF_ALLOW_METERED, App.PREF_ALLOW_METERED_DEFAULT);
        @App.ViaVpn final int vpn = Util.parseInt(prefs.getString(App.PREF_VIA_VPN, String.valueOf(App.PREF_VIA_VPN_DEFAULT)), App.PREF_VIA_VPN_DEFAULT);

        // get current situation
        updateSituation(allowMetered, vpn);

        this.networkCallback = new NetworkCallback();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            // the callback that gets called when the *active* network changes, is available only from API 26 on
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cm.registerDefaultNetworkCallback(this.networkCallback);
                this.networkCallbackRegistered = true;
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.e(NetworkChangedReceiver.class.getSimpleName(), e.toString());
        }
    }

    private synchronized void notifyListeners(@NonNull final NetworkInfo.State oldState) {
        if (oldState == this.state) {
            return;
        }
        this.handler.post(() -> {
            synchronized (this.listeners) {
                for (Reference<ConnectivityChangedListener> l : this.listeners) {
                    ConnectivityChangedListener listener = l.get();
                    if (listener == null) continue;
                    listener.onConnectivityChanged(oldState, this.state);
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED.equals(intent.getAction())) {
            ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            int restrict = cm.getRestrictBackgroundStatus();
            if (restrict == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "Data saver mode has been enabled");
                if (cm.isActiveNetworkMetered()) {
                    NetworkInfo.State oldState = this.state;
                    this.state = NetworkInfo.State.DISCONNECTED;
                    notifyListeners(oldState);
                }
            } else if (restrict == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED) {
                if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "Data saver mode has been disabled");
            } else if (restrict == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED) {
                if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "Cellar has been whitelisted for data saver mode");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (App.PREF_ALLOW_METERED.equals(key) || App.PREF_VIA_VPN.equals(key)) {
            updateSituation(prefs);
        }
    }

    /**
     * Removes a {@link ConnectivityChangedListener}.
     *
     * @param listener ConnectivityChangedListener
     */
    public void removeListener(@Nullable final ConnectivityChangedListener listener) {
        if (listener == null) return;
        Reference<ConnectivityChangedListener> toRemove = null;
        synchronized (this.listeners) {
            for (Reference<ConnectivityChangedListener> l : this.listeners) {
                if (l.get() == listener) {
                    toRemove = l;
                    break;
                }
            }
            if (toRemove != null) this.listeners.remove(toRemove);
            else if (BuildConfig.DEBUG) Log.e(getClass().getSimpleName(), "Did not remove " + listener);
        }
    }

    private void updateSituation(@Nullable SharedPreferences prefs) {
        if (prefs == null) prefs = PreferenceManager.getDefaultSharedPreferences(this.app);
        final boolean allowMetered = prefs.getBoolean(App.PREF_ALLOW_METERED, App.PREF_ALLOW_METERED_DEFAULT);
        @App.ViaVpn final int vpn = Util.parseInt(prefs.getString(App.PREF_VIA_VPN, String.valueOf(App.PREF_VIA_VPN_DEFAULT)), App.PREF_VIA_VPN_DEFAULT);
        updateSituation(allowMetered, vpn);
    }

    /**
     * Updates {@link #state the current network state}.<br>
     * <b>
     * The {@link ConnectivityManager#isActiveNetworkMetered()} method and<br>
     * the {@link NetworkCapabilities#hasCapability(int) NetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)} method<br>
     * return different values!
     * </b>
     * <br>
     * Therefore ConnectivityManager.isActiveNetworkMetered() is not used!<br>
     * Allegedly, that method calls NetworkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) internally
     * (<a href="https://cs.android.com/android/platform/superproject/+/c924e929cd3e62dc938fdc2a6fcd41cdeec1d5f6:frameworks/base/services/core/java/com/android/server/ConnectivityService.java;l=1796">see here</a>)
     * @param allowMetered true if metered connections are allowed
     * @param viaVpn indicating whether to use VPNs
     */
    private void updateSituation(boolean allowMetered, @App.ViaVpn int viaVpn) {
        ConnectivityManager cm = (ConnectivityManager) this.app.getSystemService(Context.CONNECTIVITY_SERVICE);
        // get current situation
        Network active = null;
        NetworkInfo nia = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            active = cm.getActiveNetwork();
        } else {
            nia = cm.getActiveNetworkInfo();
            if (nia != null) {
                Network[] networks = cm.getAllNetworks();
                for (Network network : networks) {
                    NetworkInfo ni = cm.getNetworkInfo(network);
                    if (ni == null) continue;
                    //TODO test this
                    if (ni.toString().equals(nia.toString())) active = network;
                }
            }
        }

        if (active != null) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(active);
            boolean isNotVpn = nc == null || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
            boolean isVpn = !isNotVpn;
            boolean isNotMetered = nc == null || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            boolean isMetered = !isNotMetered;
            if (isMetered && !allowMetered) {
                this.state = NetworkInfo.State.DISCONNECTED;
            } else {
                if (isVpn) {
                    // if the only network is a VPN, this should be considered as DISCONNECTED because the VPN needs a "real" network (e.g. wifi) as a base
                    if (cm.getAllNetworks().length == 1) this.state = NetworkInfo.State.DISCONNECTED;
                    // did the user choose to never allow VPNs?
                    else if (viaVpn == App.VIA_VPN_NEVER) this.state = NetworkInfo.State.DISCONNECTED;
                    else this.state = NetworkInfo.State.CONNECTED;
                } else {
                    // did the user choose to always use a VPN?
                    if (viaVpn == App.VIA_VPN_ALWAYS) this.state = NetworkInfo.State.DISCONNECTED;
                    else this.state = NetworkInfo.State.CONNECTED;
                }
            }
        } else if (nia != null) {
            this.state = nia.getState();
        } else {
            this.state = NetworkInfo.State.DISCONNECTED;
        }
    }

    /**
     *
     */
    public interface ConnectivityChangedListener {
        /**
         * The network connectivity has changed.
         *
         * @param old   the previous state
         * @param state the current state
         */
        @MainThread
        void onConnectivityChanged(@NonNull NetworkInfo.State old, @NonNull NetworkInfo.State state);
    }

    /**
     * Implementation of {@link ConnectivityManager.NetworkCallback}.
     */
    public class NetworkCallback extends ConnectivityManager.NetworkCallback {

        private Network available = null;

        @UiThread
        private NetworkCallback() {
            super();
        }

        /** {@inheritDoc} */
        @Override
        public void onAvailable(@NonNull Network network) {
            Network active;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                active = ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();
            } else {
                active = this.available;
            }
            if (!Objects.equals(active, network)) {
                if (BuildConfig.DEBUG) Log.w(getClass().getSimpleName(), "Ignoring onAvailable() because it does not concern the active network!");
                return;
            }
            this.available = network;
            NetworkInfo.State oldState = NetworkChangedReceiver.this.state;
            updateSituation(null);
            notifyListeners(oldState);
        }

        /** {@inheritDoc} */
        @Override
        public void onBlockedStatusChanged(@NonNull Network network, boolean blocked) {
            NetworkInfo.State oldState = NetworkChangedReceiver.this.state;
            NetworkChangedReceiver.this.state = blocked ? NetworkInfo.State.DISCONNECTED : NetworkInfo.State.CONNECTED;
            notifyListeners(oldState);
        }

        /** {@inheritDoc} */
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
        }

        /** {@inheritDoc} */
        @Override
        public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
        }

        /** {@inheritDoc} */
        @Override
        public void onLosing(@NonNull Network network, int maxMsToLive) {
            Network active;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                active = ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();
            } else {
                active = this.available;
            }
            if (!Objects.equals(active, network)) {
                if (BuildConfig.DEBUG) Log.w(getClass().getSimpleName(), "Ignoring onLosing() because it does not concern the active network!");
                return;
            }
            NetworkInfo.State oldState = NetworkChangedReceiver.this.state;
            NetworkChangedReceiver.this.state = NetworkInfo.State.DISCONNECTING;
            notifyListeners(oldState);
        }

        /** {@inheritDoc} */
        @Override
        public void onLost(@NonNull Network network) {
            Network active;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                active = ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();
            } else {
                active = this.available;
            }
            if (!Objects.equals(active, network)) {
                if (BuildConfig.DEBUG) Log.w(getClass().getSimpleName(), "Ignoring onLost() because it does not concern the active network!");
                return;
            }
            if (BuildConfig.DEBUG) Log.w(getClass().getSimpleName(), "Lost " + networkToString(app, active));
            this.available = null;
            NetworkInfo.State oldState = NetworkChangedReceiver.this.state;
            NetworkChangedReceiver.this.state = NetworkInfo.State.DISCONNECTED;
            notifyListeners(oldState);
        }

        /** {@inheritDoc} */
        @Override
        public void onUnavailable() {
            if (BuildConfig.DEBUG) Log.i(getClass().getSimpleName(), "onUnavailable()");
        }
    }

}

/*
 * ProxyPicker.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Implementation of a java.net.ProxySelector.
 */
public class ProxyPicker extends ProxySelector {

    private static final String ATTR_HTTPS_HOST = "https.proxyHost";
    private static final String ATTR_HTTPS_PORT = "https.proxyPort";
    private static final String ATTR_HTTP_HOST = "http.proxyHost";
    private static final String ATTR_HTTP_PORT = "http.proxyPort";
    private static final String ATTR_SOCKS_HOST = "socksProxyHost";
    private static final String ATTR_SOCKS_PORT = "socksProxyPort";
    private static final String TAG = "ProxyPicker";

    /**
     * Determines the proxy server to use based on the preferences.<br>
     * The proxy port defaults to {@link App#DEFAULT_PROXY_PORT}, if not given as "address:portnumber".<br>
     * Also sets the relevant system properties (which are, according to the docs, used by libvlc).<br>
     * See <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html">https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html</a>
     * @param ctx Context
     * @return java.net.Proxy or {@code null} which means direct connection
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    @Nullable
    private static Proxy determineProxy(@NonNull Context ctx, URI uri) {
        String host = uri.getHost();
        if (host == null) return null;
        return determineProxy(ctx, host);
    }

    @Nullable
    private static Proxy determineProxy(@NonNull Context ctx, @NonNull final String host) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String defaultProxyValue = Proxy.Type.DIRECT.toString();
        String type = prefs.getString(App.PREF_PROXY_TYPE, defaultProxyValue);
        if (defaultProxyValue.equals(type)) {
            proxyClear();
            return null;
        }
        String proxyServerAndPort = prefs.getString(App.PREF_PROXY_SERVER, null);
        if (TextUtils.isEmpty(proxyServerAndPort)) {
            proxyClear();
            return null;
        }
        @Nullable Set<String> restrict = getAffectedHosts(prefs);
        boolean proxyAppliesToHost;
        if (restrict != null) {
            proxyAppliesToHost = false;
            for (String r : restrict) {
                if (host.endsWith(r)) {
                    proxyAppliesToHost = true;
                    break;
                }
            }
        } else {
            proxyAppliesToHost = true;
        }
        if (!proxyAppliesToHost) {
            proxyClear();
            return null;
        }

        //
        String proxyServer;
        int proxyPort;
        //noinspection ConstantConditions
        int colon = proxyServerAndPort.indexOf(':');
        if (colon > -1) {
            proxyServer = proxyServerAndPort.substring(0, colon).trim();
            proxyPort = Util.parseInt(proxyServerAndPort.substring(colon + 1).trim(), App.DEFAULT_PROXY_PORT);
            if (proxyPort < 0 || proxyPort > 65535) proxyPort = App.DEFAULT_PROXY_PORT;
        } else {
            proxyServer = proxyServerAndPort.trim();
            proxyPort = App.DEFAULT_PROXY_PORT;
        }
        if (Proxy.Type.HTTP.toString().equals(type)) {
            System.setProperty(ATTR_HTTP_HOST, proxyServer);
            System.setProperty(ATTR_HTTP_PORT, String.valueOf(proxyPort));
            System.setProperty(ATTR_HTTPS_HOST, proxyServer);
            System.setProperty(ATTR_HTTPS_PORT, String.valueOf(proxyPort));
            System.clearProperty(ATTR_SOCKS_HOST);
            System.clearProperty(ATTR_SOCKS_PORT);
        } else if (Proxy.Type.SOCKS.toString().equals(type))  {
            System.setProperty(ATTR_SOCKS_HOST, proxyServer);
            System.setProperty(ATTR_SOCKS_PORT, String.valueOf(proxyPort));
            System.clearProperty(ATTR_HTTP_HOST);
            System.clearProperty(ATTR_HTTP_PORT);
            System.clearProperty(ATTR_HTTPS_HOST);
            System.clearProperty(ATTR_HTTPS_PORT);
        }
        //
        SocketAddress sa = InetSocketAddress.createUnresolved(proxyServer, proxyPort);
        try {
            return new Proxy(Proxy.Type.valueOf(type), sa);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        }
        proxyClear();
        return null;
    }

    /**
     * Returns the set of hosts that the proxy applies to.<br>
     * Note: if "server.com" is returned, "www.server.com" will be affected, too!
     * @param prefs SharedPreferences
     * @return Set of hosts that the proxy applies to
     */
    @Nullable
    public static Set<String> getAffectedHosts(@NonNull SharedPreferences prefs) {
        Set<String> restrict = null;
        String restrictTo = prefs.getString(App.PREF_PROXY_RESTRICT, null);
        if (restrictTo != null) {
            restrict = new HashSet<>();
            StringTokenizer st = new StringTokenizer(restrictTo, "\n,; ");
            while (st.hasMoreTokens()) {
                String token = st.nextToken().trim();
                if (token.startsWith("*")) token = token.substring(1);
                if (!TextUtils.isEmpty(token)) restrict.add(token);
            }
        } else {
            if (BuildConfig.DEBUG) Log.i(TAG, "Proxy is not restricted to any host");
        }
        return restrict;
    }

    /**
     * Resets all proxy-related system properties.<br>
     * See <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html">https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html</a>
     */
    private static void proxyClear() {
        System.clearProperty(ATTR_HTTP_HOST);
        System.clearProperty(ATTR_HTTP_PORT);
        System.clearProperty(ATTR_HTTPS_HOST);
        System.clearProperty(ATTR_HTTPS_PORT);
        System.clearProperty(ATTR_SOCKS_HOST);
        System.clearProperty(ATTR_SOCKS_PORT);
    }

    private final App app;
    private final List<Proxy> list = new ArrayList<>(1);

    /**
     * Constructor.
     * @param ctx Context
     * @throws NullPointerException if {@code ctx} is {@code null}
     */
    public ProxyPicker(@NonNull Context ctx) {
        super();
        this.app = (App)ctx.getApplicationContext();
    }

    /** {@inheritDoc} */
    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (BuildConfig.DEBUG) Log.w(TAG, "Proxy connection " + sa + " failed for " + uri + ": " + ioe.toString());
    }

    /** {@inheritDoc} */
    @Override
    public List<Proxy> select(URI uri) {
        Proxy proxy = determineProxy(this.app, uri);
        if (proxy == null) proxy = Proxy.NO_PROXY;
        this.list.clear();
        this.list.add(proxy);
        if (BuildConfig.DEBUG) Log.i(TAG, "Returning proxy " + this.list.get(0) + " for " + uri);
        return this.list;
    }

    @NonNull
    public List<Proxy> select(@NonNull String host) {
        Proxy proxy = determineProxy(this.app, host);
        if (proxy == null) proxy = Proxy.NO_PROXY;
        this.list.clear();
        this.list.add(proxy);
        if (BuildConfig.DEBUG) Log.i(TAG, "Returning proxy " + this.list.get(0) + " for " + host);
        return this.list;
    }

}

/*
 * SftpLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS4;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import net.cellar.Ancestry;
import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.LoaderService;
import net.cellar.auth.AuthManager;
import net.cellar.model.Credential;
import net.cellar.model.Delivery;
import net.cellar.model.Order;
import net.cellar.supp.Log;
import net.cellar.supp.UriUtil;
import net.cellar.supp.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.util.List;

/**
 * See
 * <ul>
 * <li><a href="https://www.iana.org/assignments/uri-schemes/prov/sftp">Scheme definition</a></li>
 * <li><a href="http://www.jcraft.com/jsch/examples/Sftp.java">JSch sample</a></li>
 * <li><a href="https://www.sftp.net/public-online-sftp-servers">Test servers</a></li>
 * </ul>
 */
public class SftpLoader extends Loader {

    private static final String TAG = "SftpLoader";
    private static final int PORT = 22;

    @NonNull private final App app;
    private Session session;

    /**
     * Constructor.
     * @param id download id
     * @param app App
     * @param loaderListener LoaderListener
     */
    public SftpLoader(int id, @NonNull App app, @Nullable LoaderListener loaderListener) {
        super(id, loaderListener);
        this.app = app;
    }

    @Override
    protected void cleanup() {
        if (this.session != null) {
            this.session.disconnect();
            this.session = null;
        }
    }

    @NonNull
    @Override
    Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        if (order.getDestinationFolder() == null) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        final File destinationDir = new File(order.getDestinationFolder());
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        if (order.getDestinationFilename() == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }
        File destinationFile = new File(destinationDir, order.getDestinationFilename());

        // Scheme syntax:
        //   sftp://[<user>[;fingerprint=<host-key fingerprint>]@]<host>[:<port>]/<path>/<file>

        final Uri uri = order.getUri();

        final String host = uri.getHost();
        if (host == null || host.length() == 0) {
            if (BuildConfig.DEBUG) Log.e(TAG, "URI does not contain a host!");
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, null, null);
        }
        final String path = uri.getPath();
        if (path == null || path.length() == 0) {
            if (BuildConfig.DEBUG) Log.e(TAG, "URI does not contain a path!");
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, null, null);
        }
        final String fileName = uri.getLastPathSegment();
        if (fileName == null || fileName.length() == 0) {
            if (BuildConfig.DEBUG) Log.e(TAG, "URI does not contain a file name!");
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, null, null);
        }
        final int port = uri.getPort() > -1 ? uri.getPort() : PORT;

        List<Proxy> proxies = this.app.getProxyPicker().select(host);
        final Proxy proxy = (proxies.isEmpty()) ? Proxy.NO_PROXY : proxies.get(0);

        Credential credential = UriUtil.getCredential(uri);
        // if the url did not contain credential information or if there was just the user id but no password
        if (credential == null || credential.getPassword() == null) {
            String user = credential != null ? credential.getUserid() : null;
            credential = Credential.findUsable(AuthManager.getInstance().getCredentials(), host, user);
            if (credential == null) {
                return new Delivery(order, 401, destinationFile, null, new Delivery.AuthenticateInfo(AuthManager.SCHEME_SFTP, host, user));
            }
        }

        if (BuildConfig.DEBUG) {
            JSch.setLogger(new Logger() {
                @Override
                public boolean isEnabled(int level) {
                    return true;
                }

                @Override
                public void log(int level, String message) {
                    switch (level) {
                        case 2:
                            Log.w(TAG + "-JSch", message);
                            break;
                        case 3:
                        case 4:
                            Log.e(TAG + "-JSch", message);
                            break;
                        default:
                            Log.i(TAG + "-JSch", message);
                    }
                }
            });
        }
        JSch jsch = new JSch();
        Channel channel = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            this.session = jsch.getSession(credential.getUserid(), host, port);
            if (credential.getPassword() != null) this.session.setPassword(credential.getPassword().toString());
            this.session.setConfig("StrictHostKeyChecking", "no");
            this.session.setConfig("PreferredAuthentications", "password");
            this.session.setConfig("compression.c2s", "zlib,zlib@openssh.com,none");
            this.session.setConfig("compression.s2c", "zlib,zlib@openssh.com,none");
            this.session.setConfig("compression_level", "9");
            //this.session.setConfig("CheckCiphers", "aes128-cbc");
             if (proxy.type() == Proxy.Type.HTTP) {
                SocketAddress sa = proxy.address();
                if (sa instanceof InetSocketAddress) {
                    InetSocketAddress isa = (InetSocketAddress)sa;
                    this.session.setProxy(new ProxyHTTP(isa.getHostName(), isa.getPort()));
                }
            } else if (proxy.type() == Proxy.Type.SOCKS) {
                SocketAddress sa = proxy.address();
                if (sa instanceof InetSocketAddress) {
                    InetSocketAddress isa = (InetSocketAddress)sa;
                    //TODO SOCKS4 or SOCKS5
                    this.session.setProxy(new ProxySOCKS4(isa.getHostName(), isa.getPort()));
                }
            }
            this.session.connect(App.TIMEOUT_CONNECT);
            channel = this.session.openChannel("sftp");
            channel.connect();
            ChannelSftp c = (ChannelSftp) channel;
            if (!path.equals(fileName)) {
                String directory = path.substring(0, path.length() - fileName.length() - 1);
                if (directory.length() > 0) {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Changing current directory to \"" + directory + "\"");
                    c.cd(directory);
                }
            }


            SftpATTRS a = c.lstat(fileName);
            final long length = a != null ? a.getSize() : -1L;
            if (BuildConfig.DEBUG && a != null) Log.i(TAG, "getMTime(): " + a.getMTime() + " (" + a.getMtimeString() + ")");
            long skip = 0L;
            if (destinationFile.isFile()) {
                String originalHost = Ancestry.getInstance().getHost(destinationFile);
                if (destinationFile.length() < length && host.equals(originalHost)) {
                    // incomplete file exists locally
                    skip = destinationFile.length();
                } else {
                    // local file may or may not exist
                    String alt = Util.suggestAlternativeFilename(destinationFile);
                    if (alt != null) destinationFile = new File(destinationDir, alt);
                }
            }

            publishProgress(Progress.resourcename(destinationFile.getName()));

            in = c.get(fileName, null, skip);
            out = new FileOutputStream(destinationFile, skip > 0L);
            final byte[] buf = new byte[length > 0L ? Math.min(16_384, (int)length) : 16_384];
            long count = 0L;
            Progress progress = null;
            for (; ; ) {
                int read = in.read(buf);
                if (read < 0) break;
                out.write(buf, 0, read);
                count += read;
                if (length <= 0L) continue;
                progress = Progress.completing((float)count / (float)length, progress);
                publishProgress(progress);
            }
        } catch (SftpException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
            String msg = e.toString();
            if (msg.contains("No such file")) return new Delivery(order, 404, null, null, e, null);
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, e, null);
        } catch (JSchException e) {
            if (BuildConfig.DEBUG) {
                Throwable cause = e.getCause();
                if (cause != null) Log.e(TAG, e.getClass().getName() + "\ncaused by\n" + cause.toString(), cause);
                else Log.e(TAG, e.toString(), e);
            }
            final String msg = e.toString();
            if (msg.contains("Auth fail")) return new Delivery(order, 403, null, null, e, null);
            if (msg.contains("Algorithm negotiation fail")) return new Delivery(order, 501, null, null, e, null);
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, e, null);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, e, null);
        } finally {
            Util.close(out, in);
            if (channel != null) channel.disconnect();
            if (this.session != null) {
                this.session.disconnect();
                this.session = null;
            }
        }
        return new Delivery(order, 200, destinationFile, null);
    }
}

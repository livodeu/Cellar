/*
 * FtpLoader.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.worker;

import androidx.annotation.CheckResult;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

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

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * Powered by
 * <ul>
 * <li><a href="https://commons.apache.org/proper/commons-net/">https://commons.apache.org/proper/commons-net/</a></li>
 * <li><a href="https://github.com/apache/commons-net">https://github.com/apache/commons-net</a></li>
 * </ul>
 * See also:<ul>
 * <li><a href="https://tools.ietf.org/html/rfc1738">Scheme definition</a></li>
 * <li><a href="https://tools.ietf.org/html/rfc959">https://tools.ietf.org/html/rfc959</a></li>
 * </ul>
 */
public class FtpLoader extends Loader {

    private static final String TAG = "FtpLoader";
    private static final String USERNAME_ANONYMOUS = "anonymous";

    /**
     * Creates fantasy names like "Cuduxadocivazi", "Pafimuhimumete" etc.
     * @param minLength minimum length
     * @param maxLength maximum length
     * @return fantasy name
     */
    @NonNull
    @CheckResult
    @VisibleForTesting
    public static CharSequence makeFantasyName(int minLength, int maxLength) {
        final int n;
        if (minLength == maxLength) {
            n = minLength;
        } else {
            n = (int) (Math.random() * (maxLength - minLength)) + minLength;
        }
        if (n <= 0) return "";
        final String[] c = new String[] {
                "b",
                "c",
                "d",
                "f",
                "g",
                "h",
                "k",
                "l",
                "m",
                "n",
                "p",
                "r",
                "s",
                "t",
                "v",
                "w",
                "x",
                "z",
                "ng",
                "st"
        };
        final char[] v = new char[] {'a', 'e', 'i', 'o', 'u'};
        final StringBuilder sb = new StringBuilder(n);
        String first;
        do {
            first = c[(int) (Math.random() * c.length)];
        } while (first.length() != 1);
        sb.append(Character.toUpperCase(first.charAt(0)));
        for (int i = 1; sb.length() < n; i++) {
            if ((i % 2) == 0) {
                sb.append(c[(int) (Math.random() * c.length)]);
            } else {
                sb.append(v[(int) (Math.random() * v.length)]);
            }
        }
        if (sb.length() > maxLength) return sb.subSequence(0, maxLength);
        return sb;
    }

    private static String makeRandomPwd() {
        return (makeFantasyName(5, 10) + "@" + makeFantasyName(6, 12) + (Math.random() < 0.5 ? ".com" : ".net")).toLowerCase();
    }

    private static void safeDisconnect(@NonNull FTPClient ftp) {
        try {ftp.disconnect();} catch (Exception ignored) {}
    }

    private static void safeLogout(@NonNull FTPClient ftp) {
        try {ftp.logout();} catch (Exception ignored) {}
    }

    /**
     * Constructor.
     * @param id download id
     * @param loaderListener Listener (optional)
     */
    public FtpLoader(int id, @Nullable LoaderListener loaderListener) {
        super(id, loaderListener);
    }

    /**
     * Attempts to find a Credential that can be used for the given host.
     * @param host host to match
     * @return Credential
     */
    @Nullable
    private static Credential findCredential(@Nullable final String host) {
        if (host == null) return null;
        final Set<Credential> credentials = AuthManager.getInstance().getCredentials();
        Credential matchingCredential = null;
        for (Credential credential : credentials) {
            if (credential.getType() == Credential.TYPE_FTP && credential.getRealm().equals(host)) {
                matchingCredential = credential;
                break;
            }
        }
        return matchingCredential;
    }

    /**
     * Determines the size of a file served via ftp <em>in the current working directory</em>.
     * @param ftpClient FTPClient to use
     * @param fileName file name
     * @return file size in bytes, or -1
     * @throws IOException if an I/O error occurs
     * @throws NullPointerException if any parameter is {@code null}
     */
    @IntRange(from = -1)
    private static long determineFileSize(@NonNull FTPClient ftpClient, @NonNull final String fileName) throws IOException {
        final FTPFile[] ftpFiles = ftpClient.listFiles();
        for (FTPFile ftpFile : ftpFiles) {
            if (ftpFile == null) continue;
            //if (BuildConfig.DEBUG) Log.i(TAG, ftpFile.toString());
            if (fileName.equals(ftpFile.getName())) {
                if (ftpFile.isDirectory()) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Requested resource is a directory!");
                    return -1L;
                }
                return ftpFile.getSize();
            }
        }
        return -1L;
    }

    @NonNull
    @Override
    Delivery load(@NonNull Order order, float progressBefore, float progressPerOrder) {
        final File destinationDir = new File(order.getDestinationFolder());
        if (!destinationDir.isDirectory() && !destinationDir.mkdirs()) {
            return new Delivery(order, LoaderService.ERROR_DEST_DIRECTORY_NOT_EXISTENT, null, null);
        }
        if (order.getDestinationFilename() == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }

        final String host = order.getUri().getHost();

        // set the destination file (not finally, there might be some corrections further down…)
        File destinationFile = new File(destinationDir, order.getDestinationFilename());

        // bail out if there is no host
        if (host == null || host.length() == 0) {
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, null, null);
        }

        // determine whether we should skip some bytes
        final long skip;
        if (destinationFile.isFile()) {
            // destination file already exists
            if (!host.equals(Ancestry.getInstance().getHost(destinationFile))) {
                // but the host for the current download is something other than the one that the existing file originated from
                String alt = Util.suggestAlternativeFilename(destinationFile);
                // alt should be always non-null here
                if (alt != null) destinationFile = new File(destinationDir, alt);
                // download to a new file, so don't skip anything
                skip = 0L;
            } else {
                // we are apparently resuming an interrupted download
                skip = destinationFile.length();
            }
        } else {
            // destination file does not exist yet; therefore a brand new download
            skip = 0L;
        }

        //
        //lockDestination(destinationFile);
        final boolean destinationFileExistedBefore = destinationFile.isFile();

        final List<String> pathSegments = order.getUri().getPathSegments();
        if (pathSegments.size() == 0) {
            return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, null, null);
        }
        final StringBuilder path = new StringBuilder(32);
        for (int i = 0; i < pathSegments.size() - 1; i++) path.append(pathSegments.get(i)).append('/');
        final String resource = order.getUri().getLastPathSegment();
        if (resource == null) {
            return new Delivery(order, LoaderService.ERROR_NO_FILENAME, null, null);
        }

        InputStream in = null;
        OutputStream out = null;
        final FTPClient ftpClient = new FTPClient();
        ftpClient.setConnectTimeout(App.TIMEOUT_CONNECT);
        ftpClient.setDataTimeout(App.TIMEOUT_READ);
        Exception ex = null;
        try {
            ftpClient.connect(host);
            int reply = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failing with rc " + reply);
                safeDisconnect(ftpClient);
                return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, null, null);
            }

            // try to find a Credential that we can use here
            boolean possiblyAddCredential;
            Credential credentialForHost = UriUtil.getCredential(order.getUri());
            if (credentialForHost != null) {
                possiblyAddCredential = true;
            } else {
                credentialForHost = findCredential(host);
                possiblyAddCredential = false;
            }

            //
            if (credentialForHost != null) {
                if (!ftpClient.login(credentialForHost.getUserid(), credentialForHost.getPassword() != null ? credentialForHost.getPassword().toString() : null)) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Login for user " + credentialForHost.getUserid() + " and pwd " + credentialForHost.getPassword() + " failed with rc " + ftpClient.getReplyCode());
                    safeLogout(ftpClient);
                    Delivery.AuthenticateInfo authenticateInfo = new Delivery.AuthenticateInfo(AuthManager.SCHEME_FTP, host);
                    safeDisconnect(ftpClient);
                    return new Delivery(order, 401, destinationFile, null, null, authenticateInfo);
                }
                // we have successfully logged in/on, so we can remember the Credential that was passed along with the url
                if (possiblyAddCredential) {
                    AuthManager.getInstance().addCredential(credentialForHost);
                }
            } else {
                if (BuildConfig.DEBUG) Log.i(TAG, "Did not find credentials for " + host);
                if (!ftpClient.login(USERNAME_ANONYMOUS, makeRandomPwd())) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Anonymous login failed with rc " + ftpClient.getReplyCode());  // the reply code should be 530
                    safeLogout(ftpClient);
                    if (BuildConfig.DEBUG) Log.w(TAG, "Did not find matching credential for \"" + host + "\"!");
                    Delivery.AuthenticateInfo authenticateInfo = new Delivery.AuthenticateInfo(AuthManager.SCHEME_FTP, host);
                    safeDisconnect(ftpClient);
                    return new Delivery(order, 401, destinationFile, null, null, authenticateInfo);
                }
            }
            if (path.length() > 0 && !ftpClient.changeWorkingDirectory(path.toString())) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to change working dir to '" + path + "'");
                safeLogout(ftpClient);
                safeDisconnect(ftpClient);
                return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, null, null);
            }
            if (BuildConfig.DEBUG && path.length() > 0) Log.i(TAG, "Changed working dir to '" + path + "'");
            long fileSize = determineFileSize(ftpClient, resource);
            if (fileSize < 0L) {
                safeLogout(ftpClient);
                safeDisconnect(ftpClient);
                return new Delivery(order, LoaderService.ERROR_NO_SOURCE_FOUND, destinationFile, null, null, null);
            }
            if (fileSize > destinationDir.getFreeSpace()) {
                safeLogout(ftpClient);
                safeDisconnect(ftpClient);
                return new Delivery(order, LoaderService.ERROR_LACKING_SPACE, destinationFile, null);
            }
            if (super.refListener != null) {
                LoaderListener l = super.refListener.get();
                if (l != null) l.contentlength(super.id, fileSize);
            }
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.enterLocalPassiveMode();
            ftpClient.setRestartOffset(skip);
            in = ftpClient.retrieveFileStream(resource);
            if (in == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Failed to open input to '" + resource + "'");
                safeLogout(ftpClient);
                safeDisconnect(ftpClient);
                return new Delivery(order, LoaderService.ERROR_CANNOT_CONNECT, destinationFile, null, null, null);
            }
            out = new BufferedOutputStream(new FileOutputStream(destinationFile));
            final byte[] buf = new byte[Math.min((int)fileSize, 16_384)];
            long totalBytes = 0L;
            Progress progress = null;
            while (!isCancelled() && !super.stopRequested) {
                int read = in.read(buf);
                if (read < 0) break;
                if (read == 0) continue;
                totalBytes += read;
                out.write(buf, 0, read);
                if (fileSize <= 0L) continue;
                progress = Progress.completing(progressBefore + (float) totalBytes / (float) fileSize * progressPerOrder, progress);
                publishProgress(progress);
            }
        } catch (Exception e) {
            ex = e;
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        } finally {
            Util.close(out, in);
            try {ftpClient.completePendingCommand();} catch (Exception ignored) {}
            safeLogout(ftpClient);
            safeDisconnect(ftpClient);
        }
        if (ex != null) {
            if (!destinationFileExistedBefore) Util.deleteFile(destinationFile);
            return new Delivery(order, LoaderService.ERROR_OTHER, destinationFile, null, ex, null);
        }
        if (isCancelled()) {
            if (!destinationFileExistedBefore && !isDeferred()) Util.deleteFile(destinationFile);
            return new Delivery(order, isDeferred() ? LoaderService.ERROR_DEFERRED : LoaderService.ERROR_CANCELLED, destinationFile, null);
        }
        return new Delivery(order, 200, destinationFile, order.getMime());
    }
}

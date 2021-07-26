/*
 * AuthManager.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.auth;

import android.os.Build;
import android.os.Handler;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.Size;
import androidx.annotation.WorkerThread;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.model.Credential;
import net.cellar.supp.Log;

import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * https://docs.oracle.com/javase/6/docs/technotes/guides/net/http-auth.html
 * https://httpbin.org/#/Auth
 * https://alvinalexander.com/java/jwarehouse/openjdk-8/jdk/src/share/classes/sun/net/www/protocol/http/HttpURLConnection.java.shtml
 */
public final class AuthManager {

    public static final String SCHEME_BASIC = "Basic";
    public static final String SCHEME_FTP = "ftp";
    public static final String SCHEME_SFTP = "sftp";
    private static final String CREDENTIALS_FILE = "credentials";
    private static final Set<Credential> EMPTY_SET = new HashSet<>(0);
    @Size(3) private static final String[] SUPPORTED_AUTH_SCHEMES = new String[] {SCHEME_BASIC, SCHEME_FTP, SCHEME_SFTP};
    private static final String TAG = "AuthManager";
    private static AuthManager instance;

    /**
     * @return the AuthManager instance
     */
    @NonNull
    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    /**
     * Determines whether the given authorization scheme is supported by this app.
     * @param scheme authorization scheme
     * @return {@code true} or {@code false}
     */
    public static boolean isAuthSchemeSupported(@Nullable final String scheme) {
        for (String supportedScheme : SUPPORTED_AUTH_SCHEMES) {
            if (supportedScheme.equals(scheme)) return true;
        }
        return false;
    }

    private final Handler handler = new Handler();
    /** credentials */
    private final Set<Credential> credentials = new HashSet<>();
    private EncryptionHelper eh;
    /** the App */
    private App app;
    /** the App's files' directory */
    private File dir;
    private File credentialsFile;
    private volatile boolean setupFinished;
    private volatile boolean setupFailed;

    /**
     * Private constructor.
     */
    private AuthManager() {
        super();
    }

    /**
     * Sets a Credential.
     * Does not do anything if such a Credential (same realm, user, type) already exists (see {@link Credential#equals(Object)}).
     * @param credential Credential
     */
    public void addCredential(@Nullable final Credential credential) {
        if (credential == null) return;
        if (BuildConfig.DEBUG) Log.i(TAG, "addCredential(" + credential + ")");
        synchronized (this.credentials) {
            if (this.credentials.contains(credential)) return;
        }
        addOrReplaceCredential(credential);
    }

    /**
     * Sets or replaces a Credential.
     * @param credential Credential
     */
    public void addOrReplaceCredential(@Nullable final Credential credential) {
        if (credential == null) return;
        if (BuildConfig.DEBUG) Log.i(TAG, "addOrReplaceCredential(" + credential + ")");
        assert this.dir != null;
        synchronized (this.credentials) {
            if (this.credentials.contains(credential)) {
                if (BuildConfig.DEBUG) {
                    Credential toReplace = Credential.findUsable(this.credentials, credential.getRealm());
                    Log.i(TAG, "Replacing " + toReplace + " with " + credential);
                }
                if (!this.credentials.remove(credential)) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Failed to remove " + credential + " which has been reported to exist!");
                }
            } else {
                if (BuildConfig.DEBUG) Log.i(TAG, "Adding credential " + credential);
            }
            this.credentials.add(credential);
            if (this.credentialsFile != null ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !this.setupFailed) {
                    if (!this.setupFinished) {
                        this.handler.postDelayed(() -> Credential.store(this.app, this.eh, this.credentialsFile, this.credentials), 5_000L);
                        return;
                    }
                    Credential.store(this.app, this.eh, this.credentialsFile, this.credentials);
                } else {
                    Credential.store(this.credentialsFile, this.credentials);
                }
            }
        }
    }

    /**
     * Logs known credentials. Does not do anything in non-debug builds.
     */
    public void dumpAuth() {
        if (!BuildConfig.DEBUG) return;
        synchronized (this.credentials) {
            Log.i(TAG, "Currently " + this.credentials.size() + " credential(s)");
            for (Credential c : this.credentials) {
                Log.i(TAG, c.toString());
            }
        }
    }

    /**
     * Returns a copy of the known Credentials.
     * The returned data is read-only.
     * @return Set of Credentials (possibly empty)
     */
    @NonNull
    public Set<Credential> getCredentials() {
        final Set<Credential> c;
        synchronized (this.credentials) {
            c = this.credentials.isEmpty() ? EMPTY_SET : new HashSet<>(this.credentials);
        }
        return c;
    }

    @TestOnly
    public EncryptionHelper getEh() {
        if (!BuildConfig.DEBUG) return null;
        return this.eh;
    }

    @AnyThread
    public void init(@NonNull App app) {
        if (this.app != null) {
            return;
        }
        this.app = app;
        this.dir = this.app.getFilesDir();
        if (!this.dir.isDirectory()) {
            if (!this.dir.mkdirs()) return;
        }
        this.credentialsFile = new File(this.dir, CREDENTIALS_FILE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // setupEncryption() takes quite some time, 300 ms or even more…
            Thread t = new Thread() {
                @Override
                public void run() {
                    setup();
                }
            };
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        } else {
            synchronized (this.credentials) {
                this.credentials.addAll(Credential.load(null, new File(this.dir, CREDENTIALS_FILE)));
            }
        }
    }

    @TestOnly
    public boolean isSetupFinished() {
        return this.setupFinished;
    }

    /**
     * Removes a Credential.
     * @param credential Credential to remove
     * @return {@code true} if the given Credential has been removed
     */
    public boolean removeCredential(@Nullable Credential credential) {
        if (credential == null) return false;
        assert this.dir != null;
        final boolean removed;
        synchronized (this.credentials) {
            removed = this.credentials.remove(credential);
            if (removed && this.credentialsFile != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !this.setupFailed) {
                    if (!this.setupFinished) {this.handler.postDelayed(() -> Credential.store(this.app, this.eh, this.credentialsFile, this.credentials), 5_000L); return true;}
                    Credential.store(this.app, this.eh, this.credentialsFile, this.credentials);
                } else {
                    Credential.store(this.credentialsFile, this.credentials);
                }
            } else if (BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to remove " + credential);
            }
        }
        return removed;
    }

    /**
     * Makes preparations to store credentials in a secure way.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @WorkerThread
    private void setup() {
        assert this.app != null;
        assert this.credentialsFile != null;
        try {
            this.eh = new EncryptionHelper(this.app);
            if (this.credentialsFile.length() > 0L) {
                synchronized (this.credentials) {
                    this.credentials.addAll(Credential.load(this.eh, this.credentialsFile));
                }
            }
            this.setupFinished = true;
        } catch (Exception e) {
            this.setupFailed = true;
            if (BuildConfig.DEBUG) Log.e(TAG, "During setup: " + e.toString());
        }
    }

}

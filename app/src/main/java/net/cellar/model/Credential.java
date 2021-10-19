/*
 * Credential.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.model;

import android.content.Context;
import android.os.Build;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import net.cellar.BuildConfig;
import net.cellar.auth.AuthManager;
import net.cellar.auth.EncryptionHelper;
import net.cellar.supp.Log;
import net.cellar.supp.Util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 *
 */
public final class Credential implements Comparable<Credential> {

    @Type public static final int TYPE_FTP = 2;
    @Type public static final int TYPE_HTTP_BASIC = 1;
    @Type public static final int TYPE_SFTP = 4;
    @Type public static final int TYPE_UNKNOWN = 0;
    private static final List<Credential> EMPTY_LIST = new ArrayList<>(0);
    private static final int NATTR = 5;
    private static final char SEP = '\t';

    /**
     * @param authorizationScheme authorization scheme
     * @return Credential Type
     */
    @Type
    public static int typeForScheme(final String authorizationScheme) {
        if (authorizationScheme == null) return Credential.TYPE_UNKNOWN;
        switch (authorizationScheme) {
            case AuthManager.SCHEME_BASIC: return Credential.TYPE_HTTP_BASIC;
            case AuthManager.SCHEME_FTP: return Credential.TYPE_FTP;
            case AuthManager.SCHEME_SFTP: return Credential.TYPE_SFTP;
        }
        return Credential.TYPE_UNKNOWN;
    }

    /**
     * Attempts to find a usable Credential within a Collection.
     * @param collection Collection of Credentials to search
     * @param realm realm to match
     * @return Credential
     */
    @Nullable
    public static Credential findUsable(@Nullable final Collection<Credential> collection, @Nullable final String realm) {
        if (collection == null || realm == null) return null;
        for (Credential c : collection) {
            if (realm.equals(c.realm) && c.isUsable()) return c;
        }
        return null;
    }


    /**
     * Attempts to find a usable Credential within a Collection.
     * @param collection Collection of Credentials to search
     * @param realm realm to match
     * @param userid user id to match (may be {@code null})
     * @return Credential
     */
    @Nullable
    public static Credential findUsable(@Nullable final Collection<Credential> collection, @Nullable final String realm, @Nullable final String userid) {
        if (collection == null || realm == null) return null;
        for (Credential c : collection) {
            if (realm.equals(c.realm) && c.isUsable()) {
                if (userid == null || userid.equals(c.getUserid()))
                return c;
            }
        }
        return null;
    }

    @Nullable
    private static Credential fromString(@Nullable String s) {
        if (s == null) return null;
        final String[] p = s.split(String.valueOf(SEP));
        if (p.length != NATTR) {
            if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), "Invalid String \"" + s + "\"");
            return null;
        }
        return new Credential(p[0], p[1], p[2], Util.parseLong(p[3], 0L), Util.parseInt(p[4], TYPE_UNKNOWN));
    }

    /**
     * Loads Credentials from the given encrypted file.
     * @param source File to read from
     * @return list of Credential objects
     */
    @NonNull
    public static List<Credential> load(@Nullable EncryptionHelper eh, @NonNull File source) {
        try {
            if (eh != null && Build.VERSION.SDK_INT >= 23) return loadInternal(eh, new FileInputStream(source));
            return loadInternal(new FileInputStream(source));
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), "While loading from encrypted file: " + e.toString(), e);
        }
        return EMPTY_LIST;
    }

    /**
     * Loads unencrypted Credentials from the given stream.
     * @param in InputStream to read from
     * @return list of Credential objects
     */
    @NonNull
    private static List<Credential> loadInternal(InputStream in) throws IOException {
        BufferedReader reader = null;
        final List<Credential> list = new ArrayList<>();
        try {
            String what = null;
            reader = new BufferedReader(new InputStreamReader(in));
            for (;;) {
                String line = reader.readLine();
                if (line == null) break;
                Credential credential = Credential.fromString(line);
                if (credential == null) {
                    if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), "Skipped line '" + line + "'!");
                    continue;
                }
                if (BuildConfig.DEBUG && what == null) {
                    what = credential.type == TYPE_FTP ? "FTP" : (credential.type == TYPE_HTTP_BASIC ? "HTTP Basic" : "SFTP");
                }
                list.add(credential);
            }
        } finally {
            Util.close(reader);
        }
        return list;
    }

    /**
     * Loads encrypted Credentials from the given stream.
     * @param eh EncryptionHelper
     * @param in InputStream
     * @return list of Credential objects
     * @throws GeneralSecurityException if tink wants it
     * @throws IOException if an I/O error occurs
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @NonNull
    private static List<Credential> loadInternal(EncryptionHelper eh, @NonNull InputStream in) throws GeneralSecurityException, IOException {
        BufferedReader reader = null;
        final List<Credential> list = new ArrayList<>();
        try {
            String what = null;
            reader = new BufferedReader(new InputStreamReader(in));
            for (;;) {
                String line = reader.readLine();
                if (line == null) break;
                byte[] encrypted = EncryptionHelper.fromHex(line);
                byte[] decrypted = eh.decrypt(encrypted);
                String decryptedLine = new String(decrypted, StandardCharsets.UTF_8);
                Credential credential = Credential.fromString(decryptedLine);
                if (credential == null) {
                    if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), "Skipped line '" + decryptedLine + "'!");
                    continue;
                }
                if (BuildConfig.DEBUG && what == null) {
                    what = credential.type == TYPE_FTP ? "FTP" : (credential.type == TYPE_HTTP_BASIC ? "HTTP Basic" : "SFTP");
                }
                list.add(credential);
            }
        } finally {
            Util.close(reader);
        }
        return list;
    }

    /**
     * Stores Credentials in an encrypted file. Only for API 23 (M) or newer.
     * @param ctx Context
     * @param eh EncryptionHelper
     * @param dest target file
     * @param credentials Credentials
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public static void store(@NonNull Context ctx, @NonNull EncryptionHelper eh, @NonNull File dest, @NonNull final Collection<Credential> credentials) {
        BufferedWriter writer = null;
        try {
            final File tmpdir = ctx.getFilesDir();
            String tmpFileName;
            do {
                tmpFileName = "tmp" + System.currentTimeMillis() + ".tmp";
            } while (new File(tmpdir, tmpFileName).isFile());
            File tmp = new File(tmpdir, tmpFileName);
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp)));
            for (Credential credential : credentials) {
                if (credential == null) continue;
                byte[] encrypted = eh.encrypt(credential.toString().getBytes(StandardCharsets.UTF_8));
                writer.write(EncryptionHelper.asHex(encrypted).toString() + '\n');
            }
            writer.close();
            writer = null;
            if (!tmp.renameTo(dest)) {
                if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), "Failed to rename \"" + tmp + "\" to \"" + dest + "\"");
                Util.deleteFile(tmp);
            }
        } catch (IOException | GeneralSecurityException e) {
            if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), "While writing: " + e.toString(), e);
        } finally {
            Util.close(writer);
        }
    }

    /**
     * Stores Credentials in the given file <em>as unencrypted text</em>.
     * Should only be used when the API version is below {@link Build.VERSION_CODES#M M}.
     * @param nonEncryptedDestinationFile File to write to
     * @param credentials Credentials
     */
    public static void store(@NonNull File nonEncryptedDestinationFile, @NonNull final Collection<Credential> credentials) {
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Log.w(Credential.class.getSimpleName(), "Storing credentials as unencrypted text!");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(nonEncryptedDestinationFile)));
            for (Credential credential : credentials) {
                if (credential == null) continue;
                writer.write(credential.toString() + '\n');
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(Credential.class.getSimpleName(), e.toString(), e);
        } finally {
            Util.close(writer);
        }
    }

    @NonNull private final String realm;
    @Nullable private String userid;
    @Nullable private CharSequence password;
    private long modified;
    @Type private int type = TYPE_UNKNOWN;

    private Credential(@NonNull String realm) {
        super();
        int n = realm.length();
        if (n > 0 && realm.charAt(0) == '"') {realm = realm.substring(1); n--;}
        if (n > 0 && realm.charAt(n - 1) == '"') realm = realm.substring(0, n - 1);
        this.realm = realm;
    }

    public Credential(@NonNull String realm, @Nullable String user, @Nullable CharSequence pwd, @Type int type) {
        this(realm);
        this.userid = user;
        this.password = pwd;
        this.modified = System.currentTimeMillis();
        this.type = type;
    }

    private Credential(@NonNull String realm, @Nullable String user, @Nullable CharSequence pwd, long modified, @Type int type) {
        this(realm);
        this.userid = user;
        this.password = pwd;
        this.modified = modified;
        this.type = type;
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(Credential o) {
        return this.realm.compareToIgnoreCase(o.realm);
    }

    /** {@inheritDoc}<br>
     * <b>Equality does not take the {@link #password} into account!</b>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Credential)) return false;
        Credential that = (Credential) o;
        return Objects.equals(this.realm, that.realm) &&
                Objects.equals(this.userid, that.userid) &&
                this.type == that.type;
    }

    @Nullable
    public CharSequence getPassword() {
        return this.password;
    }

    @NonNull
    public String getRealm() {
        return this.realm;
    }

    @Type
    public int getType() {
        return type;
    }

    @Nullable
    public String getUserid() {
        return this.userid;
    }

    /** {@inheritDoc}<br>
     * <b>Equality does not take the {@link #password} into account!</b>
     */
    @Override
    public int hashCode() {
        return Objects.hash(this.realm, this.userid, this.type);
    }

    /**
     * Determines whether this Credential can be used.
     * @return {@code true} if {@link #userid} is set
     */
    public boolean isUsable() {
        return this.userid != null && this.userid.length() > 0;
    }

    /**
     * Sets the type
     * @param type one of the defined types
     */
    public void setType(@Type int type) {
        this.type = type;
    }

    /** {@inheritDoc} */
    @Override
    @NonNull
    public String toString() {
        return this.realm + SEP + this.userid + SEP + this.password + SEP + this.modified + SEP + this.type;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_UNKNOWN, TYPE_HTTP_BASIC, TYPE_FTP, TYPE_SFTP})
    @interface Type {}
}

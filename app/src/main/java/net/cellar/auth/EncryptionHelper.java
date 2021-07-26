/*
 * EncryptionHelper.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.Size;
import androidx.preference.PreferenceManager;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.AeadKeyTemplates;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;

import net.cellar.App;
import net.cellar.BuildConfig;
import net.cellar.supp.Log;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;

/**
 * <ul>
 * <li><a href="https://github.com/google/tink/blob/master/docs/JAVA-HOWTO.md">https://github.com/google/tink/blob/master/docs/JAVA-HOWTO.md</a></li>
 * </ul>
 */
@RequiresApi(Build.VERSION_CODES.M)
public final class EncryptionHelper {

    static final String KEYSTORE_PATH_URI = "android-keystore://";
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final CharSequence EMPTY_CHARSEQUENCE = new StringBuilder(0);
    @Size(16) private static final char[] HEX = new char[] {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    private static final String KEYSET_ALIAS     = "__cellar_eh_keyset__";
    /** name of the xml file (without .xml tag) that stores the keyset alias with key {@link #KEYSET_ALIAS} (in folder "{app_dir}/shared_prefs") */
    private static final String KEYSET_PREF_NAME = "__cellar_eh_pref__";
    private static final String NOT_READY_TO_DECRYPT = "Not ready for decryption";
    private static final String NOT_READY_TO_ENCRYPT = "Not ready for encryption";
    private static final String TAG = "EncryptionHelper";

    static {
        try {
            AeadConfig.register();
        } catch (GeneralSecurityException e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        }
    }


    /**
     * Converts some bytes into their hexadecimal representation.<br>
     * Is about 10 times faster than {@link Integer#toHexString(int)}.
     * @param data input data
     * @param count number of bytes to process out of {@code data}
     * @return hexadecimal representation
     * @throws NullPointerException if {@code data} is {@code null}
     */
    @NonNull
    public static CharSequence asHex(@NonNull final byte[] data, @IntRange(from = 1) final int count) {
        final int n = Math.min(data.length, count);
        final char[] cs = new char[n << 1];
        int j = 0;
        for (int k = 0; k < n; k++) {
            byte b = data[k];
            int i = ((int)b) & 0xff;
            if (i < 0x10) {
                cs[j++] = '0';
                cs[j++] = HEX[i];
            } else {
                cs[j++] = HEX[(i & 0xf0) >> 4];
                cs[j++] = HEX[(i & 0x0f)];
            }
        }
        return CharBuffer.wrap(cs);
    }

    /**
     * Converts some bytes into their hexadecimal representation.<br>
     * Is about 10 times faster than {@link Integer#toHexString(int)}.
     * @param data input data
     * @return hexadecimal representation
     */
    @NonNull
    public static CharSequence asHex(@Nullable final byte[] data) {
        if (data == null) return EMPTY_CHARSEQUENCE;
        return asHex(data, data.length);
    }

    /**
     * Converts a hexadecimal representation of byte data into a byte array.<br>
     * Runs about twice as fast as Integer.parseInt(…, 16).
     * @param hex hexadecimal representation of byte data
     * @return byte data
     * @throws IllegalArgumentException if {@code hex} is non-null and its length is not a multiple of 2
     */
    @NonNull
    public static byte[] fromHex(@Nullable final CharSequence hex) {
        if (hex == null) return EMPTY_BYTE_ARRAY;
        final int n = hex.length();
        if (n == 0) return EMPTY_BYTE_ARRAY;
        if (n % 2 != 0) throw new IllegalArgumentException("Invalid hex \"" + hex + "\"!");
        final byte[] data = new byte[n >> 1];
        for (int i = 0; i < n; i += 2) {
            data[i >> 1] = (byte)((Character.digit(hex.charAt(i),     16) << 4)
                                + (Character.digit(hex.charAt(i + 1), 16)));
        }
        return data;
    }

    private final Aead aead;
    private final byte[] associatedData;
    private final boolean ready;

    /**
     * Constructor.
     * @param ctx Context
     * @throws EncryptionFailedException if something went wrong
     */
    EncryptionHelper(@NonNull Context ctx) throws EncryptionFailedException {
        super();
        Aead aead = null;
        byte[] associatedData = null;
        boolean ready;
        try {
            String masterKeyAlias = MasterKeys.getOrCreateMasterKeyAlias(MasterKeys.getAES256GCMKeyGenParameterSpec());
            @SuppressWarnings("deprecation")
            //  withKeyTemplate(…) is used in the official Tink Hello World example
                    //  at https://github.com/google/tink/blob/master/examples/android/helloworld/app/src/main/java/com/helloworld/TinkApplication.java
                    AndroidKeysetManager ksm = new AndroidKeysetManager.Builder()
                    .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                    .withSharedPref(ctx, KEYSET_ALIAS, KEYSET_PREF_NAME)
                    .withMasterKeyUri(KEYSTORE_PATH_URI + masterKeyAlias)
                    .build();
            aead = ksm.getKeysetHandle().getPrimitive(Aead.class);
            ready = true;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            String uuid = prefs.getString(App.PREF_UUID, null);
            if (BuildConfig.DEBUG && uuid == null) throw new RuntimeException("A UUID should have been generated in App.onCreate()");
            associatedData = uuid != null ? uuid.getBytes(StandardCharsets.UTF_8) : null;
        } catch (KeyStoreException kse) {
            if (BuildConfig.DEBUG) Log.e(TAG, kse.toString());
            ready = false;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
            throw new EncryptionFailedException(e);
        }
        this.aead = aead;
        this.associatedData = associatedData;
        this.ready = ready;
    }

    public byte[] decrypt(@NonNull byte[] data) throws GeneralSecurityException {
        if (!ready) throw new GeneralSecurityException(NOT_READY_TO_DECRYPT);
        return aead.decrypt(data, associatedData);
    }

    public byte[] encrypt(@NonNull byte[] data) throws GeneralSecurityException {
        if (!ready) throw new GeneralSecurityException(NOT_READY_TO_ENCRYPT);
        return aead.encrypt(data, associatedData);
    }

    public static class EncryptionFailedException extends Exception {

        private EncryptionFailedException(Throwable why) {
            super(why);
        }
    }
}


/*
 * MasterKeys.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.auth;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.util.Arrays;

import javax.crypto.KeyGenerator;

/**
 * See androidx.security.crypto.MasterKeys from androidx.security:security-crypto:1.1.0-alpha01<br>
 * <a href="https://android-developers.googleblog.com/2020/02/data-encryption-on-android-with-jetpack.html">Guchelblock</a>
 */
@RequiresApi(Build.VERSION_CODES.M)
final class MasterKeys {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int KEY_SIZE = 256;
    /** the key alias to use */
    private static final String MASTER_KEY_ALIAS = "_cellar_eh_master_key_";
    @RequiresApi(Build.VERSION_CODES.M)
    private static KeyGenParameterSpec keyGenParameterSpec = null;

    /**
     * Creates a KenGenParameterSpec using
     * <ul>
     * <li>Algorithm: AES</li>
     * <li>Block Mode: GCM</li>
     * <li>Padding: None</li>
     * <li>Key Size: 256</li>
     * </ul>
     * According to <a href="https://android-developers.googleblog.com/2020/02/data-encryption-on-android-with-jetpack.html">this article</a>,
     * AES256-GCM is recommended for general use cases.<br>
     * @return The spec for the master key with the specified keyAlias
     * @throws NullPointerException if {@code keyAlias} is {@code null}
     */
    @NonNull
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressWarnings("SameParameterValue")
    private static KeyGenParameterSpec createAES256GCMKeyGenParameterSpec() {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE);
        return builder.build();
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static void generateKey(@NonNull KeyGenParameterSpec keyGenParameterSpec) throws GeneralSecurityException {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            keyGenerator.init(keyGenParameterSpec);
            keyGenerator.generateKey();
        } catch (ProviderException providerException) {
            throw new GeneralSecurityException(providerException.getMessage(), providerException);
        }
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.M)
    static KeyGenParameterSpec getAES256GCMKeyGenParameterSpec() {
        if (keyGenParameterSpec == null) {
            keyGenParameterSpec = createAES256GCMKeyGenParameterSpec();
        }
        return keyGenParameterSpec;
    }

    /**
     * Creates or gets the master key provided
     * The encryption scheme is required fields to ensure that the type of encryption used is clear to developers.
     * @param keyGenParameterSpec The key encryption scheme
     * @return The key alias for the master key
     */
    @NonNull
    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressWarnings("SameParameterValue")
    static String getOrCreateMasterKeyAlias(@NonNull KeyGenParameterSpec keyGenParameterSpec) throws GeneralSecurityException, IOException {
        validate(keyGenParameterSpec);
        String keystoreAlias = keyGenParameterSpec.getKeystoreAlias();
        if (!keyExists(keystoreAlias)) {
            generateKey(keyGenParameterSpec);
        }
        return keystoreAlias;
    }

    /**
     * Checks whether the given alias exists in the key store.
     * @param keyAlias alias to check
     * @return true / false
     * @throws GeneralSecurityException if the system chooses to do so in its infinite wisdom
     * @throws IOException if the system chooses to do so in its infinite wisdom
     */
    private static boolean keyExists(@NonNull String keyAlias) throws GeneralSecurityException, IOException {
        KeyStore androidKeyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        androidKeyStore.load(null);
        return androidKeyStore.containsAlias(keyAlias);
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static void validate(KeyGenParameterSpec spec) {
        if (spec.getKeySize() != KEY_SIZE) {
            throw new IllegalArgumentException("invalid key size, want " + KEY_SIZE + " bits got " + spec.getKeySize() + " bits");
        }
        if (!Arrays.equals(spec.getBlockModes(), new String[]{KeyProperties.BLOCK_MODE_GCM})) {
            throw new IllegalArgumentException("invalid block mode, want " + KeyProperties.BLOCK_MODE_GCM + " got " + Arrays.toString(spec.getBlockModes()));
        }
        if (spec.getPurposes() != (KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)) {
            throw new IllegalArgumentException("invalid purposes mode, want PURPOSE_ENCRYPT | PURPOSE_DECRYPT got " + spec.getPurposes());
        }
        if (!Arrays.equals(spec.getEncryptionPaddings(), new String[] {KeyProperties.ENCRYPTION_PADDING_NONE})) {
            throw new IllegalArgumentException("invalid padding mode, want " + KeyProperties.ENCRYPTION_PADDING_NONE + " got " + Arrays.toString(spec.getEncryptionPaddings()));
        }
        if (spec.isUserAuthenticationRequired() && spec.getUserAuthenticationValidityDurationSeconds() < 1) {
            throw new IllegalArgumentException("per-operation authentication is not supported " + "(UserAuthenticationValidityDurationSeconds must be >0)");
        }
    }

}

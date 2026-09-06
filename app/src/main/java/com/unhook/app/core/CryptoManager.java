package com.unhook.app.core;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

/**
 * All cryptography in Unhook lives here, so it can be audited in one place.
 *
 * DESIGN (privacy pillar P1 + your "cryptographic integration" requirement):
 *
 * 1. DATABASE AT REST
 *    - A random 256-bit passphrase (SecureRandom) is generated on first run.
 *    - SQLCipher encrypts the whole Room DB with AES-256.
 *    - The passphrase is wrapped (encrypted) with an AES-256-GCM key that
 *      never leaves AndroidKeyStore. Only the ciphertext + IV are stored
 *      in private prefs. GCM's auth tag also gives tamper detection for free.
 *    - Deleting the Keystore alias (Erase Everything) makes the DB file
 *      cryptographically unrecoverable, not just deleted.
 *
 * 2. INTEGRITY OF STORED DATA (HMAC-SHA256)
 *    - Model weights and daily aggregates are covered by an HMAC-SHA256 key
 *      that also lives in AndroidKeyStore. On load we verify; on mismatch we
 *      treat the data as tampered and reset it (defensive, silent, honest).
 *    - Comparisons use MessageDigest.isEqual (constant-time).
 *
 * 3. SELF-INTEGRITY (TOFU)
 *    - On first run we record SHA-256 of our own signing certificate
 *      ("trust on first use"). Later runs verify: a repackaged/resigned APK
 *      with preserved data is flagged in the UI.
 *
 * 4. WHY NOT androidx.security:security-crypto?
 *    - Google deprecated it in 2024 (1.1.0-alpha07 release notes). We refuse
 *      to pin the privacy story to a deprecated artifact: everything above is
 *      ~150 auditable lines of platform APIs.
 */
public final class CryptoManager {

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    private final Context appContext;
    private final SharedPreferences prefs;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile byte[] dbPassphraseCache;

    public CryptoManager(@NonNull Context appContext) {
        this.appContext = appContext.getApplicationContext();
        this.prefs = this.appContext.getSharedPreferences(
                Constants.SECURE_PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 1. Database passphrase (wrapped by AndroidKeyStore AES-256-GCM)
    // ------------------------------------------------------------------

    /** @return the (cached) 32-byte SQLCipher passphrase, creating it on first use. */
    public synchronized byte[] getDbPassphrase() {
        if (dbPassphraseCache != null) {
            return dbPassphraseCache;
        }
        String wrappedB64 = prefs.getString(Constants.PREF_WRAPPED_DB_KEY, null);
        String ivB64 = prefs.getString(Constants.PREF_DB_KEY_IV, null);

        if (wrappedB64 == null || ivB64 == null) {
            byte[] fresh = new byte[32];
            secureRandom.nextBytes(fresh);
            wrapAndStore(fresh);
            dbPassphraseCache = fresh;
            return fresh;
        }

        byte[] wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP);
        byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
        byte[] plain = unwrap(wrapped, iv); // GCM tag verified here
        dbPassphraseCache = plain;
        return plain;
    }

    private void wrapAndStore(byte[] plain) {
        try {
            SecretKey key = getOrCreateWrapKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key); // fresh random IV
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plain);
            prefs.edit()
                    .putString(Constants.PREF_WRAPPED_DB_KEY,
                            Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(Constants.PREF_DB_KEY_IV,
                            Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Keystore wrap failed", e);
        }
    }

    private byte[] unwrap(byte[] wrapped, byte[] iv) {
        try {
            SecretKey key = getOrCreateWrapKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return cipher.doFinal(wrapped); // throws if tampered
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DB key unwrap failed (Keystore reset?). Data must be erased.", e);
        }
    }

    private SecretKey getOrCreateWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEY_STORE);
        ks.load(null);
        java.security.Key existing = ks.getKey(Constants.KS_DB_WRAP_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        kg.init(new KeyGenParameterSpec.Builder(
                Constants.KS_DB_WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    // ------------------------------------------------------------------
    // 2. HMAC-SHA256 integrity of stored data
    // ------------------------------------------------------------------

    /** Fresh Mac over the Keystore-resident HMAC key. */
    public Mac hmac() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEY_STORE);
            ks.load(null);
            java.security.Key existing = ks.getKey(Constants.KS_HMAC_ALIAS, null);
            if (!(existing instanceof SecretKey)) {
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE);
                kg.init(new KeyGenParameterSpec.Builder(
                        Constants.KS_HMAC_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
                existing = kg.generateKey();
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init((SecretKey) existing);
            return mac;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC key unavailable", e);
        }
    }

    public byte[] hmacBytes(byte[] data) {
        return hmac().doFinal(data);
    }

    public String hmacHex(byte[] data) {
        return toHex(hmacBytes(data));
    }

    /** Constant-time verify (MessageDigest.isEqual). */
    public boolean verify(byte[] data, byte[] expectedTag) {
        if (data == null || expectedTag == null) {
            return false;
        }
        return MessageDigest.isEqual(hmacBytes(data), expectedTag);
    }

    // ------------------------------------------------------------------
    // 3. Self-integrity (TOFU signing-certificate digest)
    // ------------------------------------------------------------------

    /** @return lowercase hex SHA-256 of our own signing certificate. */
    public String selfSigningCertDigest() {
        try {
            PackageManager pm = appContext.getPackageManager();
            String pkg = appContext.getPackageName();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sigs[0].toByteArray());
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read own signature", e);
        }
    }

    /** Store/retrieve the TOFU digest (a public value; plain prefs are fine). */
    public String getStoredCertDigest() {
        return prefs.getString(Constants.PREF_SELF_CERT_DIGEST, null);
    }

    public void storeCertDigest(String hexDigest) {
        prefs.edit().putString(Constants.PREF_SELF_CERT_DIGEST, hexDigest).apply();
    }

    // ------------------------------------------------------------------
    // 4. Wipe (Erase Everything): destroy keys so data is unrecoverable
    // ------------------------------------------------------------------

    public void wipeAll() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEY_STORE);
            ks.load(null);
            ks.deleteEntry(Constants.KS_DB_WRAP_ALIAS);
            ks.deleteEntry(Constants.KS_HMAC_ALIAS);
        } catch (Exception ignored) {
            // Deleting a non-existent alias is not an error worth crashing for.
        }
        prefs.edit().clear().commit(); // commit(): synchronous by design during erase
        dbPassphraseCache = null;
    }

    // ------------------------------------------------------------------

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }
}

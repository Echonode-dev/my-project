package com.unhook.app.core;

import android.content.Context;

import androidx.annotation.NonNull;

import com.unhook.app.ml.FeatureExtractor;

/**
 * Runtime security self-checks, surfaced honestly in the UI.
 *
 * CHECK 1 — app integrity (TOFU): first run records our signing-cert digest;
 * every later run compares. A resigned/repackaged APK on a device that kept
 * our data is flagged on the dashboard. (Limits, documented in SECURITY.md:
 * a full wipe of app data resets the TOFU baseline; this defends the common
 * "repackage over existing install" case, not a forensic attacker.)
 *
 * CHECK 2 — data integrity spot-check: recent daily aggregates and the ML
 * model weights carry HMAC-SHA256 tags. Any mismatch means on-device
 * tampering; affected data is treated as untrusted.
 */
public final class SecurityChecks {

    public enum SelfIntegrity { FIRST_RUN, OK, TAMPERED }

    private SecurityChecks() {
    }

    public static SelfIntegrity verifySelfIntegrity(
            @NonNull Context appContext, @NonNull CryptoManager crypto) {
        String actual = crypto.selfSigningCertDigest();
        String stored = crypto.getStoredCertDigest();
        if (stored == null) {
            crypto.storeCertDigest(actual);
            return SelfIntegrity.FIRST_RUN;
        }
        return constantTimeEquals(stored, actual)
                ? SelfIntegrity.OK : SelfIntegrity.TAMPERED;
    }

    /**
     * Spot-checks HMACs of the last few daily aggregates + model weights.
     * Implemented against the DAO by the caller (needs a background thread);
     * returns true when nothing was flagged. Failures are also recorded in
     * settings_kv as "integrity_flag" so the dashboard can show one banner.
     */
    public interface IntegrityCallback {
        void onResult(boolean ok, String details);
    }

    /** Verify a weights blob + tag pair produced by the ML layer. */
    public static boolean verifyWeightsBlob(
            @NonNull byte[] blob, String tagHex, @NonNull CryptoManager crypto) {
        if (tagHex == null) {
            return false;
        }
        return crypto.verify(blob, fromHex(tagHex));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    public static byte[] fromHex(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Utility kept beside the checks it protects: safe feature-blob size guard. */
    public static boolean validFeatureBlob(byte[] blob) {
        return blob != null
                && blob.length == FeatureExtractor.DIM * 8; // doubles, little/big endian per extractor
    }
}

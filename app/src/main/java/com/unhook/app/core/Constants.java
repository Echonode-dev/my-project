package com.unhook.app.core;

/**
 * Global, immutable constants. Anything mutable lives in Room or the
 * private prefs file managed by CryptoManager — never here.
 */
public final class Constants {

    /** Encrypted SQLite file name (opened through SQLCipher's SupportFactory). */
    public static final String DB_NAME = "unhook.db";

    /** Private prefs file. Stores ONLY Keystore-wrapped blobs (ciphertext),
     *  never plaintext secrets — see CryptoManager. */
    public static final String SECURE_PREFS = "unhook_secure";

    /** Rolling retention window for raw usage events, in days (P1 data minimisation). */
    public static final int RAW_EVENT_RETENTION_DAYS = 90;

    /** Feedback retention, in days. */
    public static final int FEEDBACK_RETENTION_DAYS = 180;

    /** Max daily notification budget (P2: no streak-bait, no re-engagement spam). */
    public static final int MAX_NOTIFICATIONS_PER_DAY = 1;

    // --- CryptoManager prefs keys (values are ciphertext, IVs, public digests) ---
    public static final String PREF_WRAPPED_DB_KEY = "wrapped_db_key";
    public static final String PREF_DB_KEY_IV = "db_key_iv";
    public static final String PREF_SELF_CERT_DIGEST = "self_cert_digest";

    // --- AndroidKeyStore aliases ---
    public static final String KS_DB_WRAP_ALIAS = "unhook_db_wrap";
    public static final String KS_HMAC_ALIAS = "unhook_hmac";

    // --- Coaching timeline (P2: one change at a time) ---
    public static final int BASELINE_DAYS = 3;      // days 1-3: observe only
    public static final int GENTLE_DAYS = 11;       // day 4+: interstitial on top app
    public static final int STEADY_DAYS = 18;       // week 2+: all trigger apps

    /** Bounce threshold: trigger-app session shorter than this = likely compulsive. */
    public static final long BOUNCE_MS = 10_000L;

    private Constants() {
        throw new AssertionError("No instances.");
    }
}

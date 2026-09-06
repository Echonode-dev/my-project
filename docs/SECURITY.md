# Unhook — Security & Cryptography

This document is the audit trail for Unhook's security posture. Everything
here is implemented in `com.unhook.app.core.CryptoManager`, `SecurityChecks`,
and `data.UnhookRepository` — read those files to verify every claim.

## 1. Threat model (honest scope)

| Attacker | Covered? | Notes |
|---|---|---|
| Network attacker | out of scope by construction | there is no INTERNET permission; no code performs network I/O; no SDK exists in the app that could |
| Another app on the device | covered | Android sandbox; all data in app-private storage; DB encrypted; keys in hardware Keystore |
| Backup-based exfiltration | covered | `allowBackup=false`, cloud backup + device-transfer exclude everything |
| Repackaged APK over existing install | covered (common case) | TOFU signing-cert digest check flags a different signer on next launch |
| Root attacker with the unlocked device | partially | root can read files but not extract hardware-Keystore-wrapped keys; keyed data stays confidential, but a root user can interact with the device as the user — no app can fully defend here |
| Forensic disk analysis after Erase Everything | covered | deleting the Keystore alias makes the DB cryptographically unrecoverable |

## 2. Cryptographic inventory

| Asset | Mechanism | Where |
|---|---|---|
| Database at rest | SQLCipher AES-256 (Room via `SupportFactory`) | `UnhookDb` |
| DB passphrase | 32 random bytes (SecureRandom), wrapped by AES-256-GCM AndroidKeyStore key; only ciphertext + IV stored | `CryptoManager.getDbPassphrase()` |
| Integrity of ML weights | HMAC-SHA256 (Keystore-resident key) over weights+bias+count; constant-time compare; mismatch → model resets | `LogisticRegressionModel.loadFrom/persist` |
| Integrity of daily aggregates | HMAC-SHA256 per (date, pkg) row; dashboard spot-checks last 7 days | `UnhookRepository.saveAggregateTagged / corruptedAggregateCount` |
| App self-integrity | SHA-256 of own signing cert, trust-on-first-use | `SecurityChecks.verifySelfIntegrity` |
| GCM auth tags | wrap/unwrap of DB passphrase detects ciphertext tampering | `CryptoManager.wrapAndStore/unwrap` |

Why we hand-rolled instead of using `androidx.security:security-crypto`:
Google deprecated that artifact in 2024 (1.1.0-alpha07 release notes). The
entire replacement is ~150 lines of platform APIs — auditable, no dependency.

## 3. Runtime security checks (visible in the UI)

1. **Security chip** on the dashboard: `Security: verified • on-device only`,
   or an amber warning when self-integrity, aggregate HMACs, or model HMACs
   fail. Failures never crash the app — they degrade honestly.
2. **Permission state checks**: the detector verifies Usage access each
   sweep (`AppOpsManager`) and stops cleanly when revoked; the service will
   not start without it.
3. **Onboarding integrity step**: the TOFU check runs before "Start Unhook".

## 4. Erase Everything — the cryptographic kill switch

`privacy/Eraser.erase()`:
1. stop `DetectionService`, cancel all WorkManager jobs
2. close Room, delete `unhook.db`, `-wal`, `-shm`
3. reset in-memory singletons
4. `CryptoManager.wipeAll()`: delete both Keystore aliases + clear prefs.

After step 4 the deleted DB file cannot be decrypted by anyone — the AES-GCM
key that protected it no longer exists. There is no server copy to erase.

## 5. Known limitations (we publish these)

- TOFU baseline resets if app data is cleared; it defends the common
  repackage case, not a stateful forensic attacker.
- Root users can do anything the user can; hardware keys resist extraction,
  not interactive misuse.
- SQLCipher page-level tampering *within* a session is bounded by HMACs on
  derived artifacts (weights, aggregates), not every raw row — raw rows are
  still AES-encrypted at rest.

# Unhook — Android digital wellbeing, privacy-first

Unhook interrupts the automatic "open Instagram again" habit loop, learns your
personal patterns on-device, and coaches one small change at a time.

**Two pillars, enforced architecturally:**

1. **Privacy** — 100% on-device. No `INTERNET` permission. No accounts. No
   analytics, ads, Firebase, or crash uploaders. Encrypted local storage.
   One-tap Erase Everything.
2. **Screen-time reduction — including our own app** — usable in under
   15 seconds a day. No feeds, no streaks, no dark patterns, max 1
   notification per day.

Java 8 only (no Kotlin). minSdk 24, targetSdk 35. AGP 8.7.3, Gradle 8.9.

## Milestones

- [x] **M1 — architecture skeleton + Gradle**
- [x] **M2 — permission & privacy onboarding (why-screens)**
- [x] **M3 — foreground-app detection service**
- [x] **M4 — interstitial overlay + timing logic**
- [x] **M5 — Room data layer (SQLCipher-encrypted)**
- [x] **M6 — on-device incremental ML (train/predict)**
- [x] **M7 — coach + weekly insight report**
- [x] **M8 — dashboard with charts**
- [x] **M9 — encryption hardening + Erase Everything (+ cryptographic integrity)**
- [x] **M10 — Play compliance pack (manifest declarations, docs)**

**Security & cryptography:** see `docs/SECURITY.md` — Keystore-wrapped
AES-256-GCM DB key, HMAC-SHA256 integrity on model weights and aggregates,
TOFU self-integrity check, constant-time verification, cryptographically
irreversible Erase Everything.

## Requirements

- Android Studio Ladybug (2024.2.1) or newer
- **JDK 17 to run Gradle** (AGP 8.7 requirement — this only affects the build
  toolchain; app source stays pinned to Java 8 via `compileOptions`)
- Android SDK Platform 35

## Build & run

1. Unzip and open the `unhook` folder in Android Studio.
2. Let Gradle sync (distribution downloads automatically via the committed
   wrapper). If your tooling stripped the wrapper jar, run
   `gradle wrapper --gradle-version 8.9` once.
3. Run on a device or emulator with API 24+. M1 shows a status screen only —
   tracking is off and no permission is requested yet.

## Verify the privacy claim yourself

- Merged manifest contains no `android.permission.INTERNET`:
  `adb shell dumpsys package com.unhook.app | grep -i internet` → nothing.
- Airplane-mode test: the app behaves identically.
- Cloud backup and device-to-device transfer are disabled via
  `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`.

## Where to read next

- `docs/ARCHITECTURE.md` — every decision and its reasoning
- `docs/MILESTONE-01.md` — what shipped, how to test, pitfalls, what's next

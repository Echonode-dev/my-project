# Milestone 1 — Architecture skeleton, folder structure, Gradle

## What you just built

- A compilable, installable Android skeleton: Java 8 only, minSdk 24,
  targetSdk 35, AGP 8.7.3 / Gradle 8.9, desugaring enabled so `java.time`
  (a Java 8 API) works on Android 7.x.
- The full package tree with the two core contracts already in code:
  `ForegroundAppDetector` (implemented M3) and `MindfulnessModel`
  (implemented M6) — future milestones slot into interfaces, not vibes.
- A manifest with every future permission pre-declared and commented, plus
  privacy-first backup rules (`allowBackup=false`; cloud backup and
  device-transfer exclude everything).
- Threading hub (`AppExecutors`) with a dedicated single-thread ML executor —
  ordered, race-free weight updates from day one.
- The two headline decisions documented with evidence:
  **UsageStatsManager over AccessibilityService** (policy risk + privacy
  posture) and **pure-Java incremental logistic regression over LiteRT**
  (true online learning, zero deps, fully auditable).
- Honest limits written down: a normal app cannot hard-block other apps
  (DevicePolicyManager requires device-owner), and per-app grayscale has no
  clean path — see ARCHITECTURE.md §5–6 for the legal alternatives we ship.

## How to test on a real device

1. Open the project in Android Studio, let it sync, run on any API 24+ device.
2. You should see the baseline status screen with the version name.
3. Open Settings → Apps → Unhook → App permissions: the list must be empty.
4. `adb shell dumpsys package com.unhook.app | grep -i internet` → no output.
5. Toggle airplane mode and use the app: no behaviour change (it cannot call
   out even in principle).

## Common pitfalls

- **Gradle wants JDK 17, app code is Java 8.** Both are true and not in
  conflict: JDK 17 only runs the build toolchain; `compileOptions` pins the
  produced bytecode to Java 8. Set Gradle JDK in Studio → Settings → Build
  Tools → Gradle.
- **compileSdk 35 needs AGP 8.6+.** This project pins AGP 8.7.3 — do not
  downgrade without also downgrading compileSdk.
- **Room uses `annotationProcessor`**, not kapt — there is no Kotlin in this
  project by hard constraint.
- **`applicationId` is still a placeholder** (`com.unhook.app`). It cannot be
  changed after the first Play upload — decide before M10.
- The launcher icon is a placeholder vector; proper adaptive icons arrive in
  M8.
- Some OEM emulators/image combos lack usage-stats support — test M3 on real
  hardware.

## What "next" covers (Milestone 2)

Permission & privacy onboarding: the plain-English "why we need this" screens
for Usage access, Display-over-other-apps and Notifications (shown BEFORE each
system dialog), the AppOps/Settings-Intent plumbing in Java 8, live
revocation detection, and the 15-seconds-a-day copy rules for onboarding.

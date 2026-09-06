# Unhook — Architecture Overview (Milestone 1, v0.1)

Unhook is an Android-only digital-wellbeing app that interrupts the automatic
"open Instagram again" habit loop, learns the user's personal patterns with an
on-device ML model, and coaches one small change at a time. This document
records the architectural decisions made in Milestone 1 so every later
milestone can be judged against them.

---

## 0. Product pillars → engineering principles

| Pillar | Hard rule | Architectural consequence |
|---|---|---|
| **P1 — Privacy** | 100% on-device. Zero network. No analytics/Firebase/ads/crash upload. Encrypted local storage. No accounts. | No `INTERNET` permission exists in the manifest, no networking code exists, no third-party SDK is linked. The whole app is auditable by reading ~15 small files. Cloud backup and device-transfer are disabled via backup rules. |
| **P2 — Screen-time reduction (including ours)** | Unhook must be usable in <15 s/day. No feeds, no streaks, no dark patterns. | Dashboard is one screen. Max 1 notification/day (hard-coded budget). Every interruption offers a real, respectful exit ("Not now" always works and is never punished). |

Anything that cannot be justified against both pillars gets cut, no matter how
clever it is.

---

## 1. System overview

```
        ┌────────────────────────────  Android OS  ───────────────────────────┐
        │  UsageStatsManager   WindowManager(SAW)   AppOps   WorkManager      │
        └──────────▲────────────────────▲───────────────▲──────────▲──────────┘
                   │ usage events       │ overlay       │ appops   │ jobs
┌──────────────────┴────────────────────┴───────────────┴──────────▼──────────┐
│                        Unhook — single process, pure Java 8                  │
│                                                                              │
│  (1) DETECTION          (2) DECISION             (3) INTERCEPTION            │
│  DetectionService  →    PolicyEngine         →   InterceptionController      │
│  (ForegroundAppDetector)  │   ▲                  (overlay + Interstitial)    │
│        │                  │   │                            │                  │
│        ▼                  ▼   │                            ▼                  │
│  Room (SQLCipher) ◄── FeatureExtractor ──► MindfulnessModel (ML, M6)         │
│        │                                        │                            │
│        └──────────────►  CoachEngine  ◄─────────┘                            │
│                             │                                                │
│                 (4) UI: Dashboard · Onboarding · Interstitial                │
└──────────────────────────────────────────────────────────────────────────────┘
```

**One Instagram open, end to end:**

1. The user taps Instagram. Android records `ACTIVITY_RESUMED` into UsageEvents.
2. `DetectionService` (M3, foreground service) polls `UsageEvents.queryEvents()`
   roughly once per second while the screen is on, dedupes, and emits
   `AppOpened("com.instagram.android", t)` through `ForegroundAppDetector`.
3. `PolicyEngine` (M4) checks: is this a trigger app? which mode? is the daily
   limit spent? is a scheduled block active? It also asks `MindfulnessModel`
   for `P(compulsive | features)`.
4. If the policy says *interrupt*: `InterceptionController` draws a
   `TYPE_APPLICATION_OVERLAY` cover within ~200 ms, then starts the full-screen
   `InterstitialActivity`. Holding `SYSTEM_ALERT_WINDOW` is the documented
   exemption that permits this background activity launch on API 29+.
5. The interstitial runs a 5–8 s breathing animation and asks one contextual
   question ("You open Instagram ~14x/day, usually around 11 pm when you're
   tired. Still want in?"). `[Yes]` proceeds; `[Not now]` closes and emits a
   strong *compulsive-open* label to the model.
6. Everything writes to the encrypted Room DB (M5); the single-threaded ML
   executor applies one SGD update per label (M6); `CoachEngine` (M7) turns the
   aggregates into one concrete suggestion per week.

---

## 2. Package tree and milestone map

```
unhook/
├── settings.gradle · build.gradle · gradle.properties          M1  Gradle wiring
├── gradlew · gradle/wrapper/*                                  M1  pinned Gradle 8.9
├── docs/ARCHITECTURE.md · docs/MILESTONE-*.md                  M1  decisions + logs
└── app/src/main/
    ├── AndroidManifest.xml                                     M1  permissions + components
    ├── java/com/unhook/app/
    │   ├── UnhookApplication.java                              M1  process singletons
    │   ├── core/        AppExecutors · Constants               M1  threading, constants
    │   ├── detection/   ForegroundAppDetector (contract)       M3  + UsageStatsDetector,
    │   │                                                            DetectionService (FGS)
    │   ├── policy/      PolicyEngine · AppRule                 M4  per-app modes
    │   ├── overlay/     InterceptionController                 M4  SAW overlay choreography
    │   ├── interstitial/InterstitialActivity + breathing view  M4  5–8 s interrupt
    │   ├── data/        Room entities · DAOs · UnhookDb        M5  encrypted data layer
    │   ├── ml/          FeatureVector · MindfulnessModel       M6  + LogRegModel,
    │   │                                                            FeatureExtractor
    │   ├── coach/       CoachEngine · InsightReport            M7  staged coaching
    │   ├── ui/          MainActivity (M1 placeholder)          M8  dashboard/,
    │   │                                                            onboarding/ (M2)
    │   └── privacy/     KeystoreDbKey · EraseEverything        M9  encryption + wipe
    └── res/             values/ · drawable/ · xml/ (backup rules)
```

---

## 3. Decision A — foreground-app detection (guidance #1)

| Criterion | **UsageStatsManager + UsageEvents** (chosen) | AccessibilityService (rejected) |
|---|---|---|
| Latency | ~0.5–1.5 s (poll cadence) | instant callbacks |
| Battery | low: loop sleeps when screen is off; a `queryEvents` sweep is cheap | moderate–high: a callback fires for every window change (keyboards, dialogs, shade) |
| OEM robustness | some skins batch events (seconds, not minutes) | robust, but some OEMs silently disable accessibility after OS updates |
| User setup | "Usage access" special-access screen (2 taps) | accessibility settings + double system warnings |
| Play policy | mainstream for wellbeing apps (ActionDash et al.) | Accessibility API policy: permitted-use declaration + review; blockers historically removed — unacceptable risk |
| Privacy posture | app names + timestamps only | can observe window content — contradicts P1 |
| Android 13+ | — | "restricted settings" friction for sideloaded apps; disclosure forms |

**Verdict:** `UsageStatsManager` for everything in v1. We never ship an
AccessibilityService. Implementation notes for M3:

- Loop: query `UsageEvents.queryEvents(begin, now)` since the last seen
  timestamp; forward-only cursor; dedup consecutive `ACTIVITY_RESUMED` of the
  same package (the system double-fires on cold starts).
- Battery gate: a receiver watches `ACTION_SCREEN_ON/OFF`; the loop sleeps
  with the screen. Target budget: <2%/day, measured in M3 on a real device.
- Permission check each cycle via
  `AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`: if the user
  revokes "Usage access", we stop cleanly and surface a single honest notice.
- API note: `ACTIVITY_RESUMED` (API 29 name) carries the same value as the
  pre-29 `MOVE_TO_FOREGROUND`; code handles both names.

### Android version landmines (the ones that will bite)

| API | Change | Our handling |
|---|---|---|
| 26 | legacy overlay types (`TYPE_PHONE`, …) throw at runtime | only `TYPE_APPLICATION_OVERLAY` (M4) |
| 29 | background activity starts blocked | we hold `SYSTEM_ALERT_WINDOW` → documented exemption; plus overlay-only fallback if a vendor still blocks |
| 29 | UsageEvents constant rename | handle both names |
| 30 | package visibility filtering | `<queries>` launcher intent already in the manifest |
| 31 | PendingIntents need explicit mutability | `FLAG_IMMUTABLE` everywhere (M7) |
| 31 | `dataExtractionRules` replace backup rules | both declared |
| 33 | `POST_NOTIFICATIONS` becomes a runtime permission | requested in M2 with a why-screen; FGS runs even if denied |
| 34 | FGS must declare a type; `specialUse` needs a Play declaration | `foregroundServiceType="specialUse"` + Play form in M10 |
| 35 | edge-to-edge enforced for targetSdk 35 | insets handling in M4/M8 layouts |
| 15 | Private Space: apps inside may not appear in usage events | detect and degrade honestly (documented limitation) |

---

## 4. Decision B — the on-device ML model (guidance #5 preview)

| | **Pure-Java incremental logistic regression** (chosen) | LiteRT / TFLite |
|---|---|---|
| Dependency footprint | zero (hand-written, ~150 lines) | 1.5–4 MB native libs per ABI |
| Incremental training | native fit: one SGD step per labelled event | not designed for online training; you must hand-build an optimizer graph — awkward and easy to get wrong |
| Auditability (P1) | every line reviewable by the user | opaque binary model |
| Capacity | linear + feature engineering: enough for our ~30–40 dims | needed only for sequence models later |
| Java 8 friction | none | tensors/JNI are clumsy in Java 8 |

**Verdict:** pure-Java logistic regression, trained incrementally with SGD,
L2 regularisation and class weighting (compulsive labels are rarer).
`MindfulnessModel` (already in the skeleton) is the seam — a TFLite model can
replace it later without touching callers.

**Label sources (M6):** `[Not now]` at the interstitial = strong compulsive;
session bounce <10 s = weak compulsive; completed intended session =
intentional; nightly sampled pulse "was this open worth it?" = explicit user
label. **Features (M6):** hour (sin/cos), weekday, minutes since last unlock,
unlocks today, opens of this app today + 7-day average, mean session length,
previous-app continuity, battery level, charging state, screen-on minutes
today, night-window and work-hours flags.

**Explainability:** the coach shows the top-3 signed feature contributions
("usually 11pm when you're tired") — logistic regression gives this for free.

**Storage:** weights live as a blob in the encrypted Room DB (M5 schema), never
in world-readable files; the DB key is wrapped by AndroidKeyStore (M9).

---

## 5. Interception design (M4 preview)

Choreography for every interruption:

1. **Cover first (~200 ms):** a non-focusable `TYPE_APPLICATION_OVERLAY`
   window hides the trigger app's first frames. Non-focusable = we never
   steal input focus; it is a curtain, not a hijack.
2. **Interstitial second:** the focusable, full-screen
   `InterstitialActivity` starts with `FLAG_ACTIVITY_NEW_TASK`, no transition
   animation. Allowed from the background precisely because the app holds
   `SYSTEM_ALERT_WINDOW` (API 29+ background-activity-start exemption).
3. **Breathing 5–8 s + one question** with `[Yes]` / `[Not now]`.
4. **Always a real exit.** "Not now" is one tap, always available, never
   punished. If the user relapses, the coach responds with a smaller goal
   (P2: no guilt-trips, no streak shaming).

Per-app modes and what they honestly do:

| Mode (user-facing) | What actually happens |
|---|---|
| Interstitial only | breathing + question; `[Yes]` continues into the app |
| Hard limit (N min/day) | usage is accounted from Room; at the limit the wall screen appears with a same-day "I really need it" escape that the coach logs as a relapse signal |
| Scheduled block (e.g. after 10 pm) | same wall during the configured window |
| Grayscale launch | interstitial shows a desaturated preview + gray badge; optional "true grayscale" is an advanced opt-in (see §6) |

Unhook is **friction, not jail**: we protect the user from *automatic* habits,
not from deliberate decisions. This is also what keeps us inside Play policy.

---

## 6. What a normal (non-device-owner) app can and cannot block (guidance #3)

Said plainly: **a Play-distributed app cannot hard-block another app.**
`DevicePolicyManager` powers (`setApplicationHidden`, `setPackagesSuspended`,
Lock Task, user restrictions) require *device-owner* or *profile-owner*, which
needs adb or QR provisioning at device setup — not available to a normal
install. Any app claiming otherwise is using AccessibilityService and carrying
removal risk.

| Mechanism | Available to a normal app? | Policy risk | Verdict |
|---|---|---|---|
| Usage accounting + friction wall (overlay/interstitial) | yes | low | **core approach** |
| Relaunch-redirect loops (ram the target app closed) | yes, but janky | medium (user-hostile) | rejected — dark-pattern adjacent |
| DPM hidden/suspended apps | device-owner only | n/a for Play distribution | impossible — documented honestly |
| AccessibilityService window watching | yes | high (policy + review) | rejected |
| `WRITE_SECURE_SETTINGS` (one-time adb grant) toggling system grayscale | yes | low | optional advanced opt-in only |

**Grayscale reality:** per-app grayscale of *another* app is not cleanly
possible without accessibility or root. v1 ships the interstitial
desaturated-preview approach; an optional advanced mode can toggle the system
daltonizer (grayscale) via an adb-granted `WRITE_SECURE_SETTINGS`, with the
honest caveat that it affects the whole screen.

---

## 7. Data layer plan (M5) and encryption (M9)

Entities (Room, all in the encrypted DB):

| Entity | Purpose | Key fields |
|---|---|---|
| `UsageEvent` | raw foreground transitions | pkg, type, ts, sessionId |
| `Session` | aggregated open→close | pkg, start, end, duration, endReason |
| `FeedbackEvent` | labels for ML | pkg, ts, source (INTERSTITIAL/PULSE/IMPLICIT), label, probAtTime, featuresBlob |
| `ModelWeights` | LR weights + bias | version, dims, weightsBlob, trainedCount, updatedAt |
| `AppRule` | per-trigger-app configuration | pkg, mode, dailyLimitMin, blockStartMin, blockEndMin, enabled |
| `DailyAggregate` | per-day per-app rollups | date, pkg, foregroundMs, opens, longestSessionMs |
| `SuggestionLog` | coach suggestions + uptake | ts, suggestionId, accepted |
| `SettingsKv` | small settings | key, value |

**Retention:** raw events age out after 90 days (hard-coded, P1 data
minimisation); aggregates are tiny and persist.

**Encryption at rest (M9):** SQLCipher via Room's `SupportFactory`; a random
256-bit passphrase is generated once and wrapped by an AES-GCM key inside
AndroidKeyStore; the wrapped blob lives in private prefs. We hand-roll this
(~60 auditable lines) because `androidx.security:security-crypto` was
deprecated in 2024 — we will not pin our privacy story to a deprecated artifact.

**"Erase Everything" (M9):** stop services → cancel WorkManager jobs → close
Room → delete DB files → delete prefs and the Keystore-wrapped key → confirm
with a second step. No server to tell, because there is no server.

---

## 8. Threading, lifecycle, battery

- **Single process**, three executors: main, `unhook-disk-io` (Room), and
  `unhook-ml-trainer` (ordered weight updates).
- **DetectionService** (M3) is a foreground service with
  `foregroundServiceType="specialUse"` (API 34+ manifest requirement + runtime
  `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`; Play
  declaration form in M10).
- **Reboot:** `BOOT_COMPLETED` receiver re-arms the service.
- **Doze:** the FGS survives Doze, but by design our polling already pauses
  when the screen is off — we are Doze-friendly rather than Doze-fighting.
- **WorkManager** (M7): nightly aggregation + the weekly Insight Report; both
  purely on-device jobs.
- **Battery budget:** detection <2%/day target, verified with
  `adb shell dumpsys batterystats` in M3.

---

## 9. Play policy checkpoints

- **Data Safety:** nothing is collected or shared — everything stays on
  device. The listing says exactly that, and the absence of `INTERNET` makes
  the claim verifiable by anyone.
- **Permissions:** `PACKAGE_USAGE_STATS` (usage access) and
  `SYSTEM_ALERT_WINDOW` both need in-app declarations of core-functionality
  use; we pair each with the M2 why-screens.
- **FGS specialUse:** declaration form with subtype
  "digital-wellbeing usage monitor" (M10).
- **AccessibilityService:** not used anywhere — the single biggest policy risk
  in this category, avoided entirely.
- **No dark patterns:** the interstitial always offers a real exit; there are
  no streaks, no guilt copy, no re-engagement tricks.

---

## 10. Milestone roadmap

| # | Milestone | Ships |
|---|---|---|
| 1 | Architecture skeleton + Gradle (**this zip**) | compilable app, contracts, docs |
| 2 | Permission & privacy onboarding | why-screens, AppOps plumbing, revocation detection |
| 3 | Foreground detection | `UsageStatsDetector`, FGS, screen-state gating, battery test |
| 4 | Interstitial | overlay choreography, breathing UI, modes |
| 5 | Room data layer | schema above, SQLCipher wiring, DAOs |
| 6 | On-device ML | feature extractor, incremental LogReg, train/predict loop |
| 7 | Coach + Insight Report | staged coaching, weekly on-device report |
| 8 | Dashboard | charts, "Time Reclaimed", adaptive icons |
| 9 | Encryption + Erase Everything | Keystore key wrap, one-tap wipe |
| 10 | Play compliance | checklist, Data Safety answers, privacy policy text |

Every milestone ships as a zip that installs and runs. Nothing is "designed
now, maybe later".

---

## 11. Open risks

- **OEM task killers** may kill the FGS: we detect the kill and ask the user
  once, honestly, for the battery exemption. We never bypass silently.
- **Usage access revoked** mid-week: detected each cycle; app degrades to
  dashboard-only with a single notice.
- **Overlay revoked**: interstitial falls back to a notification tap-in.
- **Private Space (Android 15+)**: private apps may not appear in usage
  events — documented limitation, gracefully handled.
- **Play review subjectivity** for `specialUse` + SAW: mitigated by the M10
  declaration pack and the no-INTERNET posture.


# Milestone FINAL — complete build with security & cryptographic integrity

This release completes the original 10-milestone plan in one pass, with
security checks and cryptographic integrity integrated throughout.

## Deliverable map (your original list → where it lives)

| # | Deliverable | Implementation |
|---|---|---|
| 1 | Architecture + folder + Gradle | `docs/ARCHITECTURE.md`, `app/build.gradle` (Java 8 flags) |
| 2 | Permission & privacy onboarding | `onboarding/OnboardingActivity.java` — why-screen before every system dialog |
| 3 | Foreground detection | `detection/UsageStatsDetector`, `DetectionService` (FGS specialUse), `BootReceiver` |
| 4 | Interstitial + timing | `overlay/InterceptionController`, `interstitial/InterstitialActivity` + `BreathingView` (5.5 s) |
| 5 | Room data layer | `data/` — 8 entities, DAO, SQLCipher `UnhookDb` |
| 6 | Incremental ML | `ml/FeatureExtractor` (15 features), `LogisticRegressionModel` (SGD), train/predict loop wired in `detection/EventHandler` |
| 7 | Coach / insight report | `coach/CoachEngine` (stages, relapse softening, suggestions, weekly report) + `work/WeeklyReportWorker` |
| 8 | Dashboard | `ui/MainActivity` + `ui/BarChartView` — one screen, Time Reclaimed front and center |
| 9 | Encryption + Erase Everything | `core/CryptoManager`, `privacy/Eraser`, HMAC-verified weights & aggregates |
| 10 | Compliance | `docs/ARCHITECTURE.md` §9 + Play declarations embedded in manifest (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) |

## Security additions you asked for

- **AES-256-GCM Keystore-wrapped DB passphrase** (hardware-backed, GCM auth tag)
- **HMAC-SHA256 integrity tags** on ML weights and daily aggregates — tampered
  data is reset/flagged, never silently trusted
- **TOFU self-integrity check** (signing-cert digest) surfaced as a dashboard chip
- **Constant-time comparisons** for all tag verification
- **Cryptographic Erase Everything** — destroying the Keystore alias makes the
  deleted DB unrecoverable
- **Data minimisation** — 90-day raw retention enforced by the nightly worker
- Full write-up: `docs/SECURITY.md`

## How to test end-to-end on a real device (API 26+ recommended)

1. **Onboarding**: run the app → welcome → pick trigger apps (pick Instagram
   or Chrome) → grant Usage access → grant overlay → (API 33+) allow
   notifications → integrity chip shows green → Start.
2. **Baseline**: days 1–3 show no interruptions by design. To test faster:
   `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -n com.unhook.app/.detection.BootReceiver`
   won't change dates — instead temporarily set `Constants.BASELINE_DAYS = 0`
   and rebuild for a demo run.
3. **Interception**: open a trigger app → teal cover flashes → breathing
   screen ~5.5 s → contextual question ("usually around 11pm…") → [Not now]
   → suggestion card → [Take me home]. Open again → [Yes] → proceeds.
4. **ML**: bounce (open + leave <10 s) a few times; the dashboard coach card
   reflects training count via suggestions; interstitial copy uses your
   opens-today and modal-hour stats once enough data exists.
5. **Dashboard**: screen time, pickups, longest session, per-app bars,
   Time Reclaimed grows on each [Not now]. Weekly report line updates live.
6. **Security checks**: chip shows verified; tamper test (optional):
   modify a `daily_aggregates` row with
   `adb shell "run-as com.unhook.app ..."` on a debug build → dashboard shows
   the amber warning on next launch of the nightly worker + spot-check.
7. **Erase Everything**: two-step dialog → app wipes DB/model/keys →
   onboarding restarts; verify `databases/` is empty via
   `adb shell run-as com.unhook.app ls databases/`.

## Common pitfalls

- Interception is silent during days 1–3 — that is the coach contract, not a bug.
- Some OEMs batch UsageEvents (a 1–3 s detection delay is normal; if slower,
  check the OEM's battery settings for Unhook).
- POST_NOTIFICATIONS denied on API 33+ hides the FGS notification but
  monitoring continues — by design.
- `adb shell run-as` only works on debuggable builds; release builds verify
  via the dashboard chip instead.
- Weekly report fires only if the week had data; the 1/day budget is enforced.

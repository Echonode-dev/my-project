package com.unhook.app.detection;

/**
 * Contract for the component that reports which app is in the foreground.
 *
 * Milestone 3 implements this with UsageStatsManager + UsageEvents inside
 * a foreground service (see docs/ARCHITECTURE.md, Decision A).
 *
 * AccessibilityService is deliberately NOT used anywhere in Unhook:
 *   * Google Play's Accessibility API policy makes blocker-style use a
 *     removal risk that we will not take;
 *   * an accessibility service could read window content — a privacy
 *     posture we refuse on principle.
 */
public interface ForegroundAppDetector {

    interface Listener {
        /** A launchable activity of {@code packageName} came to the foreground. */
        void onAppOpened(String packageName, long timestampMs);

        /** Its last activity left the foreground / screen turned off. */
        void onAppClosed(String packageName, long timestampMs, long foregroundDurationMs);
    }

    /** Idempotent. Starts the event loop on the detector's own thread. */
    void start(Listener listener);

    /** Idempotent. Releases resources; safe to call from any thread. */
    void stop();

    /** @return true while the event loop is alive. */
    boolean isRunning();
}

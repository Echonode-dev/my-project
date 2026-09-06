package com.unhook.app.policy;

import androidx.annotation.NonNull;

import com.unhook.app.coach.CoachEngine;
import com.unhook.app.data.entity.AppRuleEntity;

/**
 * Decides what happens when a trigger app opens. Pure function — trivially
 * unit-testable, no Android dependencies.
 *
 * Rules (friction, not jail — ARCHITECTURE.md §5):
 *  - WALL  = limit/schedule reached → full-screen wall, escape always exists.
 *  - INTERSTITIAL = breathing pause + question. Fires when the model thinks
 *    this open is likely mindless (P >= 0.65) or the app's mode demands it.
 *  - GRAYSCALE = interstitial + desaturated preview.
 *  - NONE = record silently (always true during baseline days).
 */
public final class PolicyEngine {

    public enum Decision { NONE, INTERSTITIAL, GRAYSCALE, WALL }

    private PolicyEngine() {
    }

    public static Decision decide(@NonNull AppRuleEntity rule, int usedMinutes,
                                  double prob, @NonNull CoachEngine.Stage stage,
                                  int minuteOfDay) {
        if (stage == CoachEngine.Stage.BASELINE || !rule.enabled) {
            return Decision.NONE;
        }
        String mode = rule.mode == null ? "INTERSTITIAL" : rule.mode;

        if ("SCHEDULE".equals(mode)
                && minuteOfDay >= rule.blockStartMin
                && minuteOfDay <= rule.blockEndMin) {
            return Decision.WALL;
        }
        if ("LIMIT".equals(mode) && usedMinutes >= rule.dailyLimitMin) {
            return Decision.WALL;
        }

        boolean grayscale = "GRAYSCALE".equals(mode);
        if (prob >= 0.65) {
            return grayscale ? Decision.GRAYSCALE : Decision.INTERSTITIAL;
        }
        if ("INTERSTITIAL".equals(mode) && prob >= 0.35) {
            return Decision.INTERSTITIAL;
        }
        if (grayscale && prob >= 0.45) {
            return Decision.GRAYSCALE;
        }
        return Decision.NONE;
    }

    /** Short machine-readable reason, mapped to human copy by the UI layer. */
    public static String reasonFor(@NonNull AppRuleEntity rule,
                                   @NonNull Decision decision, int minuteOfDay) {
        switch (decision) {
            case WALL:
                if ("SCHEDULE".equals(rule.mode)) {
                    return "SCHEDULE";
                }
                return "LIMIT";
            case GRAYSCALE:
                return "GRAYSCALE";
            case INTERSTITIAL:
            default:
                return "ML";
        }
    }
}

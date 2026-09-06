package com.unhook.app.ml;

import android.content.Context;

import com.unhook.app.data.UnhookDao;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.data.entity.UsageEventEntity;
import com.unhook.app.detection.EventHandler;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the live feature vector for one app open (M6 schema, 15 dims).
 * Pure, deterministic, documented — the model must never see features it
 * cannot explain back to the user.
 *
 *  0  sin(hour-of-day)            circadian position
 *  1  cos(hour-of-day)
 *  2  sin(day-of-week)            weekly rhythm
 *  3  cos(day-of-week)
 *  4  minutes since last app close (log-scaled, 12 h cap)
 *  5  opens of THIS app today (capped /20)
 *  6  opens of THIS app per day, 7-day average (/20)
 *  7  mean session length for this app (log-scaled, 60 min cap)
 *  8  previous app was a trigger app (continuity = doomscroll chain)
 *  9  battery level (0..1)
 * 10  is charging (0/1)
 * 11  screen-on minutes today from our sessions (/480, capped)
 * 12  night window 22:00-05:00
 * 13  work hours (Mon-Fri 09-17)
 * 14  opens today across ALL trigger apps (/30, general restlessness)
 *
 * All queries are synchronous — callers stay off the main thread.
 */
public final class FeatureExtractor {

    public static final int DIM = 15;

    private FeatureExtractor() {
    }

    public static double[] build(Context ctx, UnhookDao dao, String pkg,
                                 String prevPkg, long nowMs) {
        double[] f = new double[DIM];

        LocalDateTime now = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nowMs), ZoneId.systemDefault());
        int hour = now.getHour();
        int dow = now.getDayOfWeek().getValue(); // Mon=1..Sun=7

        f[0] = Math.sin(2 * Math.PI * hour / 24.0);
        f[1] = Math.cos(2 * Math.PI * hour / 24.0);
        f[2] = Math.sin(2 * Math.PI * dow / 7.0);
        f[3] = Math.cos(2 * Math.PI * dow / 7.0);

        Long lastEnd = dao.lastSessionEndBefore(nowMs);
        double minutesSince = lastEnd == null
                ? 720 : Math.min(720, (nowMs - lastEnd) / 60000.0);
        f[4] = Math.log1p(minutesSince) / Math.log1p(720);

        long dayStart = UnhookRepository.dayStart(nowMs);
        int opensToday = dao.countOpensSince(pkg, dayStart);
        f[5] = Math.min(opensToday, 20) / 20.0;

        long weekAgo = nowMs - 7L * 24 * 3600 * 1000L;
        int opens7 = dao.countOpensSince(pkg, weekAgo);
        f[6] = Math.min(opens7 / 7.0, 20) / 20.0;

        Double avgSession = dao.avgSessionSince(pkg, weekAgo);
        double avgMin = avgSession == null ? 0 : Math.min(60, avgSession / 60000.0);
        f[7] = Math.log1p(avgMin) / Math.log1p(60);

        f[8] = prevPkg != null && !prevPkg.isEmpty()
                && UnhookRepository.get().ruleFor(prevPkg) != null ? 1.0 : 0.0;

        f[9] = EventHandler.batteryPercent(ctx) / 100.0;
        f[10] = EventHandler.isCharging(ctx) ? 1.0 : 0.0;

        Long screenOnMs = dao.sumForegroundSince(dayStart);
        f[11] = Math.min(screenOnMs == null ? 0 : screenOnMs / 60000.0, 480) / 480.0;

        f[12] = (hour >= 22 || hour < 5) ? 1.0 : 0.0;
        f[13] = (dow <= 5 && hour >= 9 && hour < 17) ? 1.0 : 0.0;

        int allTriggerOpens = 0;
        List<UsageEventEntity> todays = dao.eventsSince(dayStart);
        Map<String, Boolean> triggerCache = new HashMap<>();
        for (UsageEventEntity e : todays) {
            if (e.type != 1) {
                continue;
            }
            Boolean trig = triggerCache.get(e.pkg);
            if (trig == null) {
                com.unhook.app.data.entity.AppRuleEntity r =
                        UnhookRepository.get().ruleFor(e.pkg);
                trig = r != null && r.isTrigger;
                triggerCache.put(e.pkg, trig);
            }
            if (trig) {
                allTriggerOpens++;
            }
        }
        f[14] = Math.min(allTriggerOpens, 30) / 30.0;

        return f;
    }
}

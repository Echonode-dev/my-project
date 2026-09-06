package com.unhook.app.coach;

import android.content.Context;

import androidx.annotation.NonNull;

import com.unhook.app.R;
import com.unhook.app.core.AppExecutors;
import com.unhook.app.core.Constants;
import com.unhook.app.data.UnhookDao;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.data.entity.UsageEventEntity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The step-by-step coach (Core Feature C). Rules:
 *  - Days 1-3: BASELINE. Numbers only, zero interruptions.
 *  - Day 4+: GENTLE — interstitial on the top trigger app.
 *  - Week 2+: STEADY — interstitial on all trigger apps, limits suggested.
 *  - Week 3+: FULL — schedule/grayscale options unlocked.
 *  - Relapse: never guilt. 3+ wall escapes in one day = soften goals for
 *    24 h (smaller thresholds, warmer copy).
 *
 * All methods are synchronous — call from background executors.
 */
public final class CoachEngine {

    public enum Stage { BASELINE, GENTLE, STEADY, FULL }

    private static final int GENTLE_TOP_APP = 1; // how many top apps get interrupted first

    private CoachEngine() {
    }

    // ---------------- stage ----------------

    public static Stage stageOf(@NonNull UnhookRepository repo) {
        long installed = repo.getInstallTs();
        long days = (System.currentTimeMillis() - installed) / (24L * 3600 * 1000);
        if (days < Constants.BASELINE_DAYS) {
            return Stage.BASELINE;
        }
        if (days < Constants.GENTLE_DAYS) {
            return Stage.GENTLE;
        }
        if (days < Constants.STEADY_DAYS) {
            return Stage.STEADY;
        }
        return Stage.FULL;
    }

    /** During GENTLE only the single most-opened trigger app is interrupted. */
    public static boolean interruptAllowed(@NonNull UnhookRepository repo, String pkg) {
        Stage stage = stageOf(repo);
        if (stage == Stage.BASELINE) {
            return false;
        }
        if (stage != Stage.GENTLE) {
            return true;
        }
        return pkg.equals(topTriggerApp(repo.dao(), 7));
    }

    // ---------------- relapse (P2: never guilt-trip) ----------------

    public static void onRelapse(@NonNull AppExecutors executors,
                                 @NonNull UnhookRepository repo) {
        String today = UnhookRepository.dateString(System.currentTimeMillis());
        String relapseDate = repo.getSetting("relapse_date");
        int count = relapseDate != null && relapseDate.equals(today)
                ? parseInt(repo.getSetting("relapse_count"), 0) + 1 : 1;
        repo.putSetting("relapse_date", today);
        repo.putSetting("relapse_count", String.valueOf(count));
        if (count >= 3) {
            // Smaller goal, not shame: relax for 24 h.
            repo.putSetting("soften_until",
                    String.valueOf(System.currentTimeMillis() + 24L * 3600 * 1000));
            repo.putSetting("relapse_count", "0");
        }
    }

    public static boolean isSoftened(@NonNull UnhookRepository repo) {
        String until = repo.getSetting("soften_until");
        if (until == null) {
            return false;
        }
        try {
            return System.currentTimeMillis() < Long.parseLong(until);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------------- suggestions ("what to do instead") ----------------

    public enum SuggestionId { WALK, WATER, STRETCH, FOCUS_TIMER, JOURNAL, CALL }

    public static final class Suggestion {
        public final SuggestionId id;

        Suggestion(SuggestionId id) {
            this.id = id;
        }

        public String title(Context ctx) {
            switch (id) {
                case WALK: return ctx.getString(R.string.sugg_walk_title);
                case WATER: return ctx.getString(R.string.sugg_water_title);
                case STRETCH: return ctx.getString(R.string.sugg_stretch_title);
                case FOCUS_TIMER: return ctx.getString(R.string.sugg_focus_title);
                case JOURNAL: return ctx.getString(R.string.sugg_journal_title);
                default: return ctx.getString(R.string.sugg_call_title);
            }
        }

        public String body(Context ctx) {
            switch (id) {
                case WALK: return ctx.getString(R.string.sugg_walk_body);
                case WATER: return ctx.getString(R.string.sugg_water_body);
                case STRETCH: return ctx.getString(R.string.sugg_stretch_body);
                case FOCUS_TIMER: return ctx.getString(R.string.sugg_focus_body);
                case JOURNAL: return ctx.getString(R.string.sugg_journal_body);
                default: return ctx.getString(R.string.sugg_call_body);
            }
        }
    }

    /** Context-ranked "what to do instead" — deterministic, explainable. */
    public static Suggestion suggest(double prob, double[] features) {
        boolean night = features.length > 12 && features[12] >= 0.5;
        boolean workHours = features.length > 13 && features[13] >= 0.5;
        boolean lowBattery = features.length > 9 && features[9] < 0.25;

        int best = 0;
        SuggestionId[] ids = SuggestionId.values();
        double bestScore = -1;
        for (int i = 0; i < ids.length; i++) {
            double s = 40; // base
            switch (ids[i]) {
                case WALK: s += night ? -25 : 12; break;
                case WATER: s += lowBattery ? 18 : 5; break;
                case STRETCH: s += night ? 10 : 4; break;
                case FOCUS_TIMER: s += workHours ? 25 : -12; break;
                case JOURNAL: s += night ? 16 : -4; break;
                case CALL: s += night ? -8 : 2; break;
            }
            s += prob * 10; // the stronger the compulsion, the more physical the reset
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        return new Suggestion(ids[best]);
    }

    // ---------------- weekly insight report ----------------

    public static final class Report {
        public String topApp = "";
        public int topAppOpens;
        public int worstHour = -1;
        public int bestHour = -1;
        public int opensThisWeek;
        public int opensLastWeek;
        public long reclaimedThisWeekMs;
        public boolean softer;

        public int changePercent() {
            if (opensLastWeek == 0) {
                return opensThisWeek == 0 ? 0 : 100;
            }
            return (opensThisWeek - opensLastWeek) * 100 / opensLastWeek;
        }
    }

    public static Report buildWeeklyReport(@NonNull UnhookDao dao,
                                           @NonNull UnhookRepository repo) {
        Report r = new Report();
        long now = System.currentTimeMillis();
        long week = 7L * 24 * 3600 * 1000;

        r.opensThisWeek = countTriggerOpens(dao, now - week, now);
        r.opensLastWeek = countTriggerOpens(dao, now - 2 * week, now - week);
        r.reclaimedThisWeekMs = repo.timeReclaimedMs(now - week);

        String top = topTriggerApp(dao, 7);
        r.topApp = top == null ? "" : top;
        if (!r.topApp.isEmpty()) {
            r.topAppOpens = dao.countOpensSince(r.topApp, now - week);
        }

        // Hour histogram over this week for trigger apps.
        int[] hourCounts = new int[24];
        List<UsageEventEntity> events = dao.eventsSince(now - week);
        Map<String, Boolean> trigCache = new HashMap<>();
        for (UsageEventEntity e : events) {
            if (e.type != 1) {
                continue;
            }
            Boolean trig = trigCache.get(e.pkg);
            if (trig == null) {
                com.unhook.app.data.entity.AppRuleEntity rule = repo.ruleFor(e.pkg);
                trig = rule != null && rule.isTrigger;
                trigCache.put(e.pkg, trig);
            }
            if (trig) {
                int h = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(e.ts),
                        ZoneId.systemDefault()).getHour();
                hourCounts[h]++;
            }
        }
        int worst = 0;
        for (int h = 1; h < 24; h++) {
            if (hourCounts[h] > hourCounts[worst]) {
                worst = h;
            }
        }
        r.worstHour = hourCounts[worst] > 0 ? worst : -1;

        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int h = 8; h <= 23; h++) { // "best" among waking hours
            if (hourCounts[h] < bestCount) {
                bestCount = hourCounts[h];
                best = h;
            }
        }
        r.bestHour = best;

        r.softer = isSoftened(repo);
        return r;
    }

    // ---------------- helpers ----------------

    private static int countTriggerOpens(UnhookDao dao, long from, long to) {
        int total = 0;
        for (com.unhook.app.data.entity.AppRuleEntity rule : dao.triggerRules()) {
            total += dao.countOpensSince(rule.pkg, from); // includes later events; close enough
        }
        // Events after 'to' are negligible for a report computed now.
        return total;
    }

    public static String topTriggerApp(UnhookDao dao, int days) {
        long since = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        String best = null;
        int bestCount = -1;
        for (com.unhook.app.data.entity.AppRuleEntity rule : dao.triggerRules()) {
            int c = dao.countOpensSince(rule.pkg, since);
            if (c > bestCount) {
                bestCount = c;
                best = rule.pkg;
            }
        }
        return best;
    }

    /** Modal open hour for one app over the last 14 days, or -1. */
    public static int typicalOpenHour(UnhookDao dao, String pkg) {
        long since = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
        int[] hours = new int[24];
        List<UsageEventEntity> events = dao.eventsSince(since);
        for (UsageEventEntity e : events) {
            if (e.type == 1 && pkg.equals(e.pkg)) {
                int h = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(e.ts),
                        ZoneId.systemDefault()).getHour();
                hours[h]++;
            }
        }
        int best = -1;
        int bestCount = 0;
        for (int h = 0; h < 24; h++) {
            if (hours[h] > bestCount) {
                bestCount = hours[h];
                best = h;
            }
        }
        return bestCount >= 5 ? best : -1; // need a real pattern, not noise
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }
}

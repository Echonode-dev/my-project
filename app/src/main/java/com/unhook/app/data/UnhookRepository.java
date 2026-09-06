package com.unhook.app.data;

import android.content.Context;

import androidx.annotation.Nullable;

import com.unhook.app.UnhookApplication;
import com.unhook.app.core.Constants;
import com.unhook.app.core.CryptoManager;
import com.unhook.app.data.entity.AppRuleEntity;
import com.unhook.app.data.entity.DailyAggregateEntity;
import com.unhook.app.data.entity.FeedbackEntity;
import com.unhook.app.data.entity.ModelWeightsEntity;
import com.unhook.app.data.entity.SessionEntity;
import com.unhook.app.data.entity.SettingsEntity;
import com.unhook.app.data.entity.UsageEventEntity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Thin facade over the DAO shared by the service, activities and workers.
 * All methods are SYNCHRONOUS — call from background executors only.
 * The HMAC tagging of aggregates lives here so every writer tags identically.
 */
public final class UnhookRepository {

    private static final byte[] LOCK = new byte[0];

    private static volatile UnhookRepository instance;

    private final UnhookDao dao;
    private final CryptoManager crypto;

    public UnhookRepository(UnhookDao dao, CryptoManager crypto) {
        this.dao = dao;
        this.crypto = crypto;
    }

    public static UnhookRepository get() {
        UnhookRepository r = instance;
        if (r == null) {
            synchronized (LOCK) {
                r = instance;
                if (r == null) {
                    UnhookApplication app = UnhookApplication.get();
                    r = new UnhookRepository(app.db().dao(), app.crypto());
                    instance = r;
                }
            }
        }
        return r;
    }

    public static void resetInstance() {
        synchronized (LOCK) {
            instance = null;
        }
    }

    public UnhookDao dao() {
        return dao;
    }

    // ---------------- events & sessions ----------------

    public void recordEvent(String pkg, int type, long ts) {
        UsageEventEntity e = new UsageEventEntity();
        e.pkg = pkg;
        e.type = type;
        e.ts = ts;
        dao.insertEvent(e);
    }

    public long openSession(String pkg, long startTs) {
        SessionEntity s = new SessionEntity();
        s.pkg = pkg;
        s.startTs = startTs;
        return dao.insertSession(s);
    }

    public void closeSession(long sessionId, long endTs, long durationMs, String reason) {
        dao.finishSession(sessionId, endTs, durationMs, reason);
    }

    /** Close any session left dangling (missed PAUSED / screen-off edge cases). */
    public void closeDangling(long nowTs, String reason) {
        List<SessionEntity> open = dao.danglingSession();
        for (SessionEntity s : open) {
            long dur = Math.max(0, nowTs - s.startTs);
            dao.finishSession(s.id, nowTs, dur, reason);
        }
    }

    /** Close the dangling session of ONE package (screen-off path). */
    public void closeDanglingFor(String pkg, long nowTs, String reason) {
        List<SessionEntity> open = dao.danglingSessionFor(pkg);
        for (SessionEntity s : open) {
            long dur = Math.max(0, nowTs - s.startTs);
            dao.finishSession(s.id, nowTs, dur, reason);
        }
    }

    // ---------------- settings ----------------

    public void putSetting(String key, String value) {
        SettingsEntity s = new SettingsEntity();
        s.key = key;
        s.value = value;
        dao.saveSetting(s);
    }

    @Nullable
    public String getSetting(String key) {
        List<String> v = dao.settingValue(key);
        return v.isEmpty() ? null : v.get(0);
    }

    public long getInstallTs() {
        String v = getSetting("install_ts");
        if (v == null) {
            long now = System.currentTimeMillis();
            putSetting("install_ts", String.valueOf(now));
            return now;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    // ---------------- rules ----------------

    public void setTrigger(String pkg, boolean trigger) {
        AppRuleEntity r = ruleFor(pkg);
        if (r == null) {
            r = new AppRuleEntity();
            r.pkg = pkg;
        }
        r.isTrigger = trigger;
        r.enabled = trigger;
        dao.saveRule(r);
    }

    public void saveRule(AppRuleEntity rule) {
        dao.saveRule(rule);
    }

    @Nullable
    public AppRuleEntity ruleFor(String pkg) {
        List<AppRuleEntity> list = dao.ruleFor(pkg);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<AppRuleEntity> triggerRules() {
        return dao.triggerRules();
    }

    // ---------------- feedback + "Time Reclaimed" ----------------

    public void recordFeedback(String pkg, String source, int label,
                               double prob, byte[] featuresBlob, long ts) {
        FeedbackEntity f = new FeedbackEntity();
        f.pkg = pkg;
        f.source = source;
        f.label = label;
        f.probAtTime = prob;
        f.featuresBlob = featuresBlob;
        f.ts = ts;
        dao.insertFeedback(f);
    }

    /**
     * Time Reclaimed (Pillar P2's headline number): every open the user
     * aborted at the interstitial ("Not now") saves roughly their typical
     * session for that app. Conservative: 7-day mean session length,
     * fallback 90 s. WALL escapes are NOT counted (the app was opened).
     */
    public long timeReclaimedMs(long sinceTs) {
        List<FeedbackEntity> fb = dao.recentFeedback(500);
        long total = 0;
        long weekAgo = sinceTs - 7L * 24 * 3600 * 1000L;
        for (FeedbackEntity f : fb) {
            if (f.ts < sinceTs) {
                continue;
            }
            if (f.label == 1 && "INTERSTITIAL".equals(f.source)) {
                Double avg = dao.avgSessionSince(f.pkg, weekAgo);
                total += avg == null || avg <= 0 ? 90_000L : avg.longValue();
            }
        }
        return total;
    }

    // ---------------- daily aggregates (HMAC-protected) ----------------

    private static byte[] aggregatePayload(DailyAggregateEntity a) {
        String s = a.date + "|" + a.pkg + "|" + a.foregroundMs + "|" + a.opens
                + "|" + a.longestSessionMs;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public void saveAggregateTagged(DailyAggregateEntity a) {
        a.hmac = crypto.hmacBytes(aggregatePayload(a));
        dao.saveAggregate(a);
    }

    /** @return number of recent aggregates whose HMAC does not verify. */
    public int corruptedAggregateCount(int days) {
        String sinceDate = LocalDate.now(ZoneId.systemDefault())
                .minusDays(days).toString();
        List<DailyAggregateEntity> rows = dao.aggregatesSince(sinceDate);
        int bad = 0;
        for (DailyAggregateEntity a : rows) {
            if (a.hmac == null || !crypto.verify(aggregatePayload(a), a.hmac)) {
                bad++;
            }
        }
        return bad;
    }

    // ---------------- day helpers ----------------

    public static long dayStart(long nowMs) {
        return LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    public static String dateString(long epochMs) {
        // Pure Java 8: LocalDate.ofInstant() is a Java 9+ API — forbidden here.
        return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    // ---------------- serialization helper (features) ----------------

    public static byte[] doublesToBytes(double[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 8);
        for (double v : arr) {
            buf.putDouble(v);
        }
        return buf.array();
    }

    public static double[] bytesToDoubles(byte[] bytes) {
        if (bytes == null || bytes.length % 8 != 0) {
            return new double[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        double[] out = new double[bytes.length / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getDouble();
        }
        return out;
    }
}

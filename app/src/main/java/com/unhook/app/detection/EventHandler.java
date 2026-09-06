package com.unhook.app.detection;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;

import com.unhook.app.UnhookApplication;
import com.unhook.app.coach.CoachEngine;
import com.unhook.app.data.UnhookDao;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.data.entity.AppRuleEntity;
import com.unhook.app.interstitial.InterstitialActivity;
import com.unhook.app.ml.FeatureExtractor;
import com.unhook.app.ml.LogisticRegressionModel;
import com.unhook.app.overlay.InterceptionController;
import com.unhook.app.policy.PolicyEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * The habit-loop interrupter, wired end to end:
 * detector event → record → policy (rules + coach stage + ML probability)
 * → overlay + interstitial, or record-only during baseline days.
 *
 * Threading: detector thread for cheap routing; ML work on the dedicated
 * single-thread executor (ordered, race-free); UI launches on main.
 */
public final class EventHandler implements ForegroundAppDetector.Listener {

    private static volatile EventHandler instance;

    public static EventHandler get() {
        EventHandler h = instance;
        if (h == null) {
            synchronized (EventHandler.class) {
                h = instance;
                if (h == null) {
                    h = new EventHandler();
                    instance = h;
                }
            }
        }
        return h;
    }

    private final UnhookApplication app;
    private final UnhookRepository repo;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** pkg -> open session id (worker-thread confined). */
    private final Map<String, Long> openSessions = new HashMap<>();
    private String lastOpenedPkg = "";

    private EventHandler() {
        this.app = UnhookApplication.get();
        this.repo = UnhookRepository.get();
    }

    @Override
    public void onAppOpened(final String pkg, final long ts) {
        final String prevPkg = lastOpenedPkg;
        lastOpenedPkg = pkg;

        final long sessionId = repo.openSession(pkg, ts);
        openSessions.put(pkg, sessionId);
        repo.recordEvent(pkg, 1, ts);

        final AppRuleEntity rule = repo.ruleFor(pkg);
        if (rule == null || !rule.isTrigger || !rule.enabled) {
            return;
        }
        final CoachEngine.Stage stage = CoachEngine.stageOf(repo);
        if (stage == CoachEngine.Stage.BASELINE) {
            return; // days 1-3: observe only, no interruption (P2 contract)
        }

        app.executors().mlTrainer().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    routeToInterruption(pkg, ts, prevPkg, rule, stage);
                } catch (Exception ignored) {
                    // A broken prediction must never break the user's phone.
                }
            }
        });
    }

    private void routeToInterruption(String pkg, long ts, String prevPkg,
                                     AppRuleEntity rule, CoachEngine.Stage stage) {
        UnhookDao dao = app.db().dao();
        double[] features = FeatureExtractor.build(app, dao, pkg, prevPkg, ts);
        LogisticRegressionModel model = LogisticRegressionModel.load(app);
        double prob = model.predictCompulsiveProbability(
                new com.unhook.app.ml.FeatureVector(features));

        long dayStart = UnhookRepository.dayStart(ts);
        Long usedMs = dao.sumForegroundSinceFor(pkg, dayStart);
        int usedMinutes = usedMs == null ? 0 : (int) (usedMs / 60000L);

        int minuteOfDay = (int) ((ts / 60000L) % 1440L);
        PolicyEngine.Decision decision =
                PolicyEngine.decide(rule, usedMinutes, prob, stage, minuteOfDay);
        if (decision == PolicyEngine.Decision.NONE) {
            return;
        }

        final int opensToday = dao.countOpensSince(pkg, dayStart);
        final int typicalHour = CoachEngine.typicalOpenHour(dao, pkg);
        final double p = prob;
        final String mode = decision.name();
        final String reason = PolicyEngine.reasonFor(rule, decision, minuteOfDay);

        main.post(new Runnable() {
            @Override
            public void run() {
                InterceptionController.show(app, pkg, mode, reason, p,
                        opensToday, typicalHour, features);
            }
        });
    }

    @Override
    public void onAppClosed(final String pkg, final long ts, final long durationMs) {
        Long sessionId = openSessions.remove(pkg);
        if (sessionId != null) {
            repo.closeSession(sessionId, ts, durationMs, "OTHER_APP");
        }
        repo.recordEvent(pkg, 2, ts);

        final AppRuleEntity rule = repo.ruleFor(pkg);
        if (rule == null || !rule.isTrigger) {
            return;
        }
        if (durationMs > 0 && durationMs < com.unhook.app.core.Constants.BOUNCE_MS) {
            // Bounce: opened and immediately left = classic compulsive signature.
            app.executors().mlTrainer().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        UnhookDao dao = app.db().dao();
                        double[] f = FeatureExtractor.build(app, dao, pkg, "", ts);
                        LogisticRegressionModel model = LogisticRegressionModel.load(app);
                        double prob = model.predictCompulsiveProbability(
                                new com.unhook.app.ml.FeatureVector(f));
                        model.update(new com.unhook.app.ml.FeatureVector(f), 1);
                        repo.recordFeedback(pkg, "IMPLICIT", 1, prob,
                                UnhookRepository.doublesToBytes(f), ts);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    public void onScreenOff() {
        app.executors().diskIo().execute(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Long> e : openSessions.entrySet()) {
                    // duration computed by repository against start time
                    repo.closeDanglingFor(e.getKey(), now, "SCREEN_OFF");
                }
                openSessions.clear();
            }
        });
    }

    /** Convenience for interstitial callbacks. */
    public static Context appContext() {
        return UnhookApplication.get();
    }

    @SuppressWarnings("unused")
    private void unused(Intent i) { // keep Intent import for future routing
    }

    public static int batteryPercent(Context ctx) {
        android.content.IntentFilter f = new android.content.IntentFilter(
                Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = ctx.registerReceiver(null, f);
        if (sticky == null) {
            return 50;
        }
        int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0) {
            return 50;
        }
        return Math.max(0, Math.min(100, level * 100 / scale));
    }

    public static boolean isCharging(Context ctx) {
        android.content.IntentFilter f = new android.content.IntentFilter(
                Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = ctx.registerReceiver(null, f);
        if (sticky == null) {
            return false;
        }
        int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }
}

package com.unhook.app.work;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.unhook.app.UnhookApplication;
import com.unhook.app.data.UnhookDao;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.data.entity.DailyAggregateEntity;
import com.unhook.app.data.entity.SessionEntity;
import com.unhook.app.ml.LogisticRegressionModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Nightly on-device maintenance: rolls finished sessions into HMAC-tagged
 * daily aggregates, prunes raw data past its retention window, and persists
 * the ML weights. Purely local — there is nowhere to upload to.
 */
public class NightlyWorker extends Worker {

    private static final String UNIQUE_NAME = "nightly_aggregation";

    public NightlyWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            UnhookApplication app = (UnhookApplication) getApplicationContext();
            UnhookRepository repo = UnhookRepository.get();
            UnhookDao dao = repo.dao();
            long now = System.currentTimeMillis();
            long since = now - 2L * 24 * 3600 * 1000; // cover yesterday fully

            List<SessionEntity> sessions = dao.sessionsSince(since);
            // key: date|pkg
            Map<String, DailyAggregateEntity> acc = new HashMap<>();
            for (SessionEntity s : sessions) {
                if (s.durationMs <= 0) {
                    continue;
                }
                String date = UnhookRepository.dateString(s.startTs);
                String key = date + "|" + s.pkg;
                DailyAggregateEntity a = acc.get(key);
                if (a == null) {
                    a = new DailyAggregateEntity();
                    a.date = date;
                    a.pkg = s.pkg;
                    acc.put(key, a);
                }
                a.foregroundMs += s.durationMs;
                a.opens += 1;
                if (s.durationMs > a.longestSessionMs) {
                    a.longestSessionMs = s.durationMs;
                }
            }
            for (DailyAggregateEntity a : acc.values()) {
                repo.saveAggregateTagged(a); // HMAC-protected (SECURITY.md §3)
            }

            // Data minimisation: raw data ages out, aggregates persist.
            dao.pruneEvents(now - Constants_Raw.retentionMs());
            dao.pruneSessions(now - Constants_Raw.retentionMs());
            dao.pruneFeedback(now - Constants_Raw.feedbackMs());
            dao.pruneSuggestions(now - Constants_Raw.feedbackMs());

            LogisticRegressionModel.load(app).persist(app);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    /** Indirection to keep Constants imports tidy in this file. */
    private static final class Constants_Raw {
        static long retentionMs() {
            return com.unhook.app.core.Constants.RAW_EVENT_RETENTION_DAYS
                    * 24L * 3600 * 1000;
        }

        static long feedbackMs() {
            return com.unhook.app.core.Constants.FEEDBACK_RETENTION_DAYS
                    * 24L * 3600 * 1000;
        }
    }

    public static void enqueue(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(NightlyWorker.class, 24, TimeUnit.HOURS)
                        .build());
    }
}

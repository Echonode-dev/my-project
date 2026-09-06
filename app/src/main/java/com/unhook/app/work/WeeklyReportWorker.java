package com.unhook.app.work;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.unhook.app.R;
import com.unhook.app.UnhookApplication;
import com.unhook.app.coach.CoachEngine;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.ui.MainActivity;

import java.util.concurrent.TimeUnit;

/**
 * Weekly on-device Insight Report (Core Feature B), delivered as the only
 * notification Unhook will ever send you that week — hard budget of
 * MAX_NOTIFICATIONS_PER_DAY (Constants), no streaks, no re-engagement bait.
 */
public class WeeklyReportWorker extends Worker {

    private static final String UNIQUE_NAME = "weekly_insight";
    private static final String CHANNEL_ID = "unhook_insights";

    public WeeklyReportWorker(@NonNull Context ctx, @NonNull WorkerParameters p) {
        super(ctx, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            UnhookApplication app = (UnhookApplication) getApplicationContext();
            UnhookRepository repo = UnhookRepository.get();

            String today = UnhookRepository.dateString(System.currentTimeMillis());
            if (today.equals(repo.getSetting("last_notif_day"))) {
                return Result.success(); // daily budget already used
            }

            CoachEngine.Report r = CoachEngine.buildWeeklyReport(repo.dao(), repo);
            if (r.opensThisWeek == 0 && r.reclaimedThisWeekMs == 0) {
                return Result.success(); // nothing worth a ping yet
            }

            postNotification(r);
            repo.putSetting("last_notif_day", today);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void postNotification(CoachEngine.Report r) {
        Context ctx = getApplicationContext();
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.notif_channel_insights),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(ctx.getString(R.string.notif_channel_insights_desc));
            nm.createNotificationChannel(ch);
        }

        int change = r.changePercent();
        String trend = change <= -10
                ? ctx.getString(R.string.report_trend_down, -change)
                : change >= 10
                ? ctx.getString(R.string.report_trend_up, change)
                : ctx.getString(R.string.report_trend_flat);

        long reclaimedMin = r.reclaimedThisWeekMs / 60000L;
        String text = ctx.getString(R.string.report_notification_text,
                r.topAppOpens, reclaimedMin) + " " + trend;

        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        nm.notify(2, new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_unhook)
                .setContentTitle(ctx.getString(R.string.report_notification_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build());
    }

    @SuppressWarnings("unused")
    private static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    public static void enqueue(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(WeeklyReportWorker.class,
                        7, TimeUnit.DAYS)
                        .build());
    }
}

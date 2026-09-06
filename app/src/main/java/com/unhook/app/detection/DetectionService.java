package com.unhook.app.detection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.unhook.app.R;
import com.unhook.app.core.AppExecutors;
import com.unhook.app.detection.EventHandler;
import com.unhook.app.ui.MainActivity;

/**
 * Foreground service (API 34+: foregroundServiceType="specialUse") hosting
 * the detector. START_STICKY + boot receiver re-arm it. We deliberately do
 * NOT fight Doze: the poll loop parks while the screen is off.
 *
 * Android-version landmines handled here:
 *  - API 26+: notification channel required before startForeground.
 *  - API 33+: POST_NOTIFICATIONS gates notification VISIBILITY only; the
 *    service still runs if the user denies it.
 *  - API 34+: must declare the FGS type in the manifest AND pass it to
 *    startForeground(); Play also requires the specialUse declaration form.
 */
public class DetectionService extends Service {

    public static final String CHANNEL_ID = "unhook_monitoring";
    private static final int NOTIFICATION_ID = 1;

    private UsageStatsDetector detector;
    private BroadcastReceiver screenReceiver;

    public static void start(Context ctx) {
        if (!UsageStatsDetector.isUsageAccessGranted(ctx)) {
            return; // never run without the permission we explained first
        }
        Intent i = new Intent(ctx, DetectionService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, DetectionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        detector = new UsageStatsDetector(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        detector.setScreenOn(true);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        detector.setScreenOn(false);
                        EventHandler.get().onScreenOff();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, filter);
        }

        if (!detector.isRunning()) {
            detector.start(EventHandler.get());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            screenReceiver = null;
        }
        if (detector != null) {
            detector.stop();
        }
        EventHandler.get().onScreenOff(); // close any dangling session
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel_monitoring),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_monitoring_desc));
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_unhook)
                .setContentTitle(getString(R.string.notif_monitoring_title))
                .setContentText(getString(R.string.notif_monitoring_text))
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}

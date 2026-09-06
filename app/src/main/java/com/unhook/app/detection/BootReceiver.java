package com.unhook.app.detection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.unhook.app.UnhookApplication;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.core.AppExecutors;

/**
 * Re-arms detection after reboot or app update. Only runs when the user
 * completed onboarding — never surprises anyone.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        final UnhookApplication app = (UnhookApplication) context.getApplicationContext();
        app.executors().diskIo().execute(new Runnable() {
            @Override
            public void run() {
                UnhookRepository repo = UnhookRepository.get();
                if (!"1".equals(repo.getSetting("onboarding_done"))) {
                    return;
                }
                if (!"0".equals(repo.getSetting("monitor_enabled"))) {
                    DetectionService.start(context);
                }
            }
        });
    }
}

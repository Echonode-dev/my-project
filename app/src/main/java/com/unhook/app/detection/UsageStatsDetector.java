package com.unhook.app.detection;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;

import com.unhook.app.core.AppExecutors;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Milestone-3 engine, final implementation: UsageStatsManager + UsageEvents
 * event loop on a dedicated thread (Decision A in ARCHITECTURE.md).
 *
 * Design notes that matter:
 *  - Poll cadence ~1 s while the screen is ON; the thread parks on wait()
 *    when the screen is OFF (battery gate — we never fight Doze).
 *  - Dedup: OEMs double-fire RESUMED and re-deliver overlapping windows.
 *    We keep a rolling set of event signatures (pkg|type|ts) and treat
 *    "RESUMED of the current package" as a no-op.
 *  - We ignore our own package and the home launcher (opening the launcher
 *    closes the current session but is never an "open").
 */
public final class UsageStatsDetector implements ForegroundAppDetector {

    private static final long POLL_MS = 1000L;
    private static final long OVERLAP_MS = 2000L;

    private final Context context;
    private final Object sleepLock = new Object();
    private volatile boolean running;
    private volatile boolean screenOn;
    private Thread worker;
    private Listener listener;

    /** Guarded by worker thread only. */
    private final Set<String> recentSignatures = new HashSet<>();
    private final Map<String, Long> resumedAt = new HashMap<>();
    private final Deque<String> signatureOrder = new ArrayDeque<>();
    private String lastResumedPkg;
    private String homePkg;

    public UsageStatsDetector(Context context) {
        this.context = context.getApplicationContext();
        this.screenOn = isInteractive();
        this.homePkg = resolveHomePackage();
    }

    public static boolean isUsageAccessGranted(Context ctx) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) {
            return false;
        }
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void start(final Listener listener) {
        if (running) {
            return;
        }
        this.listener = listener;
        this.running = true;
        worker = new Thread(this::loop, "unhook-detector");
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.setDaemon(false);
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running && worker != null && worker.isAlive();
    }

    /** Called by DetectionService on SCREEN_ON / SCREEN_OFF broadcasts. */
    public void setScreenOn(boolean on) {
        this.screenOn = on;
        if (on) {
            synchronized (sleepLock) {
                sleepLock.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------------

    private void loop() {
        long windowStart = System.currentTimeMillis() - 60_000L;
        while (running) {
            try {
                if (!screenOn) {
                    parkUntilScreenOn();
                    if (!running) {
                        return;
                    }
                    windowStart = System.currentTimeMillis() - OVERLAP_MS;
                }
                long now = System.currentTimeMillis();
                windowStart = sweep(windowStart, now);
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return; // stop() requested
            } catch (Exception e) {
                // Usage access may have been revoked mid-run or the binder hiccuped.
                if (!isUsageAccessGranted(context)) {
                    return; // service notices isRunning()==false and degrades honestly
                }
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** @return new window start for the next sweep. */
    private long sweep(long windowStart, long now) {
        UsageStatsManager usm = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return now - OVERLAP_MS;
        }
        UsageEvents events = usm.queryEvents(windowStart, now);
        UsageEvents.Event ev = new UsageEvents.Event();
        long maxTs = windowStart;

        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            int type = ev.getEventType();
            boolean resumed = type == UsageEvents.Event.ACTIVITY_RESUMED;
            boolean paused = type == UsageEvents.Event.ACTIVITY_PAUSED;
            if (!resumed && !paused) {
                continue; // CONFIGURATION_CHANGE, NOTIFICATIONS, ... — noise for us
            }
            long ts = ev.getTimeStamp();
            if (ts > maxTs) {
                maxTs = ts;
            }
            String pkg = String.valueOf(ev.getPackageName());
            if (pkg.equals(context.getPackageName()) || pkg.equals(homePkg)) {
                continue;
            }
            String sig = pkg + "|" + type + "|" + ts;
            if (!recentSignatures.add(sig)) {
                continue; // replayed event
            }
            rememberSignature(sig);

            if (resumed) {
                if (pkg.equals(lastResumedPkg)) {
                    continue; // OEM double-fire while app already foreground
                }
                closeCurrent(ts, "OTHER_APP");
                lastResumedPkg = pkg;
                resumedAt.put(pkg, ts);
                if (listener != null) {
                    listener.onAppOpened(pkg, ts);
                }
            } else { // paused
                if (pkg.equals(lastResumedPkg)) {
                    Long start = resumedAt.remove(pkg);
                    lastResumedPkg = null;
                    if (listener != null) {
                        long dur = start == null ? 0 : Math.max(0, ts - start);
                        listener.onAppClosed(pkg, ts, dur);
                    }
                }
            }
        }

        recentSignatures.clear();
        signatureOrder.clear(); // rebuild each sweep keeps memory flat
        return now - OVERLAP_MS;
    }

    private void closeCurrent(long ts, String reason) {
        if (lastResumedPkg == null) {
            return;
        }
        Long start = resumedAt.remove(lastResumedPkg);
        String pkg = lastResumedPkg;
        lastResumedPkg = null;
        if (listener != null) {
            long dur = start == null ? 0 : Math.max(0, ts - start);
            listener.onAppClosed(pkg, ts, dur);
        }
    }

    private void rememberSignature(String sig) {
        signatureOrder.addLast(sig);
        while (signatureOrder.size() > 256) {
            String old = signatureOrder.removeFirst();
            recentSignatures.remove(old);
        }
    }

    private void parkUntilScreenOn() throws InterruptedException {
        synchronized (sleepLock) {
            while (running && !screenOn) {
                sleepLock.wait(60_000L);
            }
        }
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private String resolveHomePackage() {
        PackageManager pm = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null
                ? "com.android.launcher" : ri.activityInfo.packageName;
    }
}

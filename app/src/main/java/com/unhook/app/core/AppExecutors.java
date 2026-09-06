package com.unhook.app.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central threading hub.
 *
 * Unhook performs ZERO network I/O by design (Pillar P1), so there is
 * deliberately no network executor and no pool for one: everything is
 * either main-thread UI, short disk work, or single-threaded ML
 * bookkeeping (which must stay strictly ordered so weight updates never
 * race each other).
 */
public final class AppExecutors {

    private final ExecutorService diskIo;
    private final ExecutorService mlTrainer;
    private final Handler main;

    public AppExecutors() {
        this.diskIo = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unhook-disk-io");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.mlTrainer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "unhook-ml-trainer");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.main = new Handler(Looper.getMainLooper());
    }

    /** Room reads/writes, file ops. Single thread = no lock contention. */
    public ExecutorService diskIo() {
        return diskIo;
    }

    /** Model train/predict. Single thread = ordered, race-free weight updates. */
    public ExecutorService mlTrainer() {
        return mlTrainer;
    }

    /** Post results back to the main thread from any executor. */
    public Handler main() {
        return main;
    }

    /** Only used on process shutdown paths (and by tests). */
    public void shutdownNow() {
        diskIo.shutdownNow();
        mlTrainer.shutdownNow();
    }
}

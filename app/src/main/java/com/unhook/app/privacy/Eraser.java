package com.unhook.app.privacy;

import android.content.Context;

import com.unhook.app.UnhookApplication;
import com.unhook.app.core.Constants;
import com.unhook.app.data.UnhookDb;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.detection.DetectionService;
import com.unhook.app.ml.LogisticRegressionModel;

import androidx.work.WorkManager;

import java.io.File;

/**
 * "Erase Everything" — the P1 escape hatch. Order matters:
 *   1. stop monitoring + cancel all background work
 *   2. close the encrypted DB, delete its files (db, -wal, -shm)
 *   3. reset in-memory singletons
 *   4. wipe private prefs AND destroy both AndroidKeyStore keys.
 *
 * After step 4 the deleted database file is cryptographically unrecoverable
 * on this device — the AES-GCM key that could decrypt it no longer exists.
 * There is no server copy to erase, because there is no server.
 */
public final class Eraser {

    private Eraser() {
    }

    public static void erase(Context ctx) {
        Context app = ctx.getApplicationContext();

        DetectionService.stop(app);
        try {
            WorkManager.getInstance(app).cancelAllWork();
        } catch (Exception ignored) {
            // WorkManager may not be initialized on a fresh process; fine.
        }

        UnhookDb.closeAndReset();
        app.deleteDatabase(Constants.DB_NAME);
        File dbFile = app.getDatabasePath(Constants.DB_NAME);
        File parent = dbFile.getParentFile();
        if (parent != null && parent.isDirectory()) {
            File[] files = parent.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith(Constants.DB_NAME)) {
                        // matches unhook.db, unhook.db-wal, unhook.db-shm
                        f.delete();
                    }
                }
            }
        }

        LogisticRegressionModel.resetInstance();
        UnhookRepository.resetInstance();
        UnhookApplication.get().crypto().wipeAll();
    }
}

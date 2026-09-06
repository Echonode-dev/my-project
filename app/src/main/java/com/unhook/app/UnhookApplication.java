package com.unhook.app;

import android.app.Application;

import androidx.annotation.NonNull;

import com.unhook.app.core.AppExecutors;
import com.unhook.app.core.CryptoManager;
import com.unhook.app.data.UnhookDb;

/**
 * Process-wide singletons live here — and ONLY here. Everything is lazy:
 * the Keystore is touched and the encrypted DB is opened on first real use,
 * never at process start (fast cold start, no crypto work if unused).
 */
public class UnhookApplication extends Application {

    private static volatile UnhookApplication instance;

    private volatile AppExecutors appExecutors;
    private volatile CryptoManager cryptoManager;
    private volatile UnhookDb database;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        appExecutors = new AppExecutors();
    }

    public static UnhookApplication get() {
        return instance;
    }

    public AppExecutors executors() {
        return appExecutors;
    }

    public CryptoManager crypto() {
        CryptoManager c = cryptoManager;
        if (c == null) {
            synchronized (this) {
                c = cryptoManager;
                if (c == null) {
                    c = new CryptoManager(this);
                    cryptoManager = c;
                }
            }
        }
        return c;
    }

    public UnhookDb db() {
        UnhookDb db = database;
        if (db == null) {
            synchronized (this) {
                db = database;
                if (db == null) {
                    db = UnhookDb.get(this, crypto());
                    database = db;
                }
            }
        }
        return db;
    }

    /** Used by Erase Everything: drop the in-process handle after wiping files. */
    public void resetDatabaseHandle() {
        synchronized (this) {
            database = null;
        }
    }

    @NonNull
    public AppExecutors executorsDirect() { // alias for readability in services
        return appExecutors;
    }
}

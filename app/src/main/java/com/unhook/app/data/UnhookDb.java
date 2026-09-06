package com.unhook.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.unhook.app.UnhookApplication;
import com.unhook.app.core.Constants;
import com.unhook.app.core.CryptoManager;
import com.unhook.app.data.entity.AppRuleEntity;
import com.unhook.app.data.entity.DailyAggregateEntity;
import com.unhook.app.data.entity.FeedbackEntity;
import com.unhook.app.data.entity.ModelWeightsEntity;
import com.unhook.app.data.entity.SessionEntity;
import com.unhook.app.data.entity.SettingsEntity;
import com.unhook.app.data.entity.SuggestionEntity;
import com.unhook.app.data.entity.UsageEventEntity;

import net.sqlcipher.database.SupportFactory;

/**
 * The single Room database, fully encrypted at rest with SQLCipher
 * (AES-256). The passphrase is a random 256-bit key, wrapped by an
 * AndroidKeyStore AES-GCM key — see CryptoManager. Nobody, including this
 * app's own prefs readers, can open the DB without the Keystore.
 *
 * Threading: build lazily, use only from background executors.
 */
@Database(
        entities = {
                UsageEventEntity.class,
                SessionEntity.class,
                FeedbackEntity.class,
                ModelWeightsEntity.class,
                AppRuleEntity.class,
                DailyAggregateEntity.class,
                SuggestionEntity.class,
                SettingsEntity.class
        },
        version = 1,
        exportSchema = false)
public abstract class UnhookDb extends RoomDatabase {

    public abstract UnhookDao dao();

    private static volatile UnhookDb instance;

    public static UnhookDb get(Context context, CryptoManager crypto) {
        UnhookDb db = instance;
        if (db == null) {
            synchronized (UnhookDb.class) {
                db = instance;
                if (db == null) {
                    byte[] passphrase = crypto.getDbPassphrase();
                    SupportFactory factory = new SupportFactory(passphrase);
                    db = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    UnhookDb.class,
                                    Constants.DB_NAME)
                            .openHelperFactory(factory)
                            .fallbackToDestructiveMigration()
                            .build();
                    instance = db;
                }
            }
        }
        return db;
    }

    /** Close + drop the process handle (Erase Everything path). */
    public static void closeAndReset() {
        synchronized (UnhookDb.class) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception ignored) {
                }
                instance = null;
            }
        }
        UnhookApplication.get().resetDatabaseHandle();
    }
}

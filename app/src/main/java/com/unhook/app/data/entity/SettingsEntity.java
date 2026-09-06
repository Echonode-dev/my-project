package com.unhook.app.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Tiny key-value settings store (inside the encrypted DB, unlike the
 * Keystore-wrapped blobs which live in private prefs).
 * Keys used: onboarding_done, install_ts, monitor_enabled, last_notif_day,
 * relapse_date, relapse_count, soften_until, integrity_flag.
 */
@Entity(tableName = "settings_kv")
public class SettingsEntity {
    @PrimaryKey
    public String key;
    public String value;
}

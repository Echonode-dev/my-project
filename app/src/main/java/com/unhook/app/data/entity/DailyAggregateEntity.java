package com.unhook.app.data.entity;

import androidx.room.Entity;

/**
 * Nightly rollup of one app's usage for one calendar day.
 * SECURITY: hmac = HMAC-SHA256 over date|pkg|foregroundMs|opens|longestSessionMs.
 * The dashboard's "Time Reclaimed" and weekly report only trust tagged rows.
 * Composite PK prevents duplicate rows per (date, pkg) — insert is REPLACE.
 */
@Entity(tableName = "daily_aggregates", primaryKeys = {"date", "pkg"})
public class DailyAggregateEntity {
    public String date;     // yyyy-MM-dd, local zone
    public String pkg;
    public long foregroundMs;
    public int opens;
    public long longestSessionMs;
    public byte[] hmac;
}

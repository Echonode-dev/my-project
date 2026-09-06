package com.unhook.app.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Raw foreground transition from UsageStatsManager.
 * type mirrors UsageEvents.Event values we care about: 1 = ACTIVITY_RESUMED,
 * 2 = ACTIVITY_PAUSED (same ints as the pre-29 MOVE_TO_* constants).
 * Pruned after Constants.RAW_EVENT_RETENTION_DAYS (data minimisation).
 */
@Entity(tableName = "usage_events",
        indices = {@Index("pkg"), @Index("ts"), @Index(value = {"ts", "pkg"})})
public class UsageEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String pkg;
    public int type;
    public long ts;
}

package com.unhook.app.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One app open, aggregated from RESUMED..PAUSED events.
 * endReason: OTHER_APP | SCREEN_OFF | SERVICE_STOPPED.
 */
@Entity(tableName = "sessions",
        indices = {@Index("pkg"), @Index("startTs")})
public class SessionEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String pkg;
    public long startTs;
    public long endTs;          // 0 while still open
    public long durationMs;
    public String endReason;
}

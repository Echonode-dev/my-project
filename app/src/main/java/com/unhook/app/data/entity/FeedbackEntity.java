package com.unhook.app.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A training label for the ML model.
 * source: INTERSTITIAL ([Not now]/[Yes]) | WALL (relapse escape) |
 *         IMPLICIT (bounce &lt;10 s) | PULSE ("was this open worth it?")
 * label:  1 = compulsive/mindless, 0 = intentional.
 * featuresBlob = the exact FeatureVector used at prediction time (audit trail).
 */
@Entity(tableName = "feedback",
        indices = {@Index("ts"), @Index("pkg")})
public class FeedbackEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long ts;
    public String pkg;
    public String source;
    public int label;
    public double probAtTime;
    public byte[] featuresBlob;
}

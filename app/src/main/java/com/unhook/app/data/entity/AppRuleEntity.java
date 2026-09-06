package com.unhook.app.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Per-trigger-app configuration the user chose onboarding / dashboard.
 * mode: INTERSTITIAL | LIMIT | SCHEDULE | GRAYSCALE
 * blockStartMin/blockEndMin: minutes-since-midnight window for SCHEDULE.
 */
@Entity(tableName = "app_rules")
public class AppRuleEntity {
    @PrimaryKey
    @NonNull
    public String pkg;
    public String mode = "INTERSTITIAL";
    public int dailyLimitMin = 30;
    public int blockStartMin = 22 * 60;
    public int blockEndMin = 23 * 60 + 59;
    public boolean enabled = true;
    public boolean isTrigger = true;
}

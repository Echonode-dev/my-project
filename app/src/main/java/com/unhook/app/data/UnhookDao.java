package com.unhook.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.unhook.app.data.entity.AppRuleEntity;
import com.unhook.app.data.entity.DailyAggregateEntity;
import com.unhook.app.data.entity.FeedbackEntity;
import com.unhook.app.data.entity.ModelWeightsEntity;
import com.unhook.app.data.entity.SessionEntity;
import com.unhook.app.data.entity.SettingsEntity;
import com.unhook.app.data.entity.SuggestionEntity;
import com.unhook.app.data.entity.UsageEventEntity;

import java.util.List;

/**
 * One DAO for the whole (small, local, encrypted) database. Sections are
 * ordered by subsystem. Every method is synchronous — callers MUST stay off
 * the main thread (AppExecutors.diskIo / detector thread / mlTrainer).
 */
@Dao
public interface UnhookDao {

    // ---------------- usage events ----------------

    @Insert
    long insertEvent(UsageEventEntity e);

    @Query("SELECT COUNT(*) FROM usage_events WHERE pkg = :pkg AND ts >= :since AND type = 1")
    int countOpensSince(String pkg, long since);

    @Query("SELECT * FROM usage_events WHERE ts >= :since ORDER BY ts ASC")
    List<UsageEventEntity> eventsSince(long since);

    @Query("DELETE FROM usage_events WHERE ts < :cutoff")
    int pruneEvents(long cutoff);

    // ---------------- sessions ----------------

    @Insert
    long insertSession(SessionEntity s);

    @Query("UPDATE sessions SET endTs = :end, durationMs = :dur, endReason = :reason WHERE id = :id")
    void finishSession(long id, long end, long dur, String reason);

    @Query("SELECT * FROM sessions WHERE endTs = 0 ORDER BY startTs DESC LIMIT 1")
    List<SessionEntity> danglingSession();

    @Query("SELECT * FROM sessions WHERE pkg = :pkg AND endTs = 0 ORDER BY startTs DESC LIMIT 1")
    List<SessionEntity> danglingSessionFor(String pkg);

    @Query("SELECT COUNT(*) FROM sessions WHERE startTs >= :since")
    int countSessionsSince(long since);

    @Query("SELECT SUM(durationMs) FROM sessions WHERE startTs >= :since")
    Long sumForegroundSince(long since);

    @Query("SELECT SUM(durationMs) FROM sessions WHERE pkg = :pkg AND startTs >= :since")
    Long sumForegroundSinceFor(String pkg, long since);

    @Query("SELECT MAX(durationMs) FROM sessions WHERE startTs >= :since")
    Long maxSessionSince(long since);

    @Query("SELECT AVG(durationMs) FROM sessions WHERE pkg = :pkg AND startTs >= :since")
    Double avgSessionSince(String pkg, long since);

    @Query("SELECT MAX(endTs) FROM sessions WHERE endTs > 0 AND endTs <= :now")
    Long lastSessionEndBefore(long now);

    @Query("SELECT * FROM sessions WHERE startTs >= :since AND durationMs >= 120000 "
            + "AND pkg IN (SELECT pkg FROM app_rules WHERE isTrigger = 1) "
            + "ORDER BY startTs DESC LIMIT 1")
    List<SessionEntity> lastLongTriggerSession(long since);

    @Query("SELECT * FROM sessions WHERE startTs >= :since ORDER BY startTs ASC")
    List<SessionEntity> sessionsSince(long since);

    @Query("DELETE FROM sessions WHERE startTs < :cutoff")
    int pruneSessions(long cutoff);

    // ---------------- feedback (ML labels) ----------------

    @Insert
    void insertFeedback(FeedbackEntity f);

    @Query("SELECT * FROM feedback ORDER BY ts DESC LIMIT :limit")
    List<FeedbackEntity> recentFeedback(int limit);

    @Query("SELECT COUNT(*) FROM feedback WHERE label = 1 AND source IN ('INTERSTITIAL','WALL') AND ts >= :since")
    int countAbortedOpensSince(long since);

    @Query("DELETE FROM feedback WHERE ts < :cutoff")
    int pruneFeedback(long cutoff);

    // ---------------- model weights (HMAC-verified) ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveWeights(ModelWeightsEntity w);

    @Query("SELECT * FROM model_weights WHERE id = 1")
    List<ModelWeightsEntity> loadWeights();

    // ---------------- app rules (trigger apps) ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveRule(AppRuleEntity r);

    @Query("SELECT * FROM app_rules WHERE pkg = :pkg")
    List<AppRuleEntity> ruleFor(String pkg);

    @Query("SELECT * FROM app_rules WHERE isTrigger = 1")
    List<AppRuleEntity> triggerRules();

    @Query("DELETE FROM app_rules")
    void clearRules();

    // ---------------- daily aggregates (HMAC-verified) ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveAggregate(DailyAggregateEntity a);

    @Query("SELECT * FROM daily_aggregates WHERE date >= :sinceDate ORDER BY date DESC")
    List<DailyAggregateEntity> aggregatesSince(String sinceDate);

    // ---------------- suggestions ----------------

    @Insert
    void insertSuggestion(SuggestionEntity s);

    @Query("DELETE FROM suggestions WHERE ts < :cutoff")
    int pruneSuggestions(long cutoff);

    // ---------------- settings ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveSetting(SettingsEntity s);

    @Query("SELECT value FROM settings_kv WHERE `key` = :k")
    List<String> settingValue(String k);

    @Query("DELETE FROM settings_kv")
    void clearSettings();
}

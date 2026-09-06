package com.unhook.app.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Log of coach suggestions and whether the user took them (uptake metric). */
@Entity(tableName = "suggestions", indices = {@Index("ts")})
public class SuggestionEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long ts;
    public String suggestionId;   // WALK | WATER | STRETCH | FOCUS_TIMER | JOURNAL | CALL
    public boolean accepted;
}

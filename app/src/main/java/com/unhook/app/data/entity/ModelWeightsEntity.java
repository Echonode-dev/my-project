package com.unhook.app.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Incremental logistic-regression state, single row (id = 1).
 * SECURITY: hmac = HMAC-SHA256 over weightsBlob|bias|trainedCount, keyed by
 * the AndroidKeyStore HMAC key. Verified on every load; mismatch = tampered
 * = model resets (see CryptoManager / SECURITY.md).
 */
@Entity(tableName = "model_weights")
public class ModelWeightsEntity {
    @PrimaryKey
    public long id = 1;
    public int version = 1;
    public int dims;
    public byte[] weightsBlob;
    public double bias;
    public int trainedCount;
    public byte[] hmac;
    public long updatedAt;
}

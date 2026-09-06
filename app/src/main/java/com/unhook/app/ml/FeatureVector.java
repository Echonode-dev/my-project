package com.unhook.app.ml;

/**
 * Immutable feature container.
 *
 * M6 replaces the bare array with a named, documented schema (hour of day,
 * day of week, time since last unlock, unlock count so far, session length,
 * previous-app continuity, battery level, charging state, ...).
 *
 * Keep this class dumb: extraction logic belongs to the FeatureExtractor
 * (M6), never to the model, so features stay testable in isolation.
 */
public final class FeatureVector {

    private final double[] values;

    public FeatureVector(double[] values) {
        // Defensive copy: vectors cross threads (detector thread -> ml-trainer).
        this.values = values == null ? new double[0] : values.clone();
    }

    public double[] values() {
        return values.clone();
    }

    public int size() {
        return values.length;
    }
}

package com.unhook.app.ml;

/**
 * Contract for the on-device model that estimates how compulsive an app
 * open is, and powers the coach ("what to do instead").
 *
 * Implemented in M6 as a pure-Java INCREMENTAL logistic regression
 * (one SGD step per labelled event — see docs/ARCHITECTURE.md, Decision B).
 * We chose pure Java over LiteRT/TFLite because the model is tiny, needs
 * true online learning, and must stay fully auditable (privacy pillar).
 * This interface is the seam: if we ever add a sequence model, a TFLite
 * implementation can slot in without touching callers.
 *
 * Threading: implementations are confined to the dedicated "unhook-ml-trainer"
 * executor in AppExecutors — no internal locking needed.
 */
public interface MindfulnessModel {

    /**
     * @param features live feature vector for this open
     * @return probability in [0.0, 1.0] that this open is mindless/compulsive
     */
    double predictCompulsiveProbability(FeatureVector features);

    /**
     * One-sample online update (single SGD step). Weights are persisted to
     * the encrypted Room DB after each update so training survives restarts.
     *
     * @param label 1 = compulsive/mindless open, 0 = intentional open
     */
    void update(FeatureVector features, int label);

    /** @return true once at least one real (non-prior) example was trained. */
    boolean isTrained();

    /** @return count of labelled examples seen (drives "model maturity" in the dashboard). */
    int trainingExampleCount();

    /** Hard wipe. Called by the "Erase Everything" flow (M9). */
    void reset();
}

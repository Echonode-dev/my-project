package com.unhook.app.ml;

import androidx.annotation.NonNull;

import com.unhook.app.UnhookApplication;
import com.unhook.app.core.CryptoManager;
import com.unhook.app.data.UnhookDao;
import com.unhook.app.data.UnhookRepository;
import com.unhook.app.data.entity.ModelWeightsEntity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Incremental logistic regression in pure Java 8 (Decision B).
 *
 *   predict: p = sigmoid(w·x + b)
 *   update:  w[i] -= lr * (weight(y) * (p - y) * x[i] + lambda * w[i])
 *            b     -= lr * weight(y) * (p - y)
 *
 * WHY THIS AND NOT TFLITE: true online learning in ~20 lines, zero native
 * deps, every weight auditable, weights fit in one encrypted DB row.
 * The MindfulnessModel interface is the seam if we ever need more capacity.
 *
 * SECURITY: weights are persisted WITH an HMAC-SHA256 tag (Keystore key).
 * On load, a failed verification resets the model — tampered numbers must
 * never silently drive coaching decisions (SECURITY.md §3).
 *
 * Threading: confined to the unhook-ml-trainer single thread. No locks.
 */
public final class LogisticRegressionModel implements MindfulnessModel {

    private static final double LEARNING_RATE = 0.05;
    private static final double L2 = 1e-4;
    private static final double POSITIVE_WEIGHT = 1.5; // compulsive labels are rarer

    private static volatile LogisticRegressionModel instance;

    private double[] w = new double[FeatureExtractor.DIM];
    private double bias = -0.5; // gentle prior toward "intentional"
    private int trainedCount;
    private boolean tampered;   // set when stored weights failed HMAC

    private LogisticRegressionModel() {
    }

    /** Load (or create) the singleton model on the ml-trainer thread. */
    public static LogisticRegressionModel load(UnhookApplication app) {
        LogisticRegressionModel m = instance;
        if (m != null) {
            return m;
        }
        synchronized (LogisticRegressionModel.class) {
            m = instance;
            if (m == null) {
                m = new LogisticRegressionModel();
                try {
                    m.loadFrom(app.db().dao(), app.crypto());
                } catch (Exception e) {
                    m = new LogisticRegressionModel(); // DB not ready: prior model
                }
                instance = m;
            }
            return m;
        }
    }

    /** Drop the in-memory model (Erase Everything). */
    public static void resetInstance() {
        synchronized (LogisticRegressionModel.class) {
            instance = null;
        }
    }

    // ---------------- prediction ----------------

    @Override
    public double predictCompulsiveProbability(FeatureVector features) {
        double[] x = features.values();
        if (x.length != w.length) {
            return 0.5; // schema mismatch: refuse to guess
        }
        double z = bias;
        for (int i = 0; i < x.length; i++) {
            z += w[i] * x[i];
        }
        return sigmoid(z);
    }

    // ---------------- online training ----------------

    @Override
    public void update(FeatureVector features, int label) {
        double[] x = features.values();
        if (x.length != w.length) {
            return;
        }
        double p = predictCompulsiveProbability(features);
        double err = p - label;
        double weight = label == 1 ? POSITIVE_WEIGHT : 1.0;
        for (int i = 0; i < w.length; i++) {
            double grad = weight * err * x[i] + L2 * w[i];
            w[i] -= LEARNING_RATE * grad;
            // Keep weights bounded; a runaway weight would wreck explanations.
            if (w[i] > 8.0) {
                w[i] = 8.0;
            } else if (w[i] < -8.0) {
                w[i] = -8.0;
            }
        }
        bias -= LEARNING_RATE * weight * err;
        trainedCount++;
    }

    // ---------------- persistence (HMAC-protected) ----------------

    public void persist(UnhookApplication app) {
        try {
            ModelWeightsEntity e = new ModelWeightsEntity();
            e.dims = w.length;
            e.weightsBlob = toBytes(w);
            e.bias = bias;
            e.trainedCount = trainedCount;
            e.updatedAt = System.currentTimeMillis();
            e.hmac = hmacOver(app.crypto(), e);
            app.db().dao().saveWeights(e);
        } catch (Exception ignored) {
            // Persistence failure must not crash the coach; weights stay hot.
        }
    }

    private void loadFrom(UnhookDao dao, CryptoManager crypto) {
        List<ModelWeightsEntity> rows = dao.loadWeights();
        if (rows.isEmpty()) {
            return; // fresh install: keep the gentle prior
        }
        ModelWeightsEntity e = rows.get(0);
        byte[] expected = hmacOver(crypto, e);
        if (e.hmac == null || !java.security.MessageDigest.isEqual(expected, e.hmac)) {
            tampered = true;
            reset(); // treat as untrusted, start over, flag in UI
            return;
        }
        double[] loaded = fromBytes(e.weightsBlob);
        if (loaded.length != FeatureExtractor.DIM) {
            return; // older schema: keep prior
        }
        w = loaded;
        bias = e.bias;
        trainedCount = e.trainedCount;
    }

    private static byte[] hmacOver(CryptoManager crypto, ModelWeightsEntity e) {
        byte[] weights = e.weightsBlob == null ? new byte[0] : e.weightsBlob;
        byte[] meta = (e.bias + "|" + e.trainedCount + "|" + e.dims)
                .getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[weights.length + meta.length];
        System.arraycopy(weights, 0, payload, 0, weights.length);
        System.arraycopy(meta, 0, payload, weights.length, meta.length);
        return crypto.hmacBytes(payload);
    }

    // ---------------- introspection for the coach ----------------

    /** Top signed contributions of the last feature vector — powers the
     *  "why" line ("usually 11pm when you're tired"). */
    public double[] contributions(FeatureVector features) {
        double[] x = features.values();
        double[] out = new double[Math.min(x.length, w.length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = w[i] * x[i];
        }
        return out;
    }

    public boolean isTampered() {
        return tampered;
    }

    // ---------------- MindfulnessModel contract ----------------

    @Override
    public boolean isTrained() {
        return trainedCount > 0;
    }

    @Override
    public int trainingExampleCount() {
        return trainedCount;
    }

    @Override
    public void reset() {
        w = new double[FeatureExtractor.DIM];
        bias = -0.5;
        trainedCount = 0;
    }

    // ---------------- helpers ----------------

    private static double sigmoid(double z) {
        if (z > 30) {
            return 1.0;
        }
        if (z < -30) {
            return 0.0;
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static byte[] toBytes(double[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 8);
        for (double v : arr) {
            buf.putDouble(v);
        }
        return buf.array();
    }

    private static double[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 8 != 0) {
            return new double[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        double[] out = new double[bytes.length / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getDouble();
        }
        return out;
    }

    @NonNull
    @Override
    public String toString() {
        return "LogReg{trained=" + trainedCount + ", tampered=" + tampered + "}";
    }
}

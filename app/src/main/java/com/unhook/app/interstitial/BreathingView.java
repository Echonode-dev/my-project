package com.unhook.app.interstitial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The 5–8 s breathing pause that interrupts the habit loop before any
 * buttons appear (spec: Core Feature A). Pure View + ValueAnimator,
 * zero dependencies.
 */
public final class BreathingView extends View {

    private static final long TOTAL_MS = 5500L;   // within the 5–8 s budget
    private static final long BREATH_MS = 4000L;  // one in+out cycle

    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase;      // 0..1 within a breath
    private boolean done;

    public interface OnFinished {
        void onBreathingFinished();
    }

    public BreathingView(Context context) {
        super(context);
        init();
    }

    public BreathingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        circle.setColor(0xFF14B8A6);
        circle.setAlpha(200);
        text.setColor(Color.WHITE);
        text.setTextSize(sp(15));
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void start(final OnFinished listener) {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(TOTAL_MS);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                long t = (long) animation.getCurrentPlayTime();
                phase = (t % BREATH_MS) / (float) BREATH_MS;
                invalidate();
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                done = true;
                invalidate();
                if (listener != null) {
                    listener.onBreathingFinished();
                }
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f - sp(14);
        float base = Math.min(getWidth(), getHeight()) / 5.5f;
        float scale = base * (1.18f + 0.34f * (float) Math.sin(phase * 2 * Math.PI));

        if (!done) {
            canvas.drawCircle(cx, cy, scale, circle);
            String label = phase < 0.5f
                    ? getResources().getString(com.unhook.app.R.string.breathe_in)
                    : getResources().getString(com.unhook.app.R.string.breathe_out);
            canvas.drawText(label, cx, cy + scale + sp(26), text);
        } else {
            canvas.drawCircle(cx, cy, base, circle);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}

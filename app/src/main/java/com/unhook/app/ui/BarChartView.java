package com.unhook.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal bar chart for the per-app breakdown. Hand-rolled (no chart
 * library, no webview): ~80 lines, zero data leaves the view. One screen,
 * no scrolling feed — P2 in spirit and in bytes.
 */
public final class BarChartView extends View {

    public static final class Entry {
        public final String label;
        public final float minutes;

        public Entry(String label, float minutes) {
            this.label = label;
            this.minutes = minutes;
        }
    }

    private List<Entry> entries = new ArrayList<>();
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public BarChartView(Context context) {
        super(context);
        init();
    }

    public BarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bar.setColor(0xFF0F766E);
        track.setColor(0x14800080);
        label.setColor(0xFF1C1917);
        value.setColor(0xFF6B7280);
        label.setTextSize(sp(13));
        value.setTextSize(sp(12));
    }

    public void setData(List<Entry> data) {
        entries = data == null ? new ArrayList<Entry>() : data;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int rowH = (int) dp(30);
        int desired = Math.max(rowH, entries.size() * rowH + (int) dp(4));
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (entries.isEmpty()) {
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(noDataText(), getWidth() / 2f, getHeight() / 2f, label);
            label.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float max = 1;
        for (Entry e : entries) {
            if (e.minutes > max) {
                max = e.minutes;
            }
        }
        float rowH = dp(30);
        float labelW = dp(110);
        float valueW = dp(56);
        float barStart = labelW;
        float barEnd = getWidth() - valueW - dp(8);
        float top = dp(2);

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            float y = top + i * rowH;
            canvas.drawText(ellipsize(e.label, 12), dp(4), y + rowH * 0.72f, label);

            float w = (barEnd - barStart) * (e.minutes / max);
            rect.set(barStart, y + dp(7), barEnd, y + rowH - dp(7));
            canvas.drawRoundRect(rect, dp(6), dp(6), track);
            if (w > dp(2)) {
                rect.set(barStart, y + dp(7), barStart + w, y + rowH - dp(7));
                canvas.drawRoundRect(rect, dp(6), dp(6), bar);
            }
            String v = e.minutes >= 60
                    ? String.format("%dh %dm", (int) (e.minutes / 60), (int) (e.minutes % 60))
                    : String.format("%dm", (int) e.minutes);
            canvas.drawText(v, barEnd + dp(8), y + rowH * 0.72f, value);
        }
    }

    private String noDataText() {
        return getResources().getString(com.unhook.app.R.string.chart_no_data);
    }

    private String ellipsize(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private float dp(int v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(int v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}

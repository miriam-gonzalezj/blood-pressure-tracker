package com.mtagj.bptracker.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Small self-contained line chart (no external charting library, so there is
 * one less dependency that could fail to resolve): systolic in red and
 * diastolic in blue over the selected period, each with a dashed line at its
 * median — the same visual already built by hand in Excel.
 */
public class BPTrendChartView extends View {

    private final List<Integer> systolic = new ArrayList<>();
    private final List<Integer> diastolic = new ArrayList<>();
    private double systolicMedian = Double.NaN;
    private double diastolicMedian = Double.NaN;
    private String startDateLabel = "";
    private String endDateLabel = "";

    private final Paint systolicPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diastolicPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint medianPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BPTrendChartView(Context context) {
        super(context);
        init();
    }

    public BPTrendChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        systolicPaint.setColor(Color.parseColor("#D32F2F"));
        systolicPaint.setStrokeWidth(4f);
        systolicPaint.setStyle(Paint.Style.STROKE);
        systolicPaint.setStrokeCap(Paint.Cap.ROUND);
        systolicPaint.setStrokeJoin(Paint.Join.ROUND);

        diastolicPaint.setColor(Color.parseColor("#1976D2"));
        diastolicPaint.setStrokeWidth(4f);
        diastolicPaint.setStyle(Paint.Style.STROKE);
        diastolicPaint.setStrokeCap(Paint.Cap.ROUND);
        diastolicPaint.setStrokeJoin(Paint.Join.ROUND);

        medianPaint.setStrokeWidth(2f);
        medianPaint.setStyle(Paint.Style.STROKE);
        medianPaint.setPathEffect(new DashPathEffect(new float[]{12f, 8f}, 0f));

        axisPaint.setColor(Color.parseColor("#BDBDBD"));
        axisPaint.setStrokeWidth(2f);

        gridPaint.setColor(Color.parseColor("#EEEEEE"));
        gridPaint.setStrokeWidth(1.5f);

        textPaint.setColor(Color.parseColor("#424242"));
        textPaint.setTextSize(28f);

        gridLabelPaint.setColor(Color.parseColor("#9E9E9E"));
        gridLabelPaint.setTextSize(22f);
    }

    public void setData(List<Integer> systolicValues, List<Integer> diastolicValues,
                         double systolicMedian, double diastolicMedian) {
        this.systolic.clear();
        this.systolic.addAll(systolicValues);
        this.diastolic.clear();
        this.diastolic.addAll(diastolicValues);
        this.systolicMedian = systolicMedian;
        this.diastolicMedian = diastolicMedian;
        invalidate();
    }

    /** Optional: shown along the bottom axis, e.g. "01/08/2026" .. "31/08/2026", purely for scale/context. */
    public void setDateRangeLabels(String startDateLabel, String endDateLabel) {
        this.startDateLabel = startDateLabel == null ? "" : startDateLabel;
        this.endDateLabel = endDateLabel == null ? "" : endDateLabel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (systolic.isEmpty() || diastolic.isEmpty()) return;

        float paddingLeft = 60f, paddingRight = 20f, paddingTop = 40f, paddingBottom = 40f;
        float chartWidth = getWidth() - paddingLeft - paddingRight;
        float chartHeight = getHeight() - paddingTop - paddingBottom;

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : systolic) { min = Math.min(min, v); max = Math.max(max, v); }
        for (int v : diastolic) { min = Math.min(min, v); max = Math.max(max, v); }
        min -= 5;
        max += 5;
        if (max == min) max = min + 1;

        // Neutral reference gridlines every 10 mmHg — pure scale reading aid, same idea as a lab
        // report's gridlines, with no "normal/abnormal" labeling of any kind.
        int gridStart = (min / 10) * 10;
        for (int gridValue = gridStart; gridValue <= max; gridValue += 10) {
            if (gridValue < min) continue;
            float y = yFor(gridValue, min, max, paddingTop, chartHeight);
            canvas.drawLine(paddingLeft, y, getWidth() - paddingRight, y, gridPaint);
            canvas.drawText(String.valueOf(gridValue), 4f, y + 8f, gridLabelPaint);
        }

        // Axis
        canvas.drawLine(paddingLeft, paddingTop, paddingLeft, getHeight() - paddingBottom, axisPaint);
        canvas.drawLine(paddingLeft, getHeight() - paddingBottom, getWidth() - paddingRight, getHeight() - paddingBottom, axisPaint);

        if (!startDateLabel.isEmpty()) {
            canvas.drawText(startDateLabel, paddingLeft, getHeight() - 8f, gridLabelPaint);
        }
        if (!endDateLabel.isEmpty()) {
            float w = gridLabelPaint.measureText(endDateLabel);
            canvas.drawText(endDateLabel, getWidth() - paddingRight - w, getHeight() - 8f, gridLabelPaint);
        }

        drawSeries(canvas, systolic, min, max, paddingLeft, paddingTop, chartWidth, chartHeight, systolicPaint);
        drawSeries(canvas, diastolic, min, max, paddingLeft, paddingTop, chartWidth, chartHeight, diastolicPaint);

        if (!Double.isNaN(systolicMedian)) {
            drawMedianLine(canvas, systolicMedian, min, max, paddingLeft, paddingTop, chartWidth, chartHeight, systolicPaint.getColor());
        }
        if (!Double.isNaN(diastolicMedian)) {
            drawMedianLine(canvas, diastolicMedian, min, max, paddingLeft, paddingTop, chartWidth, chartHeight, diastolicPaint.getColor());
        }

        String firstLabel = "sistólica";
        float firstLabelWidth = 18f + coloredText(systolicPaint.getColor()).measureText(firstLabel);
        drawLegendEntry(canvas, firstLabel, paddingLeft, 18f, systolicPaint.getColor());
        drawLegendEntry(canvas, "diastólica", paddingLeft + firstLabelWidth + 24f, 18f, diastolicPaint.getColor());
    }

    /** Dibuja un punto de color + etiqueta -- más nítido en el PNG exportado que el carácter "●". */
    private void drawLegendEntry(Canvas canvas, String label, float x, float centerY, int color) {
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(color);
        dot.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x + 6f, centerY, 6f, dot);
        canvas.drawText(label, x + 18f, centerY + 9f, coloredText(color));
    }

    private Paint coloredText(int color) {
        Paint p = new Paint(textPaint);
        p.setColor(color);
        return p;
    }

    private void drawSeries(Canvas canvas, List<Integer> values, int min, int max,
                             float left, float top, float width, float height, Paint paint) {
        int n = values.size();
        if (n < 2) return;
        float stepX = width / (n - 1);
        float prevX = left, prevY = yFor(values.get(0), min, max, top, height);
        for (int i = 1; i < n; i++) {
            float x = left + stepX * i;
            float y = yFor(values.get(i), min, max, top, height);
            canvas.drawLine(prevX, prevY, x, y, paint);
            prevX = x;
            prevY = y;
        }
    }

    private void drawMedianLine(Canvas canvas, double medianValue, int min, int max,
                                 float left, float top, float width, float height, int color) {
        Paint p = new Paint(medianPaint);
        p.setColor(color);
        float y = yFor(medianValue, min, max, top, height);
        canvas.drawLine(left, y, left + width, y, p);
    }

    private float yFor(double value, int min, int max, float top, float height) {
        double fraction = (value - min) / (double) (max - min);
        return (float) (top + height - fraction * height);
    }
}

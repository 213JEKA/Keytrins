package com.keytrins.zonemonitor;

import static com.keytrins.zonemonitor.MarketModels.Candle;
import static com.keytrins.zonemonitor.MarketModels.Snapshot;
import static com.keytrins.zonemonitor.MarketModels.Zone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;
import java.util.Locale;

public final class ZoneChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Snapshot snapshot;

    public ZoneChartView(Context context) { super(context); init(); }
    public ZoneChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setBackgroundColor(Color.rgb(7, 16, 25));
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
    }

    void setSnapshot(Snapshot snapshot) {
        this.snapshot = snapshot;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float left = dp(10), top = dp(16), right = width - dp(58), bottom = height - dp(40);
        drawGrid(canvas, left, top, right, bottom);
        if (snapshot == null || snapshot.candles == null || snapshot.candles.isEmpty()) {
            drawCentered(canvas, "ЗАГРУЗКА ДАННЫХ…", width / 2f, height / 2f, 13, 0xFF8294A8);
            return;
        }

        List<Candle> all = snapshot.candles;
        int start = Math.max(0, all.size() - 120);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = start; i < all.size(); i++) {
            min = Math.min(min, all.get(i).low);
            max = Math.max(max, all.get(i).high);
        }
        for (Zone z : snapshot.zones) {
            if (z.high >= min && z.low <= max) {
                min = Math.min(min, z.low);
                max = Math.max(max, z.high);
            }
        }
        double padding = Math.max((max - min) * 0.06, 0.00005);
        min -= padding;
        max += padding;

        drawZones(canvas, snapshot.zones, min, max, left, top, right, bottom);
        drawCandles(canvas, all, start, min, max, left, top, right, bottom);
        drawPriceScale(canvas, min, max, right, top, bottom);
        drawCurrentPrice(canvas, snapshot.price, min, max, left, right, top, bottom);
        drawLegend(canvas, left, height - dp(20));
    }

    private void drawGrid(Canvas c, float left, float top, float right, float bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.6f));
        paint.setColor(0xFF172635);
        for (int i = 0; i <= 5; i++) {
            float y = top + (bottom - top) * i / 5f;
            c.drawLine(left, y, right, y, paint);
        }
        for (int i = 0; i <= 6; i++) {
            float x = left + (right - left) * i / 6f;
            c.drawLine(x, top, x, bottom, paint);
        }
    }

    private void drawZones(Canvas c, List<Zone> zones, double min, double max,
                           float left, float top, float right, float bottom) {
        int visible = 0;
        for (Zone z : zones) {
            if (z.high < min || z.low > max || visible >= 8) continue;
            int color = zoneColor(z);
            float y1 = y(z.high, min, max, top, bottom);
            float y2 = y(z.low, min, max, top, bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, z.score >= 85 ? 56 : z.score >= 70 ? 40 : 28));
            c.drawRect(left, y1, right, y2, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(z.score >= 85 ? 1.5f : 1f));
            paint.setColor(withAlpha(color, z.score >= 85 ? 220 : 160));
            c.drawLine(left, y1, right, y1, paint);
            c.drawLine(left, y2, right, y2, paint);

            String label = typeLabel(z.type) + "  Q" + z.score;
            textPaint.setTextSize(dp(9));
            textPaint.setColor(color);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText(label, left + dp(5), Math.max(top + dp(10), y1 - dp(3)), textPaint);
            visible++;
        }
    }

    private void drawCandles(Canvas c, List<Candle> candles, int start, double min, double max,
                             float left, float top, float right, float bottom) {
        int count = candles.size() - start;
        float step = (right - left) / Math.max(1, count);
        float body = Math.max(dp(1.2f), step * 0.62f);
        for (int i = start; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            float x = left + (i - start + 0.5f) * step;
            float open = y(candle.open, min, max, top, bottom);
            float close = y(candle.close, min, max, top, bottom);
            float high = y(candle.high, min, max, top, bottom);
            float low = y(candle.low, min, max, top, bottom);
            int color = candle.close >= candle.open ? 0xFF34D399 : 0xFFFF5D75;
            paint.setColor(color);
            paint.setStrokeWidth(dp(0.8f));
            paint.setStyle(Paint.Style.STROKE);
            c.drawLine(x, high, x, low, paint);
            paint.setStyle(Paint.Style.FILL);
            float bodyTop = Math.min(open, close);
            float bodyBottom = Math.max(open, close);
            if (bodyBottom - bodyTop < dp(1)) bodyBottom = bodyTop + dp(1);
            c.drawRect(x - body / 2, bodyTop, x + body / 2, bodyBottom, paint);
        }
    }

    private void drawPriceScale(Canvas c, double min, double max, float right, float top, float bottom) {
        int digits = max >= 10 ? 3 : 5;
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        textPaint.setTextSize(dp(9));
        textPaint.setColor(0xFF7890A6);
        for (int i = 0; i <= 5; i++) {
            double price = max - (max - min) * i / 5.0;
            float y = top + (bottom - top) * i / 5f + dp(3);
            c.drawText(String.format(Locale.US, "% ." + digits + "f", price).trim(), right + dp(5), y, textPaint);
        }
    }

    private void drawCurrentPrice(Canvas c, double price, double min, double max,
                                  float left, float right, float top, float bottom) {
        float y = y(price, min, max, top, bottom);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF6EE7F9);
        paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4), dp(4)}, 0));
        c.drawLine(left, y, right, y, paint);
        paint.setPathEffect(null);
    }

    private void drawLegend(Canvas c, float x, float y) {
        String[] labels = {"SUP", "RES", "FLIP", "TRADE"};
        int[] colors = {0xFF34D399, 0xFFFF5D75, 0xFFB58CFF, 0xFFF5C451};
        float cursor = x;
        for (int i = 0; i < labels.length; i++) {
            paint.setColor(colors[i]);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cursor + dp(3), y - dp(3), dp(3), paint);
            textPaint.setColor(0xFF91A3B5);
            textPaint.setTextSize(dp(8));
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText(labels[i], cursor + dp(9), y, textPaint);
            cursor += dp(i == 3 ? 58 : 50);
        }
    }

    private float y(double value, double min, double max, float top, float bottom) {
        return (float) (bottom - (value - min) / Math.max(1e-12, max - min) * (bottom - top));
    }

    private int zoneColor(Zone z) {
        if ("ACTIVE".equals(z.type)) return 0xFF6EE7F9;
        if ("FLIP".equals(z.type)) return 0xFFB58CFF;
        if ("SUP".equals(z.type)) return 0xFF34D399;
        if ("RES".equals(z.type)) return 0xFFFF5D75;
        return 0xFFF5C451;
    }

    private String typeLabel(String type) {
        if ("ACTIVE".equals(type)) return "В ЗОНЕ";
        if ("FLIP".equals(type)) return "ПЕРЕВОРОТ";
        if ("SUP".equals(type)) return "ПОДДЕРЖКА";
        if ("RES".equals(type)) return "СОПРОТИВЛ.";
        return "ПРОТОРГОВКА";
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void drawCentered(Canvas c, String text, float x, float y, float size, int color) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(size));
        textPaint.setColor(color);
        c.drawText(text, x, y, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}

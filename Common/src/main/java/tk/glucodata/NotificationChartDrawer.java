package tk.glucodata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class NotificationChartDrawer {

    public static Bitmap drawArrow(Context context, float rate, boolean isMmol) {
        int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int uiMode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isDark ? Color.WHITE : Color.BLACK);

        Path path = new Path();
        float scale = size / 24f;
        canvas.scale(scale, scale);

        if (rate > 0.5f) {
            path.moveTo(16f, 6f);
            path.lineTo(18.29f, 8.29f);
            path.lineTo(13.41f, 13.17f);
            path.lineTo(9.41f, 9.17f);
            path.lineTo(2f, 16.59f);
            path.lineTo(3.41f, 18f);
            path.lineTo(9.41f, 12f);
            path.lineTo(13.41f, 16f);
            path.lineTo(19.71f, 9.71f);
            path.lineTo(22f, 12f);
            path.lineTo(22f, 6f);
            path.lineTo(16f, 6f);
            path.close();
        } else if (rate < -0.5f) {
            path.moveTo(16f, 18f);
            path.lineTo(18.29f, 15.71f);
            path.lineTo(13.41f, 10.83f);
            path.lineTo(9.41f, 14.83f);
            path.lineTo(2f, 7.41f);
            path.lineTo(3.41f, 6f);
            path.lineTo(9.41f, 12f);
            path.lineTo(13.41f, 8f);
            path.lineTo(19.71f, 14.29f);
            path.lineTo(22f, 12f);
            path.lineTo(22f, 18f);
            path.lineTo(16f, 18f);
            path.close();
        } else {
            path.moveTo(22f, 12f);
            path.lineTo(18f, 8f);
            path.lineTo(18f, 11f);
            path.lineTo(3f, 11f);
            path.lineTo(3f, 13f);
            path.lineTo(18f, 13f);
            path.lineTo(18f, 16f);
            path.lineTo(22f, 12f);
            path.close();
        }

        canvas.drawPath(path, paint);
        return bitmap;
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode) {
        try {
            return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode);
        } catch (Exception e) {
            // If chart drawing fails, return empty transparent bitmap
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = dm.widthPixels;
            int height = (int) (256 * dm.density);
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private static Bitmap drawChartInternal(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode) {
        // Get display metrics for proper sizing - 256dp height works best with
        // fitCenter
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = (int) (256 * dm.density);

        // Theme detection
        int uiMode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        int lineColor = isDark ? Color.WHITE : Color.BLACK;
        int lineColorSecondary = isDark ? 0xFF9E9E9E : 0xFF757575;
        int gridColor = isDark ? 0x33FFFFFF : 0x22000000;
        int textColor = isDark ? 0xAAFFFFFF : 0xAA000000;

        // Create bitmap and canvas
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Zero left margin - chart starts at edge
        float leftMargin = 0;
        float bottomMargin = 0;
        float topMargin = 2 * dm.density;
        float rightMargin = 2 * dm.density;

        float chartLeft = leftMargin;
        float chartRight = width - rightMargin;
        float chartTop = topMargin;
        float chartBottom = height - bottomMargin;
        float chartWidth = chartRight - chartLeft;
        float chartHeight = chartBottom - chartTop;

        // Time range: 3 hours
        long now = System.currentTimeMillis();
        long duration = 3 * 60 * 60 * 1000L;
        long startTime = now - duration;

        // Filter data to visible range
        List<GlucosePoint> visiblePoints = new ArrayList<>();
        if (data != null) {
            for (GlucosePoint p : data) {
                if (p.timestamp >= startTime) {
                    visiblePoints.add(p);
                }
            }
        }

        // Determine which lines to show
        boolean showAuto = (viewMode == 0 || viewMode == 2 || viewMode == 3);
        boolean showRaw = (viewMode == 1 || viewMode == 2 || viewMode == 3);

        int autoColor = (viewMode == 3) ? lineColorSecondary : lineColor;
        int rawColor = (viewMode == 3) ? lineColor : lineColorSecondary;

        // Calculate Y range from ALL visible data
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        for (GlucosePoint p : visiblePoints) {
            if (p.value > 0) {
                minY = Math.min(minY, p.value);
                maxY = Math.max(maxY, p.value);
            }
            if (p.rawValue > 0) {
                minY = Math.min(minY, p.rawValue);
                maxY = Math.max(maxY, p.rawValue);
            }
        }

        // Use defaults if no data
        if (minY == Float.MAX_VALUE || maxY == Float.MIN_VALUE) {
            minY = isMmol ? 3 : 50;
            maxY = isMmol ? 10 : 180;
        }

        // Add buffer
        float range = maxY - minY;
        float buffer = Math.max(range * 0.15f, isMmol ? 0.5f : 10f);
        minY -= buffer;
        maxY += buffer;

        // Paints
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(1);
        gridPaint.setStyle(Paint.Style.STROKE);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextSize(12 * dm.density);

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * dm.density);

        // Draw grid lines (3 horizontal) with labels centered to line
        float yRange = maxY - minY;
        float textHeight = textPaint.getTextSize();
        for (int i = 1; i <= 3; i++) {
            float yVal = minY + yRange * (i / 4.0f);
            float y = chartBottom - ((yVal - minY) / yRange) * chartHeight;
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);

            // Y label: centered vertically to grid line, with padding to the right
            String label = String.valueOf(Math.round(yVal));
            textPaint.setTextAlign(Paint.Align.LEFT);
            // Vertical center: y + textHeight/3 approximately centers text on the line
            canvas.drawText(label, chartLeft + 4 * dm.density, y + textHeight / 3, textPaint);
        }

        // Draw X-axis hour labels and vertical grid lines
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(startTime);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.HOUR_OF_DAY, 1);

        textPaint.setTextAlign(Paint.Align.CENTER);
        while (cal.getTimeInMillis() < now) {
            float x = chartLeft + ((cal.getTimeInMillis() - startTime) / (float) duration) * chartWidth;
            canvas.drawLine(x, chartTop, x, chartBottom, gridPaint);
            // X label inside chart area (bottom)
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            canvas.drawText(String.valueOf(hour), x, chartBottom - 3 * dm.density, textPaint);
            cal.add(Calendar.HOUR_OF_DAY, 1);
        }

        // Draw auto line
        if (showAuto && !visiblePoints.isEmpty()) {
            linePaint.setColor(autoColor);
            Path path = new Path();
            boolean first = true;
            for (GlucosePoint p : visiblePoints) {
                if (p.value > 0) {
                    float x = chartLeft + ((p.timestamp - startTime) / (float) duration) * chartWidth;
                    float y = chartBottom - ((p.value - minY) / yRange) * chartHeight;
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
            }
            if (!first) {
                canvas.drawPath(path, linePaint);
            }
        }

        // Draw raw line
        if (showRaw && !visiblePoints.isEmpty()) {
            linePaint.setColor(rawColor);
            Path path = new Path();
            boolean first = true;
            for (GlucosePoint p : visiblePoints) {
                if (p.rawValue > 0) {
                    float x = chartLeft + ((p.timestamp - startTime) / (float) duration) * chartWidth;
                    float y = chartBottom - ((p.rawValue - minY) / yRange) * chartHeight;
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
            }
            if (!first) {
                canvas.drawPath(path, linePaint);
            }
        }

        return bitmap;
    }
}

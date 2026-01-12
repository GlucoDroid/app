package tk.glucodata;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.graphics.Matrix;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class NotificationChartDrawer {

    public static int getGlucoseColor(Context context, float value, boolean isMmol) {
        // Defaults
        float low = 70.0f;
        float high = 180.0f;

        // Try to get from Natives
        try {
            float nLow = Natives.targetlow();
            float nHigh = Natives.targethigh();
            if (nLow > 0)
                low = nLow;
            if (nHigh > 0)
                high = nHigh;
        } catch (Throwable t) {
            // ignore
        }

        // Standard Text Color (User requested removal of Red/Orange)
        int uiMode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return isDark ? Color.WHITE : Color.BLACK;
    }

    public static Bitmap drawArrow(Context context, float rate, boolean isMmol, int color) {
        // Default: 100% scale
        return drawArrow(context, rate, isMmol, color, 1.0f);
    }

    public static Bitmap drawArrow(Context context, float rate, boolean isMmol, int color, float arrowScale) {
        float density = context.getResources().getDisplayMetrics().density;
        // Arrow size: 20dp * scale (slightly smaller than 24dp for notifications)
        int size = (int) (20 * density * arrowScale);

        // Render at actual size (no upscaling since ImageView is wrap_content)
        int bitmapSize = size;

        Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // TrendIndicator.kt Logic:
        // strokeWidth = size.width * 0.12f
        float drawSize = bitmapSize;
        paint.setStrokeWidth(drawSize * 0.1f);

        // Rotation Formula: Rate -> Degrees
        // 2.0 -> 50 deg? No, TrendIndicator uses:
        // sensitivity = 25f
        // rotation = (-velocity * sensitivity).coerceIn(-90f, 90f)
        float sensitivity = 25f;
        float rotation = (-rate * sensitivity);
        if (rotation < -90f)
            rotation = -90f;
        if (rotation > 90f)
            rotation = 90f;

        // Base Dimensions from TrendIndicator.kt
        float headSpan = drawSize * 0.5f;
        float headDepth = headSpan / 2;
        float gap = headDepth * 0.3f;

        boolean showDouble = Math.abs(rate) > 2.0f;

        // Total Length Calculation
        // arrowLenFactor = if (showDouble) 0.35f else 0.6f
        float arrowLenFactor = showDouble ? 0.3f : 0.5f;

        // Apply Scaling Logic (TrendIndicator uses baseScale + pulse)
        // We pulse a static scale here to look "Active"
        float totalScale = 1.0f;
        float speed = Math.abs(rate);
        totalScale = 1.0f + (Math.min(speed * 0.12f, 0.5f)); // baseScale

        float arrowLen = drawSize * arrowLenFactor * totalScale;
        float totalVisualLen = arrowLen;
        if (showDouble) {
            totalVisualLen += gap + headDepth;
        }

        float cx = bitmapSize / 2.0f;
        float cy = bitmapSize / 2.0f;

        // Rotate Canvas
        canvas.save();
        canvas.rotate(rotation, cx, cy);

        // Centering Logic
        float startX = cx - totalVisualLen / 2.0f;

        // 1. Draw Main Arrow (->)
        float arrowTipX = startX + arrowLen;
        float arrowWingX = arrowTipX - headDepth;

        Path pArrow = new Path();
        // Shaft
        pArrow.moveTo(startX, cy);
        pArrow.lineTo(arrowTipX, cy);
        // Head
        pArrow.moveTo(arrowWingX, cy - headSpan / 2.0f);
        pArrow.lineTo(arrowTipX, cy);
        pArrow.lineTo(arrowWingX, cy + headSpan / 2.0f);

        canvas.drawPath(pArrow, paint);

        if (showDouble) {
            // 2. Draw Second Head (>)
            float secondTipX = arrowTipX + gap + headDepth;
            float secondWingX = arrowTipX + gap;

            Path pSecond = new Path();
            pSecond.moveTo(secondWingX, cy - headSpan / 2.0f);
            pSecond.lineTo(secondTipX, cy);
            pSecond.lineTo(secondWingX, cy + headSpan / 2.0f);

            canvas.drawPath(pSecond, paint);
        }

        canvas.restore();
        return bitmap;
    }

    public static Bitmap drawGlucoseText(Context context, String text, int color) {
        // Default: 100% size, weight 400, App Font
        return drawGlucoseText(context, text, color, 1.0f, 400, false);
    }

    public static Bitmap drawGlucoseText(Context context, String text, int color, float fontSizeScale, int fontWeight) {
        return drawGlucoseText(context, text, color, fontSizeScale, fontWeight, false);
    }

    public static Bitmap drawGlucoseText(Context context, String text, int color, float fontSizeScale, int fontWeight,
            boolean useSystemFont) {
        float density = context.getResources().getDisplayMetrics().density;
        float textSize = 24f * density * fontSizeScale;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.LEFT);

        if (useSystemFont) {
            // System Font Logic (Google Sans) behavior matching Notify.java
            String familyName = "google-sans";
            // Pre-API 28, we might need "google-sans-medium" for 500+
            // For Light < 400, on older devices, standard "google-sans" might just look
            // regular.
            // But on API 28+, we can force weight.
            if (fontWeight >= 500) {
                familyName = "google-sans-medium";
            } else if (fontWeight < 400) {
                familyName = "sans-serif-light";
            }

            try {
                android.graphics.Typeface tf = android.graphics.Typeface.create(familyName,
                        android.graphics.Typeface.NORMAL);

                // API 28+ (Pie): Precise weight creation
                // This allows fetching "Light" (300) from "google-sans" family correctly
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    tf = android.graphics.Typeface.create(tf, fontWeight, false);
                }

                paint.setTypeface(tf);
            } catch (Throwable t) {
                paint.setTypeface(android.graphics.Typeface.DEFAULT);
            }
        } else {
            // App Font Logic (IBM Plex Sans Variable)
            try {
                android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(context,
                        R.font.ibm_plex_sans_var);
                paint.setTypeface(tf);

                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    // Apply font weight from preferences
                    paint.setFontVariationSettings("'wght' " + fontWeight + ", 'wdth' 100");
                }
            } catch (Throwable t) {
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
        }

        // Multi-Color Logic: "Main / Raw"
        String part1 = text;
        String part2 = "";
        String separator = " / ";

        if (text.contains(separator)) {
            String[] parts = text.split(separator);
            if (parts.length >= 2) {
                part1 = parts[0];
                part2 = separator + parts[1];
            }
        }

        // Measure Part 1
        float width1 = paint.measureText(part1);

        // Measure Part 2 (Scaled 0.7x)
        float width2 = 0;
        Paint paint2 = new Paint(paint); // Clone paint
        if (!part2.isEmpty()) {
            paint2.setTextSize(textSize * 0.7f); // Scale down
            width2 = paint2.measureText(part2);
        }

        int totalWidth = (int) (width1 + width2 + 1);
        int height = (int) (28 * density * fontSizeScale);

        if (totalWidth <= 0)
            totalWidth = 1;
        if (height <= 0)
            height = 1;

        Bitmap bitmap = Bitmap.createBitmap(totalWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = height / 2.0f - (fm.ascent + fm.descent) / 2.0f;

        // Draw Part 1 (Main) - Semantic Color
        paint.setColor(color);
        canvas.drawText(part1, 0, y, paint);

        // Draw Part 2 (Raw) - Gray/Secondary
        if (!part2.isEmpty()) {
            paint2.setColor(Color.GRAY);
            // Baseline alignment: use same 'y' because drawText is baseline-based
            canvas.drawText(part2, width1, y, paint2);
        }

        return bitmap;
    }

    /**
     * Render status text (e.g., "Connecting to sensor") as a bitmap with custom
     * font.
     */
    public static Bitmap drawStatusText(Context context, String text) {
        float density = context.getResources().getDisplayMetrics().density;
        float textSize = 11f * density;

        // Theme detection
        int uiMode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int textColor = isDark ? 0xAAFFFFFF : 0xAA000000; // Info-style gray

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(textColor);

        // Font: IBM Plex Sans Variable
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(context,
                    R.font.ibm_plex_sans_var);
            paint.setTypeface(tf);

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                paint.setFontVariationSettings("'wght' 400, 'wdth' 100");
            }
        } catch (Throwable t) {
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        float textWidth = paint.measureText(text);
        int width = (int) (textWidth + 1);
        int height = (int) (14 * density);

        if (width <= 0)
            width = 1;
        if (height <= 0)
            height = 1;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = height / 2.0f - (fm.ascent + fm.descent) / 2.0f;

        canvas.drawText(text, 0, y, paint);

        return bitmap;
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode) {
        // Default: show target range
        return drawChart(context, data, widthHint, heightHint, isMmol, viewMode, true);
    }

    public static Bitmap drawChart(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange) {
        try {
            return drawChartInternal(context, data, widthHint, heightHint, isMmol, viewMode, showTargetRange);
        } catch (Exception e) {
            // If chart drawing fails, return empty transparent bitmap
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = dm.widthPixels;
            int height = (int) (256 * dm.density);
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private static Bitmap drawChartInternal(Context context, List<GlucosePoint> data, int widthHint, int heightHint,
            boolean isMmol, int viewMode, boolean showTargetRange) {
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
        int textColor = isDark ? 0x88FFFFFF : 0x88000000; // Lighter shade (53% opacity)

        // Create bitmap and canvas
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Zero/Small margins - chart fills the bitmap edge to edge
        float leftMargin = 0;
        float bottomMargin = (height < 120) ? 6.0f * dm.density : 2.0f * dm.density; // Add margin for collapsed
        float topMargin = (height < 120) ? 12.0f * dm.density : 3.0f * dm.density; // Much larger margin for collapsed
                                                                                   // to avoid clip

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

        // Target Range (Get from Natives or Defaults)
        float targetLow = 70.0f;
        float targetHigh = 180.0f;
        try {
            float nLow = Natives.targetlow();
            float nHigh = Natives.targethigh();
            if (nLow > 0)
                targetLow = nLow;
            if (nHigh > 0)
                targetHigh = nHigh;
        } catch (Throwable t) {
        }

        // Unit Normalization (Heuristic) indicating Mmol usage
        // If values are small (<30), assume mmol.
        boolean valueIsMmol = isMmol;
        if (!visiblePoints.isEmpty() && visiblePoints.get(visiblePoints.size() - 1).value < 30) {
            valueIsMmol = true;
        }

        // Ensure targets are in correct unit
        // If Value is Mmol but Target is Mg/dL (>30), convert target
        if (valueIsMmol && targetLow > 30) {
            targetLow /= 18.0182f;
            targetHigh /= 18.0182f;
        }
        // If Value is Mg/dL but Target is Mmol (<30), convert target
        else if (!valueIsMmol && targetLow < 30) {
            targetLow *= 18.0182f;
            targetHigh *= 18.0182f;
        }

        // Calculate Y range
        // Standard Strategy: Expand to fit Data, BUT ensure we cover Target Range +
        // 1mmol (18mg/dl) buffer
        float bufferVal = valueIsMmol ? 0.1f : 2.0f; // Increased buffer for safety (was 0.1/2.0)

        float minY = targetLow - bufferVal;
        float maxY = targetHigh + bufferVal;

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

        // Add robust extra cosmetic buffer (10%) so lines don't touch edges exact
        float range = maxY - minY;
        if (range == 0)
            range = 1; // avoid div by zero
        minY -= range * 0.05f;
        maxY += range * 0.05f;

        // Paints
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(gridColor); // 0x22000000 or 0x33FFFFFF
        gridPaint.setStrokeWidth(1);
        gridPaint.setStyle(Paint.Style.STROKE);

        // Target Range Shade Paint
        // Very subtle shade (5% opacity)
        // If Dark Mode: 0x0D4CAF50 (Green ~5%)
        // If Light Mode: 0x0D4CAF50
        Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetPaint.setStyle(Paint.Style.FILL);
        targetPaint.setColor(0x0D4CAF50); // ~5% Green

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(textColor);
        textPaint.setTextSize(12 * dm.density);
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(context,
                    R.font.ibm_plex_sans_var);
            textPaint.setTypeface(tf);
        } catch (Throwable t) {
            // ignore
        }

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * dm.density);

        float yRange = maxY - minY;

        // DRAW TARGET RANGE SHADING (if enabled)
        if (showTargetRange && yRange > 0) {
            float yHigh = chartBottom - ((targetHigh - minY) / yRange) * chartHeight;
            float yLow = chartBottom - ((targetLow - minY) / yRange) * chartHeight;

            // Canvas draws rect Top to Bottom
            // yHigh is visually HIGHER (smaller Y coordinate)
            // yLow is visually LOWER (larger Y coordinate)
            if (yHigh < chartTop)
                yHigh = chartTop;
            if (yLow > chartBottom)
                yLow = chartBottom;

            if (yLow > yHigh) {
                canvas.drawRect(chartLeft, yHigh, chartRight, yLow, targetPaint);
            }
        }

        // Check if collapsed (from Notify.java: passed 100 for collapsed, 180 for
        // expanded)
        boolean isCollapsed = heightHint < 150;

        // Draw grid lines (3 horizontal)
        // yRange already calculated above
        float textHeight = textPaint.getTextSize();
        // Generous offset (e.g. 8dp)
        float gridLabelOffset = 4 * dm.density;

        // Store Y-label bounds for intersection testing
        List<android.graphics.RectF> yLabelRects = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            float yVal = minY + yRange * (i / 4.0f);
            float y = chartBottom - ((yVal - minY) / yRange) * chartHeight;

            // Y label - Only if NOT collapsed
            float lineStart = chartLeft; // Default start

            if (!isCollapsed) {
                String label = String.valueOf(Math.round(yVal));
                textPaint.setTextAlign(Paint.Align.LEFT);
                float labelWidth = textPaint.measureText(label);

                // Text position
                float textX = chartLeft + 4 * dm.density;
                canvas.drawText(label, textX, y + textHeight / 3, textPaint);

                // Capture bounds (with padding) for line breaking
                float pad = 4 * dm.density;
                android.graphics.RectF rect = new android.graphics.RectF(
                        textX - pad,
                        y - textHeight * 0.8f - pad,
                        textX + labelWidth + pad,
                        y + textHeight * 0.4f + pad);
                yLabelRects.add(rect);

                // Line starts after label + offset
                lineStart = textX + labelWidth + gridLabelOffset;
            }

            if (lineStart < chartRight) {
                canvas.drawLine(lineStart, y, chartRight, y, gridPaint);
            }
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

            // X label inside chart area (bottom)
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            String label = String.valueOf(hour);

            // Label Y position
            float labelY = chartBottom - 3 * dm.density;

            float lineEnd = chartBottom; // Default end

            if (!isCollapsed) {
                canvas.drawText(label, x, labelY, textPaint);
                // Line ends before label (above it)
                lineEnd = labelY - textHeight - gridLabelOffset;
            }

            if (lineEnd > chartTop) {
                // Check for intersections with Y-labels (only if we have labels)
                List<float[]> gaps = new ArrayList<>();
                if (!yLabelRects.isEmpty()) {
                    for (android.graphics.RectF r : yLabelRects) {
                        if (x >= r.left && x <= r.right) {
                            gaps.add(new float[] { r.top, r.bottom });
                        }
                    }
                }

                // Draw line in segments skipping gaps
                if (gaps.isEmpty()) {
                    canvas.drawLine(x, chartTop, x, lineEnd, gridPaint);
                } else {
                    // Sort gaps by top Y
                    java.util.Collections.sort(gaps, new java.util.Comparator<float[]>() {
                        @Override
                        public int compare(float[] o1, float[] o2) {
                            return Float.compare(o1[0], o2[0]);
                        }
                    });

                    float currentY = chartTop;
                    for (float[] gap : gaps) {
                        // Draw segment before gap
                        if (gap[0] > currentY) {
                            // ensure we don't overshoot bottom
                            float segEnd = Math.min(gap[0], lineEnd);
                            if (segEnd > currentY) {
                                canvas.drawLine(x, currentY, x, segEnd, gridPaint);
                            }
                        }
                        // Skip gap
                        currentY = Math.max(currentY, gap[1]);
                    }
                    // Draw final segment
                    if (currentY < lineEnd) {
                        canvas.drawLine(x, currentY, x, lineEnd, gridPaint);
                    }
                }
            }

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

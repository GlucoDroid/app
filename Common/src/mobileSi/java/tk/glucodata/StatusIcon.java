package tk.glucodata;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import tk.glucodata.Log;

public class StatusIcon {
    final private static String LOG_ID = "StatusIcon";
    final static int size = 96; // High res

    private android.content.Context mContext;

    StatusIcon(android.content.Context context) {
        mContext = context;
    }

    Icon getIcon(String value) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);

        // Standard Width / Regular Weight for Status Bar
        try {
            Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(mContext, R.font.ibm_plex_sans_var);
            paint.setTypeface(tf);
            if (Build.VERSION.SDK_INT >= 26) {
                paint.setFontVariationSettings("'wght' 400, 'wdth' 100");
            }
        } catch (Throwable t) {
            // Fallback
            Typeface font = Typeface.create("sans-serif", Typeface.BOLD);
            paint.setTypeface(font);
        }

        // 1. Measure at test size
        float testSize = 100f;
        paint.setTextSize(testSize);
        Rect bounds = new Rect();
        paint.getTextBounds(value, 0, value.length(), bounds);

        // 2. Calculate Scale
        float textW = bounds.width();
        float textH = bounds.height();

        // Target: Fill 100% of width/height (Maximizing size as requested)
        float targetW = size * 1.0f;
        float targetH = size * 1.0f;

        float scaleW = targetW / textW;
        float scaleH = targetH / textH;

        // 2b. Calculate Max Scale (based on "88" to match '2 numbers' consistency)
        paint.getTextBounds("88", 0, 2, bounds);
        float refW = bounds.width();
        // Use the same targetW for consistency
        float maxScale = targetW / refW;

        // Use the smaller of the two scales (Auto-fit vs Max-cap)
        float scale = Math.min(Math.min(scaleW, scaleH), maxScale);

        float finalSize = testSize * scale;
        paint.setTextSize(finalSize);

        // 3. Re-measure for exact centering
        paint.getTextBounds(value, 0, value.length(), bounds);

        // Center X
        float x = (size - bounds.width()) / 2f - bounds.left;

        // Center Y with Offset for Status Bar alignment
        // Push down by ~10% of size to align with clock baseline
        // float yOffset = size * 0.10f;
        // float y = (size - bounds.height()) / 2f - bounds.top + yOffset;
        float y = (size - bounds.height()) / 2f - bounds.top;

        canvas.drawText(value, x, y, paint);

        return Icon.createWithBitmap(bitmap);
    }
}

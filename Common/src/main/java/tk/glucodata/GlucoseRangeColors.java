package tk.glucodata;

/**
 * Shared glucose range tones. The hue order follows AGP convention:
 * below range is red/dark red, in range is green, above range is amber/orange.
 */
public final class GlucoseRangeColors {
    public static final int VERY_LOW = 0xFFC7655C;
    public static final int LOW = 0xFFC97970;
    public static final int IN_RANGE = 0xFF4E8A55;
    public static final int HIGH = 0xFFD0A23A;
    public static final int VERY_HIGH = 0xFFC56F33;

    public static final int VERY_LOW_DARK = VERY_LOW;
    public static final int LOW_DARK = LOW;
    public static final int IN_RANGE_DARK = IN_RANGE;
    public static final int HIGH_DARK = HIGH;
    public static final int VERY_HIGH_DARK = VERY_HIGH;

    public static final float DEFAULT_LOW_MGDL = 70.0f;
    public static final float DEFAULT_HIGH_MGDL = 180.0f;
    public static final float DEFAULT_VERY_LOW_MGDL = 54.0f;
    public static final float DEFAULT_VERY_HIGH_MGDL = 250.0f;

    public static final float DEFAULT_LOW_MMOL = 3.9f;
    public static final float DEFAULT_HIGH_MMOL = 10.0f;
    public static final float DEFAULT_VERY_LOW_MMOL = 3.0f;
    public static final float DEFAULT_VERY_HIGH_MMOL = 13.9f;

    private GlucoseRangeColors() {
    }

    public static int veryLow(boolean darkTheme) {
        return darkTheme ? VERY_LOW_DARK : VERY_LOW;
    }

    public static int low(boolean darkTheme) {
        return darkTheme ? LOW_DARK : LOW;
    }

    public static int inRange(boolean darkTheme) {
        return darkTheme ? IN_RANGE_DARK : IN_RANGE;
    }

    public static int high(boolean darkTheme) {
        return darkTheme ? HIGH_DARK : HIGH;
    }

    public static int veryHigh(boolean darkTheme) {
        return darkTheme ? VERY_HIGH_DARK : VERY_HIGH;
    }

    public static float defaultLow(boolean isMmol) {
        return isMmol ? DEFAULT_LOW_MMOL : DEFAULT_LOW_MGDL;
    }

    public static float defaultHigh(boolean isMmol) {
        return isMmol ? DEFAULT_HIGH_MMOL : DEFAULT_HIGH_MGDL;
    }

    public static float defaultVeryLow(boolean isMmol) {
        return isMmol ? DEFAULT_VERY_LOW_MMOL : DEFAULT_VERY_LOW_MGDL;
    }

    public static float defaultVeryHigh(boolean isMmol) {
        return isMmol ? DEFAULT_VERY_HIGH_MMOL : DEFAULT_VERY_HIGH_MGDL;
    }

    // Traffic-light palette for coloring the current value itself (GDH-style):
    // green inside the target range, yellow between target and alarm bounds,
    // red beyond the alarms. Dark variants are lighter for dark backgrounds.
    public static final int VALUE_IN_RANGE = 0xFF2E7D32;
    public static final int VALUE_IN_RANGE_DARK = 0xFF81C784;
    public static final int VALUE_BORDERLINE = 0xFFF9A825;
    public static final int VALUE_BORDERLINE_DARK = 0xFFFFD54F;
    public static final int VALUE_OUT = 0xFFC62828;
    public static final int VALUE_OUT_DARK = 0xFFE57373;

    public static int valueInRange(boolean darkTheme) {
        return darkTheme ? VALUE_IN_RANGE_DARK : VALUE_IN_RANGE;
    }

    public static int valueBorderline(boolean darkTheme) {
        return darkTheme ? VALUE_BORDERLINE_DARK : VALUE_BORDERLINE;
    }

    public static int valueOut(boolean darkTheme) {
        return darkTheme ? VALUE_OUT_DARK : VALUE_OUT;
    }

    public static int trafficColorForValue(
            float value,
            float targetLow,
            float targetHigh,
            float alarmLow,
            float alarmHigh,
            boolean darkTheme,
            boolean isMmol,
            int fallbackColor) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return fallbackColor;
        }

        float low = Float.isFinite(targetLow) && targetLow > 0.0f ? targetLow : defaultLow(isMmol);
        float highCandidate = Float.isFinite(targetHigh) && targetHigh > low ? targetHigh : defaultHigh(isMmol);
        float high = Math.max(highCandidate, low + 0.1f);
        float redLow = Float.isFinite(alarmLow) && alarmLow > 0.0f ? alarmLow : defaultVeryLow(isMmol);
        redLow = Math.min(redLow, low - 0.1f);
        float redHigh = Float.isFinite(alarmHigh) && alarmHigh > 0.0f ? alarmHigh : defaultVeryHigh(isMmol);
        redHigh = Math.max(redHigh, high + 0.1f);

        if (value <= redLow || value >= redHigh) {
            return valueOut(darkTheme);
        }
        if (value < low || value > high) {
            return valueBorderline(darkTheme);
        }
        return valueInRange(darkTheme);
    }

    public static int blend(int startColor, int endColor, float fraction) {
        float safeFraction = Math.max(0.0f, Math.min(1.0f, fraction));
        float inverse = 1.0f - safeFraction;
        int alpha = Math.round((ColorPart.alpha(startColor) * inverse) + (ColorPart.alpha(endColor) * safeFraction));
        int red = Math.round((ColorPart.red(startColor) * inverse) + (ColorPart.red(endColor) * safeFraction));
        int green = Math.round((ColorPart.green(startColor) * inverse) + (ColorPart.green(endColor) * safeFraction));
        int blue = Math.round((ColorPart.blue(startColor) * inverse) + (ColorPart.blue(endColor) * safeFraction));
        return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
    }

    public static int colorForValue(
            float value,
            float targetLow,
            float targetHigh,
            float veryLowThreshold,
            float veryHighThreshold,
            int inRangeColor,
            boolean darkTheme,
            boolean isMmol) {
        if (!Float.isFinite(value) || value <= 0.0f) {
            return inRangeColor;
        }

        float low = Float.isFinite(targetLow) && targetLow > 0.0f ? targetLow : defaultLow(isMmol);
        float highCandidate = Float.isFinite(targetHigh) && targetHigh > low ? targetHigh : defaultHigh(isMmol);
        float high = Math.max(highCandidate, low + 0.1f);
        float veryLow = Float.isFinite(veryLowThreshold) && veryLowThreshold > 0.0f
                ? veryLowThreshold
                : defaultVeryLow(isMmol);
        veryLow = Math.min(veryLow, low - 0.1f);
        float veryHigh = Float.isFinite(veryHighThreshold) && veryHighThreshold > 0.0f
                ? veryHighThreshold
                : defaultVeryHigh(isMmol);
        veryHigh = Math.max(veryHigh, high + 0.1f);

        if (value <= veryLow) {
            return veryLow(darkTheme);
        }
        if (value < low) {
            float severity = (low - value) / Math.max(0.1f, low - veryLow);
            return blend(low(darkTheme), veryLow(darkTheme), severity);
        }
        if (value >= veryHigh) {
            return veryHigh(darkTheme);
        }
        if (value > high) {
            float severity = (value - high) / Math.max(0.1f, veryHigh - high);
            return blend(high(darkTheme), veryHigh(darkTheme), severity);
        }
        return inRangeColor;
    }

    private static final class ColorPart {
        static int alpha(int color) {
            return (color >>> 24) & 0xFF;
        }

        static int red(int color) {
            return (color >>> 16) & 0xFF;
        }

        static int green(int color) {
            return (color >>> 8) & 0xFF;
        }

        static int blue(int color) {
            return color & 0xFF;
        }
    }
}

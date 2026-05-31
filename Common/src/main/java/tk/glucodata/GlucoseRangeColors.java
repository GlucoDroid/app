package tk.glucodata;

/**
 * Shared glucose range tones. The hue order follows AGP convention:
 * below range is red/dark red, in range is green, above range is amber/orange.
 */
public final class GlucoseRangeColors {
    public static final int VERY_LOW = 0xFFB3261E;
    public static final int LOW = 0xFFC85D55;
    public static final int IN_RANGE = 0xFF4E8A55;
    public static final int HIGH = 0xFFD0A23A;
    public static final int VERY_HIGH = 0xFFC56F33;

    public static final int VERY_LOW_DARK = 0xFFFFB4AB;
    public static final int LOW_DARK = 0xFFF0A19A;
    public static final int IN_RANGE_DARK = 0xFF81C784;
    public static final int HIGH_DARK = 0xFFE6C56B;
    public static final int VERY_HIGH_DARK = 0xFFE5A06A;

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
}

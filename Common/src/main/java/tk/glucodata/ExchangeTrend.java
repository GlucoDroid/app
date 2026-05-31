package tk.glucodata;

import java.util.concurrent.ConcurrentHashMap;

public final class ExchangeTrend {
    public static final int UNKNOWN = 0;
    public static final int DOUBLE_UP = 1;
    public static final int SINGLE_UP = 2;
    public static final int FORTY_FIVE_UP = 3;
    public static final int FLAT = 4;
    public static final int FORTY_FIVE_DOWN = 5;
    public static final int SINGLE_DOWN = 6;
    public static final int DOUBLE_DOWN = 7;

    private static final long NATIVE_MATCH_WINDOW_MS = 2L * 60L * 1000L;
    private static final long CACHE_MATCH_WINDOW_MS = 30L * 1000L;

    private static final String[] NAMES = {
            "",
            "DoubleUp",
            "SingleUp",
            "FortyFiveUp",
            "Flat",
            "FortyFiveDown",
            "SingleDown",
            "DoubleDown"
    };

    private static final String[] ARROWS = {
            "",
            "\u2191\u2191",
            "\u2191",
            "\u2197",
            "\u2192",
            "\u2198",
            "\u2193",
            "\u2193\u2193"
    };

    private static final float[] REPRESENTATIVE_RATES = {
            Float.NaN,
            3.5f,
            2.5f,
            1.5f,
            0f,
            -1.5f,
            -2.5f,
            -3.5f
    };

    private static final ConcurrentHashMap<String, Sample> sensorTrendCache = new ConcurrentHashMap<>();

    public final int index;
    public final String name;
    public final String arrow;
    public final float rateMgdlPerMinute;
    public final String source;

    private ExchangeTrend(int index, float rateMgdlPerMinute, String source) {
        this.index = normalizeIndex(index);
        this.name = NAMES[this.index];
        this.arrow = ARROWS[this.index];
        this.rateMgdlPerMinute = Float.isFinite(rateMgdlPerMinute)
                ? rateMgdlPerMinute
                : REPRESENTATIVE_RATES[this.index];
        this.source = source;
    }

    public static ExchangeTrend unknown() {
        return new ExchangeTrend(UNKNOWN, Float.NaN, "unknown");
    }

    public static ExchangeTrend resolve(String sensorId, long timeMillis, float fallbackRate) {
        final ExchangeTrend cached = fromCache(sensorId, timeMillis);
        if (cached.isKnown()) {
            return cached;
        }

        final ExchangeTrend nativeTrend = fromNativeLatest(sensorId, timeMillis, fallbackRate);
        if (nativeTrend.isKnown()) {
            return nativeTrend;
        }

        return fromRate(fallbackRate, "rate");
    }

    public static ExchangeTrend fromRate(float rateMgdlPerMinute) {
        return fromRate(rateMgdlPerMinute, "rate");
    }

    public static String nameForIndex(int index) {
        return NAMES[normalizeIndex(index)];
    }

    public static String arrowForIndex(int index) {
        return ARROWS[normalizeIndex(index)];
    }

    public static void cacheAiDexTrend(String sensorId, long timeMillis, int trendByte) {
        final String key = cacheKey(sensorId);
        if (key == null || timeMillis <= 0L) {
            return;
        }
        final ExchangeTrend trend = fromAiDexTrendByte(trendByte);
        if (!trend.isKnown()) {
            return;
        }
        sensorTrendCache.put(key, new Sample(timeMillis, trend));
    }

    private static ExchangeTrend fromRate(float rateMgdlPerMinute, String source) {
        if (!Float.isFinite(rateMgdlPerMinute)) {
            return unknown();
        }
        if (rateMgdlPerMinute >= 3.0f) {
            return new ExchangeTrend(DOUBLE_UP, rateMgdlPerMinute, source);
        }
        if (rateMgdlPerMinute >= 2.0f) {
            return new ExchangeTrend(SINGLE_UP, rateMgdlPerMinute, source);
        }
        if (rateMgdlPerMinute >= 1.0f) {
            return new ExchangeTrend(FORTY_FIVE_UP, rateMgdlPerMinute, source);
        }
        if (rateMgdlPerMinute > -1.0f) {
            return new ExchangeTrend(FLAT, rateMgdlPerMinute, source);
        }
        if (rateMgdlPerMinute > -2.0f) {
            return new ExchangeTrend(FORTY_FIVE_DOWN, rateMgdlPerMinute, source);
        }
        if (rateMgdlPerMinute > -3.0f) {
            return new ExchangeTrend(SINGLE_DOWN, rateMgdlPerMinute, source);
        }
        return new ExchangeTrend(DOUBLE_DOWN, rateMgdlPerMinute, source);
    }

    private static ExchangeTrend fromAiDexTrendByte(int rawTrend) {
        final int signedTrend = (byte) rawTrend;
        return fromRate(signedTrend / 10.0f, "aidex");
    }

    private static ExchangeTrend fromNativeLatest(String sensorId, long timeMillis, float fallbackRate) {
        final strGlucose latest;
        try {
            latest = Natives.lastglucose();
        } catch (Throwable th) {
            return unknown();
        }
        if (latest == null || latest.time <= 0L) {
            return unknown();
        }
        if (!SensorIdentity.matches(latest.sensorid, sensorId)) {
            return unknown();
        }
        if (timeMillis > 0L && Math.abs(latest.time - timeMillis) > NATIVE_MATCH_WINDOW_MS) {
            return unknown();
        }
        return fromLegacySensorTrend(latest.trend, fallbackRate);
    }

    private static ExchangeTrend fromLegacySensorTrend(int legacyTrend, float fallbackRate) {
        final ExchangeTrend fallback = fromRate(fallbackRate, "rate");
        switch (legacyTrend) {
            case 1:
                return fallback.index == DOUBLE_DOWN
                        ? new ExchangeTrend(DOUBLE_DOWN, fallback.rateMgdlPerMinute, "native")
                        : new ExchangeTrend(SINGLE_DOWN, REPRESENTATIVE_RATES[SINGLE_DOWN], "native");
            case 2:
                return new ExchangeTrend(FORTY_FIVE_DOWN, REPRESENTATIVE_RATES[FORTY_FIVE_DOWN], "native");
            case 3:
                return new ExchangeTrend(FLAT, REPRESENTATIVE_RATES[FLAT], "native");
            case 4:
                return new ExchangeTrend(FORTY_FIVE_UP, REPRESENTATIVE_RATES[FORTY_FIVE_UP], "native");
            case 5:
                return fallback.index == DOUBLE_UP
                        ? new ExchangeTrend(DOUBLE_UP, fallback.rateMgdlPerMinute, "native")
                        : new ExchangeTrend(SINGLE_UP, REPRESENTATIVE_RATES[SINGLE_UP], "native");
            default:
                return unknown();
        }
    }

    private static ExchangeTrend fromCache(String sensorId, long timeMillis) {
        final String key = cacheKey(sensorId);
        if (key == null || timeMillis <= 0L) {
            return unknown();
        }
        final Sample sample = sensorTrendCache.get(key);
        if (sample == null) {
            return unknown();
        }
        if (Math.abs(sample.timeMillis - timeMillis) > CACHE_MATCH_WINDOW_MS) {
            sensorTrendCache.remove(key, sample);
            return unknown();
        }
        return sample.trend;
    }

    private static String cacheKey(String sensorId) {
        if (sensorId == null || sensorId.isEmpty()) {
            return null;
        }
        final String stable = SensorIdentity.resolveAppSensorId(sensorId);
        return stable != null ? stable : sensorId;
    }

    private static int normalizeIndex(int index) {
        return index >= UNKNOWN && index <= DOUBLE_DOWN ? index : UNKNOWN;
    }

    private boolean isKnown() {
        return index != UNKNOWN;
    }

    private static final class Sample {
        final long timeMillis;
        final ExchangeTrend trend;

        Sample(long timeMillis, ExchangeTrend trend) {
            this.timeMillis = timeMillis;
            this.trend = trend;
        }
    }
}

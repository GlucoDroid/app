package tk.glucodata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExchangeTrendTests {
    @Test
    public void fromRate_usesStandardSevenStateThresholds() {
        assertTrend(3.0f, ExchangeTrend.DOUBLE_UP, "DoubleUp");
        assertTrend(2.0f, ExchangeTrend.SINGLE_UP, "SingleUp");
        assertTrend(1.0f, ExchangeTrend.FORTY_FIVE_UP, "FortyFiveUp");
        assertTrend(0.99f, ExchangeTrend.FLAT, "Flat");
        assertTrend(-1.0f, ExchangeTrend.FORTY_FIVE_DOWN, "FortyFiveDown");
        assertTrend(-2.0f, ExchangeTrend.SINGLE_DOWN, "SingleDown");
        assertTrend(-3.0f, ExchangeTrend.DOUBLE_DOWN, "DoubleDown");
    }

    @Test
    public void fromRate_unknownForNonFiniteRate() {
        final ExchangeTrend trend = ExchangeTrend.fromRate(Float.NaN);

        assertEquals(ExchangeTrend.UNKNOWN, trend.index);
        assertEquals("", trend.name);
        assertTrue(Float.isNaN(trend.rateMgdlPerMinute));
    }

    @Test
    public void resolve_usesCachedAidexSignedTrendByteBeforeRateFallback() {
        final String sensorId = "X-TESTTREND";
        final long timestamp = 1_700_000_000_000L;

        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, 15);
        ExchangeTrend upward = ExchangeTrend.resolve(sensorId, timestamp + 30_000L, 0.0f);
        assertEquals(ExchangeTrend.FORTY_FIVE_UP, upward.index);
        assertEquals("aidex", upward.source);
        assertEquals(1.5f, upward.rateMgdlPerMinute, 0.0f);

        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, -11);
        ExchangeTrend downward = ExchangeTrend.resolve(sensorId, timestamp, 0.0f);
        assertEquals(ExchangeTrend.FORTY_FIVE_DOWN, downward.index);
        assertEquals("aidex", downward.source);
        assertEquals(-1.1f, downward.rateMgdlPerMinute, 0.0f);
    }

    @Test
    public void resolve_doesNotReuseExpiredAidexTrendForLaterSamples() {
        final String sensorId = "X-TESTTREND-EXPIRED";
        final long timestamp = 1_700_000_000_000L;

        ExchangeTrend.cacheAiDexTrend(sensorId, timestamp, -11);
        ExchangeTrend trend = ExchangeTrend.resolve(sensorId, timestamp + 31_000L, 3.2f);

        assertEquals(ExchangeTrend.DOUBLE_UP, trend.index);
        assertEquals("rate", trend.source);
    }

    @Test
    public void nameAndArrowForIndex_areStableForExchangePayloads() {
        assertEquals("DoubleUp", ExchangeTrend.nameForIndex(ExchangeTrend.DOUBLE_UP));
        assertEquals("SingleUp", ExchangeTrend.nameForIndex(ExchangeTrend.SINGLE_UP));
        assertEquals("FortyFiveUp", ExchangeTrend.nameForIndex(ExchangeTrend.FORTY_FIVE_UP));
        assertEquals("Flat", ExchangeTrend.nameForIndex(ExchangeTrend.FLAT));
        assertEquals("FortyFiveDown", ExchangeTrend.nameForIndex(ExchangeTrend.FORTY_FIVE_DOWN));
        assertEquals("SingleDown", ExchangeTrend.nameForIndex(ExchangeTrend.SINGLE_DOWN));
        assertEquals("DoubleDown", ExchangeTrend.nameForIndex(ExchangeTrend.DOUBLE_DOWN));

        assertEquals("\u2191\u2191", ExchangeTrend.arrowForIndex(ExchangeTrend.DOUBLE_UP));
        assertEquals("\u2192", ExchangeTrend.arrowForIndex(ExchangeTrend.FLAT));
        assertEquals("\u2193\u2193", ExchangeTrend.arrowForIndex(ExchangeTrend.DOUBLE_DOWN));
    }

    private static void assertTrend(float rate, int expectedIndex, String expectedName) {
        final ExchangeTrend trend = ExchangeTrend.fromRate(rate);

        assertEquals(expectedIndex, trend.index);
        assertEquals(expectedName, trend.name);
        assertEquals(rate, trend.rateMgdlPerMinute, 0.0f);
    }
}

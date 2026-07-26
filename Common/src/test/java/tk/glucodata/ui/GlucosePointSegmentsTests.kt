package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucosePointSegmentsTests {
    private companion object {
        const val MINUTE_MS = 60_000L
    }

    @Test
    fun split_breaksSegmentsWhenSensorChanges() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-old"),
                point(2 * MINUTE_MS, "sensor-old"),
                point(3 * MINUTE_MS, "sensor-new"),
                point(4 * MINUTE_MS, "sensor-new")
            )
        )

        assertEquals(listOf(2, 2), segments.map { it.size })
        assertEquals(listOf("sensor-old", "sensor-new"), segments.map { it.first().sensorSerial })
    }

    @Test
    fun split_breaksSegmentsWhenGapExceedsThreshold() {
        val segments = GlucosePointSegments.split(
            listOf(
                point(1 * MINUTE_MS, "sensor-a"),
                point(2 * MINUTE_MS, "sensor-a"),
                point(20 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    /**
     * NFC history slots carry the second-offset of whichever scan wrote them, so a
     * history-only stretch straddles the threshold and segments erratically: 15m47s
     * splits, the following 14m25s does not. That is intended — a hole in the data must
     * not be drawn over — and [ChartLineRun] is what keeps the resulting lone points
     * visible as dots.
     */
    @Test
    fun split_isolatesFifteenMinuteHistoryPointsThatDriftPastTheThreshold() {
        val base = 3_600_000L
        val segments = GlucosePointSegments.split(
            listOf(
                point(base, "sensor-a"),
                point(base + 15 * MINUTE_MS + 47_000L, "sensor-a"),
                point(base + 30 * MINUTE_MS + 12_000L, "sensor-a")
            )
        )

        assertEquals(listOf(1, 2), segments.map { it.size })
    }

    private fun point(timestamp: Long, sensorSerial: String) = GlucosePoint(
        value = 100f,
        time = "",
        timestamp = timestamp,
        rawValue = 95f,
        sensorSerial = sensorSerial
    )
}

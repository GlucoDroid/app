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
                point(25 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    /**
     * Libre NFC history lands every 15 minutes give or take the second-offset of the scan
     * that wrote each slot. Those points have to stay in one segment — a threshold equal
     * to the history cadence used to cut them into invisible one-point segments.
     */
    @Test
    fun split_keepsFifteenMinuteHistoryPointsInOneSegment() {
        val base = 3_600_000L
        val segments = GlucosePointSegments.split(
            listOf(
                point(base, "sensor-a"),
                point(base + 15 * MINUTE_MS + 47_000L, "sensor-a"),
                point(base + 30 * MINUTE_MS + 12_000L, "sensor-a"),
                point(base + 45 * MINUTE_MS + 51_000L, "sensor-a")
            )
        )

        assertEquals(listOf(4), segments.map { it.size })
    }

    /** One missing 15-minute slot is a real hole and must still break the curve. */
    @Test
    fun split_breaksWhenAHistorySlotIsMissing() {
        val base = 3_600_000L
        val segments = GlucosePointSegments.split(
            listOf(
                point(base, "sensor-a"),
                point(base + 15 * MINUTE_MS, "sensor-a"),
                point(base + 45 * MINUTE_MS, "sensor-a")
            )
        )

        assertEquals(listOf(2, 1), segments.map { it.size })
    }

    private fun point(timestamp: Long, sensorSerial: String) = GlucosePoint(
        value = 100f,
        time = "",
        timestamp = timestamp,
        rawValue = 95f,
        sensorSerial = sensorSerial
    )
}

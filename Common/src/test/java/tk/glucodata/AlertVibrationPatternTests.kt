package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVibrationPatternTests {

    @Test
    fun finitePatternStopsAtConfiguredDuration() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            1
        )

        assertEquals(1_000L, pattern.totalDurationMs())
        assertTrue(pattern.timings.size <= 512)
        assertEquals(pattern.timings.size, pattern.amplitudes.size)
    }

    @Test
    fun invalidDurationFallsBackToDefaultDuration() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 200, 100),
            intArrayOf(0, 255, 0),
            65_535
        )

        assertEquals(5_000L, pattern.totalDurationMs())
    }

    @Test
    fun zeroDurationBasePatternBecomesSilentFinitePattern() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, 0, 0),
            intArrayOf(0, 255, 0),
            4
        )

        assertEquals(4_000L, pattern.totalDurationMs())
        assertEquals(1, pattern.timings.size)
        assertEquals(0, pattern.amplitudes[0])
    }

    @Test
    fun negativeBaseTimingsNeverProduceNegativeSegments() {
        val pattern = AlertVibrationPattern.buildFinite(
            longArrayOf(0, -100, 200),
            intArrayOf(0, 255, 0),
            1
        )

        assertEquals(1_000L, pattern.totalDurationMs())
        assertTrue(pattern.timings.all { it >= 0L })
    }
}

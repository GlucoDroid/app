package tk.glucodata.data.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPointSelectionPolicyTests {
    private data class Point(
        val id: String,
        val timestamp: Long,
        val enabled: Boolean = true
    )

    @Test
    fun applyToPastOffDoesNotSelectFutureCalibrationPoints() {
        val points = listOf(
            Point("first", timestamp = 1_000L),
            Point("future", timestamp = 2_000L)
        )

        val selected = select(
            points = points,
            targetTimestamp = 1_500L,
            applyToPast = false,
            lockPastHistory = false
        )

        assertEquals(listOf("first"), selected.map { it.id })
    }

    @Test
    fun applyToPastOnSelectsAllEnabledPointsWhenHistoryIsNotLocked() {
        val points = listOf(
            Point("first", timestamp = 1_000L),
            Point("future", timestamp = 2_000L)
        )

        val selected = select(
            points = points,
            targetTimestamp = 1_500L,
            applyToPast = true,
            lockPastHistory = false
        )

        assertEquals(listOf("first", "future"), selected.map { it.id })
    }

    @Test
    fun applyToPastOffBeforeFirstCalibrationSelectsNothing() {
        val points = listOf(Point("first", timestamp = 1_000L))

        val selected = select(
            points = points,
            targetTimestamp = 900L,
            applyToPast = false,
            lockPastHistory = false
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun lockedHistoryCanRetainDisabledPointUntilNextActivePoint() {
        val points = listOf(
            Point("retired", timestamp = 1_000L, enabled = false),
            Point("next", timestamp = 2_000L, enabled = true)
        )

        val selected = select(
            points = points,
            targetTimestamp = 1_500L,
            applyToPast = false,
            lockPastHistory = true,
            keepDisabledHistory = true
        )

        assertEquals(listOf("retired"), selected.map { it.id })
    }

    private fun select(
        points: List<Point>,
        targetTimestamp: Long,
        applyToPast: Boolean,
        lockPastHistory: Boolean,
        keepDisabledHistory: Boolean = false
    ): List<Point> {
        return CalibrationPointSelectionPolicy.selectForTimestamp(
            allPoints = points,
            targetTimestamp = targetTimestamp,
            applyToPast = applyToPast,
            lockPastHistory = lockPastHistory,
            keepDisabledHistory = keepDisabledHistory,
            timestampOf = { it.timestamp },
            isEnabled = { it.enabled }
        )
    }
}

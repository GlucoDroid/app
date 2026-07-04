package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.GlucosePoint

class DashboardHistoryWindowPolicyTests {

    @Test
    fun oldTailFallback_isUsedOnlyWhenRecentWindowIsEmptyAndTailIsOlder() {
        val dashboardStart = 10_000L

        assertTrue(
            DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                recentPoints = emptyList(),
                latestTimestamp = 9_000L,
                startTime = dashboardStart,
            )
        )
        assertFalse(
            DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                recentPoints = listOf(GlucosePoint(value = 100f, time = "", timestamp = 9_000L)),
                latestTimestamp = 9_000L,
                startTime = dashboardStart,
            )
        )
        assertFalse(
            DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                recentPoints = emptyList(),
                latestTimestamp = dashboardStart,
                startTime = dashboardStart,
            )
        )
        assertFalse(
            DashboardHistoryWindowPolicy.shouldUseOldTailFallback(
                recentPoints = emptyList(),
                latestTimestamp = 0L,
                startTime = dashboardStart,
            )
        )
    }

    @Test
    fun fallbackStartTime_clampsAtZero() {
        assertEquals(7_000L, DashboardHistoryWindowPolicy.fallbackStartTime(10_000L, 3_000L))
        assertEquals(0L, DashboardHistoryWindowPolicy.fallbackStartTime(1_000L, 3_000L))
    }
}

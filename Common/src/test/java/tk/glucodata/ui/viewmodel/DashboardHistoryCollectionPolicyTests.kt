package tk.glucodata.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardHistoryCollectionPolicyTests {

    @Test
    fun dashboardDoesNotUseMergedCrossSensorHistoryButHistoryRouteDoes() {
        assertFalse(
            DashboardHistoryCollectionPolicy.usesMergedCrossSensorHistory(
                DashboardViewModel.CollectionMode.DASHBOARD
            )
        )
        assertTrue(
            DashboardHistoryCollectionPolicy.usesMergedCrossSensorHistory(
                DashboardViewModel.CollectionMode.FULL_HISTORY
            )
        )
    }

    @Test
    fun dashboardHistoryCoalescingSkipsOnlyInitialEmission() {
        assertFalse(
            DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                DashboardViewModel.CollectionMode.DASHBOARD,
                hasSeenHistoryEmission = false,
            )
        )
        assertTrue(
            DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                DashboardViewModel.CollectionMode.DASHBOARD,
                hasSeenHistoryEmission = true,
            )
        )
        assertFalse(
            DashboardHistoryCollectionPolicy.shouldCoalesceEmission(
                DashboardViewModel.CollectionMode.FULL_HISTORY,
                hasSeenHistoryEmission = true,
            )
        )
    }
}

package tk.glucodata.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the arrange-mode layout: reordering and what ends up visible.
 * Persistence itself is SharedPreferences and is left to the device.
 */
class StatsLayoutTests {

    @Test
    fun movingAnItemForwardsShiftsEverythingBetweenItBack() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), order.moved(0, 2))
    }

    @Test
    fun movingAnItemBackwardsShiftsEverythingBetweenItForward() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "d", "b", "c"), order.moved(3, 1))
    }

    @Test
    fun movingOutsideTheListLeavesItAlone() {
        val order = listOf("a", "b")
        assertEquals(order, order.moved(0, 5))
        assertEquals(order, order.moved(-1, 1))
        assertEquals(order, order.moved(1, 1))
    }

    @Test
    fun hiddenSectionsDropOutOfTheRenderedOrder() {
        val state = StatsLayoutState(hiddenCards = setOf(StatsCard.RISK_INDEX, StatsCard.INSIGHTS))
        assertTrue(StatsCard.RISK_INDEX !in state.visibleCards)
        assertTrue(StatsCard.INSIGHTS !in state.visibleCards)
        assertEquals(StatsCard.entries.size - 2, state.visibleCards.size)
    }

    @Test
    fun theDefaultMetricSetIsTheFourPeopleReadFirst() {
        val visible = StatsLayoutState().visibleMetrics
        assertEquals(
            listOf(
                StatsMetric.AVERAGE,
                StatsMetric.GMI,
                StatsMetric.CV,
                StatsMetric.TIGHT_RANGE
            ),
            visible
        )
    }

    @Test
    fun theRiskIndexIsNotSecondFromTheTopAnyMore() {
        // It summarises the bands above it rather than leading with a verdict.
        val order = StatsCard.DEFAULT_ORDER
        assertTrue(order.indexOf(StatsCard.RISK_INDEX) > order.indexOf(StatsCard.PATTERNS))
        assertEquals(StatsCard.OVERVIEW, order.first())
    }

    @Test
    fun onlyPinnableMetricsCanReachTheDashboard() {
        // Anything whose value needs a paragraph of explanation stays off the dashboard.
        assertTrue(StatsMetric.TIME_IN_RANGE.pinnable)
        assertTrue(StatsMetric.AVERAGE.pinnable)
        assertTrue(!StatsMetric.GVI.pinnable)
        assertTrue(!StatsMetric.PSG.pinnable)
        assertTrue(!StatsMetric.LBGI.pinnable)
    }
}

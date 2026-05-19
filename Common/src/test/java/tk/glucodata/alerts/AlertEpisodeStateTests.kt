package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEpisodeStateTests {

    @Test
    fun coveredLowerPriorityHighDoesNotFireOnDescentFromVeryHigh() {
        val episodes = AlertEpisodeState<AlertType>()

        val first = episodes.update(setOf(AlertType.VERY_HIGH, AlertType.HIGH))
        assertTrue(first.shouldTryFire(AlertType.VERY_HIGH))
        assertTrue(first.shouldTryFire(AlertType.HIGH))

        val descending = episodes.update(setOf(AlertType.HIGH))
        assertFalse(descending.shouldTryFire(AlertType.HIGH))
    }

    @Test
    fun coveredForecastHighDoesNotFireWhenActualHighClears() {
        val episodes = AlertEpisodeState<AlertType>()

        val first = episodes.update(setOf(AlertType.HIGH, AlertType.PRE_HIGH))
        assertTrue(first.shouldTryFire(AlertType.HIGH))
        assertTrue(first.shouldTryFire(AlertType.PRE_HIGH))

        val leavingActualHigh = episodes.update(setOf(AlertType.PRE_HIGH))
        assertFalse(leavingActualHigh.shouldTryFire(AlertType.PRE_HIGH))
    }

    @Test
    fun coveredLowDoesNotFireOnRecoveryFromVeryLow() {
        val episodes = AlertEpisodeState<AlertType>()

        val first = episodes.update(setOf(AlertType.VERY_LOW, AlertType.LOW))
        assertTrue(first.shouldTryFire(AlertType.VERY_LOW))
        assertTrue(first.shouldTryFire(AlertType.LOW))

        val recovering = episodes.update(setOf(AlertType.LOW))
        assertFalse(recovering.shouldTryFire(AlertType.LOW))
    }

    @Test
    fun pendingAfterSnoozeFiresOnceWhileConditionRemainsActive() {
        val episodes = AlertEpisodeState<AlertType>()

        episodes.update(setOf(AlertType.HIGH))
        episodes.markPendingAfterSnooze(AlertType.HIGH)

        val afterSnooze = episodes.update(setOf(AlertType.HIGH))
        assertTrue(afterSnooze.shouldTryFire(AlertType.HIGH))

        episodes.clearPending(AlertType.HIGH)
        val repeated = episodes.update(setOf(AlertType.HIGH))
        assertFalse(repeated.shouldTryFire(AlertType.HIGH))
    }
}

package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorExpiryAlertStateTests {
    private val warningMs = 24L * 60L * 60L * 1000L

    @Test
    fun firstObservationInsideWarningWindowDoesNotFire() {
        val state = SensorExpiryAlertState()
        val endTime = 1_000_000L

        assertFalse(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = endTime,
                nowMs = endTime - 60_000L,
                warningMs = warningMs
            )
        )
    }

    @Test
    fun crossingIntoWarningWindowFiresOnce() {
        val state = SensorExpiryAlertState()
        val endTime = 2_000_000_000L

        assertFalse(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = endTime,
                nowMs = endTime - warningMs - 1_000L,
                warningMs = warningMs
            )
        )
        assertTrue(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = endTime,
                nowMs = endTime - warningMs,
                warningMs = warningMs
            )
        )
        assertFalse(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = endTime,
                nowMs = endTime - warningMs + 60_000L,
                warningMs = warningMs
            )
        )
    }

    @Test
    fun newSensorEndTimeStartsANewEpisode() {
        val state = SensorExpiryAlertState()
        val firstEndTime = 2_000_000_000L
        val secondEndTime = firstEndTime + (14L * 24L * 60L * 60L * 1000L)

        state.shouldTrigger(true, true, false, firstEndTime, firstEndTime - warningMs - 1_000L, warningMs)
        assertTrue(state.shouldTrigger(true, true, false, firstEndTime, firstEndTime - warningMs, warningMs))

        assertFalse(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = secondEndTime,
                nowMs = secondEndTime - warningMs - 1_000L,
                warningMs = warningMs
            )
        )
        assertTrue(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = secondEndTime,
                nowMs = secondEndTime - warningMs,
                warningMs = warningMs
            )
        )
    }

    @Test
    fun disabledStateResetsBaseline() {
        val state = SensorExpiryAlertState()
        val endTime = 2_000_000_000L

        state.shouldTrigger(true, true, false, endTime, endTime - warningMs - 1_000L, warningMs)
        assertFalse(state.shouldTrigger(false, true, false, endTime, endTime - warningMs, warningMs))

        assertFalse(
            state.shouldTrigger(
                enabled = true,
                activeNow = true,
                snoozed = false,
                endTimeMs = endTime,
                nowMs = endTime - warningMs,
                warningMs = warningMs
            )
        )
    }
}

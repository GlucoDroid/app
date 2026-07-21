package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiLifetimeTests {
    @Test
    fun activationPromotesCloudRatedLifetimeToManagedTarget() {
        val cloudFourteenDays = 14L * DAY_MS

        assertEquals(
            OttaiConstants.EXTENDED_LIFETIME_MS,
            OttaiConstants.activationMaxActiveMs(cloudFourteenDays),
        )
    }

    @Test
    fun activationPreservesLongerCloudLifetime() {
        val cloudFortyDays = 40L * DAY_MS

        assertEquals(
            cloudFortyDays,
            OttaiConstants.activationMaxActiveMs(cloudFortyDays),
        )
    }

    @Test
    fun endedSensorRecoveryIsLimitedToStatusFourInsideManagedLifetime() {
        val start = 1_000_000L

        assertTrue(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 4,
                activeTimeMs = start,
                nowMs = start + 14L * DAY_MS,
            ),
        )
        assertFalse(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 3,
                activeTimeMs = start,
                nowMs = start + 14L * DAY_MS,
            ),
        )
        assertFalse(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 4,
                activeTimeMs = start,
                nowMs = start + OttaiConstants.EXTENDED_LIFETIME_MS,
            ),
        )
        assertFalse(
            OttaiConstants.shouldAttemptEndedSensorRecovery(
                commandStatus = 4,
                activeTimeMs = 0L,
                nowMs = start + 14L * DAY_MS,
            ),
        )
    }

    @Test
    fun commandStatusBelowThreeRequiresActivation() {
        assertFalse(OttaiConstants.commandNeedsActivation(-1))
        assertTrue(OttaiConstants.commandNeedsActivation(0))
        assertTrue(OttaiConstants.commandNeedsActivation(1))
        assertTrue(OttaiConstants.commandNeedsActivation(2))
        assertFalse(OttaiConstants.commandNeedsActivation(3))
        assertFalse(OttaiConstants.commandNeedsActivation(4))
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiLifetimeTests {
    @Test
    fun activationNegotiatesDownToCloudRatedLifetime() {
        val cloudFourteenDays = 14L * DAY_MS

        assertEquals(
            ((30L downTo 15L) + 14L).map { it * DAY_MS },
            OttaiConstants.activationMaxActiveCandidatesMs(cloudFourteenDays),
        )
    }

    @Test
    fun activationTriesLongerCloudLifetimeFirst() {
        val cloudFortyDays = 40L * DAY_MS

        assertEquals(
            (listOf(40L) + (30L downTo 15L)).map { it * DAY_MS },
            OttaiConstants.activationMaxActiveCandidatesMs(cloudFortyDays),
        )
    }

    @Test
    fun activationDoesNotDuplicateCloudDurationInsideLadder() {
        val cloudTwentyFiveDays = 25L * DAY_MS

        assertEquals(
            (30L downTo 15L).map { it * DAY_MS },
            OttaiConstants.activationMaxActiveCandidatesMs(cloudTwentyFiveDays),
        )
    }

    @Test
    fun expectedLifetimeUsesAcceptedMaxActive() {
        assertEquals(
            25L * DAY_MS,
            OttaiConstants.expectedLifetimeMs(
                cloudActiveExpireMs = 14L * DAY_MS,
                acceptedMaxActiveMs = 25L * DAY_MS,
            ),
        )
        assertEquals(
            14L * DAY_MS,
            OttaiConstants.expectedLifetimeMs(
                cloudActiveExpireMs = 14L * DAY_MS,
                acceptedMaxActiveMs = 0L,
            ),
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

    @Test
    fun activationRequiresExplicitUserRequest() {
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 2, explicitlyRequested = false))
        assertTrue(OttaiConstants.shouldStartActivation(commandStatus = 2, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 3, explicitlyRequested = true))
        assertFalse(OttaiConstants.shouldStartActivation(commandStatus = 4, explicitlyRequested = true))
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

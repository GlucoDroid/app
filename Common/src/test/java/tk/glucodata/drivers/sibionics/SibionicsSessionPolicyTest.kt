package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSessionPolicyTest {
    @Test
    fun repeatedHistoricalIndexOneDoesNotResetSensorState() {
        assertFalse(
            SibionicsSessionPolicy.isConfirmedIndexRestart(
                index = 1,
                previousNextIndex = 421,
                isCurrentReading = false,
                isRehydrating = false,
            ),
        )
    }

    @Test
    fun currentIndexOneConfirmsPhysicalSensorRestart() {
        assertTrue(
            SibionicsSessionPolicy.isConfirmedIndexRestart(
                index = 1,
                previousNextIndex = 421,
                isCurrentReading = true,
                isRehydrating = false,
            ),
        )
    }

    @Test
    fun newResetCycleRebasesNativeWindowOnlyAtItsBeginning() {
        assertTrue(
            SibionicsSessionPolicy.shouldRebaseNativeWindow(
                hadStartTime = false,
                index = 1,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRebaseNativeWindow(
                hadStartTime = true,
                index = 1,
            ),
        )
        assertTrue(
            SibionicsSessionPolicy.shouldRebaseNativeWindow(
                hadStartTime = false,
                index = 42,
            ),
        )
    }

    @Test
    fun liveStreamDoesNotRemainLabelledAsPartialHistory() {
        assertFalse(
            SibionicsSessionPolicy.shouldShowHistoryProgress(
                receivedCount = 11,
                totalCount = 420,
                hasReceivedLiveReading = true,
            ),
        )
        assertTrue(
            SibionicsSessionPolicy.shouldShowHistoryProgress(
                receivedCount = 11,
                totalCount = 420,
                hasReceivedLiveReading = false,
            ),
        )
    }

    @Test
    fun setupTimeoutOnlyRecoversAnActivePendingStage() {
        assertTrue(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = true,
                isStopped = false,
                isPaused = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = false,
                isStopped = false,
                isPaused = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = true,
                isStopped = true,
                isPaused = false,
            ),
        )
        assertFalse(
            SibionicsSessionPolicy.shouldRecoverSetupTimeout(
                isPending = true,
                isStopped = false,
                isPaused = true,
            ),
        )
    }

    @Test
    fun connectCallbackDeadlineStartsAfterRequestedDelay() {
        assertEquals(
            25_000L,
            SibionicsSessionPolicy.connectCallbackTimeoutDelayMs(
                requestedDelayMs = 5_000L,
                callbackTimeoutMs = 20_000L,
            ),
        )
        assertEquals(
            20_000L,
            SibionicsSessionPolicy.connectCallbackTimeoutDelayMs(
                requestedDelayMs = -1L,
                callbackTimeoutMs = 20_000L,
            ),
        )
    }
}

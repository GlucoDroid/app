package tk.glucodata.drivers.sibionics

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
}

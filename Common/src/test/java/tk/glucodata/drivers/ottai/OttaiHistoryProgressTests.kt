package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiHistoryProgressTests {

    @Test
    fun detectsCorruptedAheadProgressFromRejectedFrames() {
        assertTrue(OttaiBleManager.isPersistedDataNoAheadOfLive(29_284, 19_832))
        assertTrue(OttaiBleManager.isPersistedDataNoAheadOfLive(60_571, 19_832))
    }

    @Test
    fun keepsNormalProgressPointers() {
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(19_831, 19_832))
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(19_900, 19_832))
        assertFalse(OttaiBleManager.isPersistedDataNoAheadOfLive(-1, 19_832))
    }

    @Test
    fun corruptedAheadProgressForcesRoomGapScan() {
        assertEquals(-1, OttaiBleManager.previousDataNoForHistory(29_284, 19_832))
        assertEquals(19_831, OttaiBleManager.previousDataNoForHistory(19_831, 19_832))
        assertEquals(-1, OttaiBleManager.previousDataNoForHistory(-1, 19_832))
    }
}

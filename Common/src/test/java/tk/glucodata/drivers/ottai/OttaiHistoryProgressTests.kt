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

    @Test
    fun backfillPercentTracksTheRunningChain() {
        // A full re-add fetches ~19000 records in 270-record chunks over several minutes:
        // one chunk in is 270/18856, i.e. 1%.
        assertEquals(1, OttaiBleManager.historyBackfillPercent(0, 270, 18_856))
        assertEquals(35, OttaiBleManager.historyBackfillPercent(0, 6_750, 18_856))
        // A chain that does not start at zero measures from its own start, not from zero.
        assertEquals(50, OttaiBleManager.historyBackfillPercent(10_000, 14_000, 18_000))
    }

    @Test
    fun backfillPercentReportsNothingWhenNoChainIsRunning() {
        assertEquals(-1, OttaiBleManager.historyBackfillPercent(-1, 0, 0))
        assertEquals(-1, OttaiBleManager.historyBackfillPercent(0, 0, 0))
        assertEquals(-1, OttaiBleManager.historyBackfillPercent(500, 500, 500))
    }

    @Test
    fun backfillPercentNeverReadsAsFinishedWhileRequestsAreStillInFlight() {
        // The chain is only cleared once it completes, so 100% would read as "done" for however
        // long the tail takes; and a pointer past the end must not produce a nonsense value.
        assertEquals(99, OttaiBleManager.historyBackfillPercent(0, 18_856, 18_856))
        assertEquals(99, OttaiBleManager.historyBackfillPercent(0, 99_999, 18_856))
        assertEquals(0, OttaiBleManager.historyBackfillPercent(0, -50, 18_856))
    }
}

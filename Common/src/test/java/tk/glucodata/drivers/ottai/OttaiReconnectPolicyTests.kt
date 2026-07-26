package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiReconnectPolicyTests {

    private val now = 1_785_000_000_000L

    @Test
    fun twoDropsInsideWindowMakeLinkUnstable() {
        val drops = listOf(now - 5 * 60_000L, now - 30_000L)
        assertTrue(OttaiBleManager.isLinkUnstable(drops, now))
    }

    @Test
    fun singleDropIsNotUnstable() {
        assertFalse(OttaiBleManager.isLinkUnstable(listOf(now - 30_000L), now))
    }

    @Test
    fun dropsOlderThanWindowDoNotCount() {
        val drops = listOf(
            now - OttaiBleManager.UNSTABLE_LINK_WINDOW_MS - 60_000L,
            now - OttaiBleManager.UNSTABLE_LINK_WINDOW_MS - 1_000L,
            now - 30_000L,
        )
        assertFalse(OttaiBleManager.isLinkUnstable(drops, now))
    }

    @Test
    fun holdsFastParamsForStormRenegotiation() {
        // The exact renegotiation observed in the 2026-07-25 jamming storm logs.
        assertTrue(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
            ),
        )
    }

    @Test
    fun highSlaveLatencyAloneTriggersHold() {
        assertTrue(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 24,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
            ),
        )
    }

    @Test
    fun fastParamsAreLeftAlone() {
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 12,
                latency = 0,
                unstable = true,
                nowMs = now,
                lastReassertMs = 0L,
            ),
        )
    }

    @Test
    fun stableLinkKeepsSensorPreferredParams() {
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = false,
                nowMs = now,
                lastReassertMs = 0L,
            ),
        )
    }

    @Test
    fun reassertIsRateLimited() {
        assertFalse(
            OttaiBleManager.shouldHoldFastParams(
                intervalUnits = 308,
                latency = 4,
                unstable = true,
                nowMs = now,
                lastReassertMs = now - OttaiBleManager.PRIORITY_REASSERT_MIN_GAP_MS + 1_000L,
            ),
        )
    }

    @Test
    fun connectingStaleThresholdBacksOffAndCaps() {
        assertEquals(90_000L, OttaiBleManager.connectingStaleThresholdMs(0))
        assertEquals(180_000L, OttaiBleManager.connectingStaleThresholdMs(1))
        assertEquals(360_000L, OttaiBleManager.connectingStaleThresholdMs(2))
        assertEquals(360_000L, OttaiBleManager.connectingStaleThresholdMs(7))
        assertEquals(90_000L, OttaiBleManager.connectingStaleThresholdMs(-1))
    }
}

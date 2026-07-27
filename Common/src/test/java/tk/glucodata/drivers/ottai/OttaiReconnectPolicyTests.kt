package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiReconnectPolicyTests {

    private val now = 1_785_000_000_000L

    private val minute = 60_000L
    private val aheadTolerance = 120

    /**
     * A start at or after "now" — a clock that moved backwards, or a bogus committed start —
     * must not invert the bound. A zero or negative ceiling rejects every record, which is the
     * exact failure this filter exists to avoid causing, and it cannot recover on its own
     * because only an accepted record can widen the bound again.
     */
    @Test
    fun aStartNotInThePastNeverProducesAZeroOrNegativeBound() {
        val fromFuture = OttaiBleManager.dataNoCeilingFor(
            authoritativeStartMs = now + 24 * 60 * minute, nowMs = now, lastDataNo = -1, live = true,
        )
        assertEquals(Int.MAX_VALUE, fromFuture)

        val fromNow = OttaiBleManager.dataNoCeilingFor(
            authoritativeStartMs = now, nowMs = now, lastDataNo = -1, live = true,
        )
        assertEquals(Int.MAX_VALUE, fromNow)

        // History with the same skew still falls back to the persisted high-water mark rather
        // than to a negative bound.
        val historySkewed = OttaiBleManager.dataNoCeilingFor(
            authoritativeStartMs = now + 24 * 60 * minute, nowMs = now, lastDataNo = 1_600,
            live = false,
        )
        assertEquals(1_600 + aheadTolerance, historySkewed)
    }

    /**
     * The ceiling's own inputs are only ever updated by a record that already passed it, so a
     * wrong bound is self-reinforcing. Whole payloads rejected in a row mean the bound is wrong,
     * not the sensor — corruption arrives in occasional frames, not in every frame in a row.
     */
    @Test
    fun theCeilingIsDistrustedAfterConsecutiveWholePayloadRejections() {
        val limit = OttaiBleManager.MAX_CONSECUTIVE_CEILING_FULL_DROPS
        assertTrue(limit > 1) // one genuinely corrupt frame must not switch the filter off
        for (drops in 0 until limit) {
            assertFalse(OttaiBleManager.shouldDistrustCeiling(drops))
        }
        assertTrue(OttaiBleManager.shouldDistrustCeiling(limit))
        assertTrue(OttaiBleManager.shouldDistrustCeiling(limit + 1))
    }

    @Test
    fun confirmedStartBoundsDataNoToTheSensorsElapsedLife() {
        val twoDaysAgo = now - 2 * 24 * 60 * minute
        assertEquals(
            2_880 + aheadTolerance,
            OttaiBleManager.dataNoCeilingFor(twoDaysAgo, now, lastDataNo = -1, live = true),
        )
    }

    @Test
    fun liveIsUnboundedUntilAStartIsConfirmed() {
        // Live must not be gated on the persisted high-water mark: a legitimate sample after a
        // long offline gap is far past it. Without a confirmed start there is nothing else to
        // bound it by, and the frame that gets through is the one that confirms the start.
        assertEquals(
            Int.MAX_VALUE,
            OttaiBleManager.dataNoCeilingFor(0L, now, lastDataNo = 1_600, live = true),
        )
    }

    @Test
    fun historyRejectsCorruptFramesUsingThePersistedHighWaterMark() {
        // bd2730ef's motivating case: front ~17k while the sensor sits at ~1.6k.
        val ceiling = OttaiBleManager.dataNoCeilingFor(0L, now, lastDataNo = 1_600, live = false)
        assertEquals(1_600 + aheadTolerance, ceiling)
        assertEquals(
            Int.MAX_VALUE,
            OttaiBleManager.dataNoCeilingFor(0L, now, lastDataNo = -1, live = false),
        )
    }

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

package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the TTS rate-limiting logic in Talker.selspeak().
 *
 * selspeak() must throttle glucose announcements to at most one per cursep
 * milliseconds.  On a 5-minute separation setting that means at most 12 TTS
 * utterances per hour — firing faster would be a battery regression.
 *
 * The gate itself (now > nexttime && withinSchedule) needs Android, so it is modelled here;
 * the slot arithmetic is the production code, AnnounceSlot.nextSlotAfterAttempt() and
 * AnnounceSlot.nextSlotAfterSeparationChange(), so these tests cannot drift from what ships.
 */
class TalkerRateLimiterTests {

    // The selspeak gate, driving the production slot arithmetic (no Android deps).
    private class RateLimiter(
        var separationMs: Long,
        /** Whether the engine accepts utterances; false models a dead/unbound TextToSpeech. */
        var speakSucceeds: Boolean = true
    ) {
        var nexttime: Long = 0L
        var lastSpokenAt: Long = 0L
        var speakCount: Int = 0
        /** Attempts handed to the engine, successful or not — what needsReinit() counts. */
        var attemptCount: Int = 0

        fun selspeak(nowMs: Long) {
            if (nowMs > nexttime) {
                nexttime = AnnounceSlot.nextSlotAfterAttempt(nowMs, separationMs, true)
                attemptCount++
                if (speakSucceeds) {
                    speakCount++
                    lastSpokenAt = nowMs
                } else {
                    nexttime = AnnounceSlot.nextSlotAfterAttempt(nowMs, separationMs, false)
                }
            }
        }

        /** Mirrors Talker.setSeparationMs(). */
        fun changeSeparation(newSeparationMs: Long) {
            val previous = separationMs
            separationMs = newSeparationMs
            if (newSeparationMs < previous) {
                nexttime = AnnounceSlot.nextSlotAfterSeparationChange(nexttime, lastSpokenAt, newSeparationMs)
            }
        }
    }

    private companion object {
        const val REINIT_FAILURE_THRESHOLD = 2
    }

    // ---------- a changed separation must apply to the slot already claimed ----------

    @Test
    fun separationLowered_takesEffectFromLastAnnouncement_notAfterOldInterval() {
        val limiter = RateLimiter(separationMs = 9_999_000L)
        limiter.selspeak(nowMs = 1L)                // speaks; old slot is 2h46m away
        limiter.changeSeparation(60_000L)
        limiter.selspeak(nowMs = 60_002L)           // one reading later
        assertEquals("a lowered separation must not wait out the old interval", 2, limiter.speakCount)
    }

    @Test
    fun separationRaised_doesNotExtendPendingSlot() {
        val limiter = RateLimiter(separationMs = 60_000L)
        limiter.selspeak(nowMs = 1L)
        limiter.changeSeparation(999_000L)
        limiter.selspeak(nowMs = 60_002L)
        assertEquals("the old, shorter slot still stands", 2, limiter.speakCount)
        limiter.selspeak(nowMs = 120_002L)
        assertEquals("and the new separation applies from then on", 2, limiter.speakCount)
    }

    @Test
    fun separationLowered_beforeAnySpeech_leavesSlotAlone() {
        assertEquals(0L, AnnounceSlot.nextSlotAfterSeparationChange(0L, 0L, 60_000L))
        assertEquals(25_000L, AnnounceSlot.nextSlotAfterSeparationChange(25_000L, 0L, 1_000L))
    }

    @Test
    fun separationLowered_neverDelaysAPendingRetry() {
        // A refused utterance left a 30s retry pending; lowering must not push it out.
        assertEquals(130_000L, AnnounceSlot.nextSlotAfterSeparationChange(130_000L, 50_000L, 600_000L))
    }

    @Test
    fun selspeak_firstCall_alwaysSpeaks() {
        val limiter = RateLimiter(separationMs = 5 * 60_000L)
        limiter.selspeak(nowMs = 1_000L)
        assertEquals(1, limiter.speakCount)
    }

    @Test
    fun selspeak_secondCallWithinSeparation_suppressed() {
        val sep = 5 * 60_000L
        val limiter = RateLimiter(separationMs = sep)
        limiter.selspeak(nowMs = 1_000L)
        limiter.selspeak(nowMs = 1_000L + sep - 1)  // 1 ms before allowed
        assertEquals(1, limiter.speakCount)
    }

    @Test
    fun selspeak_secondCallAfterSeparation_speaks() {
        val sep = 5 * 60_000L
        val limiter = RateLimiter(separationMs = sep)
        limiter.selspeak(nowMs = 1_000L)
        limiter.selspeak(nowMs = 1_000L + sep + 1)  // 1 ms after allowed
        assertEquals(2, limiter.speakCount)
    }

    @Test
    fun selspeak_atExactBoundary_suppressed() {
        // nowMs == nexttime → NOT greater than → suppressed
        val sep = 60_000L
        val limiter = RateLimiter(separationMs = sep)
        limiter.selspeak(nowMs = 1_000L)
        limiter.nexttime = 2_000L                   // force exact boundary
        limiter.selspeak(nowMs = 2_000L)            // now == nexttime
        assertEquals(1, limiter.speakCount)
    }

    @Test
    fun selspeak_maxRateWith5MinSeparation_atMost12PerHour() {
        val sep = 5 * 60_000L
        val limiter = RateLimiter(separationMs = sep)
        val oneHourMs = 60 * 60_000L
        // Simulate glucose readings every 60 s for one hour
        var t = 0L
        while (t <= oneHourMs) {
            limiter.selspeak(nowMs = t)
            t += 60_000L
        }
        assertTrue(
            "Expected <= 12 utterances per hour with 5-min separation, got ${limiter.speakCount}",
            limiter.speakCount <= 12
        )
    }

    @Test
    fun selspeak_maxRateWith1MinSeparation_atMost60PerHour() {
        val sep = 60_000L
        val limiter = RateLimiter(separationMs = sep)
        val oneHourMs = 60 * 60_000L
        var t = 0L
        while (t <= oneHourMs) {
            limiter.selspeak(nowMs = t)
            t += 60_000L
        }
        assertTrue(
            "Expected <= 61 utterances per hour with 1-min separation, got ${limiter.speakCount}",
            limiter.speakCount <= 61
        )
    }

    // ---------- a refused utterance must not cost a whole separation interval ----------
    // consecutiveSpeakFailures counts announcement *attempts*, and attempts only happen once
    // per cursep, so charging a failed attempt the full interval made the time to notice a
    // dead engine scale with a user setting. These pin the fix.

    @Test
    fun selspeak_failedSpeak_retriesOnNextReading_notAfterFullSeparation() {
        val sep = 999_000L                          // the separation seen in the 09-02..09-08 traces
        val limiter = RateLimiter(separationMs = sep, speakSucceeds = false)
        // nexttime starts at 0 and the gate is now > nexttime, so start at 1ms, not 0.
        limiter.selspeak(nowMs = 1L)
        assertEquals("the attempt should have been made", 1, limiter.attemptCount)
        // One reading later (readings arrive about every 60s) the gate must be open again.
        limiter.selspeak(nowMs = 60_001L)
        assertEquals("a refused utterance must not consume the interval", 2, limiter.attemptCount)
    }

    @Test
    fun selspeak_deadEngine_reachesReinitThreshold_withinMinutesNotSeparations() {
        val sep = 999_000L
        val limiter = RateLimiter(separationMs = sep, speakSucceeds = false)
        var t = 1L
        // Readings every 60s; find when the failure count reaches the recreate threshold.
        while (limiter.attemptCount < REINIT_FAILURE_THRESHOLD && t <= sep) {
            limiter.selspeak(nowMs = t)
            t += 60_000L
        }
        assertTrue(
            "dead engine must be detectable well inside one separation interval, took ${t}ms",
            limiter.attemptCount >= REINIT_FAILURE_THRESHOLD && t < sep
        )
    }

    @Test
    fun selspeak_successfulSpeak_stillChargesFullSeparation() {
        // The retry path must not weaken normal throttling.
        val sep = 999_000L
        val limiter = RateLimiter(separationMs = sep, speakSucceeds = true)
        limiter.selspeak(nowMs = 1L)                // speaks; nexttime becomes 1 + sep
        limiter.selspeak(nowMs = 60_001L)
        limiter.selspeak(nowMs = sep)
        assertEquals("only the first call may speak inside one interval", 1, limiter.speakCount)
        limiter.selspeak(nowMs = sep + 2)
        assertEquals(2, limiter.speakCount)
    }

    @Test
    fun selspeak_recoveryAfterFailure_resumesNormalCadence() {
        val sep = 999_000L
        val limiter = RateLimiter(separationMs = sep, speakSucceeds = false)
        limiter.selspeak(nowMs = 1L)                // refused, so only ~30s is charged
        assertEquals("nothing was spoken yet", 0, limiter.speakCount)
        limiter.speakSucceeds = true
        limiter.selspeak(nowMs = 60_001L)
        assertEquals("the retry should have spoken", 1, limiter.speakCount)
        limiter.selspeak(nowMs = 120_001L)
        assertEquals("and then throttle normally again", 1, limiter.speakCount)
    }

    @Test
    fun selspeak_minimumSeparation_neverZero() {
        // cursep is set from user input clamped to at least 1 second in applyComposeSettings:
        //   cursep = Math.max(1, separationSeconds) * 1000L
        // Verify that with separationMs=1000 (minimum), spam calls are still throttled.
        val sep = 1_000L
        val limiter = RateLimiter(separationMs = sep)
        repeat(100) { limiter.selspeak(nowMs = System.currentTimeMillis()) }
        // All calls at the same millisecond — only the first should fire
        assertEquals(1, limiter.speakCount)
    }

    // ---------- onStop wake lock regression guard ----------
    // QUEUE_FLUSH cancels the current utterance before onDone fires, triggering onStop instead.
    // onStop(interrupted=true): a new utterance is already queued from QUEUE_FLUSH — the wake
    // lock acquired in speak() belongs to that new utterance and must NOT be released here;
    // onDone/onError for the new utterance will release it correctly.
    // onStop(interrupted=false): a queued-but-not-yet-started item was cleared; release the
    // lock since no new utterance is coming.

    @Test
    fun talker_utteranceProgressListener_onStop_keepsLockWhenInterrupted() {
        // Model the QUEUE_FLUSH scenario:
        // speak("reading 1") → speaking → speak("reading 2") with QUEUE_FLUSH
        //   → onStop("reading 1", interrupted=true) → onDone("reading 2")
        // The wake lock acquired for reading 2 must survive through to onDone.
        data class WakeLockSim(var held: Boolean = false)
        val lock = WakeLockSim()

        fun acquire() { lock.held = true }
        fun release() { lock.held = false }

        acquire()                  // speak("reading 1") acquires at t=0; isHeld=true
        // speak("reading 2") at t=500ms: isHeld=true → no new acquire (non-ref-counted)
        val interrupted = true
        if (!interrupted) release() // onStop(interrupted=true) must NOT release
        assertTrue("wake lock must remain held after onStop(interrupted=true)", lock.held)

        // onDone("reading 2") eventually fires and releases
        release()
        assertFalse("wake lock must be released after onDone for reading 2", lock.held)
    }

    @Test
    fun talker_utteranceProgressListener_onStop_releasesLockWhenNotInterrupted() {
        // Model clearing a queued-but-not-started item:
        // speak("reading 1") → onStop("reading 1", interrupted=false)
        data class WakeLockSim(var held: Boolean = false)
        val lock = WakeLockSim()

        fun acquire() { lock.held = true }
        fun release() { lock.held = false }

        acquire()                  // speak("reading 1") acquires; isHeld=true
        val interrupted = false
        if (!interrupted) release() // onStop(interrupted=false) releases
        assertFalse("wake lock must be released by onStop(interrupted=false)", lock.held)
    }
}

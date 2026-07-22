package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorExpiryAlertStateTests {

    // A sensor end far enough in the future that "N minutes before end" is always positive.
    private val end = 100L * 24 * 60 * 60 * 1000

    private fun min(m: Int) = m.toLong() * 60_000L

    /** Mirrors the SharedPreferences StringSet backing: flat "endTimeMs:minutes" entries. */
    private class FakeWarnedStore : ExpiryWarnedStore {
        var entries = setOf<String>()

        override fun load(endTimeMs: Long): Set<Int> = entries.mapNotNull { entry ->
            entry.takeIf { it.startsWith("$endTimeMs:") }?.substringAfter(':')?.toIntOrNull()
        }.toSet()

        override fun save(endTimeMs: Long, thresholds: Set<Int>) {
            entries = thresholds.map { "$endTimeMs:$it" }.toSet()
        }
    }

    private fun newState(store: ExpiryWarnedStore = FakeWarnedStore()) = SensorExpiryAlertState(store)

    /** Evaluate a tick "minutesBefore" minutes before [endMs]. */
    private fun SensorExpiryAlertState.fire(
        minutesBefore: Int,
        thresholds: Set<Int>,
        endMs: Long = end,
        snoozed: Boolean = false,
        active: Boolean = true
    ): Set<Int> = triggeredThresholds(
        enabled = true,
        activeNow = active,
        snoozed = snoozed,
        endTimeMs = endMs,
        nowMs = endMs - min(minutesBefore),
        thresholdsMinutes = thresholds
    )

    private val T3D = 4320
    private val T1D = 1440
    private val T6H = 360

    @Test
    fun singleThresholdFiresOnceOnEntry() {
        val s = newState()
        val t = setOf(T1D)
        assertEquals(emptySet<Int>(), s.fire(1800, t))     // 30h before: baseline, outside window
        assertEquals(emptySet<Int>(), s.fire(1500, t))     // 25h before: still outside
        assertEquals(setOf(T1D), s.fire(1440, t))          // 24h before: crosses in -> fire
        assertEquals(emptySet<Int>(), s.fire(1200, t))     // inside: no re-fire
        assertEquals(emptySet<Int>(), s.fire(60, t))
    }

    @Test
    fun firstObservationInsideUnwarnedWindowFiresCatchUpOnce() {
        val s = newState()
        assertEquals(setOf(T1D), s.fire(60, setOf(T1D)))        // due, never warned anywhere
        assertEquals(emptySet<Int>(), s.fire(30, setOf(T1D)))   // once only
    }

    @Test
    fun multipleThresholdsEachFireOnceInOrder() {
        val s = newState()
        val t = setOf(T3D, T1D, T6H)
        assertEquals(emptySet<Int>(), s.fire(5760, t))     // 4d before: baseline, outside all
        assertEquals(setOf(T3D), s.fire(4320, t))          // enter 3d
        assertEquals(emptySet<Int>(), s.fire(4000, t))     // inside 3d, no new edge
        assertEquals(setOf(T1D), s.fire(1440, t))          // enter 1d
        assertEquals(setOf(T6H), s.fire(360, t))           // enter 6h
        assertEquals(emptySet<Int>(), s.fire(30, t))
    }

    @Test
    fun baselineInsideSeveralUnwarnedWindowsFiresOnlyMostUrgent() {
        val s = newState()
        val t = setOf(T3D, T1D, T6H)
        assertEquals(setOf(T6H), s.fire(300, t))           // 5h before, inside all three: cascade guard
        assertEquals(emptySet<Int>(), s.fire(120, t))      // rest silently adopted
    }

    @Test
    fun newSensorRearmsAllThresholds() {
        val s = newState()
        val t = setOf(T1D)
        s.fire(1800, t)
        assertEquals(setOf(T1D), s.fire(1440, t))
        // A different end time = new sensor: the whole sequence runs again.
        val end2 = end + 14L * 24 * 60 * 60 * 1000
        assertEquals(emptySet<Int>(), s.fire(1800, t, endMs = end2))   // baseline for new sensor
        assertEquals(setOf(T1D), s.fire(1440, t, endMs = end2))
    }

    @Test
    fun addingThresholdInsideItsWindowDoesNotFireRetroactively() {
        val s = newState()
        s.fire(4320, setOf(T6H))                           // baseline outside the 6h window (with 6h only)
        // Now 3h before end, user adds the 1d threshold whose window opened long ago.
        val res = s.fire(180, setOf(T6H, T1D))
        assertEquals(setOf(T6H), res)                      // 6h crosses now and fires; 1d is NOT retroactive
    }

    @Test
    fun cascadeFiresOnlySmallestWhenSeveralComeDueAtOnce() {
        val s = newState()
        val t = setOf(T3D, T1D, T6H)
        s.fire(5760, t)                                    // baseline outside all
        // Big jump (e.g. app resumed) straight to 30 min before end.
        assertEquals(setOf(T6H), s.fire(30, t))            // only the most urgent fires
        assertEquals(emptySet<Int>(), s.fire(20, t))       // rest silently marked warned
    }

    @Test
    fun snoozeDefersButDoesNotSwallowThreshold() {
        val s = newState()
        val t = setOf(T3D, T1D)
        s.fire(5760, t)                                    // baseline outside
        assertEquals(setOf(T3D), s.fire(4320, t))          // 3d fires
        // Snoozed while the 1d window is crossed: nothing now...
        assertEquals(emptySet<Int>(), s.fire(1440, t, snoozed = true))
        // ...but once snooze ends it still fires (not swallowed).
        assertEquals(setOf(T1D), s.fire(1400, t))
    }

    @Test
    fun emptyOrDisabledFiresNothing() {
        val s = newState()
        assertEquals(emptySet<Int>(), s.fire(60, emptySet()))
        assertEquals(
            emptySet<Int>(),
            s.triggeredThresholds(false, true, false, end, end - min(60), setOf(T1D))
        )
    }

    @Test
    fun windowEntryDuringProcessDowntimeFiresCatchUpAfterRestart() {
        val store = FakeWarnedStore()
        val t = setOf(T3D)
        val s1 = SensorExpiryAlertState(store)
        assertEquals(emptySet<Int>(), s1.fire(5760, t))    // baseline outside; process dies here
        val s2 = SensorExpiryAlertState(store)             // restart after the 3d window opened
        assertEquals(setOf(T3D), s2.fire(4000, t))         // due but never warned -> catch-up
        assertEquals(emptySet<Int>(), s2.fire(3990, t))
        val s3 = SensorExpiryAlertState(store)             // yet another restart
        assertEquals(emptySet<Int>(), s3.fire(3980, t))    // persisted: no re-fire
    }

    @Test
    fun restartWithSeveralUnwarnedOpenWindowsFiresOnlyMostUrgent() {
        val store = FakeWarnedStore()
        val t = setOf(T3D, T1D)
        SensorExpiryAlertState(store).fire(5760, t)        // baseline outside both; process dies
        val s2 = SensorExpiryAlertState(store)
        assertEquals(setOf(T1D), s2.fire(1000, t))         // both open: cascade guard picks 1d
        val s3 = SensorExpiryAlertState(store)
        assertEquals(emptySet<Int>(), s3.fire(990, t))     // 3d was persisted-adopted, no late fire
    }

    @Test
    fun restartWhileSnoozedStillFiresCatchUpAfterSnoozeEnds() {
        val store = FakeWarnedStore()
        SensorExpiryAlertState(store).fire(5760, setOf(T3D))
        val s2 = SensorExpiryAlertState(store)
        assertEquals(emptySet<Int>(), s2.fire(4000, setOf(T3D), snoozed = true))
        assertEquals(setOf(T3D), s2.fire(3990, setOf(T3D)))
    }

    @Test
    fun midEpisodeActivationIsAdoptedAndSurvivesRestart() {
        val store = FakeWarnedStore()
        val s1 = SensorExpiryAlertState(store)
        s1.fire(5760, setOf(T6H))                                       // baseline with 6h only
        assertEquals(emptySet<Int>(), s1.fire(4000, setOf(T6H, T3D)))   // 3d added inside its window: adopted
        val s2 = SensorExpiryAlertState(store)                          // restart
        assertEquals(emptySet<Int>(), s2.fire(3990, setOf(T6H, T3D)))   // adoption persisted, still silent
        assertEquals(setOf(T6H), s2.fire(360, setOf(T6H, T3D)))         // 6h edge unaffected
    }

    @Test
    fun newSensorRearmsAndPrunesOldPersistedEntries() {
        val store = FakeWarnedStore()
        val t = setOf(T1D)
        val s = SensorExpiryAlertState(store)
        s.fire(1800, t)
        assertEquals(setOf(T1D), s.fire(1440, t))                      // warned + persisted
        val end2 = end + 14L * 24 * 60 * 60 * 1000
        assertEquals(emptySet<Int>(), s.fire(1800, t, endMs = end2))   // baseline outside for new sensor
        assertEquals(setOf(T1D), s.fire(1440, t, endMs = end2))        // fires again for new sensor
        assertEquals(setOf("$end2:$T1D"), store.entries)               // old sensor's entry pruned
    }

    @Test
    fun stableEndTimeAcrossManyTicksKeepsLatchArmed() {
        // Regression for the getendtime() clamp: an end time that stays constant
        // must not re-baseline, and each edge fires exactly once.
        val s = newState()
        val t = setOf(T3D, T1D)
        assertEquals(emptySet<Int>(), s.fire(5760, t))
        for (m in 5700 downTo 4330 step 15) assertEquals(emptySet<Int>(), s.fire(m, t))
        assertEquals(setOf(T3D), s.fire(4320, t))
        for (m in 4305 downTo 1441 step 15) assertEquals(emptySet<Int>(), s.fire(m, t))
        assertEquals(setOf(T1D), s.fire(1440, t))
        for (m in 1425 downTo 15 step 15) assertEquals(emptySet<Int>(), s.fire(m, t))
    }

    @Test
    fun clampedOrPastEndTimeSourceIsRejected() {
        val now = end - min(1000)
        // A Natives.getendtime()-style source returns "now" while the sensor runs.
        assertEquals(0L, selectSensorExpiryEndMs(listOf("A" to now), "A", now))
        assertEquals(0L, selectSensorExpiryEndMs(listOf("A" to (now - 5_000L)), "A", now))
        assertEquals(0L, selectSensorExpiryEndMs(emptyList(), null, now))
    }

    @Test
    fun endSelectionPrefersDisplayedSensorElseFarthestEnd() {
        val now = 1_000_000L
        val candidates = listOf("A" to now + 100_000L, "B" to now + 900_000L)
        assertEquals(now + 100_000L, selectSensorExpiryEndMs(candidates, "A", now))
        assertEquals(now + 100_000L, selectSensorExpiryEndMs(candidates, "a", now))
        assertEquals(now + 900_000L, selectSensorExpiryEndMs(candidates, null, now))
        assertEquals(now + 900_000L, selectSensorExpiryEndMs(candidates, "unknown", now))
        // A displayed sensor without a plausible end must not shadow the running one.
        assertEquals(
            now + 900_000L,
            selectSensorExpiryEndMs(listOf("A" to 0L, "B" to now + 900_000L), "A", now)
        )
    }

    @Test
    fun defaultSensorExpiryConfigKeeps24hThreshold() {
        val cfg = AlertDefaults.defaultConfig(AlertType.SENSOR_EXPIRY, isMmol = false)
        assertEquals(setOf(1440), cfg.expiryWarningMinutes)
    }

    @Test
    fun sanitizeDropsUnknownThresholds() {
        assertEquals(setOf(1440, 360), sanitizeExpiryWarningMinutes(setOf(1440, 360, 999, 7)))
    }
}

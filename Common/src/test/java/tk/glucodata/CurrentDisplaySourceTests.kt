package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentDisplaySourceTests {

    @Test
    fun resolveFromLive_usesMatchedHistoryRawInRawPrimaryMode() {
        val timestamp = 1_700_000_000_000L
        val recentPoints = listOf(GlucosePoint(timestamp, 75f, 31f))

        val snapshot = CurrentDisplaySource.resolveFromLive(
            liveValueText = null,
            liveNumericValue = 75f,
            rate = 0f,
            targetTimeMillis = timestamp,
            sensorId = "8760080A00070000",
            sensorGen = 0,
            index = 0,
            source = "test",
            recentPoints = recentPoints,
            viewMode = 1,
            isMmol = false
        )

        requireNotNull(snapshot)
        assertEquals(31f, snapshot.rawValue)
        assertEquals(31f, snapshot.primaryValue)
        assertEquals(75f, snapshot.autoValue)
    }

    @Test
    fun prepareRecentPointsForCurrent_smoothsLivePointBeforeTrendResolution() {
        val minute = 60_000L
        val recentPoints = listOf(
            GlucosePoint(0L * minute, 100f, 90f),
            GlucosePoint(5L * minute, 100f, 90f),
            GlucosePoint(10L * minute, 100f, 90f)
        )
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = 15L * minute,
            valueText = "",
            numericValue = 130f,
            rawNumericValue = Float.NaN,
            rate = 0f,
            sensorId = "test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 0,
            smoothAllData = true,
            smoothingMinutes = 10,
            collapseChunks = false
        )

        assertEquals(15L * minute, processed.last().timestamp)
        assertEquals(115f, processed.last().value, 0.001f)
    }

    @Test
    fun prepareRecentPointsForCurrent_usesLiveValueAsRawFallbackInRawMode() {
        val minute = 60_000L
        val recentPoints = listOf(
            GlucosePoint(0L, 100f, 80f),
            GlucosePoint(5L * minute, 101f, 82f)
        )
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = 10L * minute,
            valueText = "",
            numericValue = 90f,
            rawNumericValue = Float.NaN,
            rate = 0f,
            sensorId = "test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 1,
            smoothAllData = false,
            smoothingMinutes = 0,
            collapseChunks = false
        )

        assertEquals(10L * minute, processed.last().timestamp)
        assertEquals(90f, processed.last().rawValue, 0.001f)
    }

    @Test
    fun prepareRecentPointsForCurrent_keepsHistoryRawWhenLiveRawIsOnlyFallback() {
        val timestamp = 10L * 60_000L
        val recentPoints = listOf(GlucosePoint(timestamp, 101f, 82f))
        val current = CurrentGlucoseSource.Snapshot(
            timeMillis = timestamp,
            valueText = "",
            numericValue = 105f,
            rawNumericValue = Float.NaN,
            rate = 0f,
            sensorId = "test",
            sensorGen = 0,
            index = 0,
            source = "test"
        )

        val processed = CurrentDisplaySource.prepareRecentPointsForCurrent(
            recentPoints = recentPoints,
            current = current,
            historyStart = 0L,
            viewMode = 1,
            smoothAllData = false,
            smoothingMinutes = 0,
            collapseChunks = false
        )

        assertEquals(105f, processed.last().value, 0.001f)
        assertEquals(82f, processed.last().rawValue, 0.001f)
    }
}

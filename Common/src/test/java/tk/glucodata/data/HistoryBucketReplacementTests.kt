package tk.glucodata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryBucketReplacementTests {

    @Test
    fun plan_groupsReadingsIntoDistinctBucketsAndPreservesTimestampOrder() {
        val plan = HistoryBucketReplacement.plan(
            readings = listOf(
                HistoryReading(timestamp = 60_000L, sensorSerial = "sensor", value = 100f, rawValue = 100f, rate = null),
                HistoryReading(timestamp = 61_000L, sensorSerial = "sensor", value = 101f, rawValue = 101f, rate = null),
                HistoryReading(timestamp = 119_999L, sensorSerial = "sensor", value = 102f, rawValue = 102f, rate = null),
                HistoryReading(timestamp = 120_000L, sensorSerial = "sensor", value = 103f, rawValue = 103f, rate = null),
                HistoryReading(timestamp = 120_000L, sensorSerial = "sensor", value = 103f, rawValue = 103f, rate = null),
            ),
            bucketDurationMs = 60_000L,
        )

        requireNotNull(plan)
        assertEquals(listOf(1L, 2L), plan.bucketIds)
        assertEquals(listOf(119_999L, 120_000L), plan.protectedTimestamps)
    }

    @Test
    fun collapseReadings_keepsOneBestReadingPerBucket() {
        val collapsed = HistoryBucketReplacement.collapseReadings(
            readings = listOf(
                HistoryReading(timestamp = 60_000L, sensorSerial = "sensor", value = 0f, rawValue = 100f, rate = null),
                HistoryReading(timestamp = 61_000L, sensorSerial = "sensor", value = 101f, rawValue = 101f, rate = null),
                HistoryReading(timestamp = 62_000L, sensorSerial = "sensor", value = 102f, rawValue = 102f, rate = 1f),
                HistoryReading(timestamp = 120_000L, sensorSerial = "sensor", value = 103f, rawValue = 103f, rate = null),
            ),
            bucketDurationMs = 60_000L,
        )

        assertEquals(listOf(62_000L, 120_000L), collapsed.map { it.timestamp })
        assertEquals(listOf(102f, 103f), collapsed.map { it.value })
    }

    @Test
    fun plan_returnsNullWhenNoValidTimestampsRemain() {
        val plan = HistoryBucketReplacement.plan(
            readings = listOf(
                HistoryReading(timestamp = 0L, sensorSerial = "sensor", value = 100f, rawValue = 100f, rate = null),
                HistoryReading(timestamp = -1L, sensorSerial = "sensor", value = 101f, rawValue = 101f, rate = null),
            ),
            bucketDurationMs = 60_000L,
        )

        assertNull(plan)
    }
}

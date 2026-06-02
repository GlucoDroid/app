package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSensorDisplayTests {
    @Test
    fun peerLookupKeepsLatestReadingPerSensorPerMinuteBucket() {
        val timestamp = 1_800_000L
        val readings = listOf(
            point(sensor = "peer-a", timestamp = timestamp + 5_000L, value = 6.1f),
            point(sensor = "peer-a", timestamp = timestamp + 50_000L, value = 6.4f),
            point(sensor = "peer-b", timestamp = timestamp + 10_000L, value = 5.7f),
        )

        val peers = MultiSensorDisplay.pointsAtTimestamp(
            points = readings,
            timestamp = timestamp,
            preferredSerial = "main"
        )

        assertEquals(listOf("peer-a", "peer-b"), peers.map { it.sensorSerial })
        assertEquals(6.4f, peers[0].value)
    }

    @Test
    fun peerLookupOrdersPreferredSensorFirst() {
        val timestamp = 2_400_000L
        val readings = listOf(
            point(sensor = "peer-a", timestamp = timestamp + 10_000L, value = 4.1f),
            point(sensor = "main", timestamp = timestamp + 20_000L, value = 4.8f),
            point(sensor = "peer-b", timestamp = timestamp + 30_000L, value = 5.2f),
        )

        val values = MultiSensorDisplay.pointsAtTimestamp(
            points = readings,
            timestamp = timestamp,
            preferredSerial = "main"
        )

        assertEquals("main", values.first().sensorSerial)
    }

    @Test
    fun readingGroupsFilterPrimaryOutOfPeers() {
        val timestamp = 3_000_000L
        val primary = point(sensor = "main", timestamp = timestamp, value = 4.8f)
        val groups = MultiSensorDisplay.buildReadingGroups(
            primaryReadings = listOf(primary),
            peerHistory = listOf(
                primary.copy(timestamp = timestamp + 15_000L),
                point(sensor = "peer-a", timestamp = timestamp + 20_000L, value = 5.1f)
            ),
            preferredSerial = "main"
        )

        assertEquals(1, groups.size)
        assertEquals(primary, groups[0].primary)
        assertEquals(listOf("peer-a"), groups[0].peers.map { it.sensorSerial })
    }

    @Test
    fun peerLookupIgnoresBucketsThatWereNotRequested() {
        val requested = 4_200_000L
        val readings = listOf(
            point(sensor = "peer-a", timestamp = requested + 20_000L, value = 5.1f),
            point(sensor = "peer-b", timestamp = requested + 90_000L, value = 7.1f),
        )

        val peers = MultiSensorDisplay.pointsAtTimestamp(
            points = readings,
            timestamp = requested,
            preferredSerial = "main"
        )

        assertEquals(listOf("peer-a"), peers.map { it.sensorSerial })
        assertTrue(peers.none { it.sensorSerial == "peer-b" })
    }

    private fun point(sensor: String, timestamp: Long, value: Float): GlucosePoint =
        GlucosePoint(
            value = value,
            time = "",
            timestamp = timestamp,
            rawValue = value,
            rate = null,
            sensorSerial = sensor
        )
}

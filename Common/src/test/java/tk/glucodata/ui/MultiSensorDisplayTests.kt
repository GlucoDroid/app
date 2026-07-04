package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSensorDisplayTests {
    @Test
    fun bucketLookupKeepsLatestReadingPerSensorPerMinuteBucket() {
        val timestamp = 1_800_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-a", timestamp = timestamp + 5_000L, value = 6.1f),
                point(sensor = "peer-a", timestamp = timestamp + 50_000L, value = 6.4f),
                point(sensor = "peer-b", timestamp = timestamp + 10_000L, value = 5.7f),
            ),
            selectedPeerIds = listOf("peer-a", "peer-b"),
            sensorViewModes = emptyMap()
        )

        val peers = data.peersAt(timestamp)

        assertEquals(listOf("peer-a", "peer-b"), peers.map { it.sensorSerial })
        assertEquals(6.4f, peers[0].value)
    }

    @Test
    fun bucketLookupOrdersPeersBySelectionOrder() {
        val timestamp = 2_400_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-b", timestamp = timestamp + 10_000L, value = 4.1f),
                point(sensor = "peer-a", timestamp = timestamp + 20_000L, value = 4.8f),
            ),
            selectedPeerIds = listOf("peer-a", "peer-b"),
            sensorViewModes = emptyMap()
        )

        assertEquals(listOf("peer-a", "peer-b"), data.peersAt(timestamp).map { it.sensorSerial })
    }

    @Test
    fun buildDisplayDataDropsSensorsOutsideSelection() {
        val timestamp = 3_000_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "main", timestamp = timestamp + 15_000L, value = 4.8f),
                point(sensor = "peer-a", timestamp = timestamp + 20_000L, value = 5.1f)
            ),
            selectedPeerIds = listOf("peer-a"),
            sensorViewModes = emptyMap()
        )

        assertEquals(listOf("peer-a"), data.series.map { it.sensorId })
        assertEquals(listOf("peer-a"), data.peersAt(timestamp).map { it.sensorSerial })
    }

    @Test
    fun bucketLookupIgnoresOtherBuckets() {
        val requested = 4_200_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-a", timestamp = requested + 20_000L, value = 5.1f),
                point(sensor = "peer-b", timestamp = requested + 90_000L, value = 7.1f),
            ),
            selectedPeerIds = listOf("peer-a", "peer-b"),
            sensorViewModes = emptyMap()
        )

        val peers = data.peersAt(requested)

        assertEquals(listOf("peer-a"), peers.map { it.sensorSerial })
        assertTrue(peers.none { it.sensorSerial == "peer-b" })
    }

    @Test
    fun seriesPointsAreSortedAscendingAndCarryViewMode() {
        val timestamp = 5_400_000L
        val data = MultiSensorDisplay.buildDisplayData(
            points = listOf(
                point(sensor = "peer-a", timestamp = timestamp + 120_000L, value = 6.0f),
                point(sensor = "peer-a", timestamp = timestamp, value = 5.0f),
                point(sensor = "peer-a", timestamp = timestamp + 60_000L, value = 5.5f),
            ),
            selectedPeerIds = listOf("peer-a"),
            sensorViewModes = mapOf("peer-a" to 3)
        )

        val series = data.seriesFor("peer-a")!!
        assertEquals(3, series.viewMode)
        assertEquals(
            listOf(timestamp, timestamp + 60_000L, timestamp + 120_000L),
            series.points.map { it.timestamp }
        )
    }

    @Test
    fun recentWindowReturnsNewestFirstUpToTimestamp() {
        val base = 6_000_000L
        val ascending = (0 until 10).map { i ->
            point(sensor = "peer-a", timestamp = base + i * 60_000L, value = 5f + i)
        }

        val window = MultiSensorDisplay.recentWindow(
            pointsAscending = ascending,
            untilTimestamp = base + 4 * 60_000L,
            count = 3
        )

        assertEquals(
            listOf(base + 4 * 60_000L, base + 3 * 60_000L, base + 2 * 60_000L),
            window.map { it.timestamp }
        )
    }

    @Test
    fun recentWindowEmptyWhenAllPointsAreNewer() {
        val base = 7_000_000L
        val ascending = listOf(
            point(sensor = "peer-a", timestamp = base + 60_000L, value = 5f),
            point(sensor = "peer-a", timestamp = base + 120_000L, value = 6f),
        )

        assertTrue(
            MultiSensorDisplay.recentWindow(
                pointsAscending = ascending,
                untilTimestamp = base,
                count = 5
            ).isEmpty()
        )
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

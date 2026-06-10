package tk.glucodata.ui

import tk.glucodata.SensorIdentity

internal data class MultiSensorReadingGroup(
    val primary: GlucosePoint,
    val peers: List<GlucosePoint>
)

internal object MultiSensorDisplay {
    private const val BUCKET_MS = 60_000L

    fun buildReadingGroups(
        primaryReadings: List<GlucosePoint>,
        peerHistory: List<GlucosePoint>,
        preferredSerial: String?
    ): List<MultiSensorReadingGroup> {
        val lookup = buildPeerLookup(
            points = peerHistory,
            timestamps = primaryReadings.map { it.timestamp },
            preferredSerial = preferredSerial
        )
        return primaryReadings.map { primary ->
            MultiSensorReadingGroup(
                primary = primary,
                peers = lookup[primary.timestamp].orEmpty()
                    .filterNot { peer -> SensorIdentity.matches(peer.sensorSerial, primary.sensorSerial ?: preferredSerial) }
            )
        }
    }

    fun buildPeerLookup(
        points: List<GlucosePoint>,
        timestamps: Collection<Long>,
        preferredSerial: String?
    ): Map<Long, List<GlucosePoint>> {
        if (points.isEmpty() || timestamps.isEmpty()) return emptyMap()

        val requestedBuckets = timestamps
            .associateBy(::bucketKey)
            .toMutableMap()
        if (requestedBuckets.isEmpty()) return emptyMap()

        val latestByBucketSensor = LinkedHashMap<Pair<Long, String>, GlucosePoint>()
        points.forEach { point ->
            val bucket = bucketKey(point.timestamp)
            if (!requestedBuckets.containsKey(bucket)) return@forEach
            val serial = point.sensorSerial?.takeIf { it.isNotBlank() } ?: return@forEach
            val key = bucket to (SensorIdentity.resolveAppSensorId(serial) ?: serial)
            val existing = latestByBucketSensor[key]
            if (existing == null || point.timestamp >= existing.timestamp) {
                latestByBucketSensor[key] = point
            }
        }

        val result = LinkedHashMap<Long, List<GlucosePoint>>()
        requestedBuckets.forEach { (bucket, timestamp) ->
            val bucketPoints = latestByBucketSensor
                .filterKeys { it.first == bucket }
                .values
                .toList()
            val sorted = sortPoints(bucketPoints, preferredSerial)
            if (sorted.isNotEmpty()) {
                result[timestamp] = sorted
            }
        }
        return result
    }

    fun buildBucketLookup(
        points: List<GlucosePoint>,
        preferredSerial: String?
    ): Map<Long, List<GlucosePoint>> {
        if (points.isEmpty()) return emptyMap()
        val latestByBucketSensor = LinkedHashMap<Long, LinkedHashMap<String, GlucosePoint>>()
        points.forEach { point ->
            val serial = point.sensorSerial?.takeIf { it.isNotBlank() } ?: return@forEach
            val bucket = bucketKey(point.timestamp)
            val sensorKey = SensorIdentity.resolveAppSensorId(serial) ?: serial
            val bucketPoints = latestByBucketSensor.getOrPut(bucket) { LinkedHashMap() }
            val existing = bucketPoints[sensorKey]
            if (existing == null || point.timestamp >= existing.timestamp) {
                bucketPoints[sensorKey] = point
            }
        }
        return latestByBucketSensor.mapValues { (_, bucketPoints) ->
            sortPoints(bucketPoints.values.toList(), preferredSerial)
        }
    }

    fun groupHistoryBySensor(points: List<GlucosePoint>): Map<String, List<GlucosePoint>> {
        if (points.isEmpty()) return emptyMap()
        val grouped = LinkedHashMap<String, MutableList<GlucosePoint>>()
        points.forEach { point ->
            val serial = point.sensorSerial?.takeIf { it.isNotBlank() } ?: return@forEach
            val key = SensorIdentity.resolveAppSensorId(serial) ?: serial
            grouped.getOrPut(key) { ArrayList() }.add(point)
        }
        return grouped.mapValues { (_, sensorPoints) -> sensorPoints.sortedByDescending { it.timestamp } }
    }

    fun pointsAtTimestamp(
        points: List<GlucosePoint>,
        timestamp: Long,
        preferredSerial: String?
    ): List<GlucosePoint> =
        buildPeerLookup(points, listOf(timestamp), preferredSerial)[timestamp].orEmpty()

    fun bucketKeyForTimestamp(timestamp: Long): Long = bucketKey(timestamp)

    private fun sortPoints(points: List<GlucosePoint>, preferredSerial: String?): List<GlucosePoint> =
        points.sortedWith(
            compareByDescending<GlucosePoint> { point ->
                preferredSerial != null && SensorIdentity.matches(point.sensorSerial, preferredSerial)
            }.thenByDescending { it.timestamp }
                .thenBy { it.sensorSerial.orEmpty() }
        )

    private fun bucketKey(timestamp: Long): Long = timestamp / BUCKET_MS
}

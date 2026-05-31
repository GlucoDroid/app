package tk.glucodata.data

import java.util.LinkedHashSet

internal object HistoryBucketReplacement {
    data class Plan(
        val bucketIds: List<Long>,
        val protectedTimestamps: List<Long>,
    )

    fun plan(
        readings: List<HistoryReading>,
        bucketDurationMs: Long,
    ): Plan? = planForCollapsedReadings(
        collapsedReadings = collapseReadings(readings, bucketDurationMs),
        bucketDurationMs = bucketDurationMs,
    )

    fun planForCollapsedReadings(
        collapsedReadings: List<HistoryReading>,
        bucketDurationMs: Long,
    ): Plan? {
        if (collapsedReadings.isEmpty() || bucketDurationMs <= 0L) return null
        val protectedTimestamps = LinkedHashSet<Long>(collapsedReadings.size)
        val bucketIds = LinkedHashSet<Long>(collapsedReadings.size)

        for (reading in collapsedReadings) {
            val timestamp = reading.timestamp
            protectedTimestamps.add(timestamp)
            bucketIds.add(timestamp / bucketDurationMs)
        }

        if (protectedTimestamps.isEmpty() || bucketIds.isEmpty()) return null

        return Plan(
            bucketIds = bucketIds.toList(),
            protectedTimestamps = protectedTimestamps.toList(),
        )
    }

    fun collapseReadings(
        readings: List<HistoryReading>,
        bucketDurationMs: Long,
    ): List<HistoryReading> {
        if (readings.isEmpty() || bucketDurationMs <= 0L) return emptyList()

        val byBucket = LinkedHashMap<Long, HistoryReading>(readings.size)
        for (reading in readings) {
            val timestamp = reading.timestamp
            if (timestamp <= 0L) continue
            val bucket = timestamp / bucketDurationMs
            val existing = byBucket[bucket]
            byBucket[bucket] = if (existing == null) reading else choosePreferred(existing, reading)
        }

        return byBucket.values.sortedBy { it.timestamp }
    }

    private fun choosePreferred(current: HistoryReading, candidate: HistoryReading): HistoryReading {
        val currentScore = score(current)
        val candidateScore = score(candidate)
        if (candidateScore != currentScore) {
            return if (candidateScore > currentScore) candidate else current
        }
        return if (candidate.timestamp >= current.timestamp) candidate else current
    }

    private fun score(reading: HistoryReading): Int {
        var score = 0
        if (reading.value.isFinite() && reading.value > 0f) score += 10
        if (reading.rawValue.isFinite() && reading.rawValue > 0f) score += 5
        if (reading.rate != null && reading.rate.isFinite()) score += 1
        return score
    }
}

package tk.glucodata.ui

import tk.glucodata.SensorIdentity

internal object GlucosePointSegments {
    private const val DEFAULT_GAP_THRESHOLD_MS = 15L * 60L * 1000L

    fun split(
        points: List<GlucosePoint>,
        gapThresholdMs: Long = DEFAULT_GAP_THRESHOLD_MS
    ): List<List<GlucosePoint>> {
        if (points.isEmpty()) return emptyList()

        val segments = ArrayList<List<GlucosePoint>>()
        var current = ArrayList<GlucosePoint>()
        var lastTimestamp = Long.MIN_VALUE
        var lastSensorSerial: String? = null

        fun flushCurrent() {
            if (current.isNotEmpty()) {
                segments.add(current)
                current = ArrayList()
            }
        }

        for (point in points) {
            val sensorChanged = current.isNotEmpty() && sensorChanged(lastSensorSerial, point.sensorSerial)
            val gapExceeded = current.isNotEmpty() &&
                lastTimestamp != Long.MIN_VALUE &&
                (point.timestamp - lastTimestamp) > gapThresholdMs

            if (sensorChanged || gapExceeded) {
                flushCurrent()
            }

            current.add(point)
            lastTimestamp = point.timestamp
            lastSensorSerial = point.sensorSerial
        }

        flushCurrent()
        return segments
    }

    private fun sensorChanged(previous: String?, current: String?): Boolean {
        val previousNormalized = normalize(previous)
        val currentNormalized = normalize(current)
        if (previousNormalized == null && currentNormalized == null) return false
        if (previousNormalized == null || currentNormalized == null) return true
        return !SensorIdentity.matches(previousNormalized, currentNormalized)
    }

    private fun normalize(sensorSerial: String?): String? {
        return sensorSerial?.trim()?.takeIf { it.isNotEmpty() }
    }
}

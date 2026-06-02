package tk.glucodata

import kotlin.math.abs

/**
 * Shared deterministic visual treatment for sensors.
 *
 * Keep this non-Compose so display surfaces outside the mobile Compose UI can
 * reuse the exact same color assignment.
 */
object SensorVisuals {
    private val palette = intArrayOf(
        0xFF6750A4.toInt(), // Primary purple
        0xFF00796B.toInt(), // Teal
        0xFF5C6BC0.toInt(), // Indigo
        0xFFD81B60.toInt(), // Pink
        0xFF1E88E5.toInt(), // Blue
        0xFF43A047.toInt(), // Green
        0xFFF4511E.toInt(), // Deep orange
        0xFF8E24AA.toInt(), // Purple
    )

    @JvmStatic
    fun colorArgb(sensorId: String?): Int = palette[colorIndex(sensorId)]

    @JvmStatic
    fun colorIndex(sensorId: String?): Int {
        val normalized = sensorId?.trim().orEmpty()
        if (normalized.isEmpty()) return 0
        return abs(normalized.hashCode()) % palette.size
    }
}

package tk.glucodata

import kotlin.math.abs

/**
 * Shared deterministic visual treatment for sensors.
 *
 * Keep this non-Compose so display surfaces outside the mobile Compose UI can
 * reuse the exact same color assignment.
 */
object SensorVisuals {
    /**
     * Blend fraction of the identity color mixed into the surface text color
     * for the dominant (primary) sensor's values on multi-sensor surfaces.
     */
    const val PRIMARY_TEXT_BLEND = 0.30f

    /**
     * Blend fraction for peer sensor values. Stronger than the primary so
     * peers are identifiable, still far from the raw palette color.
     */
    const val PEER_TEXT_BLEND = 0.45f

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
    fun colorArgbAt(index: Int): Int = palette[wrappedPaletteIndex(index)]

    @JvmStatic
    fun colorIndex(sensorId: String?): Int {
        val normalized = sensorId?.trim().orEmpty()
        if (normalized.isEmpty()) return 0
        val hash = normalized.hashCode()
        return (if (hash == Int.MIN_VALUE) 0 else abs(hash)) % palette.size
    }

    @JvmStatic
    fun distinctColorArgbs(sensorIds: List<String?>): List<Int> {
        if (sensorIds.size <= 1) return sensorIds.map(::colorArgb)

        val used = BooleanArray(palette.size)
        return sensorIds.map { sensorId ->
            val baseIndex = colorIndex(sensorId)
            val assignedIndex = if (!used[baseIndex]) {
                baseIndex
            } else {
                nextUnusedPaletteIndex(baseIndex, used)
            }
            used[assignedIndex] = true
            colorArgbAt(assignedIndex)
        }
    }

    @JvmStatic
    fun distinctColorArgbMap(sensorIds: List<String?>): Map<String, Int> {
        val normalized = SensorIdentity.distinctLogicalSensorIds(sensorIds)
        val colors = distinctColorArgbs(normalized)
        return normalized.zip(colors).toMap()
    }

    @JvmStatic
    fun colorArgbForSelected(sensorId: String?, selectedSensorIds: List<String?>): Int {
        val normalized = SensorIdentity.resolveAppSensorId(sensorId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: sensorId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return colorArgb(sensorId)
        val selectedColors = distinctColorArgbMap(selectedSensorIds)
        return selectedColors.entries.firstOrNull { (selected, _) ->
            SensorIdentity.matches(selected, normalized)
        }?.value ?: colorArgb(normalized)
    }

    /**
     * Subtle identity tint for a peer sensor's value text: the base text color
     * (e.g. white on dark notification background) nudged toward the sensor's
     * identity color. Alpha of [baseTextColorArgb] is preserved.
     */
    @JvmStatic
    fun subtlePeerTextColor(baseTextColorArgb: Int, sensorId: String?): Int =
        blendArgb(baseTextColorArgb, colorArgb(sensorId), PEER_TEXT_BLEND)

    /**
     * Reduces a color's saturation by blending it toward its own luminance grey.
     * Theme-independent (no fixed target color) so it works on any background —
     * used to tone down peer (secondary sensor) traces consistently.
     */
    @JvmStatic
    fun desaturate(color: Int, fraction: Float): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val lum = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
        val grey = (0xFF shl 24) or (lum shl 16) or (lum shl 8) or lum
        return blendArgb(color, grey, fraction)
    }

    private fun nextUnusedPaletteIndex(startIndex: Int, used: BooleanArray): Int {
        for (offset in 1 until palette.size) {
            val candidate = wrappedPaletteIndex(startIndex + offset)
            if (!used[candidate]) {
                return candidate
            }
        }
        return startIndex
    }

    private fun wrappedPaletteIndex(index: Int): Int {
        val size = palette.size
        return ((index % size) + size) % size
    }

    @JvmStatic
    fun blendArgb(base: Int, tint: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val b = (base shr shift) and 0xFF
            val t = (tint shr shift) and 0xFF
            return (b + ((t - b) * f)).toInt().coerceIn(0, 255)
        }
        val alpha = (base shr 24) and 0xFF
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

package tk.glucodata.ui

internal data class ChartYRange(
    val min: Float,
    val max: Float
)

internal fun autoExpandedChartYRange(
    baselineMin: Float,
    baselineMax: Float,
    visibleMin: Float?,
    visibleMax: Float?,
    isMmol: Boolean
): ChartYRange {
    val safeMin = baselineMin.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val safeMax = baselineMax.takeIf { it.isFinite() && it > safeMin } ?: if (isMmol) 13f else 234f
    val span = safeMax - safeMin
    val expansionStep = if (isMmol) 1f else 18f
    val edgePadding = maxOf(span * 0.04f, if (isMmol) 0.4f else 7f)

    val low = visibleMin
        ?.takeIf { it.isFinite() && it > 0.1f && it < safeMin }
        ?.let { value ->
            (kotlin.math.floor((value - edgePadding) / expansionStep) * expansionStep)
                .toFloat()
                .coerceAtLeast(0f)
        }
        ?.coerceAtMost(safeMin)
        ?: safeMin
    val high = visibleMax
        ?.takeIf { it.isFinite() && it > safeMax }
        ?.let { value ->
            (kotlin.math.ceil((value + edgePadding) / expansionStep) * expansionStep).toFloat()
        }
        ?.coerceAtLeast(safeMax)
        ?: safeMax

    return ChartYRange(min = low, max = high)
}

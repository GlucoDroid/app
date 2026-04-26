package tk.glucodata.data.prediction

import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.journal.JournalCurvePoint
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.util.GlucoseFormatter
import kotlin.math.exp
import kotlin.math.sqrt

data class GlucosePredictionPoint(
    val timestamp: Long,
    val value: Float,
    val confidence: Float
)

data class PredictiveSimulationSettings(
    val enabled: Boolean,
    val trendMomentumEnabled: Boolean,
    val horizonMinutes: Int = 120,
    val stepMinutes: Int = 5,
    val carbRatioGramsPerUnit: Float = 10f,
    val insulinSensitivityMgDlPerUnit: Float = 54f,
    val carbAbsorptionGramsPerHour: Float = 35f
)

fun buildGlucosePrediction(
    history: List<GlucosePoint>,
    journalEntries: List<JournalEntry>,
    insulinPresetsById: Map<Long, JournalInsulinPreset>,
    unit: String,
    targetLow: Float,
    targetHigh: Float,
    settings: PredictiveSimulationSettings
): List<GlucosePredictionPoint> {
    if (!settings.enabled || history.size < 2) return emptyList()

    val baseline = history.asReversed().firstOrNull { it.value.isFinite() && it.value > 0.1f } ?: return emptyList()
    val baselineTime = baseline.timestamp
    if (baselineTime <= 0L) return emptyList()

    val isMmol = GlucoseFormatter.isMmol(unit)
    val sensitivity = GlucoseFormatter.displayFromMgDl(settings.insulinSensitivityMgDlPerUnit, isMmol)
    val safeCarbRatio = settings.carbRatioGramsPerUnit.coerceAtLeast(1f)
    val safeAbsorption = settings.carbAbsorptionGramsPerHour.coerceAtLeast(5f)
    val stepMinutes = settings.stepMinutes.coerceIn(3, 15)
    val horizonMinutes = settings.horizonMinutes.coerceIn(30, 360)
    val trendSlopePerMinute = if (settings.trendMomentumEnabled) {
        recentTrendSlopePerMinute(history, baselineTime).let { slope ->
            val maxSlope = if (isMmol) 0.16f else 3f
            slope.coerceIn(-maxSlope, maxSlope)
        }
    } else {
        0f
    }
    val targetCenter = ((targetLow + targetHigh) * 0.5f).takeIf { it.isFinite() && it > 0f }
        ?: baseline.value
    val relevantEntries = journalEntries.filter { entry ->
        entry.timestamp in (baselineTime - 36L * 60L * 60L * 1000L)..(baselineTime + horizonMinutes * 60_000L)
    }

    fun projectedDeltaAt(timestamp: Long): Float {
        val minutesFuture = ((timestamp - baselineTime) / 60_000f).coerceAtLeast(0f)
        val trend = trendSlopePerMinute * minutesFuture * exp(-minutesFuture / 70f)
        val settling = (targetCenter - baseline.value) * (1f - exp(-minutesFuture / 240f)) * 0.18f
        val journalDelta = relevantEntries.sumOf { entry ->
            entry.projectedDisplayDelta(
                atMillis = timestamp,
                baselineMillis = baselineTime,
                sensitivityDisplay = sensitivity,
                carbRatioGramsPerUnit = safeCarbRatio,
                carbAbsorptionGramsPerHour = safeAbsorption,
                insulinPresetsById = insulinPresetsById
            ).toDouble()
        }.toFloat()
        return trend + settling + journalDelta
    }

    val lowClamp = if (isMmol) 1.0f else 18f
    val highClamp = if (isMmol) 30f else 540f
    return buildList {
        add(GlucosePredictionPoint(baselineTime, baseline.value, confidence = 1f))
        var minute = stepMinutes
        while (minute <= horizonMinutes) {
            val timestamp = baselineTime + minute * 60_000L
            val progress = minute.toFloat() / horizonMinutes.toFloat()
            val confidence = (0.88f - 0.62f * sqrt(progress)).coerceIn(0.18f, 0.88f)
            val value = (baseline.value + projectedDeltaAt(timestamp)).coerceIn(lowClamp, highClamp)
            add(
                GlucosePredictionPoint(
                    timestamp = timestamp,
                    value = value,
                    confidence = confidence
                )
            )
            minute += stepMinutes
        }
    }
}

private fun recentTrendSlopePerMinute(history: List<GlucosePoint>, baselineTime: Long): Float {
    val recent = history
        .asReversed()
        .asSequence()
        .filter { it.timestamp <= baselineTime && baselineTime - it.timestamp <= 45L * 60L * 1000L }
        .filter { it.value.isFinite() && it.value > 0.1f }
        .take(10)
        .toList()
        .asReversed()
    if (recent.size < 2) return 0f

    val firstTime = recent.first().timestamp
    val xMean = recent.map { (it.timestamp - firstTime) / 60_000f }.average().toFloat()
    val yMean = recent.map { it.value }.average().toFloat()
    var numerator = 0f
    var denominator = 0f
    recent.forEach { point ->
        val x = (point.timestamp - firstTime) / 60_000f
        val dx = x - xMean
        numerator += dx * (point.value - yMean)
        denominator += dx * dx
    }
    return if (denominator > 0.001f) numerator / denominator else 0f
}

private fun JournalEntry.projectedDisplayDelta(
    atMillis: Long,
    baselineMillis: Long,
    sensitivityDisplay: Float,
    carbRatioGramsPerUnit: Float,
    carbAbsorptionGramsPerHour: Float,
    insulinPresetsById: Map<Long, JournalInsulinPreset>
): Float {
    return when (type) {
        JournalEntryType.CARBS -> {
            val grams = amount?.takeIf { it > 0f } ?: return 0f
            val absorptionMinutes = (grams / carbAbsorptionGramsPerHour * 60f)
                .coerceIn(30f, 360f)
            val totalRise = (grams / carbRatioGramsPerUnit) * sensitivityDisplay
            totalRise * (
                linearProgress(timestamp, absorptionMinutes, atMillis) -
                    linearProgress(timestamp, absorptionMinutes, baselineMillis)
                )
        }
        JournalEntryType.INSULIN -> {
            val units = amount?.takeIf { it > 0f } ?: return 0f
            val preset = insulinPresetId?.let(insulinPresetsById::get) ?: return 0f
            if (preset.isArchived || !preset.countsTowardIob) return 0f
            val futureAction = cumulativeCurveFraction(preset.curvePoints, timestamp, atMillis)
            val baselineAction = cumulativeCurveFraction(preset.curvePoints, timestamp, baselineMillis)
            -(units * sensitivityDisplay * (futureAction - baselineAction))
        }
        JournalEntryType.ACTIVITY -> {
            val duration = (durationMinutes ?: 30).coerceIn(5, 240).toFloat()
            val intensityFactor = when (intensity) {
                JournalIntensity.LIGHT -> 0.35f
                JournalIntensity.MODERATE -> 0.65f
                JournalIntensity.INTENSE -> 1f
                null -> 0.5f
            }
            val oneHourDrop = sensitivityDisplay * 0.22f * intensityFactor
            -oneHourDrop * (duration / 60f) * (
                linearProgress(timestamp, duration, atMillis) -
                    linearProgress(timestamp, duration, baselineMillis)
                )
        }
        JournalEntryType.FINGERSTICK,
        JournalEntryType.NOTE -> 0f
    }
}

private fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
    if (atMillis <= startMillis) return 0f
    val elapsedMinutes = (atMillis - startMillis) / 60_000f
    return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
}

private fun cumulativeCurveFraction(points: List<JournalCurvePoint>, doseTimestamp: Long, atMillis: Long): Float {
    if (points.size < 2 || atMillis <= doseTimestamp) return 0f
    val elapsedMinutes = ((atMillis - doseTimestamp) / 60_000f).coerceAtLeast(0f)
    val total = integrateCurve(points, points.last().minute.toFloat())
    if (total <= 0.0001f) return 0f
    return (integrateCurve(points, elapsedMinutes) / total).coerceIn(0f, 1f)
}

private fun integrateCurve(points: List<JournalCurvePoint>, upToMinute: Float): Float {
    if (points.size < 2 || upToMinute <= points.first().minute) return 0f
    var area = 0f
    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        if (upToMinute <= start.minute) break
        val segmentEndMinute = minOf(upToMinute, end.minute.toFloat())
        val segmentWidth = segmentEndMinute - start.minute
        if (segmentWidth <= 0f) continue
        val fullWidth = (end.minute - start.minute).coerceAtLeast(1).toFloat()
        val endFraction = ((segmentEndMinute - start.minute) / fullWidth).coerceIn(0f, 1f)
        val segmentEndActivity = start.activity + ((end.activity - start.activity) * endFraction)
        area += ((start.activity + segmentEndActivity) * 0.5f) * segmentWidth
        if (upToMinute <= end.minute) break
    }
    return area
}

package tk.glucodata.ui

import tk.glucodata.DataSmoothing
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.prediction.GlucosePredictionSeries
import tk.glucodata.data.prediction.GlucosePredictionSeriesKind
import tk.glucodata.data.prediction.PredictiveSimulationSettings
import tk.glucodata.data.prediction.buildGlucosePrediction

internal enum class PredictionHistorySource {
    AUTO,
    RAW,
    CALIBRATED_AUTO,
    CALIBRATED_RAW
}

internal const val PREDICTION_HISTORY_WINDOW_MS = 60L * 60L * 1000L
internal const val PREDICTION_HISTORY_MAX_POINTS = 96
internal const val DASHBOARD_CONSUMER_HISTORY_MAX_POINTS = 512

internal fun trimHistoryForPrediction(points: List<GlucosePoint>): List<GlucosePoint> {
    if (points.size <= PREDICTION_HISTORY_MAX_POINTS) return points

    var latestTimestamp = 0L
    for (index in points.indices.reversed()) {
        val point = points[index]
        if (point.timestamp > 0L &&
            ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        ) {
            latestTimestamp = point.timestamp
            break
        }
    }
    if (latestTimestamp <= 0L) return points.takeLast(PREDICTION_HISTORY_MAX_POINTS)

    val startTimestamp = latestTimestamp - PREDICTION_HISTORY_WINDOW_MS
    val recent = ArrayList<GlucosePoint>(PREDICTION_HISTORY_MAX_POINTS)
    for (index in points.indices.reversed()) {
        val point = points[index]
        if (point.timestamp < startTimestamp && recent.size >= 2) break
        if (point.timestamp in startTimestamp..latestTimestamp &&
            ((point.value.isFinite() && point.value > 0.1f) ||
                (point.rawValue.isFinite() && point.rawValue > 0.1f))
        ) {
            recent.add(point)
            if (recent.size >= PREDICTION_HISTORY_MAX_POINTS) break
        }
    }
    recent.reverse()

    return when {
        recent.size >= 2 -> recent
        else -> points.takeLast(PREDICTION_HISTORY_MAX_POINTS)
    }
}

internal fun buildDisplayHistoryForPrediction(
    points: List<GlucosePoint>,
    source: PredictionHistorySource
): List<GlucosePoint> {
    if (points.isEmpty()) return emptyList()

    val useRaw = source == PredictionHistorySource.RAW || source == PredictionHistorySource.CALIBRATED_RAW
    val useCalibration = source == PredictionHistorySource.CALIBRATED_AUTO || source == PredictionHistorySource.CALIBRATED_RAW
    val calibrationActiveBySensor = HashMap<String?, Boolean>()

    return points.map { point ->
        val baseValue = if (useRaw) point.rawValue else point.value
        val sensorId = point.sensorSerial?.takeIf { it.isNotBlank() }
        val activeCalibration = useCalibration && calibrationActiveBySensor.getOrPut(sensorId) {
            tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(useRaw, sensorId)
        }
        val displayValue = if (activeCalibration && baseValue.isFinite() && baseValue > 0.1f) {
            tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                baseValue,
                point.timestamp,
                useRaw,
                sensorIdOverride = sensorId
            )
        } else {
            baseValue
        }
        point.copy(value = displayValue, rawValue = baseValue)
    }
}

internal fun hasAnyActiveCalibrationForPrediction(
    points: List<GlucosePoint>,
    useRaw: Boolean
): Boolean {
    val checkedSensors = HashMap<String?, Boolean>()
    return points.any { point ->
        val value = if (useRaw) point.rawValue else point.value
        if (!value.isFinite() || value <= 0.1f) {
            false
        } else {
            val sensorId = point.sensorSerial?.takeIf { it.isNotBlank() }
            checkedSensors.getOrPut(sensorId) {
                tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(useRaw, sensorId)
            }
        }
    }
}

internal fun buildPredictionSeriesForChart(
    points: List<GlucosePoint>,
    viewMode: Int,
    journalEntries: List<JournalEntry>,
    insulinPresetsById: Map<Long, JournalInsulinPreset>,
    unit: String,
    targetLow: Float,
    targetHigh: Float,
    settings: PredictiveSimulationSettings
): List<GlucosePredictionSeries> {
    if (!settings.enabled || points.size < 2) return emptyList()

    val predictionHistory = trimHistoryForPrediction(points)
    if (predictionHistory.size < 2) return emptyList()

    val isRawMode = viewMode == 1 || viewMode == 3
    val hasCalibration = hasAnyActiveCalibrationForPrediction(predictionHistory, isRawMode)
    val hideInitialWhenCalibrated = hasCalibration &&
        tk.glucodata.data.calibration.CalibrationManager.shouldHideInitialWhenCalibrated()
    val drawRaw = !(hideInitialWhenCalibrated && isRawMode) && (viewMode == 1 || viewMode == 2 || viewMode == 3)
    val drawAuto = !(hideInitialWhenCalibrated && !isRawMode) && (viewMode == 0 || viewMode == 2 || viewMode == 3)

    fun buildSeries(
        kind: GlucosePredictionSeriesKind,
        source: PredictionHistorySource
    ): GlucosePredictionSeries? {
        val seriesPoints = buildGlucosePrediction(
            history = buildDisplayHistoryForPrediction(predictionHistory, source),
            journalEntries = journalEntries,
            insulinPresetsById = insulinPresetsById,
            unit = unit,
            targetLow = targetLow,
            targetHigh = targetHigh,
            settings = settings
        )
        return seriesPoints.takeIf { it.size >= 2 }?.let { GlucosePredictionSeries(kind, it) }
    }

    return buildList {
        if (drawRaw) {
            buildSeries(GlucosePredictionSeriesKind.RAW, PredictionHistorySource.RAW)?.let(::add)
        }
        if (drawAuto) {
            buildSeries(GlucosePredictionSeriesKind.AUTO, PredictionHistorySource.AUTO)?.let(::add)
        }
        if (hasCalibration) {
            buildSeries(
                GlucosePredictionSeriesKind.CALIBRATED,
                if (isRawMode) PredictionHistorySource.CALIBRATED_RAW else PredictionHistorySource.CALIBRATED_AUTO
            )?.let(::add)
        }
    }
}

/**
 * Single source of truth for the history that downstream non-chart consumers see
 * (hero trend arrow, recent readings, predictive simulation).
 *
 * When data smoothing is on and not restricted to the graph, the recent dashboard
 * consumer tail is smoothed segment-by-segment so the trend, prediction, and
 * reading list reflect the same recent line without reprocessing all stored history.
 */
internal fun buildSmoothedConsumerHistory(
    points: List<GlucosePoint>,
    smoothingMinutes: Int,
    smoothOnlyGraph: Boolean,
    collapseChunks: Boolean
): List<GlucosePoint> {
    if (points.isEmpty()) {
        return points
    }
    val source = if (points.size > DASHBOARD_CONSUMER_HISTORY_MAX_POINTS) {
        points.takeLast(DASHBOARD_CONSUMER_HISTORY_MAX_POINTS)
    } else {
        points
    }
    if (smoothOnlyGraph || smoothingMinutes <= 0) {
        return source
    }

    val processed = ArrayList<GlucosePoint>(source.size)
    GlucosePointSegments.split(source).forEach { segment ->
        val sourceByTimestamp = segment.associateBy { it.timestamp }
        val smoothed = DataSmoothing.smoothNativePoints(
            segment.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) },
            smoothingMinutes,
            collapseChunks
        )

        smoothed.forEach { point ->
            val source = sourceByTimestamp[point.timestamp]
            processed.add(
                GlucosePoint(
                    value = point.value,
                    time = source?.time ?: java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(point.timestamp)),
                    timestamp = point.timestamp,
                    rawValue = point.rawValue,
                    rate = source?.rate,
                    sensorSerial = source?.sensorSerial
                )
            )
        }
    }
    return processed
}

internal fun buildDisplayReadings(
    consumerHistory: List<GlucosePoint>,
    limit: Int = 10
): List<GlucosePoint> {
    if (consumerHistory.isEmpty()) {
        return emptyList()
    }
    return consumerHistory
        .asReversed()
        .distinctBy { it.timestamp }
        .sortedByDescending { it.timestamp }
        .take(limit)
}

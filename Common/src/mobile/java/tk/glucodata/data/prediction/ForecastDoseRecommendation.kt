package tk.glucodata.data.prediction

import tk.glucodata.ui.util.GlucoseFormatter
import kotlin.math.roundToInt

enum class ForecastDoseRecommendationKind {
    CARBS,
    INSULIN
}

data class ForecastDoseRecommendation(
    val kind: ForecastDoseRecommendationKind,
    val amount: Float
)

/**
 * What a correction aims at, stored in mg/dL. Deliberately not derived from the in-range
 * band: that band is a display range and far too wide to dose against.
 */
object DoseTarget {
    /** 90 mg/dL ≈ 5.0 mmol/L. */
    const val DEFAULT_MGDL = 90f
    const val MIN_MGDL = 70f
    const val MAX_MGDL = 180f
}

/**
 * @param targetLow display-unit low bound of the in-range band; only guards against
 *   suggesting insulin over a forecast that dips low first.
 * @param doseTargetMgDl the configured dose target — what a correction aims at. Distinct
 *   from the in-range band, which is a display range and far too wide to dose against.
 */
fun calculateForecastDoseRecommendation(
    predictionPoints: List<GlucosePredictionPoint>,
    unit: String,
    targetLow: Float,
    doseTargetMgDl: Float,
    settings: PredictiveSimulationSettings,
    nowMillis: Long,
    maxBaselineAgeMillis: Long
): ForecastDoseRecommendation? {
    if (!settings.enabled || predictionPoints.size < 2) return null

    val baseline = predictionPoints.first()
    val endpoint = predictionPoints.last()
    if (baseline.timestamp <= 0L || endpoint.timestamp <= baseline.timestamp) return null
    val baselineAge = nowMillis - baseline.timestamp
    if (baselineAge !in 0..maxBaselineAgeMillis) return null

    val low = targetLow.takeIf { it.isFinite() && it > 0f } ?: return null
    val target = doseTargetMgDl.takeIf { it.isFinite() && it > 0f } ?: return null
    // Unclamped: the drawn curve bottoms out at the chart floor, so reading `value` here
    // made every dose past that floor produce the same carb suggestion.
    val predicted = endpoint.unclampedValue.takeIf { it.isFinite() } ?: return null
    val isMmol = GlucoseFormatter.isMmol(unit)
    val predictedMgDl = if (isMmol) GlucoseFormatter.mmolToMg(predicted) else predicted
    val differenceMgDl = kotlin.math.abs(predictedMgDl - target)
    if (!differenceMgDl.isFinite() || differenceMgDl <= 0f) return null

    val currentParameters = settings.modelParametersAt(baseline.timestamp)
    val endpointParameters = settings.modelParametersAt(endpoint.timestamp)
    fun correctionUnits(parameters: PredictionModelParameters): Float? {
        val sensitivity = parameters.insulinSensitivityMgDlPerUnit
            .takeIf { it.isFinite() && it > 0f }
            ?: return null
        return differenceMgDl / sensitivity
    }

    val currentUnits = correctionUnits(currentParameters) ?: return null
    val endpointUnits = correctionUnits(endpointParameters) ?: return null
    return if (predictedMgDl < target) {
        val currentRatio = currentParameters.carbRatioGramsPerUnit
            .takeIf { it.isFinite() && it > 0f }
            ?: return null
        val endpointRatio = endpointParameters.carbRatioGramsPerUnit
            .takeIf { it.isFinite() && it > 0f }
            ?: return null
        val grams = ((currentUnits * currentRatio + endpointUnits * endpointRatio) * 0.5f)
            .roundToInt()
            .toFloat()
        grams.takeIf { it >= 1f }?.let {
            ForecastDoseRecommendation(ForecastDoseRecommendationKind.CARBS, it)
        }
    } else {
        if (predictionPoints.any { it.unclampedValue.isFinite() && it.unclampedValue < low }) return null
        val units = (((currentUnits + endpointUnits) * 0.5f) * 10f)
            .roundToInt() / 10f
        units.takeIf { it >= 0.1f }?.let {
            ForecastDoseRecommendation(ForecastDoseRecommendationKind.INSULIN, it)
        }
    }
}

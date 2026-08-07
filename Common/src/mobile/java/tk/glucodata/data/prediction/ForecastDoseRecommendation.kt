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

fun calculateForecastDoseRecommendation(
    predictionPoints: List<GlucosePredictionPoint>,
    unit: String,
    targetLow: Float,
    targetHigh: Float,
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
    val high = targetHigh.takeIf { it.isFinite() && it >= low } ?: return null
    // Unclamped: the drawn curve bottoms out at the chart floor, so reading `value` here
    // made every dose past that floor produce the same carb suggestion.
    val predicted = endpoint.unclampedValue.takeIf { it.isFinite() } ?: return null
    val target = (low + high) * 0.5f
    val displayDifference = kotlin.math.abs(predicted - target)
    val differenceMgDl = if (GlucoseFormatter.isMmol(unit)) {
        GlucoseFormatter.mmolToMg(displayDifference)
    } else {
        displayDifference
    }
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
    return if (predicted < target) {
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

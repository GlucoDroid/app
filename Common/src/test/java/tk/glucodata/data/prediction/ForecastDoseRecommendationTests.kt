package tk.glucodata.data.prediction

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastDoseRecommendationTests {
    private val now = 7L * 60L * 60L * 1000L + 55L * 60L * 1000L
    private val endpoint = now + 60L * 60L * 1000L
    private val profile = PredictionModelProfile.single(10f, 50f)
        .addBlock(8 * 60)
        .updateBlock(
            startMinuteOfDay = 8 * 60,
            carbRatioGramsPerUnit = 15f,
            insulinSensitivityMgDlPerUnit = 60f
        )
    private val settings = PredictiveSimulationSettings(
        enabled = true,
        trendMomentumEnabled = true,
        modelProfile = profile,
        profileTimeZone = TimeZone.getTimeZone("UTC")
    )

    @Test
    fun lowForecastSuggestsCarbsUsingCurrentAndEndpointProfiles() {
        val recommendation = calculateForecastDoseRecommendation(
            predictionPoints = predictionEndingAt(82f),
            unit = "mg/dL",
            targetLow = 90f,
            doseTargetMgDl = 100f,
            settings = settings,
            nowMillis = now,
            maxBaselineAgeMillis = 15 * 60_000L
        )

        assertEquals(ForecastDoseRecommendationKind.CARBS, recommendation!!.kind)
        assertEquals(4f, recommendation.amount, 0f)
    }

    @Test
    fun highForecastSuggestsInsulinUsingCurrentAndEndpointProfiles() {
        val recommendation = calculateForecastDoseRecommendation(
            predictionPoints = predictionEndingAt(136f),
            unit = "mg/dL",
            targetLow = 90f,
            doseTargetMgDl = 100f,
            settings = settings,
            nowMillis = now,
            maxBaselineAgeMillis = 15 * 60_000L
        )

        assertEquals(ForecastDoseRecommendationKind.INSULIN, recommendation!!.kind)
        assertEquals(0.7f, recommendation.amount, 0f)
    }

    @Test
    fun mmolForecastIsConvertedBeforeDoseMath() {
        val mmolSettings = settings.copy(
            modelProfile = PredictionModelProfile.single(10f, 54f)
        )
        val recommendation = calculateForecastDoseRecommendation(
            predictionPoints = predictionEndingAt(4f),
            unit = "mmol/L",
            targetLow = 5f,
            doseTargetMgDl = 99.1f,
            settings = mmolSettings,
            nowMillis = now,
            maxBaselineAgeMillis = 15 * 60_000L
        )

        assertEquals(ForecastDoseRecommendationKind.CARBS, recommendation!!.kind)
        assertEquals(5f, recommendation.amount, 0f)
    }

    @Test
    fun stalePredictionProducesNoRecommendation() {
        assertNull(
            calculateForecastDoseRecommendation(
                predictionPoints = predictionEndingAt(136f),
                unit = "mg/dL",
                targetLow = 90f,
                doseTargetMgDl = 100f,
                settings = settings,
                nowMillis = now + 20 * 60_000L,
                maxBaselineAgeMillis = 15 * 60_000L
            )
        )
    }

    @Test
    fun earlierLowSuppressesAnInsulinRecommendation() {
        assertNull(
            calculateForecastDoseRecommendation(
                predictionPoints = listOf(
                    GlucosePredictionPoint(timestamp = now, value = 100f, confidence = 1f),
                    GlucosePredictionPoint(timestamp = now + 30 * 60_000L, value = 80f, confidence = 0.7f),
                    GlucosePredictionPoint(timestamp = endpoint, value = 136f, confidence = 0.5f)
                ),
                unit = "mg/dL",
                targetLow = 90f,
                doseTargetMgDl = 100f,
                settings = settings,
                nowMillis = now,
                maxBaselineAgeMillis = 15 * 60_000L
            )
        )
    }

    private fun predictionEndingAt(value: Float): List<GlucosePredictionPoint> = listOf(
        GlucosePredictionPoint(timestamp = now, value = 100f, confidence = 1f),
        GlucosePredictionPoint(timestamp = endpoint, value = value, confidence = 0.5f)
    )
}

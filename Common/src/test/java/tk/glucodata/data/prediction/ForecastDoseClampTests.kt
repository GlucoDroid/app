package tk.glucodata.data.prediction

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.GlucosePoint
import org.junit.Test

/**
 * The drawn forecast is clamped to the chart floor (1.0 mmol/L, 18 mg/dL). Dose maths that
 * read the clamped value produced an identical carb suggestion for every dose big enough to
 * push the curve onto that floor — the reported "always 17 g no matter how much insulin".
 */
class ForecastDoseClampTests {

    private val now = 1_700_000_000_000L
    private val settings = PredictiveSimulationSettings(
        enabled = true,
        trendMomentumEnabled = false,
        horizonMinutes = 120,
        stepMinutes = 5,
        modelProfile = PredictionModelProfile.single(10f, 60f),
        profileTimeZone = TimeZone.getTimeZone("UTC")
    )

    private val preset = JournalInsulinPreset(
        id = 1L,
        displayName = "Rapid",
        onsetMinutes = 0,
        durationMinutes = 300,
        accentColor = 0,
        curveJson = "0:0;60:1;300:0",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = true,
        sortOrder = 0
    )

    private fun flatHistory(valueMmol: Float): List<GlucosePoint> = (0..11).map { index ->
        GlucosePoint(
            value = valueMmol,
            time = "",
            timestamp = now - (11 - index) * 5L * 60_000L
        )
    }

    private fun insulinEntry(units: Float) = JournalEntry(
        id = 1L,
        timestamp = now,
        sensorSerial = null,
        type = JournalEntryType.INSULIN,
        title = "Bolus",
        note = null,
        amount = units,
        glucoseValueMgDl = null,
        durationMinutes = null,
        intensity = null,
        insulinPresetId = preset.id,
        foodId = null,
        proteinGrams = null,
        fatGrams = null,
        source = JournalEntrySource.MANUAL,
        sourceRecordId = null,
        createdAt = now,
        updatedAt = now
    )

    private fun predictionFor(units: Float): List<GlucosePredictionPoint> = buildGlucosePrediction(
        history = flatHistory(4.9f),
        journalEntries = listOf(insulinEntry(units)),
        insulinPresetsById = mapOf(preset.id to preset),
        unit = "mmol/L",
        targetLow = 3.9f,
        targetHigh = 10f,
        settings = settings
    )

    private fun recommendationFor(units: Float): ForecastDoseRecommendation? =
        calculateForecastDoseRecommendation(
            predictionPoints = predictionFor(units),
            unit = "mmol/L",
            targetLow = 3.9f,
            targetHigh = 10f,
            settings = settings,
            nowMillis = now,
            maxBaselineAgeMillis = 15 * 60_000L
        )

    @Test
    fun drawnValueStaysOnTheChartFloorWhileTheUnclampedValueKeepsFalling() {
        val small = predictionFor(6.5f).last()
        val large = predictionFor(16.5f).last()

        assertEquals("drawn floor", 1.0f, small.value, 0.001f)
        assertEquals("drawn floor", 1.0f, large.value, 0.001f)
        assertTrue("unclamped must fall below the floor", large.unclampedValue < small.unclampedValue)
        assertTrue("unclamped must fall below the floor", small.unclampedValue < 1.0f)
    }

    @Test
    fun biggerDoseSuggestsMoreCarbs() {
        val small = recommendationFor(6.5f)
        val large = recommendationFor(16.5f)

        assertNotNull(small)
        assertNotNull(large)
        assertEquals(ForecastDoseRecommendationKind.CARBS, small!!.kind)
        assertEquals(ForecastDoseRecommendationKind.CARBS, large!!.kind)
        assertTrue(
            "16.5 U must suggest more carbs than 6.5 U, got ${small.amount} vs ${large.amount}",
            large.amount > small.amount * 1.5f
        )
    }

    @Test
    fun carbSuggestionTracksTheCarbRatio() {
        // 6.5 U on a flat 4.9 mmol/L line: the shortfall against the 6.95 target is the
        // insulin the dose still has to act out, converted through ISF and the carb ratio.
        val recommendation = recommendationFor(6.5f)
        assertNotNull(recommendation)
        assertEquals(ForecastDoseRecommendationKind.CARBS, recommendation!!.kind)
        assertTrue(
            "expected a dose-proportional carb figure, got ${recommendation.amount}",
            recommendation.amount in 30f..90f
        )
    }
}

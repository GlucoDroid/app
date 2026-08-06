package tk.glucodata.data.journal

import kotlin.math.roundToInt

object JournalDoseCalculator {
    data class InsulinSuggestion(
        val foodInsulinUnits: Float,
        val correctionInsulinUnits: Float,
        val activeInsulinCreditUnits: Float,
        val totalInsulinUnits: Float
    )

    fun insulinForCarbs(
        carbsGrams: Float?,
        proteinGrams: Float?,
        fatGrams: Float?,
        macrosEnabled: Boolean,
        glucoseMgDl: Float?,
        carbRatioGramsPerUnit: Float,
        insulinSensitivityMgDlPerUnit: Float,
        targetHighMgDl: Float,
        activeInsulinUnits: Float
    ): InsulinSuggestion? {
        val carbRatio = carbRatioGramsPerUnit.takeIf { it.isFinite() && it > 0f } ?: return null
        val foodDoseCarbs = journalFoodDoseCarbs(
            carbsGrams = carbsGrams,
            proteinGrams = proteinGrams,
            fatGrams = fatGrams,
            macrosEnabled = macrosEnabled
        ) ?: return null
        val food = foodDoseCarbs / carbRatio
        val rawCorrection = correctionUnits(
            glucoseMgDl = glucoseMgDl,
            insulinSensitivityMgDlPerUnit = insulinSensitivityMgDlPerUnit,
            targetHighMgDl = targetHighMgDl
        ) ?: return null
        val activeCredit = minOf(rawCorrection, activeInsulinUnits.finitePositiveOrZero())
        val correction = (rawCorrection - activeCredit).coerceAtLeast(0f)
        return InsulinSuggestion(
            foodInsulinUnits = food,
            correctionInsulinUnits = correction,
            activeInsulinCreditUnits = activeCredit,
            totalInsulinUnits = roundInsulinDose(food + correction)
        )
    }

    fun carbsCoveredByInsulin(
        insulinUnits: Float?,
        glucoseMgDl: Float?,
        carbRatioGramsPerUnit: Float,
        insulinSensitivityMgDlPerUnit: Float,
        targetHighMgDl: Float,
        activeInsulinUnits: Float
    ): Float? {
        val insulin = insulinUnits?.takeIf { it.isFinite() && it > 0f } ?: return null
        val carbRatio = carbRatioGramsPerUnit.takeIf { it.isFinite() && it > 0f } ?: return null
        val rawCorrection = correctionUnits(
            glucoseMgDl = glucoseMgDl,
            insulinSensitivityMgDlPerUnit = insulinSensitivityMgDlPerUnit,
            targetHighMgDl = targetHighMgDl
        ) ?: return null
        val correction = (rawCorrection - minOf(rawCorrection, activeInsulinUnits.finitePositiveOrZero()))
            .coerceAtLeast(0f)
        return roundCarbs((insulin - correction).coerceAtLeast(0f) * carbRatio)
    }

    fun carbEquivalentForInsulin(
        insulinUnits: Float?,
        carbRatioGramsPerUnit: Float
    ): Float? {
        val insulin = insulinUnits?.takeIf { it.isFinite() && it > 0f } ?: return null
        val carbRatio = carbRatioGramsPerUnit.takeIf { it.isFinite() && it > 0f } ?: return null
        return roundCarbs(insulin * carbRatio)
    }

    private fun correctionUnits(
        glucoseMgDl: Float?,
        insulinSensitivityMgDlPerUnit: Float,
        targetHighMgDl: Float
    ): Float? {
        if (glucoseMgDl == null) return 0f
        val glucose = glucoseMgDl.takeIf { it.isFinite() && it > 0f } ?: return null
        val sensitivity = insulinSensitivityMgDlPerUnit.takeIf { it.isFinite() && it > 0f } ?: return null
        val target = targetHighMgDl.takeIf { it.isFinite() && it > 0f } ?: return null
        return ((glucose - target) / sensitivity).coerceAtLeast(0f)
    }

    private fun Float.finitePositiveOrZero(): Float =
        takeIf { it.isFinite() && it > 0f } ?: 0f

    private fun roundInsulinDose(value: Float): Float =
        ((value / 0.5f).roundToInt() * 0.5f).coerceAtLeast(0.5f)

    private fun roundCarbs(value: Float): Float? =
        ((value / 5f).roundToInt() * 5f)
            .takeIf { it >= 5f }
}

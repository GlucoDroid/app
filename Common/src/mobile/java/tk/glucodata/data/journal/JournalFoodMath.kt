package tk.glucodata.data.journal

private const val PROTEIN_KCAL_PER_GRAM = 4f
private const val FAT_KCAL_PER_GRAM = 9f
private const val CARB_EQ_KCAL = 10f

fun journalFoodTailEquivalentCarbs(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val protein = proteinGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val fat = fatGrams?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (protein <= 0f && fat <= 0f) return 0f

    val energyEquivalent = ((protein * PROTEIN_KCAL_PER_GRAM) + (fat * FAT_KCAL_PER_GRAM)) / CARB_EQ_KCAL
    val conservativeDoseEquivalent = (energyEquivalent * 0.55f)
    val macroBound = (protein * 0.32f) + (fat * 0.45f)
    return minOf(conservativeDoseEquivalent, macroBound).coerceIn(0f, 70f)
}

fun journalFoodDoseCarbs(
    carbsGrams: Float?,
    proteinGrams: Float?,
    fatGrams: Float?,
    macrosEnabled: Boolean
): Float? {
    val carbs = carbsGrams?.takeIf { it.isFinite() && it > 0f } ?: return null
    return if (macrosEnabled) {
        carbs + journalFoodTailEquivalentCarbs(proteinGrams, fatGrams)
    } else {
        carbs
    }
}

fun journalFoodTailDelayMinutes(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val protein = proteinGrams?.coerceAtLeast(0f) ?: 0f
    val fat = fatGrams?.coerceAtLeast(0f) ?: 0f
    return when {
        fat >= 25f || protein >= 35f -> 110f
        fat >= 15f || protein >= 20f -> 85f
        else -> 65f
    }
}

fun journalFoodTailDurationMinutes(
    proteinGrams: Float?,
    fatGrams: Float?
): Float {
    val protein = proteinGrams?.coerceAtLeast(0f) ?: 0f
    val fat = fatGrams?.coerceAtLeast(0f) ?: 0f
    return (150f + protein * 2f + fat * 5f).coerceIn(120f, 480f)
}

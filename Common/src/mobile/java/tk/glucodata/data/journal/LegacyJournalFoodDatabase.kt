package tk.glucodata.data.journal

import java.util.Locale
import kotlin.math.roundToInt
import tk.glucodata.Natives

object LegacyJournalFoodDatabase {
    private const val LEGACY_ID_OFFSET = 10_000_000L

    fun search(query: String, limit: Int = 8): List<JournalFood> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()

        return runCatching {
            val ptr = Natives.foodsearch(needle)
            if (ptr == 0L) return@runCatching emptyList()
            try {
                val count = Natives.foodhitnr(ptr).coerceAtMost(limit).coerceAtLeast(0)
                List(count) { index ->
                    val id = Natives.getfoodid(ptr, index)
                    val label = Natives.foodlabel(ptr, index).orEmpty()
                    legacyFoodToJournalFood(id, label)
                }.filterNotNull()
            } finally {
                Natives.freefoodptr(ptr)
            }
        }.getOrDefault(emptyList())
    }

    private fun legacyFoodToJournalFood(id: Int, label: String): JournalFood? {
        if (id < 0 || label.isBlank()) return null
        val components = runCatching { Natives.getcomponents(id) }.getOrNull() ?: return null
        val indexes = componentIndexes()
        val carbs = components.componentGrams(indexes.carbs) ?: return null
        val protein = components.componentGrams(indexes.protein)
        val fat = components.componentGrams(indexes.fat)
        val absorption = estimateAbsorptionMinutes(carbs, protein, fat)
        return JournalFood(
            id = -(LEGACY_ID_OFFSET + id),
            displayName = label,
            carbsGrams = carbs,
            proteinGrams = protein,
            fatGrams = fat,
            absorptionMinutes = absorption,
            accentColor = foodAccentColor(carbs, protein, fat),
            isBuiltIn = true,
            isArchived = false,
            sortOrder = id
        )
    }

    private fun IntArray.componentGrams(index: Int?): Float? {
        if (index == null || index !in indices) return null
        val raw = this[index]
        if (raw < 0) return null
        return (raw / 1000f).takeIf { it.isFinite() && it >= 0f }
    }

    private fun estimateAbsorptionMinutes(carbs: Float, protein: Float?, fat: Float?): Int {
        val carbWindow = 45f + carbs.coerceAtLeast(0f) * 1.4f
        val macroWindow = journalFoodTailDurationMinutes(protein, fat)
        return maxOf(carbWindow, macroWindow).roundToInt().coerceIn(45, 480)
    }

    private fun foodAccentColor(carbs: Float, protein: Float?, fat: Float?): Int {
        val p = protein ?: 0f
        val f = fat ?: 0f
        return when {
            f >= 18f -> 0xFF8A6A3B.toInt()
            p >= 25f -> 0xFF6F7B4C.toInt()
            carbs <= 12f -> 0xFF4F7F63.toInt()
            else -> 0xFF5F8A58.toInt()
        }
    }

    private fun componentIndexes(): ComponentIndexes {
        val labels = runCatching { Natives.getcomponentlabels().toList() }.getOrDefault(emptyList())
        fun find(vararg needles: String): Int? {
            return labels.indexOfFirst { label ->
                val normalized = label.lowercase(Locale.ROOT)
                needles.any { normalized.contains(it) }
            }.takeIf { it >= 0 }
        }
        return ComponentIndexes(
            carbs = find("carbohydrate", "carb") ?: 0,
            protein = find("protein"),
            fat = find("fat", "lipid")
        )
    }

    private data class ComponentIndexes(
        val carbs: Int,
        val protein: Int?,
        val fat: Int?
    )
}

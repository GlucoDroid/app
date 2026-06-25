package tk.glucodata.data.calibration

internal object CalibrationPointSelectionPolicy {
    fun <T> selectForTimestamp(
        allPoints: List<T>,
        targetTimestamp: Long,
        applyToPast: Boolean,
        lockPastHistory: Boolean,
        keepDisabledHistory: Boolean,
        timestampOf: (T) -> Long,
        isEnabled: (T) -> Boolean
    ): List<T> {
        if (allPoints.isEmpty()) return emptyList()

        val enabledPoints = allPoints.filter(isEnabled)
        if (enabledPoints.isEmpty()) return emptyList()

        if (applyToPast && !lockPastHistory) {
            return enabledPoints.sortedBy(timestampOf)
        }

        val historicalCandidates = allPoints.filter { timestampOf(it) <= targetTimestamp }
        val activeAtTimestamp = historicalCandidates.filter(isEnabled)

        if (!lockPastHistory) {
            return activeAtTimestamp.sortedBy(timestampOf)
        }

        val retiredAtTimestamp = if (keepDisabledHistory) {
            historicalCandidates.filter { retired ->
                if (isEnabled(retired)) {
                    false
                } else {
                    val retiredTimestamp = timestampOf(retired)
                    val nextActiveTimestamp = enabledPoints
                        .asSequence()
                        .map(timestampOf)
                        .filter { it > retiredTimestamp }
                        .minOrNull()
                    nextActiveTimestamp != null && targetTimestamp < nextActiveTimestamp
                }
            }
        } else {
            emptyList()
        }

        return (activeAtTimestamp + retiredAtTimestamp)
            .sortedBy(timestampOf)
            .ifEmpty {
                if (applyToPast) {
                    listOf(enabledPoints.minByOrNull(timestampOf) ?: return@ifEmpty emptyList())
                } else {
                    emptyList()
                }
            }
    }
}

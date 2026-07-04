package tk.glucodata.alerts

internal data class StandardGlucoseAlertCondition(
    val glucoseValue: Float,
    val evaluatedValue: Float,
    val threshold: Float
)

internal object StandardGlucoseAlertEvaluator {
    fun resolveActive(
        glucoseValue: Float,
        rate: Float,
        configs: Map<AlertType, AlertConfig>,
        alertTypes: Iterable<AlertType>,
        isMmol: Boolean,
        isConfigActive: (AlertConfig) -> Boolean = { it.isActiveNow() }
    ): Map<AlertType, StandardGlucoseAlertCondition> {
        if (!glucoseValue.isFinite()) {
            return emptyMap()
        }

        return alertTypes.mapNotNull { type ->
            val config = configs[type] ?: return@mapNotNull null
            if (!config.enabled) return@mapNotNull null
            if (!isConfigActive(config)) return@mapNotNull null
            val threshold = config.threshold?.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
            val value = if (isForecastAlert(type)) {
                AlertGlucoseMath.projectedDisplayValue(
                    glucoseValue = glucoseValue,
                    rateMgdlPerMinute = rate,
                    forecastMinutes = config.forecastMinutes,
                    isMmol = isMmol
                )
            } else {
                glucoseValue
            }
            if (!value.isFinite()) return@mapNotNull null

            if (isThresholdConditionActive(type, value, threshold)) {
                type to StandardGlucoseAlertCondition(glucoseValue, value, threshold)
            } else {
                null
            }
        }.toMap()
    }

    private fun isForecastAlert(type: AlertType): Boolean {
        return type == AlertType.PRE_LOW || type == AlertType.PRE_HIGH
    }

    private fun isThresholdConditionActive(type: AlertType, value: Float, threshold: Float): Boolean {
        return when (type) {
            AlertType.LOW,
            AlertType.VERY_LOW,
            AlertType.PRE_LOW -> value < threshold
            AlertType.HIGH,
            AlertType.VERY_HIGH,
            AlertType.PRE_HIGH -> value > threshold
            else -> false
        }
    }
}

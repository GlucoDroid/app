package tk.glucodata

object SensorIdentity {
    private fun normalized(sensorId: String?): String? {
        return sensorId?.trim()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun resolveMainSensor(): String? {
        val main = Natives.lastsensorname()
        if (!main.isNullOrBlank()) {
            return main
        }
        return Natives.activeSensors()?.firstOrNull { !it.isNullOrBlank() }
    }

    @JvmStatic
    fun resolveLiveMainSensor(preferredSensorId: String?): String? {
        val activeSensors = Natives.activeSensors()
        if (activeSensors.isNullOrEmpty()) {
            return resolveMainSensor()
        }
        return resolveAvailableMainSensor(
            selectedMain = Natives.lastsensorname(),
            preferredSensorId = preferredSensorId,
            activeSensors = activeSensors
        ) ?: resolveMainSensor()
    }

    @JvmStatic
    fun resolveAvailableMainSensor(
        selectedMain: String?,
        preferredSensorId: String?,
        activeSensors: Array<String?>?
    ): String? {
        val active = activeSensors
            ?.mapNotNull(::normalized)
            ?.distinct()
            .orEmpty()
        if (active.isEmpty()) {
            return normalized(selectedMain) ?: normalized(preferredSensorId)
        }

        val normalizedSelected = normalized(selectedMain)
        if (normalizedSelected != null && active.any { matches(it, normalizedSelected) }) {
            return normalizedSelected
        }

        val preferred = normalized(preferredSensorId)
        if (preferred != null && active.any { matches(it, preferred) }) {
            return preferred
        }

        return active.firstOrNull()
    }

    @JvmStatic
    fun matches(candidate: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            return true
        }
        val left = normalized(candidate) ?: return false
        val right = normalized(expected) ?: return false
        return left.equals(right, ignoreCase = true)
    }
}

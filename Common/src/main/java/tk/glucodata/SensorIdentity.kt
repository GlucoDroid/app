package tk.glucodata

import tk.glucodata.drivers.ManagedSensorIdentityRegistry

object SensorIdentity {
    private fun normalized(sensorId: String?): String? {
        return sensorId?.trim()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun invalidateCaches() {
        // main keeps no identity cache by default; managed adapters call this
        // when persisted identity state changes.
    }

    @JvmStatic
    fun resolveAppSensorId(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveCanonicalSensorId(raw) }
            .firstOrNull { it.isNotBlank() }
            ?: raw
    }

    @JvmStatic
    fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return ManagedSensorIdentityRegistry.all
            .asSequence()
            .mapNotNull { it.resolveNativeSensorName(raw) }
            .firstOrNull { it.isNotBlank() }
            ?: raw
    }

    @JvmStatic
    fun resolveMainSensor(): String? {
        val main = resolveAppSensorId(Natives.lastsensorname())
        if (!main.isNullOrBlank()) {
            return main
        }
        return Natives.activeSensors()
            ?.asSequence()
            ?.mapNotNull(::resolveAppSensorId)
            ?.firstOrNull { !it.isNullOrBlank() }
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
        val left = resolveAppSensorId(candidate) ?: return false
        val right = resolveAppSensorId(expected) ?: return false
        return left.equals(right, ignoreCase = true)
    }
}

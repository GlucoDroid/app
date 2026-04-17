package tk.glucodata

import tk.glucodata.drivers.ManagedSensorIdentityRegistry

object SensorIdentity {
    private fun normalized(sensorId: String?): String? {
        return sensorId?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun managedMatches(left: String?, right: String?): Boolean {
        val normalizedLeft = normalized(left) ?: return false
        val normalizedRight = normalized(right) ?: return false
        return ManagedSensorIdentityRegistry.all.any { adapter ->
            adapter.matchesCallbackId(normalizedLeft, normalizedRight) ||
                adapter.matchesCallbackId(normalizedRight, normalizedLeft)
        }
    }

    private fun canonicalOrRaw(sensorId: String?): String? {
        val raw = normalized(sensorId) ?: return null
        return resolveAppSensorId(raw) ?: raw
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
    fun shouldUseNativeHistorySync(sensorId: String?): Boolean {
        val raw = normalized(sensorId) ?: return true
        val canonical = canonicalOrRaw(raw) ?: raw
        return ManagedSensorIdentityRegistry.shouldUseNativeHistorySync(canonical)
            ?: ManagedSensorIdentityRegistry.shouldUseNativeHistorySync(raw)
            ?: true
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
            ?.mapNotNull(::canonicalOrRaw)
            ?.distinct()
            .orEmpty()
        val canonicalSelected = canonicalOrRaw(selectedMain)
        val canonicalPreferred = canonicalOrRaw(preferredSensorId)

        if (active.isEmpty()) {
            return canonicalSelected ?: canonicalPreferred
        }

        if (canonicalSelected != null && active.any { matches(it, canonicalSelected) }) {
            return canonicalSelected
        }

        if (canonicalPreferred != null && active.any { matches(it, canonicalPreferred) }) {
            return canonicalPreferred
        }

        return active.firstOrNull()
    }

    @JvmStatic
    fun matches(candidate: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            return true
        }
        val normalizedCandidate = normalized(candidate) ?: return false
        val normalizedExpected = normalized(expected) ?: return false
        if (normalizedCandidate.equals(normalizedExpected, ignoreCase = true)) {
            return true
        }
        if (managedMatches(normalizedCandidate, normalizedExpected)) {
            return true
        }
        val left = resolveAppSensorId(normalizedCandidate)
        val right = resolveAppSensorId(normalizedExpected)
        if (!left.isNullOrBlank() && !right.isNullOrBlank()) {
            return left.equals(right, ignoreCase = true)
        }
        return false
    }

    private fun prefersLogicalCandidate(candidate: String, existing: String): Boolean {
        val candidateResolved = resolveAppSensorId(candidate)
        val existingResolved = resolveAppSensorId(existing)
        val candidateScore = (if (!candidateResolved.isNullOrBlank() && candidateResolved.equals(candidate, ignoreCase = true)) 2 else 0) +
            candidate.length
        val existingScore = (if (!existingResolved.isNullOrBlank() && existingResolved.equals(existing, ignoreCase = true)) 2 else 0) +
            existing.length
        return candidateScore > existingScore
    }

    @JvmStatic
    fun distinctLogicalSensorIds(sensorIds: Iterable<String?>): List<String> {
        val distinct = ArrayList<String>()
        sensorIds.forEach { sensorId ->
            val normalized = canonicalOrRaw(sensorId) ?: return@forEach
            val existingIndex = distinct.indexOfFirst { matches(it, normalized) }
            if (existingIndex < 0) {
                distinct.add(normalized)
            } else if (prefersLogicalCandidate(normalized, distinct[existingIndex])) {
                distinct[existingIndex] = normalized
            }
        }
        return distinct
    }
}

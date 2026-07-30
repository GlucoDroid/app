package tk.glucodata

object SyncedWearCalibrationProvider : CalibrationProvider {
    private const val MGDL_PER_MMOL = 18.0182f
    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY = "wear_synced_calibration_v1"

    @Volatile
    private var payload: WearCalibrationPayload? = null

    @Volatile
    private var restored = false

    /**
     * The payload used to live only in memory, so every app restart left the
     * watch believing the sensor was uncalibrated until the next serve landed —
     * the displayed value flipped between the calibrated and uncalibrated lane
     * on each reopen. Persist it so the choice is stable across restarts.
     */
    @Synchronized
    private fun restoreLocked(): WearCalibrationPayload? {
        if (restored) return payload
        restored = true
        runCatching {
            val prefs = Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val encoded = prefs?.getString(KEY, null) ?: return@runCatching
            payload = WearCalibrationPayload.decode(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP))
        }.onFailure { Log.stack("SyncedWearCalibration", "restore", it) }
        return payload
    }

    @Synchronized
    fun update(next: WearCalibrationPayload) {
        if (next.sensorId.isBlank()) return
        restoreLocked()
        val current = payload
        if (current != null && next.revision < current.revision) return
        payload = next
        runCatching {
            val prefs = Applic.app?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.putString(
                KEY,
                android.util.Base64.encodeToString(WearCalibrationPayload.encode(next), android.util.Base64.NO_WRAP),
            )?.apply()
        }.onFailure { Log.stack("SyncedWearCalibration", "persist", it) }
        UiRefreshBus.requestDataRefresh()
    }

    override fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean {
        val current = matchingPayload(sensorId) ?: return false
        return mode(current, isRawMode).anchorsMgdl.isNotEmpty()
    }

    /**
     * Corrects a reading with the phone's anchors and settings, using the same
     * computation the phone runs.
     *
     * This used to return the value untouched, because sync2 corrected values
     * before sending them. That left the watch unable to correct anything it read
     * itself, so taking the sensor over dropped the display straight back to raw
     * numbers. Both devices now store raw and correct here.
     */
    override fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float {
        val current = matchingPayload(sensorId) ?: return value
        // Older phones corrected on the way out; correcting again would double it.
        if (current.valuesPrecalibrated || current.overwriteSensorValues) return value
        if (!value.isFinite() || value <= 0f) return value
        val anchors = mode(current, isRawMode).anchorsMgdl
        if (anchors.isEmpty()) return value
        val isMmol = runCatching { Applic.unit == 1 }.getOrDefault(false)
        // The anchors are canonical mg/dL while the value passed in is in display
        // units, so convert into mg/dL, correct, and convert back.
        val toMgdl = if (isMmol) MGDL_PER_MMOL else 1f
        val points = ArrayList<tk.glucodata.data.calibration.CalPoint>(anchors.size / 3)
        var offset = 0
        while (offset + 2 < anchors.size) {
            points.add(
                tk.glucodata.data.calibration.CalPoint(
                    x = anchors[offset],
                    y = anchors[offset + 1],
                    timestamp = anchors[offset + 2].toLong(),
                ),
            )
            offset += 3
        }
        if (points.isEmpty()) return value
        val sorted = points.sortedBy { it.timestamp }
        val resolved = tk.glucodata.data.calibration.CalibrationMath.resolvePointsForTimestamp(
            allPoints = sorted,
            targetTimestamp = timestamp,
            earliestPoint = sorted.firstOrNull(),
            tuning = current.tuning,
        )
        if (resolved.isEmpty()) return value
        val mgdl = value * toMgdl
        val computation = tk.glucodata.data.calibration.CalibrationMath.computeAlgorithm(
            algorithm = current.tuning.algorithm,
            targetValue = mgdl.toDouble(),
            targetTimestamp = timestamp,
            points = resolved,
            tuning = current.tuning,
        )
        val correctedMgdl = tk.glucodata.data.calibration.CalibrationMath.sanitizeCalibratedValue(
            computation.prediction,
            mgdl,
        )
        val finalMgdl = if (current.tuning.applyToPast) {
            correctedMgdl
        } else {
            tk.glucodata.data.calibration.CalibrationMath.applyPastPolicy(
                originalValue = mgdl,
                calibratedValue = correctedMgdl,
                targetTimestamp = timestamp,
                points = resolved,
            )
        }
        if (!finalMgdl.isFinite() || finalMgdl <= 0f) return value
        return finalMgdl / toMgdl
    }

    override fun shouldHideInitialWhenCalibrated(): Boolean =
        (payload ?: restoreLocked())?.hideInitialWhenCalibrated == true

    override fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray {
        val current = matchingPayload(sensorId) ?: return DoubleArray(0)
        // Canonical mg/dL, exactly like CalibrationManager on the phone: the
        // callers convert to the display unit themselves. Converting here too
        // divided every anchor twice, so a 3.0 mmol calibration was listed as
        // "0,2".
        return mode(current, isRawMode).anchorsMgdl.copyOf()
    }

    override fun getRevision(): Long = payload?.revision ?: 0L

    private fun mode(payload: WearCalibrationPayload, isRawMode: Boolean): WearCalibrationMode =
        if (isRawMode) payload.raw else payload.auto

    private fun matchingPayload(sensorId: String?): WearCalibrationPayload? {
        val current = payload ?: restoreLocked() ?: return null
        return current.takeIf {
            sensorId.isNullOrBlank() || SensorIdentity.matches(it.sensorId, sensorId)
        }
    }
}

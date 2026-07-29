package tk.glucodata

object SyncedWearCalibrationProvider : CalibrationProvider {
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
        if (!next.valuesPrecalibrated || next.sensorId.isBlank()) return
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

    override fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float {
        // sync2 values were calibrated by CalibrationManager on the phone. Applying the
        // anchors again here would double-calibrate them.
        return value
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

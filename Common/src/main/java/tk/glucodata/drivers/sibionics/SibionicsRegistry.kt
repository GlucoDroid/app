package tk.glucodata.drivers.sibionics

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorUiSignals

object SibionicsRegistry {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_SENSORS = "sibionics_managed_sensors"
    private const val PREF_LAST_INDEX_PREFIX = "sibionics_managed_last_index_"
    private const val PREF_START_TIME_PREFIX = "sibionics_managed_start_time_"
    private const val PREF_PROTOCOL_PREFIX = "sibionics_managed_protocol_"
    private const val PREF_VARIANT_PREFIX = "sibionics_managed_variant_"
    private const val PREF_SHORT_CODE_PREFIX = "sibionics_managed_short_code_"
    private const val PREF_LAST_GLUCOSE_MGDL_PREFIX = "sibionics_managed_last_glucose_mgdl_"
    private const val PREF_LAST_RAW_MGDL_PREFIX = "sibionics_managed_last_raw_mgdl_"
    private const val PREF_LAST_READING_TIME_PREFIX = "sibionics_managed_last_reading_time_"
    private const val PREF_ALGORITHM_STATE_PREFIX = "sibionics_managed_algorithm_state_"
    private const val PREF_AUTO_RESET_DAYS_PREFIX = "sibionics_managed_auto_reset_days_"
    private const val PREF_CUSTOM_ALGORITHM_PREFIX = "sibionics_managed_custom_algorithm_"
    private const val PREF_ALGORITHM_SELECTION_PREFIX = "sibionics_managed_algorithm_selection_"
    private const val PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX = "sibionics_managed_rebuild_fingerprint_"

    data class SensorRecord(
        val sensorId: String,
        val address: String,
        val displayName: String,
        val variant: SibionicsConstants.Variant,
        val shortCode: String,
        val bleName: String = "",
    ) {
        fun matchesId(id: String?): Boolean =
            SibionicsConstants.matchesId(sensorId, id) ||
                SibionicsConstants.matchesId(displayName, id) ||
                SibionicsConstants.matchesId(shortCode, id) ||
                SibionicsConstants.matchesId(bleName, id)
    }

    data class SetupIdentity(
        val sensorId: String,
        val displayName: String,
        val shortCode: String,
        val bleName: String,
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun buildIdentity(
        rawInput: String?,
        bleName: String?,
        variant: SibionicsConstants.Variant,
    ): SetupIdentity {
        val normalizedBle = SibionicsConstants.normalizeBleName(bleName)
        val normalizedRaw = SibionicsConstants.normalizeBleName(rawInput)
        val source = when {
            normalizedRaw.isNotBlank() -> normalizedRaw
            normalizedBle.isNotBlank() -> normalizedBle
            else -> variant.displayLabel
        }
        val qrName = deriveNativeQrName(source)
        val qrShortView = qrName?.takeLast(11)?.takeIf { it.length == 11 }
        val resolvedBleName = deriveBleName(normalizedBle.takeIf { it.isNotBlank() } ?: source).orEmpty()
        val display = qrShortView ?: deriveDisplayName(source, variant)
        val fallback = variant.fallbackShortCode
        val shortCode = qrShortView
            ?.take(8)
            ?.takeIf { it.length == 8 }
            ?: SibionicsSensitivity.deriveShortCode(
                resolvedBleName.takeIf { it.isNotBlank() } ?: source.takeIf { it.isNotBlank() } ?: rawInput,
                fallback,
            )
        val idBase = if (display.isNotBlank()) display else shortCode
        return SetupIdentity(
            sensorId = SibionicsConstants.canonicalSensorId(idBase),
            displayName = display.ifBlank { "${variant.displayLabel} $shortCode" },
            shortCode = shortCode,
            bleName = resolvedBleName,
        )
    }

    @JvmStatic
    fun ensureSensorRecord(
        context: Context,
        rawInput: String?,
        address: String?,
        displayName: String?,
        variant: SibionicsConstants.Variant,
        shortCodeOverride: String? = null,
        bleNameOverride: String? = null,
    ): SensorRecord {
        val identity = buildIdentity(rawInput, bleNameOverride ?: displayName, variant)
        val sensorId = identity.sensorId
        val shortCode = shortCodeOverride
            ?.let { SibionicsConstants.normalizeBleName(it) }
            ?.takeIf { it.length == 8 }
            ?: identity.shortCode
        val records = persistedRecords(context).toMutableList()
        val idx = records.indexOfFirst {
            it.matchesId(sensorId) ||
                (identity.bleName.isNotBlank() && it.matchesId(identity.bleName))
        }
        val existing = records.getOrNull(idx)
        val normalizedAddress = SibionicsConstants.normalizeBleAddress(address)
            ?: SibionicsConstants.normalizeBleAddress(existing?.address)
            ?: ""
        val visible = usableDisplayName(displayName)
            ?: usableDisplayName(existing?.displayName)
            ?: identity.displayName
        val bleName = deriveBleName(bleNameOverride)
            .orEmpty()
            .ifBlank { identity.bleName }
            .ifBlank { deriveBleName(displayName) ?: "" }
            .ifBlank { existing?.bleName.orEmpty() }
        val record = SensorRecord(sensorId, normalizedAddress, visible, variant, shortCode, bleName)
        if (idx >= 0) records[idx] = record else records.add(record)
        writeRecords(context, records)
        saveVariant(context, sensorId, variant)
        saveShortCode(context, sensorId, shortCode)
        ManagedSensorUiSignals.markDeviceListDirty()
        SensorIdentity.invalidateCaches()
        return record
    }

    @JvmStatic
    fun addSensorAndStart(
        context: Context,
        rawInput: String?,
        address: String?,
        displayName: String?,
        variant: SibionicsConstants.Variant,
        bleName: String? = null,
    ): SensorRecord {
        val record = ensureSensorRecord(
            context = context,
            rawInput = rawInput,
            address = address,
            displayName = displayName,
            variant = variant,
            bleNameOverride = bleName,
        )
        runCatching {
            if (tk.glucodata.Natives.getusebluetooth()) {
                SensorBluetooth.updateDevices()
            }
        }.onFailure {
            Log.stack(SibionicsConstants.TAG, "addSensorAndStart(updateDevices)", it)
        }
        UiRefreshBus.requestStatusRefresh()
        return record
    }

    @JvmStatic
    fun persistedRecords(context: Context): List<SensorRecord> {
        val raw = prefs(context).getStringSet(PREF_SENSORS, emptySet()) ?: return emptyList()
        return raw.mapNotNull { parseRecord(it) }
    }

    @JvmStatic
    fun findRecord(context: Context?, sensorId: String?): SensorRecord? {
        val ctx = context ?: return null
        val id = sensorId?.takeIf { it.isNotBlank() } ?: return null
        val records = persistedRecords(ctx)
        records.firstOrNull { it.matchesId(id) }?.let { return it }
        val stripped = SibionicsConstants.stripManagedPrefix(id)
        return records
            .filter {
                val display = SibionicsConstants.normalizeBleName(it.displayName)
                stripped.isNotBlank() && display.endsWith(stripped.takeLast(6), ignoreCase = true)
            }
            .singleOrNull()
    }

    internal fun canClaimUnboundSibionics2Device(
        record: SensorRecord,
        records: List<SensorRecord>,
        deviceName: String?,
        address: String?,
    ): Boolean {
        if (record.variant != SibionicsConstants.Variant.SIBIONICS2 || record.address.isNotBlank()) return false
        if (!SibionicsConstants.isSibionics2TransmitterName(deviceName)) return false
        val normalizedAddress = SibionicsConstants.normalizeBleAddress(address) ?: return false
        if (records.any {
                !it.matchesId(record.sensorId) &&
                    SibionicsConstants.normalizeBleAddress(it.address)?.equals(normalizedAddress, ignoreCase = true) == true
            }
        ) return false
        val unbound = records.filter {
            it.variant == SibionicsConstants.Variant.SIBIONICS2 && it.address.isBlank()
        }
        return unbound.size == 1 && unbound.single().matchesId(record.sensorId)
    }

    @JvmStatic
    fun resolveCanonicalSensorId(context: Context?, sensorId: String?): String? =
        findRecord(context, sensorId)?.sensorId

    @JvmStatic
    fun removeSensor(context: Context, sensorId: String?) {
        val record = findRecord(context, sensorId) ?: return
        writeRecords(context, persistedRecords(context).filter { !it.matchesId(record.sensorId) })
        val id = record.sensorId
        prefs(context).edit().apply {
            remove(PREF_LAST_INDEX_PREFIX + id)
            remove(PREF_START_TIME_PREFIX + id)
            remove(PREF_PROTOCOL_PREFIX + id)
            remove(PREF_VARIANT_PREFIX + id)
            remove(PREF_SHORT_CODE_PREFIX + id)
            remove(PREF_LAST_GLUCOSE_MGDL_PREFIX + id)
            remove(PREF_LAST_RAW_MGDL_PREFIX + id)
            remove(PREF_LAST_READING_TIME_PREFIX + id)
            remove(PREF_ALGORITHM_STATE_PREFIX + id)
            remove(PREF_AUTO_RESET_DAYS_PREFIX + id)
            remove(PREF_CUSTOM_ALGORITHM_PREFIX + id)
            remove(PREF_ALGORITHM_SELECTION_PREFIX + id)
            remove(PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX + id)
        }.apply()
        ManagedSensorUiSignals.markDeviceListDirty()
        SensorIdentity.invalidateCaches()
    }

    fun loadLastIndex(context: Context, sensorId: String): Int =
        prefs(context).getInt(PREF_LAST_INDEX_PREFIX + sensorId, 0)

    fun saveLastIndex(context: Context, sensorId: String, index: Int) {
        prefs(context).edit().putInt(PREF_LAST_INDEX_PREFIX + sensorId, index.coerceAtLeast(0)).apply()
    }

    fun loadAlgorithmState(context: Context, sensorId: String): ByteArray? {
        val encoded = prefs(context).getString(PREF_ALGORITHM_STATE_PREFIX + sensorId, null) ?: return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    fun saveAlgorithmCheckpoint(context: Context, sensorId: String, nextIndex: Int, state: ByteArray) {
        if (state.isEmpty()) return
        prefs(context).edit().apply {
            putInt(PREF_LAST_INDEX_PREFIX + sensorId, nextIndex.coerceAtLeast(0))
            putString(PREF_ALGORITHM_STATE_PREFIX + sensorId, Base64.getEncoder().encodeToString(state))
        }.apply()
    }

    fun clearAlgorithmState(context: Context, sensorId: String) {
        prefs(context).edit().remove(PREF_ALGORITHM_STATE_PREFIX + sensorId).apply()
    }

    fun loadAutoResetDays(context: Context, sensorId: String): Int =
        prefs(context).getInt(PREF_AUTO_RESET_DAYS_PREFIX + sensorId, 300)

    fun saveAutoResetDays(context: Context, sensorId: String, days: Int) {
        prefs(context).edit()
            .putInt(PREF_AUTO_RESET_DAYS_PREFIX + sensorId, days.coerceIn(1, 300))
            .apply()
    }

    fun loadCustomAlgorithmEnabled(context: Context, sensorId: String): Boolean =
        prefs(context).getBoolean(PREF_CUSTOM_ALGORITHM_PREFIX + sensorId, false)

    fun saveCustomAlgorithmEnabled(context: Context, sensorId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_CUSTOM_ALGORITHM_PREFIX + sensorId, enabled).apply()
    }

    fun loadAlgorithmSelection(context: Context, sensorId: String): SibionicsAlgorithmSelection {
        val preferences = prefs(context)
        val selectionKey = PREF_ALGORITHM_SELECTION_PREFIX + sensorId
        if (preferences.contains(selectionKey)) {
            return SibionicsAlgorithmSelection.fromStorage(
                preferences.getInt(selectionKey, SibionicsAlgorithmSelection.STOCK.storageId),
            )
        }
        val legacyKey = PREF_CUSTOM_ALGORITHM_PREFIX + sensorId
        return if (preferences.contains(legacyKey)) {
            if (preferences.getBoolean(legacyKey, false)) {
                SibionicsAlgorithmSelection.ADAPTIVE_CALIBRATED
            } else {
                SibionicsAlgorithmSelection.STOCK_CALIBRATED
            }
        } else {
            SibionicsAlgorithmSelection.STOCK
        }
    }

    fun saveAlgorithmSelection(
        context: Context,
        sensorId: String,
        selection: SibionicsAlgorithmSelection,
    ) {
        prefs(context).edit()
            .putInt(PREF_ALGORITHM_SELECTION_PREFIX + sensorId, selection.storageId)
            .apply()
    }

    fun loadLocalRebuildFingerprint(context: Context, sensorId: String): String =
        prefs(context).getString(PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX + sensorId, "").orEmpty()

    fun saveLocalRebuildFingerprint(context: Context, sensorId: String, fingerprint: String) {
        prefs(context).edit()
            .putString(PREF_LOCAL_REBUILD_FINGERPRINT_PREFIX + sensorId, fingerprint)
            .apply()
    }

    fun loadStartTimeMs(context: Context, sensorId: String): Long =
        prefs(context).getLong(PREF_START_TIME_PREFIX + sensorId, 0L)

    fun saveStartTimeMs(context: Context, sensorId: String, startTimeMs: Long) {
        if (startTimeMs <= 0L) return
        prefs(context).edit().putLong(PREF_START_TIME_PREFIX + sensorId, startTimeMs).apply()
    }

    fun clearStartTimeMs(context: Context, sensorId: String) {
        prefs(context).edit().remove(PREF_START_TIME_PREFIX + sensorId).apply()
    }

    fun loadProtocolMode(context: Context, sensorId: String): SibionicsConstants.ProtocolMode =
        runCatching {
            SibionicsConstants.ProtocolMode.valueOf(
                prefs(context).getString(PREF_PROTOCOL_PREFIX + sensorId, null).orEmpty(),
            )
        }.getOrDefault(SibionicsConstants.ProtocolMode.UNKNOWN)

    fun saveProtocolMode(context: Context, sensorId: String, mode: SibionicsConstants.ProtocolMode) {
        prefs(context).edit().putString(PREF_PROTOCOL_PREFIX + sensorId, mode.name).apply()
    }

    fun loadVariant(context: Context, sensorId: String): SibionicsConstants.Variant =
        SibionicsConstants.Variant.fromId(
            prefs(context).getString(PREF_VARIANT_PREFIX + sensorId, null)
                ?: findRecord(context, sensorId)?.variant?.id,
        )

    fun saveVariant(context: Context, sensorId: String, variant: SibionicsConstants.Variant) {
        prefs(context).edit().putString(PREF_VARIANT_PREFIX + sensorId, variant.id).apply()
    }

    fun loadShortCode(context: Context, sensorId: String): String =
        prefs(context).getString(PREF_SHORT_CODE_PREFIX + sensorId, null)
            ?.takeIf { it.isNotBlank() }
            ?: findRecord(context, sensorId)?.shortCode
            ?: SibionicsConstants.Variant.EU.fallbackShortCode

    fun saveShortCode(context: Context, sensorId: String, shortCode: String) {
        if (shortCode.isBlank()) return
        prefs(context).edit().putString(PREF_SHORT_CODE_PREFIX + sensorId, shortCode).apply()
    }

    fun saveLastReading(
        context: Context,
        sensorId: String,
        timeMs: Long,
        glucoseMgdl: Float,
        rawMgdl: Float,
    ) {
        prefs(context).edit().apply {
            putLong(PREF_LAST_READING_TIME_PREFIX + sensorId, timeMs)
            putFloat(PREF_LAST_GLUCOSE_MGDL_PREFIX + sensorId, glucoseMgdl)
            putFloat(PREF_LAST_RAW_MGDL_PREFIX + sensorId, rawMgdl)
        }.apply()
    }

    fun loadLastReading(context: Context, sensorId: String): Triple<Long, Float, Float> =
        Triple(
            prefs(context).getLong(PREF_LAST_READING_TIME_PREFIX + sensorId, 0L),
            prefs(context).getFloat(PREF_LAST_GLUCOSE_MGDL_PREFIX + sensorId, Float.NaN),
            prefs(context).getFloat(PREF_LAST_RAW_MGDL_PREFIX + sensorId, Float.NaN),
        )

    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val record = findRecord(context, sensorId) ?: return null
        return SibionicsBleManager(record.sensorId, dataptr).also { it.restoreFromPersistence(context) }
    }

    private fun writeRecords(context: Context, records: List<SensorRecord>) {
        val set = records.map { encodeRecord(it) }.toSet()
        prefs(context).edit().putStringSet(PREF_SENSORS, set).apply()
    }

    private fun encodeRecord(r: SensorRecord): String =
        listOf(
            r.sensorId,
            r.address,
            r.displayName.replace('|', ' '),
            r.variant.id,
            r.shortCode,
            r.bleName,
        ).joinToString("|")

    private fun parseRecord(line: String): SensorRecord? {
        val parts = line.split('|')
        if (parts.size < 5) return null
        val id = SibionicsConstants.canonicalSensorId(parts[0]).takeIf { it.isNotBlank() } ?: return null
        return SensorRecord(
            sensorId = id,
            address = SibionicsConstants.normalizeBleAddress(parts[1]).orEmpty(),
            displayName = parts[2].ifBlank { SibionicsConstants.stripManagedPrefix(id) },
            variant = SibionicsConstants.Variant.fromId(parts[3]),
            shortCode = parts[4].ifBlank { SibionicsConstants.Variant.fromId(parts[3]).fallbackShortCode },
            bleName = parts.getOrNull(5).orEmpty(),
        )
    }

    private fun deriveDisplayName(source: String, variant: SibionicsConstants.Variant): String {
        val normalized = SibionicsConstants.normalizeBleName(source)
        if (normalized.isBlank()) return variant.displayLabel
        if (normalized.length <= 18) return normalized
        val magic = "0697283164"
        val magicPos = normalized.indexOf(magic)
        if (magicPos >= 0 && normalized.length >= 17) {
            return normalized.takeLast(17).dropLast(1).takeLast(11).ifBlank { normalized.takeLast(11) }
        }
        return normalized.takeLast(16)
    }

    private fun usableDisplayName(raw: String?): String? {
        val normalized = SibionicsConstants.normalizeBleName(raw)
        if (normalized.isBlank()) return null
        if (deriveNativeQrName(raw) != null) return null
        return normalized.takeIf { it.length <= 18 }
    }

    internal fun deriveBleName(source: String?): String? {
        val compact = SibionicsConstants.normalizeBleName(source)
        if (compact.length in 8..16) return compact
        val ai10 = findBatchAi10(compact)
        if (ai10 < 0) return null
        val start = ai10 + 2
        val nextAi21 = compact.indexOf("21", startIndex = start + 8)
        val end = when {
            nextAi21 > start -> nextAi21
            compact.length - start in 8..16 -> compact.length
            else -> -1
        }
        if (end <= start) return null
        val candidate = compact.substring(start, end)
        return candidate.takeIf { it.length in 8..16 }
    }

    private fun findBatchAi10(compact: String): Int {
        val magic = "0697283164"
        val searchStart = compact.indexOf(magic)
            .takeIf { it >= 0 }
            ?.let { it + magic.length }
            ?: 0
        var pos = compact.indexOf("10", startIndex = searchStart)
        while (pos >= 0) {
            val valueStart = pos + 2
            val nextAi21 = compact.indexOf("21", startIndex = valueStart + 8)
            if (nextAi21 > valueStart && nextAi21 - valueStart in 8..16) return pos
            val tailLength = compact.length - valueStart
            if (tailLength in 8..16) return pos
            pos = compact.indexOf("10", startIndex = pos + 1)
        }
        return -1
    }

    private fun deriveNativeQrName(source: String?): String? {
        val payload = cleanQrPayload(source)
        val magic = "0697283164"
        if (!payload.contains(magic) || payload.length < 50) return null
        return if (payload.length < 65) {
            val endLen = payload.length - 49
            val startLen = 16 - endLen
            if (endLen <= 0 || startLen < 0 || payload.length < 49 + endLen || payload.length < 22 + startLen) {
                null
            } else {
                (payload.substring(22, 22 + startLen) + payload.substring(49, 49 + endLen))
                    .takeIf { it.length == 16 }
            }
        } else {
            val start = payload.length - 17
            payload.substring(start, start + 16).takeIf { it.length == 16 }
        }?.let { SibionicsConstants.normalizeBleName(it) }
    }

    private fun cleanQrPayload(source: String?): String =
        source.orEmpty()
            .trim()
            .uppercase(java.util.Locale.US)
            .replace("^]", "\u001D")
            .filter { it.isLetterOrDigit() || it == '\u001D' }
}

object SibionicsManagedSensorIdentityAdapter : tk.glucodata.drivers.ManagedSensorIdentityAdapter {
    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean =
        SibionicsConstants.matchesId(callbackId, sensorId)

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        SibionicsRegistry.findRecord(Applic.app, raw)?.sensorId?.let { return it }
        return SibionicsConstants.canonicalSensorId(raw).takeIf { it.isNotBlank() }
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? =
        resolveCanonicalSensorId(sensorId)

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean =
        SibionicsRegistry.findRecord(Applic.app, sensorId) != null

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        SibionicsRegistry.findRecord(Applic.app, sensorId)?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        SibionicsRegistry.persistedRecords(context).map { it.sensorId }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? =
        SibionicsRegistry.createRestoredCallback(context, sensorId, dataptr)

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        SibionicsRegistry.removeSensor(context, sensorId)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        SibionicsRegistry.findRecord(Applic.app, sensorId) != null

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean = false

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        SibionicsRegistry.findRecord(Applic.app, sensorId) ?: return null
        return false
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        SibionicsRegistry.findRecord(Applic.app, sensorId) ?: return null
        return false
    }
}

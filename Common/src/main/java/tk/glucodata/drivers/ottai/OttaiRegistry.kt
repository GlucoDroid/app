// OttaiRegistry.kt — SharedPreferences persistence for Ottai sensors.
//
// Account-level: accessToken, glucoseSecretKey, userId.
// Per-sensor (keyed by canonical MAC): decrypted keyA (192 hex) / method /
// coefficient, activeTime, deviceVersion, lastDataNo, deviceId.
//
// SECURITY: accessToken, glucoseSecretKey, keyA-plaintext, method and coefficient
// are credentials/IP. They live only in prefs (same posture as MQ cloud creds)
// and must never be logged or committed.

package tk.glucodata.drivers.ottai

import android.content.Context
import android.content.SharedPreferences
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorUiSignals

object OttaiRegistry {
    private const val TAG = OttaiConstants.TAG
    private const val PREFS_NAME = "tk.glucodata_preferences"

    data class SensorRecord(
        val sensorId: String, // canonical MAC
        val address: String,
        val displayName: String,
    ) {
        fun matchesId(id: String?): Boolean =
            OttaiConstants.matchesCanonicalOrKnownNativeAlias(sensorId, id)
    }

    data class DeviceMaterials(
        val keyAHex: String,        // decrypted, 192 hex -> 6 auth keys
        val method: String,         // decrypted RPN expression
        val coefficient: String,    // decrypted CSV
        val activeTimeMs: Long,
        val deviceVersion: String,
        val deviceId: Int,
    ) {
        val authKeys: List<ByteArray>? get() = OttaiCrypto.parseAuthKeys(keyAHex)
        val coefficients: List<Double>
            get() = coefficient.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        val hasAll: Boolean get() = keyAHex.isNotBlank() && method.isNotBlank() && activeTimeMs > 0L
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- account ----

    @JvmStatic fun loadAccessToken(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_ACCESS_TOKEN, null).orEmpty()
    @JvmStatic fun saveAccessToken(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_ACCESS_TOKEN, v).apply()
    }

    @JvmStatic fun loadGlucoseSecretKey(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_GLUCOSE_SECRET, null).orEmpty()
    @JvmStatic fun saveGlucoseSecretKey(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_GLUCOSE_SECRET, v).apply()
    }

    @JvmStatic fun loadUserId(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_USER_ID, null).orEmpty()
    @JvmStatic fun saveUserId(c: Context, v: String?) {
        prefs(c).edit().putString(OttaiConstants.PREF_USER_ID, v).apply()
    }

    /** Stable per-install device id used in the cloud signature + deviceId header. */
    @JvmStatic
    fun loadOrCreateDeviceId(c: Context): String {
        val existing = prefs(c).getString(OttaiConstants.PREF_SELF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        prefs(c).edit().putString(OttaiConstants.PREF_SELF_DEVICE_ID, generated).apply()
        return generated
    }

    // ---- sensor record set ----

    @JvmStatic
    fun ensureSensorRecord(context: Context, sensorId: String, address: String, displayName: String) {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val records = persistedRecords(context).toMutableList()
        val idx = records.indexOfFirst { it.matchesId(canonical) }
        val record = SensorRecord(canonical, address, displayName.ifBlank { canonical })
        if (idx >= 0) records[idx] = record else records.add(record)
        writeRecords(context, records)
    }

    @JvmStatic
    fun persistedRecords(context: Context): List<SensorRecord> {
        val raw = prefs(context).getStringSet(OttaiConstants.PREF_SENSORS_KEY, emptySet()) ?: return emptyList()
        return raw.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) null else SensorRecord(parts[0], parts[1], parts[2])
        }
    }

    @JvmStatic
    fun findRecord(context: Context?, sensorId: String?): SensorRecord? {
        val ctx = context ?: return null
        val id = sensorId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return persistedRecords(ctx).firstOrNull { it.matchesId(id) }
    }

    @JvmStatic
    fun resolveCanonicalSensorId(context: Context?, sensorId: String?): String? =
        findRecord(context, sensorId)?.sensorId

    private fun writeRecords(context: Context, records: List<SensorRecord>) {
        val set = records.map { "${it.sensorId}|${it.address}|${it.displayName}" }.toSet()
        prefs(context).edit().putStringSet(OttaiConstants.PREF_SENSORS_KEY, set).apply()
    }

    @JvmStatic
    fun removeSensor(context: Context, sensorId: String?) {
        val id = sensorId?.trim() ?: return
        val canonical = OttaiConstants.canonicalSensorId(id).ifEmpty { id }
        writeRecords(context, persistedRecords(context).filter { !it.matchesId(canonical) })
        prefs(context).edit().apply {
            listOf(
                OttaiConstants.PREF_KEYA_PREFIX, OttaiConstants.PREF_METHOD_PREFIX,
                OttaiConstants.PREF_COEFF_PREFIX, OttaiConstants.PREF_ACTIVE_TIME_PREFIX,
                OttaiConstants.PREF_DEVICE_VERSION_PREFIX, OttaiConstants.PREF_LAST_DATA_NO_PREFIX,
                OttaiConstants.PREF_DEVICE_ID_PREFIX,
            ).forEach { remove(it + canonical) }
        }.apply()
    }

    // ---- per-sensor materials ----

    @JvmStatic
    fun saveMaterials(context: Context, sensorId: String, m: DeviceMaterials) {
        val id = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        prefs(context).edit().apply {
            putString(OttaiConstants.PREF_KEYA_PREFIX + id, m.keyAHex)
            putString(OttaiConstants.PREF_METHOD_PREFIX + id, m.method)
            putString(OttaiConstants.PREF_COEFF_PREFIX + id, m.coefficient)
            putLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + id, m.activeTimeMs)
            putString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + id, m.deviceVersion)
            putInt(OttaiConstants.PREF_DEVICE_ID_PREFIX + id, m.deviceId)
        }.apply()
    }

    @JvmStatic
    fun loadMaterials(context: Context, sensorId: String): DeviceMaterials {
        val id = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val p = prefs(context)
        return DeviceMaterials(
            keyAHex = p.getString(OttaiConstants.PREF_KEYA_PREFIX + id, null).orEmpty(),
            method = p.getString(OttaiConstants.PREF_METHOD_PREFIX + id, null).orEmpty(),
            coefficient = p.getString(OttaiConstants.PREF_COEFF_PREFIX + id, null).orEmpty(),
            activeTimeMs = p.getLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + id, 0L),
            deviceVersion = p.getString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + id, null).orEmpty(),
            deviceId = p.getInt(OttaiConstants.PREF_DEVICE_ID_PREFIX + id, 0),
        )
    }

    @JvmStatic fun loadLastDataNo(c: Context, id: String): Int =
        prefs(c).getInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + OttaiConstants.canonicalSensorId(id), -1)
    @JvmStatic fun saveLastDataNo(c: Context, id: String, dataNo: Int) {
        prefs(c).edit().putInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + OttaiConstants.canonicalSensorId(id), dataNo).apply()
    }

    // ---- restore / wizard ----

    @JvmStatic
    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        if (findRecord(context, canonical) == null) return null
        return runCatching {
            OttaiBleManager(canonical, dataptr).also { it.restoreFromPersistence(context) }
        }.onFailure { Log.stack(TAG, "createRestoredCallback", it) }.getOrNull()
    }

    @JvmStatic
    @JvmOverloads
    fun addSensor(
        context: Context,
        sensorId: String,
        address: String,
        displayName: String? = null,
        connectNow: Boolean = true,
    ): String? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        if (canonical.isBlank()) return null
        ensureSensorRecord(context, canonical, address, displayName ?: OttaiConstants.DEFAULT_DISPLAY_NAME)
        if (connectNow) connectSensor(context, canonical)
        ManagedSensorUiSignals.markDeviceListDirty()
        return canonical
    }

    @JvmStatic
    fun connectSensor(context: Context, sensorId: String) {
        val blue = SensorBluetooth.blueone ?: return
        val record = findRecord(context, sensorId) ?: return
        val existing = SensorBluetooth.gattcallbacks.firstOrNull { cb ->
            val d = cb as? OttaiDriver ?: return@firstOrNull false
            SensorIdentity.matches(cb.SerialNumber, sensorId) || d.matchesManagedSensorId(sensorId)
        }
        val callback = existing ?: createRestoredCallback(context, record.sensorId, 0L)?.also {
            SensorBluetooth.gattcallbacks.add(it)
            runCatching { Natives.setmaxsensors(SensorBluetooth.gattcallbacks.size) }
        } ?: return
        if (callback is OttaiBleManager) {
            callback.mActiveDeviceAddress = record.address.takeIf { it.isNotBlank() }
            callback.restoreFromPersistence(context)
        }
        runCatching { SensorBluetooth.ensureCurrentSensorSelection() }
        if (SensorBluetooth.blueone === blue) callback.connectDevice(0)
        ManagedSensorUiSignals.markDeviceListDirty()
    }
}

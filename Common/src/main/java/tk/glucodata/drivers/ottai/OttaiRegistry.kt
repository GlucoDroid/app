// OttaiRegistry.kt — SharedPreferences persistence for Ottai sensors.
//
// Account-level: accessToken, glucoseSecretKey, userId.
// Per-sensor (keyed by canonical cloud id): decrypted keyA (192 hex) / method /
// coefficient, activeTime, deviceVersion, lastDataNo, deviceId.
//
// SECURITY: accessToken, glucoseSecretKey, keyA-plaintext, method and coefficient
// are credentials/IP. They live only in prefs (same posture as MQ cloud creds)
// and must never be logged or committed.

package tk.glucodata.drivers.ottai

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
        val sensorId: String, // canonical cloud id used by server + BLE auth
        val address: String,  // Android BLE address, colon form; may be blank
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
        // From the cloud validate-by-mac response; used verbatim by BLE activation.
        // activeExpireTimeMs -> maxActive write (p.D); retainTimeMs -> destruction
        // write (p.E, defaults to 172800000 when the server omits it).
        val activeExpireTimeMs: Long = 0L,
        val retainTimeMs: Long = 0L,
        val preheatPeriodMs: Long = 0L,
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

    /** Backend host for the signed-in account (CN api.ottai.com vs global seas.ottai.com). */
    @JvmStatic fun loadApiBase(c: Context): String =
        prefs(c).getString(OttaiConstants.PREF_API_BASE, null)?.takeIf { it.isNotBlank() } ?: OttaiConstants.API_BASE
    @JvmStatic fun saveApiBase(c: Context, v: String) {
        prefs(c).edit().putString(OttaiConstants.PREF_API_BASE, v).apply()
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
        val existing = records.getOrNull(idx)
        val bleAddress = OttaiConstants.normalizeBleAddress(address, allowPlain = false)
            ?: OttaiConstants.normalizeBleAddress(existing?.address, allowPlain = false).orEmpty()
        val record = SensorRecord(canonical, bleAddress, displayName.ifBlank { canonical })
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
        val records = persistedRecords(ctx)
        records.firstOrNull { it.matchesId(id) }?.let { return it }
        val suffix = OttaiConstants.canonicalSensorId(id).takeIf { it.length >= 6 } ?: return null
        return records
            .filter { it.sensorId.endsWith(suffix, ignoreCase = true) }
            .singleOrNull()
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
                OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX,
                OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX, OttaiConstants.PREF_RETAIN_TIME_PREFIX,
                OttaiConstants.PREF_PREHEAT_PERIOD_PREFIX,
                OttaiConstants.PREF_DEVICE_VERSION_PREFIX, OttaiConstants.PREF_LAST_DATA_NO_PREFIX,
                OttaiConstants.PREF_DEVICE_ID_PREFIX, OttaiConstants.PREF_ACTIVATION_ATTEMPTED_PREFIX,
            ).forEach { remove(it + canonical) }
        }.apply()
    }

    // ---- per-sensor materials ----

    @JvmStatic
    fun saveMaterials(context: Context, sensorId: String, m: DeviceMaterials) {
        val id = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val existing = loadMaterials(context, id)
        val coefficient = m.coefficient.ifBlank { existing.coefficient }
        val method = OttaiMethodDefaults.resolve(m.method.ifBlank { existing.method }, coefficient)
        prefs(context).edit().apply {
            putString(OttaiConstants.PREF_KEYA_PREFIX + id, m.keyAHex)
            putString(OttaiConstants.PREF_METHOD_PREFIX + id, method)
            putString(OttaiConstants.PREF_COEFF_PREFIX + id, coefficient)
            putLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + id, m.activeTimeMs)
            putLong(OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + id, m.activeExpireTimeMs)
            putLong(OttaiConstants.PREF_RETAIN_TIME_PREFIX + id, m.retainTimeMs)
            putLong(OttaiConstants.PREF_PREHEAT_PERIOD_PREFIX + id, m.preheatPeriodMs)
            putString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + id, m.deviceVersion)
            putInt(OttaiConstants.PREF_DEVICE_ID_PREFIX + id, m.deviceId)
        }.apply()
    }

    @JvmStatic
    fun loadMaterials(context: Context, sensorId: String): DeviceMaterials {
        val id = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val p = prefs(context)
        val coefficient = p.getString(OttaiConstants.PREF_COEFF_PREFIX + id, null).orEmpty()
        val method = OttaiMethodDefaults.resolve(
            p.getString(OttaiConstants.PREF_METHOD_PREFIX + id, null).orEmpty(),
            coefficient,
        )
        return DeviceMaterials(
            keyAHex = p.getString(OttaiConstants.PREF_KEYA_PREFIX + id, null).orEmpty(),
            method = method,
            coefficient = coefficient,
            activeTimeMs = p.getLong(OttaiConstants.PREF_ACTIVE_TIME_PREFIX + id, 0L),
            activeExpireTimeMs = p.getLong(OttaiConstants.PREF_ACTIVE_EXPIRE_PREFIX + id, 0L),
            retainTimeMs = p.getLong(OttaiConstants.PREF_RETAIN_TIME_PREFIX + id, 0L),
            preheatPeriodMs = p.getLong(OttaiConstants.PREF_PREHEAT_PERIOD_PREFIX + id, 0L),
            deviceVersion = p.getString(OttaiConstants.PREF_DEVICE_VERSION_PREFIX + id, null).orEmpty(),
            deviceId = p.getInt(OttaiConstants.PREF_DEVICE_ID_PREFIX + id, 0),
        )
    }

    @JvmStatic fun loadProvisionalActiveTime(c: Context, id: String): Long =
        prefs(c).getLong(
            OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + OttaiConstants.canonicalSensorId(id),
            0L,
        )

    @JvmStatic fun saveProvisionalActiveTime(c: Context, id: String, activeTimeMs: Long) {
        val canonical = OttaiConstants.canonicalSensorId(id)
        prefs(c).edit().apply {
            if (activeTimeMs > 0L) putLong(OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + canonical, activeTimeMs)
            else remove(OttaiConstants.PREF_PROVISIONAL_ACTIVE_TIME_PREFIX + canonical)
        }.apply()
    }

    /**
     * One-shot guard for auto-activate-on-first-connect: true once the driver has
     * fired (or been asked to fire) the irreversible activation for this sensor, so a
     * virgin sensor activates exactly once and never re-fires on later reconnects.
     */
    @JvmStatic fun loadActivationAttempted(c: Context, id: String): Boolean =
        prefs(c).getBoolean(OttaiConstants.PREF_ACTIVATION_ATTEMPTED_PREFIX + OttaiConstants.canonicalSensorId(id), false)
    @JvmStatic fun setActivationAttempted(c: Context, id: String, v: Boolean) {
        prefs(c).edit().putBoolean(OttaiConstants.PREF_ACTIVATION_ATTEMPTED_PREFIX + OttaiConstants.canonicalSensorId(id), v).apply()
    }

    @JvmStatic fun loadLastDataNo(c: Context, id: String): Int =
        prefs(c).getInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + OttaiConstants.canonicalSensorId(id), -1)
    @JvmStatic fun saveLastDataNo(c: Context, id: String, dataNo: Int) {
        prefs(c).edit().putInt(OttaiConstants.PREF_LAST_DATA_NO_PREFIX + OttaiConstants.canonicalSensorId(id), dataNo).apply()
    }

    // ---- portable export / import ----
    //
    // Serializes the DECRYPTED per-sensor materials + record. This is everything
    // the driver needs to connect, authenticate and activate over BLE without ever
    // contacting the server again — so the user does the cloud fetch (login →
    // validate/bind) once on one device, saves the JSON, and loads it on any other
    // device running this app. accessToken / glucoseSecretKey are NOT exported (not
    // needed once keyA/coefficient are decrypted).

    @JvmStatic
    fun exportJson(context: Context, sensorId: String): String? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val record = findRecord(context, canonical) ?: return null
        val m = loadMaterials(context, canonical)
        if (m.keyAHex.isBlank()) return null
        return org.json.JSONObject().apply {
            put("v", 1)
            put("sensorId", canonical)
            put("bleAddress", record.address)
            put("displayName", record.displayName)
            put("keyAHex", m.keyAHex)
            put("method", m.method)
            put("coefficient", m.coefficient)
            put("activeTimeMs", m.activeTimeMs)
            put("activeExpireTimeMs", m.activeExpireTimeMs)
            put("retainTimeMs", m.retainTimeMs)
            put("preheatPeriodMs", m.preheatPeriodMs)
            put("provisionalActiveTimeMs", loadProvisionalActiveTime(context, canonical))
            put("deviceVersion", m.deviceVersion)
            put("deviceId", m.deviceId)
        }.toString(2)
    }

    /** Returns the canonical sensorId on success, or null if the JSON is invalid. */
    @JvmStatic
    fun importJson(context: Context, json: String): String? {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val id = OttaiConstants.canonicalSensorId(o.optString("sensorId")).ifEmpty { return null }
        val keyA = o.optString("keyAHex")
        if (OttaiCrypto.parseAuthKeys(keyA) == null) return null // must be 192 hex = 6 auth keys
        ensureSensorRecord(
            context, id, o.optString("bleAddress"),
            o.optString("displayName").ifBlank { OttaiConstants.DEFAULT_DISPLAY_NAME },
        )
        saveMaterials(
            context, id,
            DeviceMaterials(
                keyAHex = keyA,
                method = o.optString("method"),
                coefficient = o.optString("coefficient"),
                activeTimeMs = o.optLong("activeTimeMs", 0L),
                activeExpireTimeMs = o.optLong("activeExpireTimeMs", 0L),
                retainTimeMs = o.optLong("retainTimeMs", 0L),
                preheatPeriodMs = o.optLong("preheatPeriodMs", 0L),
                deviceVersion = o.optString("deviceVersion"),
                deviceId = o.optInt("deviceId", 0),
            ),
        )
        saveProvisionalActiveTime(context, id, o.optLong("provisionalActiveTimeMs", 0L))
        return id
    }

    // ---- restore / wizard ----

    @JvmStatic
    fun createRestoredCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        val record = findRecord(context, canonical) ?: return null
        return runCatching {
            OttaiBleManager(canonical, dataptr).also {
                it.mActiveDeviceAddress = OttaiConstants.normalizeBleAddress(record.address, allowPlain = false)
                it.restoreFromPersistence(context)
            }
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
        val bleAddress = OttaiConstants.normalizeBleAddress(address, allowPlain = false).orEmpty()
        ensureSensorRecord(context, canonical, bleAddress, displayName ?: OttaiConstants.DEFAULT_DISPLAY_NAME)
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
            val bleAddress = OttaiConstants.normalizeBleAddress(record.address, allowPlain = false)
            callback.mActiveDeviceAddress = bleAddress
            callback.mActiveBluetoothDevice = null
            if (bleAddress != null && BluetoothAdapter.checkBluetoothAddress(bleAddress)) {
                val adapter = runCatching {
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                        ?: BluetoothAdapter.getDefaultAdapter()
                }.getOrNull()
                callback.mActiveBluetoothDevice = runCatching { adapter?.getRemoteDevice(bleAddress) }.getOrNull()
            }
            callback.restoreFromPersistence(context)
        }
        runCatching { SensorBluetooth.ensureCurrentSensorSelection() }
        if (SensorBluetooth.blueone === blue) callback.connectDevice(0)
        ManagedSensorUiSignals.markDeviceListDirty()
    }

    /**
     * Arm + trigger the one-time BLE activation (irreversible — starts the sensor
     * lifetime). Fires immediately if the sensor is already connected+authenticated;
     * otherwise arms the flag and (re)connects so it fires on the next auth.
     * Returns true if it fired right now.
     */
    @JvmStatic
    fun requestActivation(context: Context, sensorId: String): Boolean {
        val canonical = OttaiConstants.canonicalSensorId(sensorId).ifEmpty { sensorId }
        OttaiBleManager.activateRequestedFor = canonical
        val mgr = SensorBluetooth.gattcallbacks.firstOrNull { cb ->
            (cb as? OttaiBleManager)?.matchesManagedSensorId(canonical) == true
        } as? OttaiBleManager
        // The Advanced "Activate" is an explicit user action — force it so it can also
        // attempt to re-arm/extend an already-started or expired (cmd>3) sensor.
        if (mgr != null && mgr.requestForceActivation()) return true
        connectSensor(context, canonical)
        return false
    }
}

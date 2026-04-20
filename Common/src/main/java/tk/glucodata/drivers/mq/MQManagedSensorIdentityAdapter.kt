// MQManagedSensorIdentityAdapter.kt — Vendor adapter for the managed identity seam.

package tk.glucodata.drivers.mq

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedBluetoothSensorDriver
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object MQManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        if (normalized.equals(sensorId, ignoreCase = true) ||
            MQConstants.matchesCanonicalOrKnownNativeAlias(normalized, sensorId)
        ) {
            return true
        }
        return SensorBluetooth.mygatts().any { callback ->
            val managed = callback as? ManagedBluetoothSensorDriver ?: return@any false
            managed.matchesManagedSensorId(normalized) && managed.matchesManagedSensorId(sensorId)
        }
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        SensorBluetooth.mygatts()
            .firstOrNull { cb ->
                val managed = cb as? ManagedBluetoothSensorDriver ?: return@firstOrNull false
                managed.matchesManagedSensorId(raw)
            }
            ?.SerialNumber
            ?.takeIf { it.isNotBlank() }
            ?.let(MQConstants::canonicalSensorId)
            ?.takeIf { it.isNotEmpty() && !MQConstants.isProvisionalSensorId(it) }
            ?.let { return it }

        if (MQConstants.isProvisionalSensorId(raw)) return raw
        val normalized = MQConstants.canonicalSensorId(raw)
        if (normalized.isEmpty()) return null

        runCatching { MQRegistry.resolveCanonicalSensorId(Applic.app, normalized) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return normalized.takeIf { MQConstants.isLikelyPersistedSensorName(it) }
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val canonical = resolveCanonicalSensorId(raw) ?: raw
        return canonical.takeIf { MQConstants.isLikelyPersistedSensorName(it) }
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return MQRegistry.findRecord(Applic.app, normalized) != null
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        MQRegistry.persistedRecords(context).map { it.sensorId }

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        MQRegistry.createRestoredCallback(context, sensorId, dataptr)?.let { return it }

        val canonical = resolveCanonicalSensorId(sensorId)
            ?.takeIf { it.isNotBlank() && !MQConstants.isProvisionalSensorId(it) }
            ?: return null
        return MQBleManager(canonical, dataptr).also { it.restoreFromPersistence(context) }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        MQRegistry.removeSensor(context, sensorId)
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId) != null

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        val hasPersisted = MQRegistry.findRecord(Applic.app, canonical) != null
        // Match iCanHealth semantics: if we manage the record, skip native
        // history sync; if we've only seen it once via restore, fall back.
        return !hasPersisted
    }
}

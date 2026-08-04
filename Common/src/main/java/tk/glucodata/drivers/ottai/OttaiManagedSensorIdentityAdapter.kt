// OttaiManagedSensorIdentityAdapter.kt — vendor adapter for the managed identity seam.

package tk.glucodata.drivers.ottai

import android.content.Context
import java.util.concurrent.Executors
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.drivers.ManagedSensorIdentityAdapter

object OttaiManagedSensorIdentityAdapter : ManagedSensorIdentityAdapter {

    private const val TAG = OttaiConstants.TAG

    // Removal is a UI action and the unbind below is a blocking call with a 30 s timeout, so it
    // never runs on the caller's thread. Daemon: nothing about a released binding is worth
    // holding the process open for.
    private val unbindExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Ottai-unbind").apply { isDaemon = true }
    }

    override fun matchesCallbackId(callbackId: String?, sensorId: String): Boolean {
        val normalized = callbackId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        val left = resolveCanonicalSensorId(normalized)
            ?: OttaiConstants.canonicalSensorId(normalized).takeIf { it.isNotBlank() }
        val right = resolveCanonicalSensorId(sensorId)
            ?: OttaiConstants.canonicalSensorId(sensorId).takeIf { it.isNotBlank() }
        if (!left.isNullOrBlank() && !right.isNullOrBlank() && left.equals(right, ignoreCase = true)) {
            return true
        }
        if (normalized.equals(sensorId, ignoreCase = true) ||
            OttaiConstants.matchesCanonicalOrKnownNativeAlias(normalized, sensorId)
        ) {
            return true
        }
        return SensorBluetooth.mygatts().any { cb ->
            val d = cb as? OttaiDriver ?: return@any false
            d.matchesManagedSensorId(normalized) && d.matchesManagedSensorId(sensorId)
        }
    }

    override fun resolveCanonicalSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        if (OttaiConstants.isProvisionalSensorId(raw)) return raw
        val normalized = OttaiConstants.canonicalSensorId(raw)
        if (normalized.isEmpty()) return null
        runCatching { OttaiRegistry.resolveCanonicalSensorId(Applic.app, normalized) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        return normalized.takeIf { OttaiConstants.looksLikeMac(it) }
    }

    override fun resolveStableStorageSensorId(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        resolveCanonicalSensorId(raw)?.takeIf { it.isNotBlank() }?.let { return it }
        val canonical = OttaiConstants.canonicalSensorId(raw)
        return canonical.takeIf { OttaiConstants.isProvisionalSensorId(it) || OttaiConstants.looksLikeMac(it) }
    }

    override fun resolveNativeSensorName(sensorId: String?): String? {
        val raw = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        val canonical = resolveCanonicalSensorId(raw) ?: return null
        OttaiRegistry.findRecord(Applic.app, canonical) ?: return null
        return null
    }

    override fun hasPersistedManagedRecord(sensorId: String?): Boolean {
        val normalized = sensorId?.trim().takeIf { !it.isNullOrEmpty() } ?: return false
        return OttaiRegistry.findRecord(Applic.app, normalized) != null
    }

    override fun resolveCallbackDataptr(sensorId: String?): Long? =
        resolveCanonicalSensorId(sensorId)?.let { OttaiRegistry.findRecord(Applic.app, it) }?.let { 0L }

    override fun persistedSensorIds(context: Context): List<String> =
        OttaiRegistry.persistedRecords(context)
            .filter { OttaiRegistry.isManagedConnectionAuthorized(context, it.sensorId) }
            .map { OttaiRegistry.resolveCanonicalSensorId(context, it.sensorId) ?: it.sensorId }
            .distinct()

    override fun createManagedCallback(context: Context, sensorId: String, dataptr: Long): SuperGattCallback? {
        OttaiRegistry.createRestoredCallback(context, sensorId, dataptr)?.let { return it }
        val canonical = resolveCanonicalSensorId(sensorId)
            ?.takeIf { it.isNotBlank() && !OttaiConstants.isProvisionalSensorId(it) }
            ?: return null
        OttaiRegistry.findRecord(context, canonical) ?: return null
        return OttaiBleManager(canonical, dataptr).also { it.restoreFromPersistence(context) }
    }

    override fun removePersistedSensor(context: Context, sensorId: String?) {
        // Release the account binding as well. The account holds one bound sensor at a time, and
        // wiping only the local prefs left nothing that ever calls unbind — so the cloud stayed
        // bound to the sensor the user just removed and every device call for the NEXT one came
        // back AppUser_AlreadyBinding (12 in a row on 2026-07-29; that activation finished with
        // no successful cloud call at all).
        //
        // Best-effort throughout: the canonical id must be read before the wipe removes the
        // record that resolves it, the request is fire-and-forget on a background thread, and a
        // failure changes nothing locally — the sensor is gone from the app either way.
        val canonical = resolveCanonicalSensorId(sensorId)
            ?.takeIf { OttaiConstants.looksLikeMac(it) && OttaiRegistry.loadAccessToken(context).isNotBlank() }
        OttaiRegistry.removeSensor(context, sensorId)
        if (canonical == null) return
        val app = context.applicationContext
        unbindExecutor.execute {
            runCatching { OttaiCloudClient.unbind(app, canonical) }
                .onSuccess { Log.i(TAG, "cloud unbind $canonical released=$it") }
                .onFailure { Log.w(TAG, "cloud unbind $canonical failed: ${it.message}") }
        }
    }

    override fun isExternallyManagedBleSensor(sensorId: String?): Boolean =
        resolveCanonicalSensorId(sensorId)?.let { OttaiRegistry.findRecord(Applic.app, it) } != null

    override fun usesNativeDirectStreamShell(sensorId: String?): Boolean =
        false

    override fun hasNativeSensorBacking(sensorId: String?): Boolean? {
        val canonical = resolveCanonicalSensorId(sensorId) ?: return null
        OttaiRegistry.findRecord(Applic.app, canonical) ?: return null
        return false
    }

    override fun shouldUseNativeHistorySync(sensorId: String?): Boolean? {
        resolveCanonicalSensorId(sensorId) ?: return null
        return false // driver owns Room storage end-to-end
    }

}

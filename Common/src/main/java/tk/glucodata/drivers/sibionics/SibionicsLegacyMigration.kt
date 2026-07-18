package tk.glucodata.drivers.sibionics

import android.content.Context
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.ManagedSensorViewModeStore

/** One-time handoff of active native Sibionics 1/2 sensors to the managed driver. */
object SibionicsLegacyMigration {
    private const val MIGRATION_VERSION = 1
    private const val PREF_MIGRATION_VERSION = "sibionics_managed_legacy_migration_version"

    internal data class LegacySnapshot(
        val nativeName: String,
        val address: String?,
        val subtype: Int,
        val shortCode: String?,
        val bleName: String?,
        val startTimeMs: Long,
        val viewMode: Int,
        val autoResetDays: Int,
    )

    internal data class Candidate(
        val nativeName: String,
        val address: String?,
        val variant: SibionicsConstants.Variant,
        val shortCode: String?,
        val bleName: String?,
        val startTimeMs: Long,
        val viewMode: Int,
        val autoResetDays: Int,
        val protocolMode: SibionicsConstants.ProtocolMode,
    )

    internal fun LegacySnapshot.toCandidate(): Candidate? {
        val name = nativeName.trim()
        if (!SensorIdentity.isUsableSensorId(name) ||
            name.startsWith(SibionicsConstants.MANAGED_PREFIX, ignoreCase = true)
        ) {
            return null
        }
        val variant = SibionicsConstants.Variant.entries
            .firstOrNull { it.legacySubtype == subtype }
            ?: return null
        // GS3 remains on the legacy path until the managed implementation has live validation.
        if (variant == SibionicsConstants.Variant.GS3) return null

        val normalizedShortCode = SibionicsConstants.normalizeBleName(shortCode)
            .takeIf { it.length == 8 }
        val normalizedBleName = SibionicsConstants.normalizeBleName(bleName)
            .takeIf { it.length in 8..16 }
        val normalizedAutoResetDays = when {
            variant != SibionicsConstants.Variant.SIBIONICS2 -> SibionicsResetPolicy.DISABLED_DAYS
            autoResetDays in 1 until SibionicsResetPolicy.DISABLED_DAYS -> SibionicsResetPolicy.ENABLED_DAYS
            autoResetDays == SibionicsResetPolicy.DISABLED_DAYS -> SibionicsResetPolicy.DISABLED_DAYS
            else -> SibionicsResetPolicy.ENABLED_DAYS
        }
        return Candidate(
            nativeName = name,
            address = SibionicsConstants.normalizeBleAddress(address),
            variant = variant,
            shortCode = normalizedShortCode,
            bleName = normalizedBleName,
            startTimeMs = startTimeMs.coerceAtLeast(0L),
            viewMode = ManagedSensorViewModeStore.sanitize(viewMode),
            autoResetDays = normalizedAutoResetDays,
            protocolMode = if (variant == SibionicsConstants.Variant.CHINESE) {
                SibionicsConstants.ProtocolMode.CHINESE
            } else {
                SibionicsConstants.ProtocolMode.V120
            },
        )
    }

    @JvmStatic
    fun migrateActiveSensors(context: Context): Int {
        val prefs = SibionicsRegistry.prefs(context)
        if (prefs.getInt(PREF_MIGRATION_VERSION, 0) >= MIGRATION_VERSION) return 0

        val activeNames = try {
            Natives.activeSensors().orEmpty()
        } catch (error: Throwable) {
            Log.stack(SibionicsConstants.TAG, "legacy migration: active sensors", error)
            return 0
        }

        var migrated = 0
        activeNames.forEach { nativeName ->
            val dataptr = runCatching { Natives.getdataptr(nativeName) }.getOrDefault(0L)
            if (dataptr == 0L || !runCatching { Natives.isSibionics(dataptr) }.getOrDefault(false)) {
                return@forEach
            }
            val snapshot = runCatching {
                LegacySnapshot(
                    nativeName = nativeName,
                    address = Natives.getDeviceAddress(dataptr, true),
                    subtype = Natives.getSiSubtype(dataptr),
                    shortCode = Natives.getSiBluetoothNum(dataptr),
                    bleName = Natives.siGetDeviceName(dataptr),
                    startTimeMs = Natives.getSensorStartmsec(dataptr),
                    viewMode = Natives.getViewMode(dataptr),
                    autoResetDays = Natives.getAutoResetDays(dataptr),
                )
            }.onFailure {
                Log.stack(SibionicsConstants.TAG, "legacy migration: read $nativeName", it)
            }.getOrNull() ?: return@forEach
            val candidate = snapshot.toCandidate() ?: return@forEach
            val existing = SibionicsRegistry.findRecord(context, candidate.nativeName)
            val record = SibionicsRegistry.ensureSensorRecord(
                context = context,
                rawInput = candidate.nativeName,
                address = candidate.address,
                displayName = candidate.nativeName,
                variant = candidate.variant,
                shortCodeOverride = candidate.shortCode,
                bleNameOverride = candidate.bleName,
            )
            SibionicsRegistry.bindLegacyNativeName(context, record.sensorId, candidate.nativeName)
            if (existing == null) {
                SibionicsRegistry.saveStartTimeMs(context, record.sensorId, candidate.startTimeMs)
                SibionicsRegistry.saveProtocolMode(context, record.sensorId, candidate.protocolMode)
                SibionicsRegistry.saveAutoResetDays(context, record.sensorId, candidate.autoResetDays)
                SibionicsRegistry.saveAlgorithmSelection(context, record.sensorId, SibionicsAlgorithmSelection.STOCK)
                ManagedSensorViewModeStore.write(context, record.sensorId, candidate.viewMode)
            }
            migrated++
            Log.i(
                SibionicsConstants.TAG,
                "Migrated active legacy sensor ${candidate.nativeName} as ${record.sensorId} (${candidate.variant.id})",
            )
        }

        // Commit only after native enumeration completed, so a transient JNI failure retries next start.
        prefs.edit().putInt(PREF_MIGRATION_VERSION, MIGRATION_VERSION).commit()
        return migrated
    }
}

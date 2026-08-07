package tk.glucodata

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.NovoPen.PenDose
import tk.glucodata.NovoPen.PenDoseParser
import tk.glucodata.NovoPen.opennov.OpContext
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository

/** What the app remembers about one pen between scans. */
data class InsulinPen(
    val serial: String,
    val insulinPresetId: Long = 0L,
    val insulinName: String? = null,
    val addedAt: Long = 0L,
    val lastScanAt: Long = 0L,
    /** Newest dose second imported from this pen; the "nothing new" gate reads it. */
    val lastImportedDoseSeconds: Long = 0L,
    val importedDoseCount: Int = 0,
)

/**
 * Owns insulin pen support: whether NFC pens are read at all, which pens are known, and
 * the path from a scanned dose to a journal entry.
 *
 * Doses land in the Kotlin journal rather than the legacy native number store, because the
 * journal is what the app actually shows, counts as IOB and uploads as treatments.
 */
object InsulinPenManager {
    private const val LOG_ID = "InsulinPen"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val ENABLED_KEY = "insulin_pen_enabled"
    private const val PENS_KEY = "insulin_pen_registry"

    /** A newly paired pen only offers this much of its stored log pre-selected. */
    const val FIRST_SCAN_PRESELECT_SECONDS = 24L * 60 * 60

    /** How far back the review sheet lists doses at all. */
    const val REVIEW_WINDOW_SECONDS = 30L * 24 * 60 * 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs
        get() = Applic.app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pens = MutableStateFlow(loadPens())
    val pens: StateFlow<List<InsulinPen>> = _pens.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED_KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Off until asked for. An NFC reader that reacts to every ISO-DEP card in range —
     * bank cards, transit passes, door badges — is why users saw a pen error they had
     * never gone looking for.
     */
    @JvmStatic
    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun pen(serial: String): InsulinPen? = _pens.value.firstOrNull { it.serial == serial }

    fun setInsulin(serial: String, presetId: Long, presetName: String) {
        update(serial) { it.copy(insulinPresetId = presetId, insulinName = presetName) }
    }

    fun forget(serial: String) {
        _pens.value = _pens.value.filterNot { it.serial == serial }
        persist()
    }

    /**
     * Called from the NFC protocol thread while the pen is still in the field, to stop
     * reading segments once everything they hold is already in the journal.
     */
    @JvmStatic
    fun isFullyImported(serial: String?, referenceTimeSeconds: Long, raw: ByteArray?): Boolean {
        val known = serial?.let(::pen)?.lastImportedDoseSeconds ?: return false
        if (known <= 0L) return false
        val newest = PenDoseParser.parse(referenceTimeSeconds, raw, nowSeconds())
            .maxOfOrNull(PenDose::timestampSeconds)
            ?: return false
        return newest <= known
    }

    /** Entry point for a completed pen read: decodes the chunks and offers them for review. */
    @JvmStatic
    fun onScanned(serial: String, chunks: List<OpContext.Doses>) {
        val now = nowSeconds()
        val doses = PenDoseParser.merge(
            chunks.map { PenDoseParser.parse(it.referencetime, it.rawdoses, now) }
        )
        val known = pen(serial)
        update(serial) { it.copy(lastScanAt = System.currentTimeMillis()) }

        val cutoff = now - REVIEW_WINDOW_SECONDS
        val alreadyImported = known?.lastImportedDoseSeconds ?: 0L
        val fresh = doses.filter { it.timestampSeconds > cutoff && it.timestampSeconds > alreadyImported }
        if (fresh.isEmpty()) {
            Applic.Toaster(Applic.app.getString(R.string.insulin_pen_no_new_doses))
            return
        }
        val preselectFrom = if (known == null) now - FIRST_SCAN_PRESELECT_SECONDS else 0L
        InsulinPenScanBus.offer(
            PenScanResult(
                serial = serial,
                doses = fresh.sortedByDescending(PenDose::timestampSeconds),
                preselectFromSeconds = preselectFrom,
            )
        )
    }

    /**
     * Fire-and-forget import for the review sheet. Writing runs on a manager-owned scope,
     * so dismissing the sheet mid-save cannot leave half the doses in the journal.
     */
    fun importDosesAsync(
        serial: String,
        doses: List<PenDose>,
        presetId: Long,
        presetName: String,
    ) {
        scope.launch {
            val saved = importDoses(serial, doses, presetId, presetName)
            Applic.Toaster(Applic.app.getString(R.string.insulin_pen_doses_added, saved))
        }
    }

    /**
     * Writes the chosen doses to the journal. Re-scanning a pen is normal, so every dose
     * carries a stable source id and an already-stored dose is left untouched rather than
     * overwritten — an edited amount survives the next scan.
     */
    suspend fun importDoses(
        serial: String,
        doses: List<PenDose>,
        presetId: Long,
        presetName: String,
    ): Int = withContext(Dispatchers.IO) {
        if (doses.isEmpty()) return@withContext 0
        val repository = JournalRepository()
        var saved = 0
        doses.forEach { dose ->
            val recordId = sourceRecordId(serial, dose)
            runCatching {
                if (repository.hasEntryWithSourceRecordId(recordId)) return@forEach
                repository.upsertEntry(
                    JournalEntryInput(
                        timestamp = dose.timestampSeconds * 1000L,
                        type = JournalEntryType.INSULIN,
                        title = presetName,
                        note = Applic.app.getString(R.string.insulin_pen_name, serial),
                        amount = dose.units,
                        insulinPresetId = presetId.takeIf { it > 0L },
                        source = JournalEntrySource.PEN,
                        sourceRecordId = recordId,
                    )
                )
                saved++
            }.onFailure { error ->
                Log.e(LOG_ID, "Failed to journal pen dose: ${Log.stackline(error)}")
            }
        }
        // The cursor moves to the newest dose the reader accepted, so anything older they
        // left unticked stays declined instead of being offered again at every scan.
        val newest = doses.maxOf(PenDose::timestampSeconds)
        update(serial) {
            it.copy(
                insulinPresetId = presetId,
                insulinName = presetName,
                lastImportedDoseSeconds = maxOf(it.lastImportedDoseSeconds, newest),
                importedDoseCount = it.importedDoseCount + saved,
            )
        }
        if (saved > 0) {
            UiRefreshBus.requestDataRefresh()
            if (Natives.getpostTreatments()) Natives.wakeuploader()
        }
        saved
    }

    private fun sourceRecordId(serial: String, dose: PenDose) = "pen:$serial:${dose.timestampSeconds}"

    private fun nowSeconds() = System.currentTimeMillis() / 1000L

    private fun update(serial: String, transform: (InsulinPen) -> InsulinPen) {
        val existing = pen(serial)
        val base = existing ?: InsulinPen(serial = serial, addedAt = System.currentTimeMillis())
        val updated = transform(base)
        _pens.value = if (existing == null) {
            _pens.value + updated
        } else {
            _pens.value.map { if (it.serial == serial) updated else it }
        }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        _pens.value.forEach { pen ->
            array.put(
                JSONObject()
                    .put("serial", pen.serial)
                    .put("presetId", pen.insulinPresetId)
                    .put("insulinName", pen.insulinName ?: JSONObject.NULL)
                    .put("addedAt", pen.addedAt)
                    .put("lastScanAt", pen.lastScanAt)
                    .put("lastDose", pen.lastImportedDoseSeconds)
                    .put("count", pen.importedDoseCount)
            )
        }
        prefs.edit().putString(PENS_KEY, array.toString()).apply()
    }

    private fun loadPens(): List<InsulinPen> {
        val stored = prefs.getString(PENS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val serial = item.optString("serial").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                InsulinPen(
                    serial = serial,
                    insulinPresetId = item.optLong("presetId", 0L),
                    insulinName = item.optString("insulinName").takeIf { it.isNotBlank() },
                    addedAt = item.optLong("addedAt", 0L),
                    lastScanAt = item.optLong("lastScanAt", 0L),
                    lastImportedDoseSeconds = item.optLong("lastDose", 0L),
                    importedDoseCount = item.optInt("count", 0),
                )
            }
        }.getOrElse { error ->
            Log.e(LOG_ID, "Unreadable pen registry: ${Log.stackline(error)}")
            emptyList()
        }
    }
}

/** A finished pen read waiting for the reader to confirm what goes into the journal. */
data class PenScanResult(
    val serial: String,
    val doses: List<PenDose>,
    val preselectFromSeconds: Long,
)

/**
 * A pen is tapped against the phone from wherever the user happens to be, so the review
 * sheet is hosted once at the top of the Compose tree and woken through here.
 */
@Keep
object InsulinPenScanBus {
    private val _pending = MutableStateFlow<PenScanResult?>(null)
    val pending: StateFlow<PenScanResult?> = _pending.asStateFlow()

    fun offer(result: PenScanResult) {
        _pending.value = result
    }

    fun clear() {
        _pending.value = null
    }
}

package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tk.glucodata.Applic
import tk.glucodata.Log

@Keep
object NightscoutJournalFollowerImporter {
    private const val LOG_ID = "NightscoutJournalFollowerImporter"

    @JvmStatic
    @Keep
    fun importTreatments(sensorId: String, treatmentsJson: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching { importInternal(sensorId, treatmentsJson) }
                .onFailure { Log.e(LOG_ID, "importTreatments failed: ${Log.stackline(it)}") }
                .getOrDefault(0)
        }
    }

    private suspend fun importInternal(sensorId: String, treatmentsJson: String): Int {
        val trimmed = treatmentsJson.trim()
        if (trimmed.isEmpty()) return 0
        val array = JSONArray(trimmed)
        if (array.length() == 0) return 0

        val repository = JournalRepository()
        repository.ensureDefaultInsulinPresets()
        val presets = repository.getInsulinPresetsSnapshot()
        val sourcePrefix = "nightscout:${sensorId.trim().ifBlank { "unknown" }}"
        var imported = 0
        var deleted = 0
        val context = Applic.app

        for (index in 0 until array.length()) {
            val treatment = array.optJSONObject(index) ?: continue
            val parsed = JournalTreatmentTransfer.parseTreatment(
                context = context,
                treatment = treatment,
                source = JournalEntrySource.NIGHTSCOUT,
                sourcePrefix = sourcePrefix,
                insulinPresets = presets
            ) ?: continue

            if (parsed.deleteOnly) {
                repository.deleteEntriesBySourceRecordIds(parsed.candidateSourceRecordIds)
                deleted += parsed.candidateSourceRecordIds.size
                continue
            }

            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            val importedIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
            val staleIds = parsed.candidateSourceRecordIds.filterNot { it in importedIds }
            repository.deleteEntriesBySourceRecordIds(staleIds)
            deleted += staleIds.size
        }

        if (imported > 0 || deleted > 0) {
            Log.i(LOG_ID, "Nightscout journal sync imported=$imported deleted=$deleted")
        }
        return imported
    }
}

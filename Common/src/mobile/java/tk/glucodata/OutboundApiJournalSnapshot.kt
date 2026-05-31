package tk.glucodata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFoodEntity
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalInsulinPresetEntity
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.data.journal.JournalTreatmentTransfer

object OutboundApiJournalSnapshot {
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREDICTION_CARB_ABSORPTION_KEY = "dashboard_prediction_carb_absorption_g_per_h"
    private const val PREDICTION_CARB_ABSORPTION_DEFAULT = 35f
    private const val SNAPSHOT_EVENT_WINDOW_MS = 12L * 60L * 60L * 1000L
    private const val DEFAULT_ACTIVE_WINDOW_MS = 24L * 60L * 60L * 1000L
    private const val API_SOURCE_PREFIX = "api"

    @JvmStatic
    fun snapshotJson(timeMillis: Long): String = runBlocking {
        withContext(Dispatchers.IO) {
            buildSnapshot(timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()).toString()
        }
    }

    @JvmStatic
    fun importFromJson(raw: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            importJournal(raw, API_SOURCE_PREFIX)
        }
    }

    @JvmStatic
    fun importFromJsonForSource(raw: String, sourcePrefix: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            importJournal(raw, sourcePrefix.trim().ifBlank { API_SOURCE_PREFIX })
        }
    }

    private suspend fun buildSnapshot(atMillis: Long): JSONObject {
        val database = HistoryDatabase.getInstance(Applic.app)
        val dao = database.journalDao()
        val presetEntities = dao.getInsulinPresets()
        val presets = presetEntities.map { toPresetModel(it) }
        val presetsById = presets.associateBy { it.id }
        val presetEntitiesById = presetEntities.associateBy { it.id }
        val foodsById = dao.getFoods().associateBy { it.id }
        val maxPresetDurationMs = presets.maxOfOrNull { it.durationMinutes.coerceAtLeast(0) }?.times(60_000L)
            ?: DEFAULT_ACTIVE_WINDOW_MS
        val startMillis = (atMillis - maxOf(DEFAULT_ACTIVE_WINDOW_MS, maxPresetDurationMs) - 60_000L)
            .coerceAtLeast(0L)
        val entries = dao.getEntriesBetween(startMillis, atMillis)
        val iob = activeInsulinUnits(entries, presetsById, atMillis)
        val cob = activeCarbsGrams(entries, atMillis)
        val eventWindowStart = atMillis - SNAPSHOT_EVENT_WINDOW_MS
        val events = JSONArray()
        entries
            .filter { it.timestamp >= eventWindowStart }
            .takeLast(64)
            .forEach { entry -> events.put(entry.toTransferJson(presetEntitiesById, foodsById)) }
        return JSONObject()
            .put("schema", "tk.glucodata.journal.snapshot.v2")
            .put("timestamp", atMillis)
            .put("iob", finiteOrNull(iob))
            .put("journal_iob", finiteOrNull(iob))
            .put("cob", finiteOrNull(cob))
            .put("journal_cob", finiteOrNull(cob))
            .put("events", events)
            .put("treatments", events)
    }

    private suspend fun importJournal(raw: String, sourcePrefix: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0
        val events = runCatching { collectJournalEvents(trimmed) }.getOrNull() ?: return 0
        if (events.length() == 0) return 0

        val repository = JournalRepository()
        repository.ensureDefaultInsulinPresets()
        val presets = repository.getInsulinPresetsSnapshot()
        var imported = 0
        var deleted = 0
        for (index in 0 until events.length()) {
            val item = events.optJSONObject(index) ?: continue
            val parsed = JournalTreatmentTransfer.parseTreatment(
                context = Applic.app,
                treatment = item,
                source = JournalEntrySource.API,
                sourcePrefix = sourcePrefix,
                insulinPresets = presets
            )
                ?: continue
            if (parsed.deleteOnly) {
                repository.deleteEntriesBySourceRecordIds(parsed.candidateSourceRecordIds)
                deleted += parsed.candidateSourceRecordIds.size
                continue
            }
            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            val importedSourceIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
            val staleSourceIds = parsed.candidateSourceRecordIds.filterNot { it in importedSourceIds }
            repository.deleteEntriesBySourceRecordIds(staleSourceIds)
            deleted += staleSourceIds.size
        }
        return imported
    }

    private fun collectJournalEvents(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return collectJournalEvents(JSONArray(trimmed))
        val root = JSONObject(trimmed)
        root.optJSONObject("journal")?.let { return collectJournalEvents(it.toString()) }
        root.optJSONArray("events")?.let { return collectJournalEvents(it) }
        root.optJSONArray("treatments")?.let { return collectJournalEvents(it) }
        root.optJSONArray("journal")?.let { return collectJournalEvents(it) }
        root.optJSONArray("readings")?.let { readings ->
            val events = collectJournalEvents(readings)
            if (events.length() > 0) return events
        }
        return JSONArray().also { array ->
            if (root.looksLikeJournalEvent()) array.put(root)
        }
    }

    private fun collectJournalEvents(array: JSONArray): JSONArray {
        val events = JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.looksLikeJournalEvent()) {
                events.put(item)
                continue
            }
            val nested = collectJournalEvents(item.toString())
            for (nestedIndex in 0 until nested.length()) {
                events.put(nested.opt(nestedIndex))
            }
        }
        return events
    }

    private fun JSONObject.looksLikeJournalEvent(): Boolean {
        if (has("eventType") || has("eventtype") || has("event_type") || has("entryType") || has("journalType")) {
            return true
        }
        val type = optString("type", "").lowercase()
        if (JournalEntryType.entries.any { it.storageValue == type }) return true
        return hasTreatmentTimestamp() &&
            (
                has("carbs") ||
                    has("carb") ||
                    has("enteredCarbs") ||
                    has("insulin") ||
                    has("enteredInsulin") ||
                    has("bolus")
            )
    }

    private fun JSONObject.hasTreatmentTimestamp(): Boolean {
        for (key in listOf("date", "mills", "millis", "timestamp", "time", "createdAt", "created_at", "created_at_millis")) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            if (value is Number) return true
            if (value is String && value.trim().isNotBlank()) return true
        }
        return false
    }

    private fun JournalEntryEntity.toTransferJson(
        presetsById: Map<Long, JournalInsulinPresetEntity>,
        foodsById: Map<Long, JournalFoodEntity>
    ): JSONObject {
        val type = JournalEntryType.fromStorage(entryType)
        val transferId = sourceRecordId ?: nsRemoteId ?: "journal:$id"
        val treatment = JournalTreatmentTransfer.buildTreatmentJson(
            entry = this,
            remoteId = transferId,
            preset = insulinPresetId?.let(presetsById::get),
            food = foodId?.let(foodsById::get),
            useV3 = true
        ) ?: JSONObject()
            .put("date", timestamp)
            .put("eventType", defaultEventType(type))
            .put("type", type.storageValue)
        return treatment
            .put("id", id)
            .put("timestamp", timestamp)
            .put("sensorSerial", sensorSerial)
            .put("type", type.storageValue)
            .put("title", title)
            .put("note", note)
            .put("amount", finiteOrNull(amount))
            .put("glucose_mgdl", finiteOrNull(glucoseValueMgDl))
            .put("durationMinutes", durationMinutes)
            .put("intensity", intensity)
            .put("insulinPresetId", insulinPresetId)
            .put("insulinPreset", insulinPresetId?.let(presetsById::get)?.displayName)
            .put("foodId", foodId)
            .put("proteinGrams", finiteOrNull(proteinGrams))
            .put("fatGrams", finiteOrNull(fatGrams))
            .put("source", source)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("nsUploadedAt", nsUploadedAt)
            .put("nsRemoteId", nsRemoteId)
    }

    private fun activeInsulinUnits(
        entries: List<JournalEntryEntity>,
        presetsById: Map<Long, JournalInsulinPreset>,
        atMillis: Long
    ): Float {
        return entries.sumOf { entry ->
            if (JournalEntryType.fromStorage(entry.entryType) != JournalEntryType.INSULIN) return@sumOf 0.0
            val preset = entry.insulinPresetId?.let(presetsById::get) ?: return@sumOf 0.0
            if (!preset.countsTowardIob) return@sumOf 0.0
            val amount = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
            (amount * remainingCurveFraction(preset.curvePoints, entry.timestamp, atMillis)).toDouble()
        }.toFloat()
    }

    private fun activeCarbsGrams(entries: List<JournalEntryEntity>, atMillis: Long): Float {
        val prefs = Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val absorptionGramsPerHour = prefs
            .getFloat(PREDICTION_CARB_ABSORPTION_KEY, PREDICTION_CARB_ABSORPTION_DEFAULT)
            .coerceIn(10f, 90f)
        return entries.sumOf { entry ->
            if (JournalEntryType.fromStorage(entry.entryType) != JournalEntryType.CARBS) return@sumOf 0.0
            val grams = entry.amount?.takeIf { it.isFinite() && it > 0f } ?: return@sumOf 0.0
            val absorptionMinutes = entry.durationMinutes?.toFloat()
                ?: (grams / absorptionGramsPerHour * 60f).coerceIn(30f, 360f)
            val progress = linearProgress(entry.timestamp, absorptionMinutes, atMillis)
            (grams * (1f - progress)).coerceAtLeast(0f).toDouble()
        }.toFloat()
    }

    private fun remainingCurveFraction(
        points: List<tk.glucodata.data.journal.JournalCurvePoint>,
        doseTimestamp: Long,
        atMillis: Long
    ): Float {
        if (points.size < 2 || atMillis < doseTimestamp) return 0f
        val elapsedMinutes = ((atMillis - doseTimestamp) / 60_000f).coerceAtLeast(0f)
        val total = integrateCurve(points, points.last().minute.toFloat())
        if (total <= 0.0001f) return 0f
        val delivered = (integrateCurve(points, elapsedMinutes) / total).coerceIn(0f, 1f)
        return (1f - delivered).coerceIn(0f, 1f)
    }

    private fun integrateCurve(
        points: List<tk.glucodata.data.journal.JournalCurvePoint>,
        upToMinute: Float
    ): Float {
        if (points.size < 2 || upToMinute <= points.first().minute) return 0f
        var area = 0f
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            if (upToMinute <= start.minute) break
            val segmentEndMinute = minOf(upToMinute, end.minute.toFloat())
            val segmentWidth = segmentEndMinute - start.minute
            if (segmentWidth <= 0f) continue
            val fullWidth = (end.minute - start.minute).coerceAtLeast(1).toFloat()
            val endFraction = ((segmentEndMinute - start.minute) / fullWidth).coerceIn(0f, 1f)
            val segmentEndActivity = start.activity + ((end.activity - start.activity) * endFraction)
            area += ((start.activity + segmentEndActivity) * 0.5f) * segmentWidth
            if (upToMinute <= end.minute) break
        }
        return area
    }

    private fun linearProgress(startMillis: Long, durationMinutes: Float, atMillis: Long): Float {
        if (atMillis <= startMillis) return 0f
        val elapsedMinutes = (atMillis - startMillis) / 60_000f
        return (elapsedMinutes / durationMinutes.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun toPresetModel(entity: JournalInsulinPresetEntity): JournalInsulinPreset =
        JournalInsulinPreset(
            id = entity.id,
            displayName = entity.displayName,
            onsetMinutes = entity.onsetMinutes,
            durationMinutes = entity.durationMinutes,
            accentColor = entity.accentColor,
            curveJson = entity.curveJson,
            isBuiltIn = entity.isBuiltIn,
            isArchived = entity.isArchived,
            countsTowardIob = entity.countsTowardIob,
            sortOrder = entity.sortOrder
        )

    private fun defaultEventType(type: JournalEntryType): String =
        when (type) {
            JournalEntryType.INSULIN -> "Correction Bolus"
            JournalEntryType.CARBS -> "Meal Bolus"
            JournalEntryType.FINGERSTICK -> "BG Check"
            JournalEntryType.ACTIVITY -> "Exercise"
            JournalEntryType.NOTE -> "Note"
        }

    private fun finiteOrNull(value: Float?): Any? =
        value?.takeIf { it.isFinite() }?.toDouble()

}

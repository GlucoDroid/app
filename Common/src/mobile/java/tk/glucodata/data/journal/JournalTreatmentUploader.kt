package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.data.HistoryDatabase
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.Locale

/**
 * Sends Kotlin Journal entries to Nightscout as treatments. Replaces the legacy C++
 * uploadtreatments() path that pulled
 * from Numdata. Invoked from the native upload loop via NightPost.
 *
 * Sync state is tracked per-row on JournalEntryEntity (nsUploadedAt, nsRemoteId);
 * deletes are queued in journal_pending_deletes so they survive process death.
 */
@Keep
object JournalTreatmentUploader {
    private const val LOG_ID = "JournalTreatmentUploader"
    private const val ID_PREFIX = "jng-j-"
    private const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000  // mirrors C++ nighttimeback (30 days)

    // Mirrors writetreatment(V3) acceptance: 200/201 always; 409 only on V3 (POST conflict).
    private fun isUploadOk(code: Int, useV3: Boolean): Boolean {
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) return true
        if (useV3 && code == HttpURLConnection.HTTP_CONFLICT) return true
        return false
    }

    @JvmStatic
    @Keep
    fun uploadAll(useV3: Boolean): Boolean = runBlocking {
        try {
            uploadInternal(useV3)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "uploadAll failed: ${Log.stackline(th)}")
            false
        }
    }

    private suspend fun uploadInternal(useV3: Boolean): Boolean {
        val baseUrl = Natives.getnightuploadurl()?.takeIf { it.isNotBlank() } ?: return true
        val secretHashed = if (useV3) null else hashedSecret(Natives.getnightuploadsecret())
        val dao = HistoryDatabase.getInstance(Applic.app).journalDao()
        val presetCache = HashMap<Long, JournalInsulinPresetEntity?>()
        val foodCache = HashMap<Long, JournalFoodEntity?>()

        for (tomb in dao.getPendingNightscoutDeletes()) {
            val deleteUrl = treatmentDeleteUrl(baseUrl, tomb.nsRemoteId, useV3)
            if (NightPost.deleteUrl(deleteUrl, secretHashed)) {
                dao.clearPendingNightscoutDelete(tomb.entryId)
            } else {
                Log.e(LOG_ID, "tombstone delete failed for entryId=${tomb.entryId}")
                return false
            }
        }

        val sinceMillis = System.currentTimeMillis() - LOOKBACK_MILLIS
        val pending = dao.getEntriesNeedingNightscoutUpload(sinceMillis)
        for (entry in pending) {
            if (!isSendableType(entry.entryType)) continue
            if (isExternalMirrorSource(entry.source)) continue

            val remoteId = entry.nsRemoteId ?: (ID_PREFIX + entry.id.toString(16))
            // Re-upload: drop the old copy first (mirrors legacy delete-then-PUT/POST).
            if (entry.nsRemoteId != null) {
                NightPost.deleteUrl(treatmentDeleteUrl(baseUrl, remoteId, useV3), secretHashed)
            }

            val preset = entry.insulinPresetId?.let { id ->
                presetCache.getOrPut(id) { dao.getInsulinPresetById(id) }
            }
            val food = entry.foodId?.let { id ->
                foodCache.getOrPut(id) { dao.getFoodById(id) }
            }
            val json = JournalTreatmentTransfer.buildTreatmentJson(entry, remoteId, preset, food, useV3)
                ?: continue
            val postUrl = treatmentPostUrl(baseUrl, useV3)
            val code = NightPost.upload(postUrl, json.toString().toByteArray(Charsets.UTF_8), secretHashed, !useV3)
            if (!isUploadOk(code, useV3)) {
                Log.e(LOG_ID, "upload failed entry id=${entry.id} code=$code")
                return false
            }
            dao.markEntryUploadedToNightscout(entry.id, remoteId, System.currentTimeMillis())
        }
        return true
    }

    private fun isSendableType(entryType: String): Boolean {
        val type = JournalEntryType.fromStorage(entryType)
        return type == JournalEntryType.INSULIN ||
            type == JournalEntryType.CARBS ||
            type == JournalEntryType.FINGERSTICK ||
            type == JournalEntryType.ACTIVITY ||
            type == JournalEntryType.NOTE
    }

    private fun isExternalMirrorSource(source: String): Boolean {
        return source == JournalEntrySource.AAPS.storageValue ||
            source == JournalEntrySource.NIGHTSCOUT.storageValue ||
            source == JournalEntrySource.API.storageValue
    }

    private fun treatmentPostUrl(baseUrl: String, useV3: Boolean): String =
        baseUrl + if (useV3) "/api/v3/treatments" else "/api/v1/treatments"

    private fun treatmentDeleteUrl(baseUrl: String, remoteId: String, useV3: Boolean): String =
        baseUrl + (if (useV3) "/api/v3/treatments/" else "/api/v1/treatments/") + remoteId

    private fun hashedSecret(raw: String?): String? {
        val s = raw?.takeIf { it.isNotEmpty() } ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }
}

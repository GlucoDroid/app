package tk.glucodata

/**
 * Bridges managed-driver live writes to the native Nightscout uploader.
 *
 * Call this only after the matching point has been written to native poll storage. History
 * backfills deliberately do not wake the uploader; the next live point or explicit full sync
 * handles them without producing one network wake per historical row.
 */
object NightscoutUploadWake {
    private const val TAG = "NightscoutWake"

    @JvmStatic
    fun afterLiveNativeWrite(source: String, timestampMillis: Long) {
        if (timestampMillis <= 0L || !Natives.getuseuploader()) return
        runCatching {
            Natives.wakeNightscoutForLiveReading(source, timestampMillis)
        }.onFailure {
            Log.stack(TAG, "wake failed source=$source timestamp=$timestampMillis", it)
        }
    }
}

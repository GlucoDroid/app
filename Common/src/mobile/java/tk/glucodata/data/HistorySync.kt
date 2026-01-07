package tk.glucodata.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.Natives

/**
 * Singleton that synchronizes native glucose history with the Room database.
 * 
 * This is the ONLY source of data for the Room database.
 * Called from DashboardViewModel.refreshData() when data needs to be displayed.
 */
object HistorySync {
    
    private const val TAG = "HistorySync"
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyRepository = HistoryRepository(Applic.app)
    
    // Track if we've done the initial full sync this session
    @Volatile
    private var initialSyncDone = false
    
    /**
     * Sync data from native layer to Room.
     * 
     * Strategy:
     * - First call per session: Full sync (fetch ALL data from time 0) to catch old sensor history
     * - Subsequent calls: Incremental sync (only fetch new data) for performance
     * 
     * The DAO uses OnConflictStrategy.IGNORE so duplicates are handled efficiently.
     */
    @JvmOverloads
    fun syncFromNative(forceFull: Boolean = false) {
        scope.launch {
            try {
                // Determine start time for fetch
                val startSec: Long
                val isFullSync: Boolean
                
                if (forceFull || !initialSyncDone) {
                    // First sync of session OR forced: get ALL data
                    startSec = 0L
                    isFullSync = true
                } else {
                    // ROBUST SYNC: Always fetch overlap to catch backfilled data
                    // Instead of just > lastTimestamp, we go back 24 hours from the last known reading.
                    // This ensures that if the sensor backfilled a gap in the last day, we pick it up.
                    // Room's OnConflictStrategy.REPLACE handles the duplicates efficiently.
                    val lastTimestamp = historyRepository.getLatestTimestamp()
                    val oneDayMs = 24 * 60 * 60 * 1000L
                    
                    val startMs = if (lastTimestamp > oneDayMs) lastTimestamp - oneDayMs else 0L
                    startSec = startMs / 1000L
                    isFullSync = false
                }
                
                val rawHistory = Natives.getGlucoseHistory(startSec)
                if (rawHistory == null) {
                    // No data available, but mark initial sync as done
                    if (isFullSync) initialSyncDone = true
                    return@launch
                }
                
                val readings = mutableListOf<HistoryReading>()
                
                for (i in rawHistory.indices step 3) {
                    if (i + 2 >= rawHistory.size) break
                    
                    val timeSec = rawHistory[i]
                    val timeMs = timeSec * 1000L
                    
                    val valueAutoRaw = rawHistory[i + 1]
                    val valueRawRaw = rawHistory[i + 2]
                    
                    // Values from native are in mg/dL * 10
                    val value = valueAutoRaw / 10f
                    val rawValue = valueRawRaw / 10f
                    
                    // Rate is not available in historical ScanData, only in real-time GlucoseNow
                    // Leave as null - the database field is nullable
                    
                    if (value > 0 || rawValue > 0) {
                        readings.add(HistoryReading(
                            timestamp = timeMs,
                            value = value,
                            rawValue = rawValue,
                            rate = null  // ScanData doesn't store rate - only available in real-time
                        ))
                    }
                }
                
                if (readings.isNotEmpty()) {
                    historyRepository.storeReadings(readings)
                    Log.d(TAG, "Synced ${readings.size} readings (${if (isFullSync) "full" else "incremental"})")
                }
                
                // Mark initial sync as complete
                if (isFullSync) initialSyncDone = true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing from native", e)
            }
        }
    }
    
    /**
     * Force a full resync.
     * Useful after sensor data wipe + reconnect.
     */
    fun forceFullSync() {
        syncFromNative(forceFull = true)
    }
}

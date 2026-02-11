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
    
    // Throttle: don't re-sync more often than every 30 seconds
    @Volatile
    private var lastSyncTimeMs = 0L
    private const val MIN_SYNC_INTERVAL_MS = 30_000L
    
    // Incremental overlap: 5 minutes catches any very-recent backfill without
    // re-processing thousands of readings every call.  The full 24-hour sweep
    // only happens on forceFullSync() (called after vendor history download).
    private const val INCREMENTAL_OVERLAP_MS = 5 * 60 * 1000L
    
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
        // Throttle: skip if called too frequently (unless it's the first sync or forced)
        val now = System.currentTimeMillis()
        if (!forceFull && initialSyncDone && (now - lastSyncTimeMs) < MIN_SYNC_INTERVAL_MS) {
            return  // too soon, skip
        }
        
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
                    // Incremental: only overlap by 5 minutes to pick up very-recent writes.
                    // Room's OnConflictStrategy.IGNORE handles duplicates; this avoids
                    // re-fetching and re-inserting thousands of readings on every call.
                    val lastTimestamp = historyRepository.getLatestTimestamp()
                    val startMs = if (lastTimestamp > INCREMENTAL_OVERLAP_MS) lastTimestamp - INCREMENTAL_OVERLAP_MS else 0L
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
                    if (isFullSync) {
                        Log.d(TAG, "Synced ${readings.size} readings (full)")
                    }
                    // Incremental syncs are silent — only log when there's actually new data,
                    // and that happens inside HistoryRepository/DAO via IGNORE conflict detection.
                }
                
                lastSyncTimeMs = System.currentTimeMillis()
                
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

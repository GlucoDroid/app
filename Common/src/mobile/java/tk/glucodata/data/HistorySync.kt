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
    
    /**
     * Sync ALL available data from native layer to Room.
     * Uses deduplication (IGNORE on conflict) so duplicates are ignored.
     */
    fun syncFromNative() {
        scope.launch {
            try {
                // Fetch ALL history from native (no time limit)
                val rawHistory = Natives.getGlucoseHistory(0L)
                if (rawHistory == null) {
                    Log.d(TAG, "Native history returned null")
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
                    
                    if (value > 0 || rawValue > 0) {
                        readings.add(HistoryReading(
                            timestamp = timeMs,
                            value = value,
                            rawValue = rawValue,
                            rate = 0f
                        ))
                    }
                }
                
                if (readings.isNotEmpty()) {
                    historyRepository.storeReadings(readings)
                    Log.d(TAG, "Synced ${readings.size} readings from native")
                }
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
        syncFromNative()
    }
}

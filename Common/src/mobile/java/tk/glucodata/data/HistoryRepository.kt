package tk.glucodata.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.ui.GlucosePoint

/**
 * Repository for managing the independent glucose history database.
 * Handles:
 * - Storing new readings from the native layer
 * - Backfilling ALL existing history on first run
 * - Querying history for chart display
 */
class HistoryRepository(context: Context = Applic.app) {
    
    private val dao = HistoryDatabase.getInstance(context).historyDao()
    
    companion object {
        private const val TAG = "HistoryRepo"
        
        @Volatile
        private var backfillCompleted = false
    }
    
    /**
     * Store a new glucose reading in the history database.
     * Values should be in mg/dL (will be converted on display).
     */
    suspend fun storeReading(timestamp: Long, value: Float, rawValue: Float, rate: Float) {
        // Don't store invalid readings
        if (value <= 0 && rawValue <= 0) return
        
        val reading = HistoryReading(
            timestamp = timestamp,
            value = value,
            rawValue = rawValue,
            rate = rate
        )
        withContext(Dispatchers.IO) {
            try {
                dao.insert(reading)
            } catch (e: Exception) {
                Log.e(TAG, "Error storing reading", e)
            }
        }
    }
    
    /**
     * Store multiple readings at once (used for backfill).
     */
    suspend fun storeReadings(readings: List<HistoryReading>) {
        if (readings.isEmpty()) return
        
        withContext(Dispatchers.IO) {
            try {
                dao.insertAll(readings)
                Log.d(TAG, "Stored ${readings.size} readings")
            } catch (e: Exception) {
                Log.e(TAG, "Error storing readings batch", e)
            }
        }
    }
    
    /**
     * Get history for chart display. Returns data in user's preferred unit.
     * @param startTime Start time in milliseconds (0 = all data)
     * @param isMmol Whether to convert to mmol/L
     */
    suspend fun getHistory(startTime: Long, isMmol: Boolean): List<GlucosePoint> {
        return withContext(Dispatchers.IO) {
            try {
                val readings = dao.getReadingsSince(startTime)
                Log.d(TAG, "Room returned ${readings.size} readings since $startTime")
                readings.map { reading ->
                    var value = reading.value
                    var rawValue = reading.rawValue
                    
                    if (isMmol) {
                        value = value / 18.0182f
                        rawValue = rawValue / 18.0182f
                    }
                    
                    val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(reading.timestamp))
                    
                    GlucosePoint(
                        value = value,
                        time = timeStr,
                        timestamp = reading.timestamp,
                        rawValue = rawValue,
                        rate = reading.rate
                    )
                }.distinctBy { it.timestamp } // Ensure unique timestamps
            } catch (e: Exception) {
                Log.e(TAG, "Error getting history", e)
                emptyList()
            }
        }
    }
    
    /**
     * Get the count of stored readings.
     */
    suspend fun getReadingCount(): Int {
        return withContext(Dispatchers.IO) {
            try {
                dao.getCount()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting count", e)
                0
            }
        }
    }
    
    /**
     * Backfill ALL history from native layer on first run.
     * Only runs once per app session.
     */
    suspend fun ensureBackfilled() {
        if (backfillCompleted) return
        
        withContext(Dispatchers.IO) {
            try {
                val count = dao.getCount()
                if (count == 0) {
                    Log.d(TAG, "First run - backfilling ALL history from native")
                    backfillFromNative()
                } else {
                    Log.d(TAG, "History database has $count readings, skipping backfill")
                }
                backfillCompleted = true
            } catch (e: Exception) {
                Log.e(TAG, "Error during backfill check", e)
            }
        }
    }
    
    /**
     * Import ALL existing history from the native C++ layer.
     * No time limit - imports everything the sensor has stored.
     */
    private suspend fun backfillFromNative() {
        try {
            // Fetch ALL history (startSec = 0 means everything)
            val rawHistory = Natives.getGlucoseHistory(0L)
            if (rawHistory == null) {
                Log.d(TAG, "Native history returned null")
                return
            }
            
            val readings = mutableListOf<HistoryReading>()
            for (i in rawHistory.indices step 3) {
                if (i + 2 >= rawHistory.size) break
                
                val timeSec = rawHistory[i]
                val valueAutoRaw = rawHistory[i + 1]
                val valueRawRaw = rawHistory[i + 2]
                
                // Values from native are in mg/dL * 10
                val value = valueAutoRaw / 10f
                val rawValue = valueRawRaw / 10f
                
                if (value > 0 || rawValue > 0) {
                    readings.add(HistoryReading(
                        timestamp = timeSec * 1000L,
                        value = value,
                        rawValue = rawValue,
                        rate = 0f  // Rate not available from history
                    ))
                }
            }
            
            if (readings.isNotEmpty()) {
                dao.insertAll(readings)
                Log.d(TAG, "Backfilled ${readings.size} readings from native (ALL history)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error backfilling from native", e)
        }
    }
}

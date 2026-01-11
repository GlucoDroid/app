package tk.glucodata.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.Applic
import tk.glucodata.Natives

/**
 * Handles data clearing operations for app maintenance.
 * Provides 3 levels of data clearing:
 * 1. Clear History only (Room database)
 * 2. Clear App Data (history + cache, keep settings)
 * 3. Factory Reset (everything including settings)
 */
object DataManagement {
    
    private const val TAG = "DataManagement"
    
    /**
     * Clear only the glucose history database.
     * Settings and native data are preserved.
     */
    suspend fun clearHistory(context: Context = Applic.app): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Clear Room database
                val db = HistoryDatabase.getInstance(context)
                db.clearAllTables()
                Log.d(TAG, "Cleared history database")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history", e)
                false
            }
        }
    }
    
    /**
     * Clear app data (cache + native data) but preserve settings AND Room database.
     * SharedPreferences and glucose history are kept.
     */
    suspend fun clearAppData(context: Context = Applic.app): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // NOTE: Room database is intentionally NOT cleared here
                // User wants to preserve glucose history in this mode
                
                // 1. Clear cache directory
                context.cacheDir.deleteRecursively()
                
                // 2. Clear code cache
                context.codeCacheDir?.deleteRecursively()
                
                // 3. Clear ALL native data files in filesDir
                // This is where C++ stores sensor readings, calibrations, etc.
                context.filesDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                    Log.d(TAG, "Deleted: ${file.name}")
                }
                
                Log.d(TAG, "Cleared app data (preserved settings and Room DB)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing app data", e)
                false
            }
        }
    }
    
    /**
     * Factory reset - clear EVERYTHING.
     * App returns to first-run state.
     * Settings, history, cache, and native data all wiped.
     */
    suspend fun factoryReset(context: Context = Applic.app): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Clear Room database
                val db = HistoryDatabase.getInstance(context)
                db.clearAllTables()
                
                // 2. Clear all cache
                context.cacheDir.deleteRecursively()
                context.codeCacheDir?.deleteRecursively()
                
                // 3. Clear SharedPreferences
                val prefsDir = context.applicationInfo.dataDir + "/shared_prefs"
                val prefsFolder = java.io.File(prefsDir)
                prefsFolder.deleteRecursively()
                
                // 4. Clear native data files
                clearNativeDataFiles(context)
                
                Log.d(TAG, "Factory reset complete")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during factory reset", e)
                false
            }
        }
    }
    
    /**
     * Helper to clear ALL native C++ data files.
     * Deletes everything in filesDir to fully wipe sensor data, calibrations, etc.
     */
    private fun clearNativeDataFiles(context: Context) {
        try {
            // Delete ALL files in filesDir (not just specific extensions)
            // This is where C++ stores sensor data, calibrations, etc.
            context.filesDir.listFiles()?.forEach { file ->
                val deleted = file.deleteRecursively()
                Log.d(TAG, "Deleted ${file.name}: $deleted")
            }
            
            // Also clear databases directory
            context.databaseList()?.forEach { dbName ->
                context.deleteDatabase(dbName)
                Log.d(TAG, "Deleted database: $dbName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing native files", e)
        }
    }
}

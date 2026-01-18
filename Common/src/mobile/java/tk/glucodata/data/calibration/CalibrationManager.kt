package tk.glucodata.data.calibration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import tk.glucodata.Natives

object CalibrationManager {
    private const val TAG = "CalibrationManager"
    private const val PREFS_NAME = "calibration_prefs"
    private const val KEY_ENABLED_RAW = "calibration_enabled_raw"
    private const val KEY_ENABLED_AUTO = "calibration_enabled_auto"
    
    private lateinit var database: CalibrationDatabase
    private lateinit var dao: CalibrationDao
    private lateinit var prefs: SharedPreferences
    
    // Reactive list of calibrations
    private val _calibrations = MutableStateFlow<List<CalibrationEntity>>(emptyList())
    val calibrations: StateFlow<List<CalibrationEntity>> = _calibrations
    
    // Per-mode enable/disable state
    private val _isEnabledForRaw = MutableStateFlow(true)
    val isEnabledForRaw: StateFlow<Boolean> = _isEnabledForRaw
    
    private val _isEnabledForAuto = MutableStateFlow(true)
    val isEnabledForAuto: StateFlow<Boolean> = _isEnabledForAuto

    fun init(context: Context) {
        database = CalibrationDatabase.getInstance(context)
        dao = database.calibrationDao()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabledForRaw.value = prefs.getBoolean(KEY_ENABLED_RAW, true)
        _isEnabledForAuto.value = prefs.getBoolean(KEY_ENABLED_AUTO, true)
    }
    
    fun setEnabledForMode(isRawMode: Boolean, enabled: Boolean) {
        if (isRawMode) {
            _isEnabledForRaw.value = enabled
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_ENABLED_RAW, enabled).apply()
            }
        } else {
            _isEnabledForAuto.value = enabled
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_ENABLED_AUTO, enabled).apply()
            }
        }
        Log.i(TAG, "Calibration enabled for ${if (isRawMode) "Raw" else "Auto"}: $enabled")
    }
    
    fun isEnabledForMode(isRawMode: Boolean): Boolean {
        return if (isRawMode) _isEnabledForRaw.value else _isEnabledForAuto.value
    }
    
    suspend fun loadCalibrations() {
        if (::dao.isInitialized) {
            val list = dao.getAllSync()
            _calibrations.value = list
        }
    }

    suspend fun addCalibration(timestamp: Long, sensorValue: Float, sensorValueRaw: Float, userValue: Float, sensorId: String? = null, isRawMode: Boolean = false) {
        val entity = CalibrationEntity(
            timestamp = timestamp,
            sensorId = sensorId ?: Natives.lastsensorname() ?: "",
            sensorValue = sensorValue,
            sensorValueRaw = sensorValueRaw,
            userValue = userValue,
            isRawMode = isRawMode
        )
        dao.insert(entity)
        loadCalibrations()
        Log.i(TAG, "Added calibration: auto=$sensorValue raw=$sensorValueRaw user=$userValue isRaw=$isRawMode at $timestamp")
    }

    suspend fun restoreCalibration(entity: CalibrationEntity) {
        dao.insert(entity)
        loadCalibrations()
        Log.i(TAG, "Restored calibration: id=${entity.id}")
    }

    suspend fun deleteCalibration(entity: CalibrationEntity) {
        dao.delete(entity)
        loadCalibrations()
        Log.i(TAG, "Deleted calibration: id=${entity.id}")
    }
    
    suspend fun updateCalibration(entity: CalibrationEntity) {
        dao.update(entity)
        loadCalibrations()
    }
    
    suspend fun clearAll() {
        dao.deleteAll()
        loadCalibrations()
        Log.i(TAG, "Cleared all calibrations")
    }

    suspend fun restoreAll(calibrations: List<CalibrationEntity>) {
        dao.insertAll(calibrations)
        loadCalibrations()
        Log.i(TAG, "Restored ${calibrations.size} calibrations")
    }

    /**
     * Apply calibration to a glucose value.
     * Mode-specific: only applies calibrations made in the same mode (Raw or Auto).
     * Uses simple offset (single point) or linear regression (multiple points).
     */
    fun getCalibratedValue(value: Float, timestamp: Long, isRawMode: Boolean): Float {
        // Check per-mode enable
        if (!isEnabledForMode(isRawMode)) {
            return value
        }
        
        val currentSensor = Natives.lastsensorname() ?: ""
        // Use current state value
        val currentList = _calibrations.value
        
        val validPoints = currentList.filter { 
            it.isEnabled && 
            it.isRawMode == isRawMode &&  // Mode-specific filtering
            (it.sensorId == currentSensor || it.sensorId.isEmpty())
        }
        
        if (validPoints.isEmpty()) {
            return value // No calibration for this mode
        }
        
        // Use appropriate sensor value based on mode
        return if (validPoints.size == 1) {
            // Offset mode
            val p = validPoints[0]
            val sensorVal = if (isRawMode) p.sensorValueRaw else p.sensorValue
            val offset = p.userValue - sensorVal
            value + offset
        } else {
            // Linear Regression (OLS)
            val n = validPoints.size.toDouble()
            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumX2 = 0.0
            
            for (p in validPoints) {
                val x = (if (isRawMode) p.sensorValueRaw else p.sensorValue).toDouble()
                val y = p.userValue.toDouble()
                sumX += x
                sumY += y
                sumXY += x * y
                sumX2 += x * x
            }
            
            val denominator = n * sumX2 - sumX * sumX
            if (kotlin.math.abs(denominator) > 1e-5) {
                val a = (n * sumXY - sumX * sumY) / denominator
                val b = (sumY - a * sumX) / n
                (a * value + b).toFloat()
            } else {
                // Fallback to mean offset
                val meanOffset = validPoints.map { 
                    val sv = if (isRawMode) it.sensorValueRaw else it.sensorValue
                    it.userValue - sv 
                }.average()
                (value + meanOffset).toFloat()
            }
        }
    }
    
    /**
     * Check if there's active calibration for the given mode.
     */
    fun hasActiveCalibration(isRawMode: Boolean): Boolean {
        if (!isEnabledForMode(isRawMode)) return false
        
        val currentSensor = Natives.lastsensorname() ?: ""
        // Use current state value
        return _calibrations.value.any { 
            it.isEnabled && 
            it.isRawMode == isRawMode &&
            (it.sensorId == currentSensor || it.sensorId.isEmpty()) 
        }
    }

    /** Check if an enabled calibration was added at this timestamp (±30s tolerance) for given mode */
    fun hasCalibrationAt(timestamp: Long, isRawMode: Boolean): Boolean {
        if (!isEnabledForMode(isRawMode)) return false
        val currentSensor = Natives.lastsensorname() ?: ""
        return _calibrations.value.any { cal ->
            cal.isEnabled &&
            cal.isRawMode == isRawMode &&
            (cal.sensorId == currentSensor || cal.sensorId.isEmpty()) &&
            kotlin.math.abs(cal.timestamp - timestamp) <= 30_000L
        }
    }
    
    /** Get calibration at timestamp for editing (±30s tolerance) */
    fun getCalibrationAt(timestamp: Long, isRawMode: Boolean): CalibrationEntity? {
        val currentSensor = Natives.lastsensorname() ?: ""
        return _calibrations.value.find { cal ->
            cal.isRawMode == isRawMode &&
            (cal.sensorId == currentSensor || cal.sensorId.isEmpty()) &&
            kotlin.math.abs(cal.timestamp - timestamp) <= 30_000L
        }
    }
    
    /** Get visible calibrations for chart display (only enabled, matching mode and sensor) */
    fun getVisibleCalibrations(isRawMode: Boolean): List<CalibrationEntity> {
        if (!isEnabledForMode(isRawMode)) return emptyList()
        val currentSensor = Natives.lastsensorname() ?: ""
        return _calibrations.value.filter { cal ->
            cal.isEnabled &&
            cal.isRawMode == isRawMode &&
            (cal.sensorId == currentSensor || cal.sensorId.isEmpty())
        }
    }

    fun getCachedCalibrations(): List<CalibrationEntity> = _calibrations.value
    
    // Kept for backward compatibility if needed, but redundant now
    fun getCalibrationsFlow(): Flow<List<CalibrationEntity>> = _calibrations
}

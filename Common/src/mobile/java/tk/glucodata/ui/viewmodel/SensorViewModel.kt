package tk.glucodata.ui.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.Natives
import tk.glucodata.bluediag
import kotlin.math.abs

/**
 * Color palette for sensors - M3 Expressive style.
 * Colors are assigned deterministically based on sensor serial hash.
 */
object SensorColors {
    // Muted, expressive palette that works in both light and dark modes
    private val palette = listOf(
        Color(0xFF6750A4), // Primary purple
        Color(0xFF00796B), // Teal
        Color(0xFF5C6BC0), // Indigo
        Color(0xFFD81B60), // Pink
        Color(0xFF1E88E5), // Blue
        Color(0xFF43A047), // Green
        Color(0xFFF4511E), // Deep orange
        Color(0xFF8E24AA), // Purple
    )
    
    fun getColor(serial: String): Color {
        val index = abs(serial.hashCode()) % palette.size
        return palette[index]
    }
    
    fun getColorIndex(serial: String): Int {
        return abs(serial.hashCode()) % palette.size
    }
}

data class SensorInfo(
    val serial: String,
    val deviceAddress: String,
    val connectionStatus: String,
    val starttime: String,
    val streaming: Boolean,
    val rssi: Int,
    val dataptr: Long,
    val officialEnd: String,
    val expectedEnd: String,
    val viewMode: Int,
    val autoResetDays: Int,
    val isSibionics: Boolean,
    val isSibionics2: Boolean,
    val startMs: Long,
    val officialEndMs: Long,
    val expectedEndMs: Long,
    val customCalEnabled: Boolean,
    val customCalIndex: Int,
    val customCalAutoReset: Boolean,
    val detailedStatus: String = "",
    val isActive: Boolean = false  // True if this is the primary data source
) {
    /** Get the assigned color for this sensor */
    val color: Color get() = SensorColors.getColor(serial)
}


class SensorViewModel : ViewModel() {
    private val _sensors = MutableStateFlow<List<SensorInfo>>(emptyList())
    val sensors = _sensors.asStateFlow()

    // Polling job - only active when screen is visible
    private var pollingJob: Job? = null

    init {
        // Initial refresh only - no constant polling from init!
        refreshSensors()
    }

    /**
     * Start real-time polling when Sensors screen becomes visible.
     * Call this from LaunchedEffect in SensorScreen.
     */
    fun startPolling() {
        // Cancel any existing job first
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshSensors()
                kotlinx.coroutines.delay(2000) // 2 second refresh for real-time feel
            }
        }
    }

    /**
     * Stop polling when leaving Sensors screen.
     * Call this from DisposableEffect onDispose in SensorScreen.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Set the main active sensor (for notifications/xDrip).
     */
    fun setMain(serial: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Natives.setcurrentsensor(serial)
                refreshSensors()
            } catch (e: Exception) {
                android.util.Log.e("SensorVM", "Failed to set main sensor", e)
            }
        }
    }

    fun refreshSensors() {
        viewModelScope.launch {
            SensorBluetooth.updateDevices()
            val gatts = SensorBluetooth.mygatts() ?: ArrayList()
            
            // Get the currently active sensor (primary data source)
            val activeSensorSerial = try {
                var sName = Natives.lastsensorname()
                val activeSensors = Natives.activeSensors()
                
                // Conflict Resolution: If last sensor is gone but we have valid active ones,
                // switch focus to the valid one.
                if (activeSensors != null && activeSensors.isNotEmpty()) {
                    if (sName.isNullOrEmpty() || !activeSensors.contains(sName)) {
                         sName = activeSensors[0]
                    }
                }
                sName
            } catch (e: Exception) {
                null
            }
            
            val sensorList = gatts.map { gatt ->
                try {
                    val officialEndMs = Natives.getSensorEndTime(gatt.dataptr, true)
                    val expectedEndMs = Natives.getSensorEndTime(gatt.dataptr, false)
                    val startMs = Natives.getSensorStartmsec(gatt.dataptr)
                    val currentViewMode = Natives.getViewMode(gatt.dataptr)
                    var autoResetDays = Natives.getAutoResetDays(gatt.dataptr)
                    val isSi2 = Natives.isSibionics2(gatt.dataptr)
                    val isSi = Natives.isSibionics(gatt.dataptr)
                    // If 0 (Fresh), force to 21 (Default ON)
                    if (isSi2 && autoResetDays == 0) {
                        Natives.setAutoResetDays(gatt.dataptr, 21)
                        autoResetDays = 21
                    }

                    // Get custom calibration settings
                    val customSettings = Natives.getCustomCalibrationSettings(gatt.dataptr)
                    val customEnabled = (customSettings and 1L) != 0L
                    val customAutoReset = (customSettings and 2L) != 0L
                    val customIndex = ((customSettings ushr 8) and 0xFF).toInt()

                    // Get detailed status from native code which has accurate logic
                    val nativeStatus = Natives.getsensortext(gatt.dataptr) ?: ""
                    val bleStatus = gatt.constatstatusstr ?: ""
                    
                    // Calculate streaming status first - needed for status resolution
                    val isActivelyReceiving = nativeStatus.isNotEmpty() // If native status returns text, we are likely properly connected/receiving
                                                || gatt.streamingEnabled()
                    
                    
                    // Map common BLE status codes to user-friendly messages
                    fun mapBleStatus(status: String): String = when {
                        status == "Status=22" -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_bluetooth_off)
                        status == "Status=133" -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_connection_failed)
                        status.startsWith("Status=") -> status // Keep other Status= codes as-is
                        else -> status
                    }
                    
                    // Determine final display status with smart priority
                    val finalStatus = when {
                        // Priority 1: Meaningful native sensor states (errors, warming up, receiving history)
                        // TRUST THE NATIVE CODE: If it returns a string, it's important context.
                        nativeStatus.isNotEmpty() -> nativeStatus
                        
                        // Priority 2: ANY BLE status issues (Status=X codes, BT off, searching, loss of signal)
                        // These indicate real connection problems that should always show
                        bleStatus.isNotEmpty() &&
                        (bleStatus.startsWith("Status=") ||
                         bleStatus.contains("Bluetooth off", ignoreCase = true) ||
                         bleStatus.contains("search", ignoreCase = true) ||
                         bleStatus.contains("Loss of signal", ignoreCase = true)) -> bleStatus
                        
                        // Priority 3: Show "Connected" when actively receiving AND no BLE problems
                        // Only trust streaming flag if BLE status is normal (empty or just "Disconnected")
                        isActivelyReceiving && 
                        (bleStatus.isEmpty() || bleStatus == "Disconnected") -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_connected)
                        
                        // Fallback: Show disconnected
                        else -> tk.glucodata.Applic.app.getString(tk.glucodata.R.string.status_disconnected)
                    }
                    
                    // Apply user-friendly mapping to final status
                    val displayStatus = mapBleStatus(finalStatus)
                    
                    // Check if this sensor is the active/primary one
                    val sensorSerial = gatt.SerialNumber ?: "Unknown"
                    val isActiveSensor = activeSensorSerial != null && sensorSerial == activeSensorSerial


                    SensorInfo(
                        serial = sensorSerial,
                        deviceAddress = gatt.mActiveDeviceAddress ?: "Unknown",
                        // connectionStatus: Only show REAL BLE status codes (Status=X), not app messages
                        connectionStatus = if (bleStatus.startsWith("Status=")) mapBleStatus(bleStatus) else "",
                        starttime = if (startMs > 0) tk.glucodata.bluediag.datestr(startMs) else "",
                        streaming = isActivelyReceiving,
                        rssi = gatt.readrssi,
                        dataptr = gatt.dataptr,
                        officialEnd = if(officialEndMs > 0) tk.glucodata.bluediag.datestr(officialEndMs) else "",
                        expectedEnd = if(expectedEndMs > 0) tk.glucodata.bluediag.datestr(expectedEndMs) else "",
                        viewMode = currentViewMode,
                        autoResetDays = autoResetDays,
                        isSibionics = isSi,
                        isSibionics2 = isSi2,
                        startMs = startMs,
                        officialEndMs = officialEndMs,
                        expectedEndMs = expectedEndMs,
                        customCalEnabled = customEnabled,
                        customCalIndex = customIndex,
                        customCalAutoReset = customAutoReset,
                        detailedStatus = displayStatus,
                        isActive = isActiveSensor
                    )
                } catch (e: Exception) {
                    android.util.Log.e("SensorViewModel", "Error loading sensor ${gatt.SerialNumber}", e)
                    // Return placeholder sensor with error status instead of skipping it
                    SensorInfo(
                        serial = gatt.SerialNumber ?: "Error",
                        deviceAddress = gatt.mActiveDeviceAddress ?: "Unknown",
                        connectionStatus = "Load Error",
                        starttime = "",
                        streaming = false,
                        rssi = 0,
                        dataptr = gatt.dataptr,
                        officialEnd = "",
                        expectedEnd = "",
                        viewMode = 0,
                        autoResetDays = 0,
                        isSibionics = false,
                        isSibionics2 = false,
                        startMs = 0L,
                        officialEndMs = 0L,
                        expectedEndMs = 0L,
                        customCalEnabled = false,
                        customCalIndex = 0,
                        customCalAutoReset = false,
                        detailedStatus = "Error: ${e.message}",
                        isActive = false
                    )
                }
            }
            _sensors.value = sensorList
        }
    }

    fun setAutoResetDays(serial: String, days: Int) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            Natives.setAutoResetDays(gatt.dataptr, days)
            refreshSensors()
        }
    }

    // "Disconnect" in UI now maps to "Terminate" (finishSensor) as requested.
    fun terminateSensor(serial: String, wipeData: Boolean = false) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            if (wipeData) {
                Natives.siWipeDataOnly(gatt.dataptr)
            }
            gatt.finishSensor()
            SensorBluetooth.sensorEnded(serial)
            refreshSensors()
        }
    }

    fun forgetSensor(serial: String) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            gatt.searchforDeviceAddress()
            gatt.close()
            SensorBluetooth.startscan()
            refreshSensors()
        }
    }

    fun resetSensor(serial: String) {
         val gatts = SensorBluetooth.mygatts()
         val gatt = gatts.find { it.SerialNumber == serial }
         if (gatt != null) {
             Natives.setResetSibionics2(gatt.dataptr, true)
         }
    }

    fun clearCalibration(serial: String) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            Natives.siClearCalibration(gatt.dataptr)
        }
    }

    fun clearAll(serial: String) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            Natives.siClearAll(gatt.dataptr)
        }
    }

    fun setCalibrationMode(serial: String, mode: Int) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            Natives.setViewMode(gatt.dataptr, mode)
            refreshSensors()
        }
    }

    fun updateCustomCalibration(serial: String, enabled: Boolean, index: Int, autoReset: Boolean) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            Natives.setCustomCalibrationSettings(gatt.dataptr, enabled, index, autoReset)
            refreshSensors()
        }
    }

    fun reconnectSensor(serial: String, wipeData: Boolean = false) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            viewModelScope.launch {
                if (wipeData) {
                    Natives.siWipeDataOnly(gatt.dataptr)
                }
                gatt.setPause(true)
                gatt.disconnect()
                kotlinx.coroutines.delay(2000) // Ensure full disconnect
                Natives.resetbluetooth(gatt.dataptr)
                gatt.setPause(false)
                gatt.connectDevice(200)
                refreshSensors()
            }
        }
    }

    fun wipeSensorData(serial: String) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            Natives.siWipeDataOnly(gatt.dataptr)
            refreshSensors()
        }
    }

    fun disconnectSensor(serial: String) {
        android.util.Log.d("SensorViewModel", "disconnectSensor called for: $serial")
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
            viewModelScope.launch {
                android.util.Log.d("SensorViewModel", "Found gatt, using reconnect's proven sequence")
                gatt.setPause(true)
                gatt.disconnect()
                kotlinx.coroutines.delay(2000) // Ensure full disconnect
                Natives.resetbluetooth(gatt.dataptr)
                // DON'T reconnect - just refresh to show disconnected state
                refreshSensors()
            }
        } else {
            android.util.Log.d("SensorViewModel", "Gatt not found for serial: $serial")
        }
    }
}
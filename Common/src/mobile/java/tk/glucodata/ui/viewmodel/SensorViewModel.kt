package tk.glucodata.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tk.glucodata.SensorBluetooth
import tk.glucodata.SuperGattCallback
import tk.glucodata.Natives
import tk.glucodata.bluediag

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
    val viewMode: Int
)

class SensorViewModel : ViewModel() {
    private val _sensors = MutableStateFlow<List<SensorInfo>>(emptyList())
    val sensors = _sensors.asStateFlow()

    init {
        refreshSensors()
    }

    fun refreshSensors() {
        viewModelScope.launch {
            SensorBluetooth.updateDevices()
            val gatts = SensorBluetooth.mygatts() ?: ArrayList()
            val sensorList = gatts.map { gatt ->
                val officialEndMs = Natives.getSensorEndTime(gatt.dataptr, true)
                val expectedEndMs = Natives.getSensorEndTime(gatt.dataptr, false)
                val currentViewMode = Natives.getViewMode(gatt.dataptr)
                
                SensorInfo(
                    serial = gatt.SerialNumber ?: "Unknown",
                    deviceAddress = gatt.mActiveDeviceAddress ?: "Unknown",
                    connectionStatus = gatt.constatstatusstr ?: "Disconnected",
                    starttime = tk.glucodata.bluediag.datestr(gatt.starttime),
                    streaming = gatt.streamingEnabled(),
                    rssi = gatt.readrssi,
                    dataptr = gatt.dataptr,
                    officialEnd = if(officialEndMs > 0) tk.glucodata.bluediag.datestr(officialEndMs) else "",
                    expectedEnd = if(expectedEndMs > 0) tk.glucodata.bluediag.datestr(expectedEndMs) else "",
                    viewMode = currentViewMode
                )
            }
            _sensors.value = sensorList
        }
    }

    // "Disconnect" in UI now maps to "Terminate" (finishSensor) as requested.
    fun terminateSensor(serial: String) {
        val gatts = SensorBluetooth.mygatts()
        val gatt = gatts.find { it.SerialNumber == serial }
        if (gatt != null) {
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
}

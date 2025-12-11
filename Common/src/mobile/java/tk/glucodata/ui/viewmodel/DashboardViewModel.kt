package tk.glucodata.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tk.glucodata.Natives
import tk.glucodata.data.GlucoseRepository

class DashboardViewModel(
    private val glucoseRepository: GlucoseRepository = GlucoseRepository()
) : ViewModel() {
    private val _currentGlucose = MutableStateFlow("---")
    val currentGlucose = _currentGlucose.asStateFlow()

    private val _currentRate = MutableStateFlow(0f)
    val currentRate = _currentRate.asStateFlow()

    private val _sensorName = MutableStateFlow("")
    val sensorName = _sensorName.asStateFlow()

    private val _daysRemaining = MutableStateFlow("")
    val daysRemaining = _daysRemaining.asStateFlow()

    private val _xDripBroadcastEnabled = MutableStateFlow(false)
    val xDripBroadcastEnabled = _xDripBroadcastEnabled.asStateFlow()

    private val _patchedLibreBroadcastEnabled = MutableStateFlow(false)
    val patchedLibreBroadcastEnabled = _patchedLibreBroadcastEnabled.asStateFlow()

    private val _glucoseHistory = MutableStateFlow<List<tk.glucodata.ui.GlucosePoint>>(emptyList())
    val glucoseHistory = _glucoseHistory.asStateFlow()

    private val _unit = MutableStateFlow("mg/dL")
    val unit = _unit.asStateFlow()

    private val _targetLow = MutableStateFlow(70f)
    val targetLow = _targetLow.asStateFlow()

    private val _targetHigh = MutableStateFlow(180f)
    val targetHigh = _targetHigh.asStateFlow()

    private val _viewMode = MutableStateFlow(0)
    val viewMode = _viewMode.asStateFlow()

    // Alarm States
    private val _hasLowAlarm = MutableStateFlow(false)
    val hasLowAlarm = _hasLowAlarm.asStateFlow()

    private val _lowAlarmThreshold = MutableStateFlow(0f)
    val lowAlarmThreshold = _lowAlarmThreshold.asStateFlow()

    private val _hasHighAlarm = MutableStateFlow(false)
    val hasHighAlarm = _hasHighAlarm.asStateFlow()

    private val _highAlarmThreshold = MutableStateFlow(0f)
    val highAlarmThreshold = _highAlarmThreshold.asStateFlow()

    private val _lowAlarmSoundMode = MutableStateFlow(0)
    val lowAlarmSoundMode = _lowAlarmSoundMode.asStateFlow()

    private val _highAlarmSoundMode = MutableStateFlow(0)
    val highAlarmSoundMode = _highAlarmSoundMode.asStateFlow()

    init {
        refreshData()
        startCollection()
    }
    private fun startCollection() {
        viewModelScope.launch {
            glucoseRepository.getCurrentReading().collect { point ->
                if (point != null) {
                    _currentGlucose.value = if (point.value < 30) String.format("%.1f", point.value) else point.value.toInt().toString()
                    _currentRate.value = point.rate
                    
                    // Efficiently update history by appending the new point instead of re-querying the DB
                    val currentHistory = _glucoseHistory.value
                    if (currentHistory.isEmpty() || currentHistory.last().timestamp != point.timestamp) {
                        _glucoseHistory.value = currentHistory + point
                    }
                }
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            val unitVal = Natives.getunit()
            val isMmol = unitVal == 1
            _unit.value = if (isMmol) "mmol/L" else "mg/dL"
            
            _targetLow.value = Natives.targetlow()
            _targetHigh.value = Natives.targethigh()
            _xDripBroadcastEnabled.value = Natives.getxbroadcast()
            _patchedLibreBroadcastEnabled.value = Natives.getlibrelinkused()
            
            // Alarms - Native getters return values in User Unit
            _hasLowAlarm.value = Natives.hasalarmlow()
            _lowAlarmThreshold.value = Natives.alarmlow()
            
            _hasHighAlarm.value = Natives.hasalarmhigh()
            _highAlarmThreshold.value = Natives.alarmhigh()
            
            // Sound Modes: 0 = Vibrate Only, 1 = Sound (System/Custom)
            val lowSound = Natives.alarmhassound(0)
            _lowAlarmSoundMode.value = if (lowSound) 1 else 0
            
            val highSound = Natives.alarmhassound(1)
            _highAlarmSoundMode.value = if (highSound) 1 else 0
            
            // Get view mode from active sensor.
            val sName = Natives.lastsensorname()
            if (sName != null) {
                _sensorName.value = sName
                val dataptr = Natives.getdataptr(sName)
                if (dataptr != 0L) {
                    val vm = Natives.getViewMode(dataptr)
                    _viewMode.value = vm
                    
                    val expectedEnd = Natives.getSensorEndTime(dataptr, false)
                    val startMsec = Natives.getSensorStartmsec(dataptr)
                    
                    if (expectedEnd > 0 && startMsec > 0) {
                        val now = System.currentTimeMillis()
                        val usedMs = now - startMsec
                        val leftMs = expectedEnd - now
                        
                        val oneDayMs = 86400000L
                        val oneHourMs = 3600000L
                        
                        val usedStr = if (usedMs < oneDayMs) {
                            "${usedMs / oneHourMs}h in use"
                        } else {
                            "${usedMs / oneDayMs}d in use"
                        }
                        
                        val leftStr = if (leftMs < oneDayMs) {
                             if (leftMs < 0) "Expired" else "${leftMs / oneHourMs}h remaining"
                        } else {
                            "${leftMs / oneDayMs}d remaining"
                        }
                        
                        _daysRemaining.value = "$usedStr · $leftStr"
                    } else {
                         _daysRemaining.value = ""
                    }
                } else {
                     _daysRemaining.value = ""
                }
            } else {
                 _daysRemaining.value = ""
            }

            // Initial load of history (or reload)
            glucoseRepository.getHistory().let {
                _glucoseHistory.value = it
            }
        }
    }

    fun setLowAlarm(enabled: Boolean, threshold: Float) {
        // Natives.alarmhigh() returns value in User Unit
        val highThreshold = Natives.alarmhigh()
        val highEnabled = Natives.hasalarmhigh()
        
        val available = Natives.hasvaluealarm()
        val loss = Natives.hasalarmloss()
        
        // Natives.setalarms expects User Units
        Natives.setalarms(threshold, highThreshold, enabled, highEnabled, available, loss)
        refreshData()
    }

    fun setHighAlarm(enabled: Boolean, threshold: Float) {
        // Natives.alarmlow() returns value in User Unit
        val lowThreshold = Natives.alarmlow()
        val lowEnabled = Natives.hasalarmlow()
        
        val available = Natives.hasvaluealarm()
        val loss = Natives.hasalarmloss()
        
        Natives.setalarms(lowThreshold, threshold, lowEnabled, enabled, available, loss)
        refreshData()
    }

    fun setAlarmSound(type: Int, mode: Int) {
        // mode: 0 = Vibrate Only, 1 = Sound (System)
        // type: 0 = Low, 1 = High
        val flash = Natives.alarmhasflash(type)
        val sound = mode == 1
        val vibration = true // Always vibrate for now, or could depend on mode
        
        // Passing "" as uri to use default/clear custom
        Natives.writering(type, "", sound, flash, vibration)
        refreshData()
    }

    fun setUnit(mode: Int) {
        Natives.setunit(mode)
        refreshData()
    }
    
    fun setTargetLow(value: Float) {
        // Natives.targethigh() returns value in User Unit
        val high = Natives.targethigh()
        Natives.setTargetRange(value, high)
        refreshData()
    }

    fun setTargetHigh(value: Float) {
        // Natives.targetlow() returns value in User Unit
        val low = Natives.targetlow()
        Natives.setTargetRange(low, value)
        refreshData()
    }

    fun toggleXDripBroadcast(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
             val intent = android.content.Intent("com.eveningoutpost.dexdrip.BgEstimate")
             val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
             val names = receivers.mapNotNull { it.activityInfo?.packageName }.toTypedArray()
             Natives.setxdripRecepters(names)
             tk.glucodata.SendLikexDrip.setreceivers()
        } else {
             Natives.setxdripRecepters(emptyArray())
             tk.glucodata.SendLikexDrip.setreceivers()
        }
        _xDripBroadcastEnabled.value = Natives.getxbroadcast()
    }

    fun togglePatchedLibreBroadcast(enabled: Boolean) {
        val context = tk.glucodata.Applic.app
        if (enabled) {
             val intent = android.content.Intent("com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING")
             val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
             val names = receivers.mapNotNull { it.activityInfo?.packageName }.toTypedArray()
             Natives.setlibrelinkRecepters(names)
             tk.glucodata.XInfuus.setlibrenames()
        } else {
             Natives.setlibrelinkRecepters(emptyArray())
             tk.glucodata.XInfuus.setlibrenames()
        }
        _patchedLibreBroadcastEnabled.value = Natives.getlibrelinkused()
    }
}

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

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            _unit.value = if (Natives.getunit() == 1) "mmol/L" else "mg/dL"
            _targetLow.value = Natives.targetlow()
            _targetHigh.value = Natives.targethigh()
            _xDripBroadcastEnabled.value = Natives.getxbroadcast()
            _patchedLibreBroadcastEnabled.value = Natives.getlibrelinkused()
            
            // Get view mode from active sensor.
            val sName = Natives.lastsensorname()
            if (sName != null) {
                _sensorName.value = sName
                val dataptr = Natives.getdataptr(sName)
                if (dataptr != 0L) {
                    val vm = Natives.getViewMode(dataptr)
                    android.util.Log.d("DashboardViewModel", "refreshData: sensor=$sName viewMode=$vm")
                    _viewMode.value = vm
                    
                    val expectedEnd = Natives.getSensorEndTime(dataptr, false)
                    if (expectedEnd > 0) {
                        val diff = expectedEnd - System.currentTimeMillis()
                        val days = diff / (1000f * 60 * 60 * 24)
                        _daysRemaining.value = String.format("%.1f days left", days)
                    } else {
                         _daysRemaining.value = ""
                    }
                } else {
                    android.util.Log.d("DashboardViewModel", "refreshData: dataptr is 0 for $sName")
                     _daysRemaining.value = ""
                }
            } else {
                android.util.Log.d("DashboardViewModel", "refreshData: lastsensorname is null")
                 _daysRemaining.value = ""
            }

            glucoseRepository.getHistory().let {
                _glucoseHistory.value = it
            }
            glucoseRepository.getCurrentReading().collect { point ->
                if (point != null) {
                    _currentGlucose.value = if (point.value < 30) String.format("%.1f", point.value) else point.value.toInt().toString()
                    _currentRate.value = point.rate
                    // Refresh history periodically or on new data?
                    // For now, just append? Or re-fetch?
                    // Re-fetching ensures we get the latest calibration application.
                    glucoseRepository.getHistory().let { h ->
                         _glucoseHistory.value = h
                    }
                }
            }
        }
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

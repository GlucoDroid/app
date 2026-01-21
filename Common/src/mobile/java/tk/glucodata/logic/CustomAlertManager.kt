package tk.glucodata.logic

import android.content.Context
import android.util.Log
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.Notify
import java.util.Calendar

object CustomAlertManager {
    private const val TAG = "CustomAlertManager"

    fun checkAndTrigger(context: Context, glucose: Float, timestamp: Long) {
        val allAlerts = CustomAlertRepository.getAll()
        if (allAlerts.isEmpty()) return

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        // 1. Filter active alerts (Enabled + Time Range + Not Snoozed)
        val activeCandidates = allAlerts.filter { config ->
            if (!config.enabled) return@filter false
            if (!config.isActiveTime(currentMinutes)) return@filter false
            if (System.currentTimeMillis() < config.snoozedUntil) return@filter false
            true
        }

        // 2. Check Thresholds
        val triggeredHighs = activeCandidates
            .filter { it.type == CustomAlertType.HIGH && glucose >= it.threshold }
            .sortedByDescending { it.threshold } // Highest threshold first (Severity)

        val triggeredLows = activeCandidates
            .filter { it.type == CustomAlertType.LOW && glucose <= it.threshold }
            .sortedBy { it.threshold } // Lowest threshold first (Severity)

        // 3. Determine Priority (Low > High usually for safety, but here we pick the most severe of each?)
        // If we have a Low, that's urgent.
        val alertToTrigger = triggeredLows.firstOrNull() ?: triggeredHighs.firstOrNull()

        if (alertToTrigger != null) {
            triggerAlert(context, alertToTrigger, glucose)
        }
    }

    private fun triggerAlert(context: Context, config: CustomAlertConfig, glucose: Float) {
        Log.i(TAG, "Triggering Custom Alert: ${config.name} (Threshold: ${config.threshold}, Glucose: $glucose)")
        
        Notify.triggerCustomAlert(
            config.soundUri,
            config.sound,
            config.vibrate,
            config.flash,
            config.type == CustomAlertType.HIGH,
            glucose,
            config.style,
            config.intensity,
            config.overrideDnd
        )
    }
}

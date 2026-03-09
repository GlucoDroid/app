package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.Log

/**
 * Handles notification actions for alerts (snooze, dismiss).
 */
class AlarmActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val LOG_ID = "AlarmActionReceiver"
        const val ACTION_SNOOZE = "tk.glucodata.ACTION_SNOOZE"
        const val ACTION_DISMISS = "tk.glucodata.ACTION_DISMISS"
        const val EXTRA_ALERT_TYPE_ID = "alert_type_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val alertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)
        val alertType = if (alertTypeId >= 0) AlertType.fromId(alertTypeId) else null
        
        when (intent.action) {
            ACTION_DISMISS -> {
                Log.i(LOG_ID, "Dismiss action received for alert type: $alertType")
                Notify.stopalarm()
                alertType?.let { 
                    SnoozeManager.clearSnooze(it)
                    AlertStateTracker.onAlertDismissed(it)
                    
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    notificationManager.cancel(81432) // notify.java: glucosealarmid = 81432
                }
            }
            
            ACTION_SNOOZE -> {
                Log.i(LOG_ID, "Snooze action received for alert type: $alertType")
                Notify.stopalarm()
                
                // Get snooze duration (from intent or default from config)
                val snoozeMinutes = if (intent.hasExtra(EXTRA_SNOOZE_MINUTES)) {
                    intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                } else {
                    alertType?.let { AlertRepository.loadConfig(it).defaultSnoozeMinutes } ?: 15
                }
                
                // Register the snooze with SnoozeManager
                alertType?.let { 
                    SnoozeManager.snooze(it, snoozeMinutes)
                    Log.i(LOG_ID, "Snoozed ${it.name} for $snoozeMinutes minutes")
                }
            }
        }
    }
}

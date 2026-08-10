package tk.glucodata.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.CustomAlertAccess
import tk.glucodata.Log
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager

/**
 * Handles notification actions for alerts (snooze, dismiss) on the watch.
 * Wear counterpart of the mobile receiver; custom alerts go through the
 * reflective CustomAlertAccess shim (no-op when CustomAlertManager is absent).
 */
class AlarmActionReceiver : BroadcastReceiver() {

    companion object {
        private const val LOG_ID = "AlarmActionReceiver"
        const val ACTION_SNOOZE = "tk.glucodata.ACTION_SNOOZE"
        const val ACTION_DISMISS = "tk.glucodata.ACTION_DISMISS"
        const val ACTION_IGNORE = "tk.glucodata.ACTION_IGNORE"
        const val EXTRA_ALERT_TYPE_ID = "alert_type_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val customAlertId = intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID)
        val fallbackAlertTypeId = intent.getIntExtra(EXTRA_ALERT_TYPE_ID, -1)
        val resolvedAlertType = AlertType.fromId(Notify.resolveAlertKind(fallbackAlertTypeId))

        when (intent.action) {
            ACTION_DISMISS -> {
                Log.i(LOG_ID, "Dismiss action received for alert type: $resolvedAlertType")
                Notify.cancelQueuedAlarmActivityLaunch(
                    Notify.resolveAlertKind(fallbackAlertTypeId),
                    customAlertId,
                    "notification-dismiss"
                )
                Notify.stopalarm()
                if (customAlertId != null) {
                    CustomAlertAccess.dismissAlert(customAlertId)
                } else {
                    resolvedAlertType?.let {
                        if (AlertStateTracker.onAlertDismissed(it)) {
                            SnoozeManager.clearSnooze(it)
                            Notify.cancelCurrentRetrySession("notification-dismiss")
                        }
                    }
                }
                Notify.cancelAlertNotification()
            }

            ACTION_SNOOZE -> {
                Log.i(LOG_ID, "Snooze action received for alert type: $resolvedAlertType")
                Notify.cancelQueuedAlarmActivityLaunch(
                    Notify.resolveAlertKind(fallbackAlertTypeId),
                    customAlertId,
                    "notification-snooze"
                )

                val snoozeMinutes = if (intent.hasExtra(EXTRA_SNOOZE_MINUTES)) {
                    intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                } else if (customAlertId != null) {
                    15
                } else {
                    resolvedAlertType?.let { AlertRepository.loadConfig(it).defaultSnoozeMinutes } ?: 15
                }

                var productionAction = true
                if (customAlertId != null) {
                    CustomAlertAccess.snoozeAlert(customAlertId, snoozeMinutes)
                    Notify.cancelCurrentRetrySession("notification-snooze-custom-before-stop")
                } else {
                    productionAction = resolvedAlertType?.let {
                        !AlertStateTracker.consumeManualTestAction(it)
                    } ?: true
                    if (productionAction) {
                        resolvedAlertType?.let {
                            SnoozeManager.snooze(it, snoozeMinutes)
                            AlertStateTracker.resetState(it)
                            Log.i(LOG_ID, "Snoozed ${it.name} for $snoozeMinutes minutes")
                        }
                        Notify.cancelCurrentRetrySession("notification-snooze-before-stop")
                    }
                }
                Notify.stopalarm()
                if (productionAction) {
                    Notify.cancelCurrentRetrySession("notification-snooze-after-stop")
                }
                Notify.cancelAlertNotification()
            }

            ACTION_IGNORE -> {
                Log.i(LOG_ID, "Ignore action received for alert type: $resolvedAlertType")
                Notify.cancelQueuedAlarmActivityLaunch(
                    Notify.resolveAlertKind(fallbackAlertTypeId),
                    customAlertId,
                    "notification-ignore"
                )
                Notify.stopalarm()
                if (customAlertId != null) {
                    CustomAlertAccess.ignoreAlert(customAlertId)
                }
                Notify.cancelAlertNotification()
            }
        }
    }
}

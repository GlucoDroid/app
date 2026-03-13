package tk.glucodata.alerts

import tk.glucodata.Log

/**
 * Tracks episode state for active alerts.
 *
 * The first firing for an episode still comes from the live glucose path.
 * Timed retries are scheduled by Notify after that first firing, so this
 * tracker only needs to answer "has this episode already fired or been
 * acknowledged?".
 */
object AlertStateTracker {
    private const val LOG_ID = "AlertStateTracker"

    // Last time an alert of this type was triggered (ms)
    private val lastTriggerTime = mutableMapOf<AlertType, Long>()
    
    // User explicitly dismissed this alert for the current episode.
    // It stays suppressed until the condition clears and resetState() is called.
    private val dismissedAlerts = mutableSetOf<AlertType>()

    // Manual test should bypass snooze / time-range / retry gating once.
    private val manualTestBypass = mutableSetOf<AlertType>()

    /**
     * Determine if the live-reading path should fire an alert now.
     *
     * Once an episode has fired, timed retries are handled by Notify rather than
     * by subsequent glucose readings, so repeated live readings stay suppressed
     * until resetState() is called.
     */
    fun shouldTrigger(type: AlertType, config: AlertConfig): Boolean {
        if (manualTestBypass.remove(type)) {
            Log.i(LOG_ID, "${type.name}: Manual test bypass")
            return true
        }

        if (!config.isActiveNow()) {
            // Treat inactive time windows as condition-cleared boundaries so the alert
            // rearms cleanly when the active window starts again.
            resetState(type)
            return false
        }

        // 1. Snooze Check (Global priority)
        if (SnoozeManager.isSnoozed(type)) {
            return false
        }

        if (dismissedAlerts.contains(type)) {
            return false
        }

        val lastTime = lastTriggerTime[type] ?: 0L
        if (lastTime == 0L) {
            Log.i(LOG_ID, "${type.name}: First trigger")
            return true
        }

        return false
    }

    /**
     * Call this when the alert ACTUALLY fires (sound/notification played).
     * Updates timestamps and counters.
     */
    fun onAlertTriggered(type: AlertType) {
        dismissedAlerts.remove(type)
        lastTriggerTime[type] = System.currentTimeMillis()
    }

    fun onAlertDismissed(type: AlertType) {
        dismissedAlerts.add(type)
        Log.i(LOG_ID, "Dismissed ${type.name} for current episode")
    }

    fun allowNextTriggerForTest(type: AlertType) {
        manualTestBypass.add(type)
    }

    /**
     * Reset state for an alert type.
     * Call this when:
     * - Alert is Dismissed by user
     * - Glucose returns to normal (handled by Notify logic usually?)
     */
    fun resetState(type: AlertType) {
        if (
            lastTriggerTime.containsKey(type) ||
            dismissedAlerts.contains(type) ||
            manualTestBypass.contains(type)
        ) {
            Log.i(LOG_ID, "Resetting state for ${type.name}")
        }
        lastTriggerTime.remove(type)
        dismissedAlerts.remove(type)
        manualTestBypass.remove(type)
    }
}

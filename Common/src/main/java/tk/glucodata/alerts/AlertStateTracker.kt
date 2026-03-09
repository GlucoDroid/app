package tk.glucodata.alerts

import tk.glucodata.Log

/**
 * Tracks the state of active alerts to support "Retry" logic.
 * Ensures alerts don't fire continuously if they are already active,
 * unless the retry interval has passed.
 */
object AlertStateTracker {
    private const val LOG_ID = "AlertStateTracker"

    // Last time an alert of this type was triggered (ms)
    private val lastTriggerTime = mutableMapOf<AlertType, Long>()
    
    // How many times we have retried for the current alert cycle
    private val retryCounts = mutableMapOf<AlertType, Int>()

    // User explicitly dismissed this alert for the current episode.
    // It stays suppressed until the condition clears and resetState() is called.
    private val dismissedAlerts = mutableSetOf<AlertType>()

    // Manual test should bypass snooze / time-range / retry gating once.
    private val manualTestBypass = mutableSetOf<AlertType>()

    /**
     * Determine if an alert should trigger based on retry settings.
     * 
     * Logic:
     * - If !retryEnabled -> always true (but reset state)
     * - If snoozed -> false
     * - If first time -> true
     * - If within retryInterval -> false (suppress)
     * - If passed retryInterval:
     *   - If retries left -> true (increment count)
     *   - If no retries left -> false (gave up)
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

        if (!config.retryEnabled) {
            // One shot per episode: fire once, then stay quiet until the condition clears
            // (resetState) or the user snoozes, which restarts the cycle after snooze expiry.
            val lastTime = lastTriggerTime[type] ?: 0L
            if (lastTime > 0) {
                return false
            }
            return true
        }

        val lastTime = lastTriggerTime[type] ?: 0L
        val now = System.currentTimeMillis()
        val intervalMs = if (config.retryIntervalMinutes <= 0) 0L else config.retryIntervalMinutes * 60 * 1000L

        if (lastTime == 0L) {
            Log.i(LOG_ID, "${type.name}: First trigger (Starting retry cycle)")
            return true
        }

        val timeDiff = now - lastTime

        if ((intervalMs > 0L && timeDiff < intervalMs) || (intervalMs == 0L && timeDiff <= 0L)) {
            return false
        } else {
            val currentCount = retryCounts[type] ?: 0

            if (config.retryCount == 0 || currentCount < config.retryCount) {
                Log.i(LOG_ID, "${type.name}: Retrying ($currentCount < ${config.retryCount})")
                return true
            } else {
                Log.i(LOG_ID, "${type.name}: Max retries reached ($currentCount). Suppressing.")
                return false
            }
        }
    }

    /**
     * Call this when the alert ACTUALLY fires (sound/notification played).
     * Updates timestamps and counters.
     */
    fun onAlertTriggered(type: AlertType) {
        val lastTime = lastTriggerTime[type] ?: 0L
        dismissedAlerts.remove(type)

        if (lastTime == 0L) {
            lastTriggerTime[type] = System.currentTimeMillis()
            retryCounts[type] = 0
        } else {
            lastTriggerTime[type] = System.currentTimeMillis()
            val current = retryCounts[type] ?: 0
            retryCounts[type] = current + 1
        }
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
            retryCounts.containsKey(type) ||
            dismissedAlerts.contains(type) ||
            manualTestBypass.contains(type)
        ) {
            Log.i(LOG_ID, "Resetting state for ${type.name}")
        }
        lastTriggerTime.remove(type)
        retryCounts.remove(type)
        dismissedAlerts.remove(type)
        manualTestBypass.remove(type)
    }
}

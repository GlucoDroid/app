package tk.glucodata.alerts

import tk.glucodata.Log
import tk.glucodata.sms.SmsWatchdog

/**
 * Tracks episode state for active alerts.
 *
 * The first firing for an episode comes from the display-lane alert runtime.
 * Timed retries are scheduled by Notify after that first firing, so this
 * tracker only needs to answer "has this episode already fired or been
 * acknowledged?".
 */
object AlertStateTracker {
    private const val LOG_ID = "AlertStateTracker"
    private const val DEFAULT_REARM_COOLDOWN_MS = 5L * 60L * 1000L

    // Last time an alert of this type was triggered (ms)
    private val lastTriggerTime = mutableMapOf<AlertType, Long>()
    private val cooldownUntilTime = mutableMapOf<AlertType, Long>()
    
    // User explicitly dismissed this alert for the current episode.
    // It stays suppressed until the condition clears and resetState() is called, or
    // until consumeExpiredDismissal() releases it for a type that must not stay silent.
    private val dismissals = EpisodeDismissalState<AlertType>()

    // Manual tests use the real delivery surface, but must never acknowledge,
    // snooze, or cool down the corresponding production alert episode.
    private val manualTests = ManualAlertTestState<AlertType>()

    /**
     * Determine if the runtime should fire an alert now.
     *
     * Once an episode has fired, timed retries are handled by Notify rather than
     * by subsequent glucose readings, so repeated live readings stay suppressed
     * until resetState() is called.
     */
    @Synchronized
    fun shouldTrigger(type: AlertType, config: AlertConfig): Boolean {
        if (manualTests.consumeBypassAndActivate(type)) {
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
            Log.i(LOG_ID, "${type.name}: Suppressed by snooze")
            return false
        }

        if (dismissals.isDismissed(type)) {
            Log.i(LOG_ID, "${type.name}: Suppressed by episode dismissal")
            return false
        }

        val now = System.currentTimeMillis()
        val cooldownUntil = cooldownUntilTime[type] ?: 0L
        if (cooldownUntil > now) {
            Log.i(LOG_ID, "${type.name}: Suppressed by rearm cooldown for ${cooldownUntil - now}ms")
            return false
        }

        val lastTime = lastTriggerTime[type] ?: 0L
        if (lastTime == 0L) {
            // Only an accepted real trigger supersedes an unacknowledged test surface.
            manualTests.supersede(type)
            Log.i(LOG_ID, "${type.name}: First trigger")
            return true
        }

        return false
    }

    /**
     * Call this when the alert ACTUALLY fires (sound/notification played).
     * Updates timestamps and counters.
     */
    @Synchronized
    fun onAlertTriggered(type: AlertType): Boolean {
        if (manualTests.isActive(type)) {
            return false
        }
        dismissals.clear(type)
        lastTriggerTime[type] = System.currentTimeMillis()
        cooldownUntilTime[type] = lastTriggerTime.getValue(type) + DEFAULT_REARM_COOLDOWN_MS
        SmsWatchdog.onAlertFired(type.id)
        return true
    }

    @Synchronized
    fun onAlertDismissed(type: AlertType): Boolean {
        if (manualTests.consumeAction(type)) {
            return false
        }
        dismissals.dismiss(type, System.currentTimeMillis())
        SmsWatchdog.onAlertAcknowledged(type.id)
        Log.i(LOG_ID, "Dismissed ${type.name} for current episode")
        return true
    }

    /**
     * Release an episode dismissal that has been held longer than [ceilingMs].
     *
     * A dismissal normally lasts until the condition clears. For a glucose value that
     * simply stays past its threshold that never happens, so a single tap can silence
     * the alert indefinitely — the failure mode behind a low that ran for over an hour
     * with no second alarm. Callers that cannot afford open-ended silence pass a
     * ceiling; once it passes the episode is re-armed exactly as if it had never fired,
     * so the next evaluation treats it as a first trigger.
     *
     * @return true on the single call that releases the dismissal.
     */
    @Synchronized
    fun consumeExpiredDismissal(type: AlertType, ceilingMs: Long): Boolean {
        if (!dismissals.consumeExpired(type, ceilingMs, System.currentTimeMillis())) {
            return false
        }
        Log.i(LOG_ID, "Dismissal of ${type.name} expired after ${ceilingMs}ms; rearming")
        lastTriggerTime.remove(type)
        cooldownUntilTime.remove(type)
        return true
    }

    @Synchronized
    fun consumeManualTestAction(type: AlertType): Boolean {
        return manualTests.consumeAction(type)
    }

    @Synchronized
    fun isWaitingForRearmCooldown(type: AlertType): Boolean {
        return !dismissals.isDismissed(type) &&
            (cooldownUntilTime[type] ?: 0L) > System.currentTimeMillis()
    }

    @Synchronized
    fun allowNextTriggerForTest(type: AlertType) {
        manualTests.arm(type)
    }

    /**
     * Reset state for an alert type.
     * Call this when:
     * - Glucose returns to normal.
     * - The alert's active time window closes.
     */
    @Synchronized
    fun resetState(type: AlertType) {
        if (
            lastTriggerTime.containsKey(type) ||
            dismissals.isDismissed(type) ||
            manualTests.isPending(type)
        ) {
            Log.i(LOG_ID, "Resetting state for ${type.name}")
        }
        lastTriggerTime.remove(type)
        dismissals.clear(type)
        manualTests.clearPending(type)
        SmsWatchdog.onAlertResolved(type.id)
    }
}

/**
 * Episode dismissals, each remembered with the moment it was made.
 *
 * The timestamp exists so a dismissal can be given a ceiling: see
 * [AlertStateTracker.consumeExpiredDismissal]. Without one, a dismissal lasts until the
 * condition clears, which a persistent condition never does.
 */
internal class EpisodeDismissalState<T> {
    private val dismissedAtMs = mutableMapOf<T, Long>()

    fun dismiss(key: T, nowMs: Long) {
        dismissedAtMs[key] = nowMs
    }

    fun isDismissed(key: T): Boolean = key in dismissedAtMs

    fun clear(key: T) {
        dismissedAtMs.remove(key)
    }

    /**
     * @return true on the single call where [key] has been dismissed for at least
     * [ceilingMs]; the dismissal is dropped at that point. A ceiling of zero or less
     * means "no ceiling" and never expires.
     */
    fun consumeExpired(key: T, ceilingMs: Long, nowMs: Long): Boolean {
        if (ceilingMs <= 0L) return false
        val dismissedAt = dismissedAtMs[key] ?: return false
        if (dismissedAt > nowMs) {
            // Clock moved backwards. Re-baseline rather than wait it out, so the ceiling
            // stays reachable instead of the dismissal becoming permanent.
            dismissedAtMs[key] = nowMs
            return false
        }
        if (nowMs - dismissedAt < ceilingMs) return false
        dismissedAtMs.remove(key)
        return true
    }
}

/** Keeps manual-test actions separate from production alert episode state. */
internal class ManualAlertTestState<T> {
    private val pending = mutableSetOf<T>()
    private val active = mutableSetOf<T>()

    fun arm(key: T) {
        pending.add(key)
    }

    fun consumeBypassAndActivate(key: T): Boolean {
        if (!pending.remove(key)) return false
        active.add(key)
        return true
    }

    fun supersede(key: T) {
        active.remove(key)
    }

    fun consumeAction(key: T): Boolean = active.remove(key)

    fun clearPending(key: T) {
        pending.remove(key)
    }

    fun isPending(key: T): Boolean = key in pending

    fun isActive(key: T): Boolean = key in active
}

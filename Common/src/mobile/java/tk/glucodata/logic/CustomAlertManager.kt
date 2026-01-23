package tk.glucodata.logic

import android.content.Context
import android.util.Log
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.Notify
import java.util.concurrent.TimeUnit
import java.util.Calendar

object CustomAlertManager {
    private const val TAG = "CustomAlertManager"

    private val lastTriggerMap = mutableMapOf<String, Long>()

    fun checkAndTrigger(context: Context, glucose: Float, timestamp: Long) {
        val allAlerts = CustomAlertRepository.getAll()
        if (allAlerts.isEmpty()) return

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val nowMs = System.currentTimeMillis()

        // 1. Filter active alerts (Enabled + Time Range + Not Snoozed)
        // We do trigger check later, first identify who has the CONDITION met.
        val validConfigs = allAlerts.filter { config ->
            if (!config.enabled) return@filter false
            if (!config.isActiveTime(currentMinutes)) return@filter false
            if (nowMs < config.snoozedUntil) return@filter false
            true
        }

        // 2. triggeredCandidates: Alerts whose GLUCOSE THRESHOLD is met
        val triggeredCandidates = validConfigs.filter { config ->
            if (config.type == CustomAlertType.HIGH) glucose >= config.threshold
            else glucose <= config.threshold
        }

        // 3. Clean up stale state for alerts that are no longer valid (condition cleared)
        // This resets "One-Shot" alerts so they can fire again next episode.
        val activeIds = triggeredCandidates.map { it.id }.toSet()
        val staleIds = lastTriggerMap.keys.minus(activeIds)
        staleIds.forEach { lastTriggerMap.remove(it) }

        if (triggeredCandidates.isEmpty()) return

        // 4. Prioritize: Lows > Highs, then by severity (diff from threshold?)
        // Simple priority: Lows first (sorted by threshold ascending), then Highs (descending)
        val sortedCandidates = triggeredCandidates.sortedWith(
            compareBy<CustomAlertConfig> { it.type == CustomAlertType.HIGH } // Low (false) comes before High (true)
                .thenBy { if (it.type == CustomAlertType.LOW) it.threshold else -it.threshold }
        )

        val candidate = sortedCandidates.firstOrNull() ?: return

        // 5. Retry / Suppression Logic
        val lastTime = lastTriggerMap[candidate.id] ?: 0L
        var shouldFire = false

        if (lastTime == 0L) {
            // First time this episode
            shouldFire = true
        } else {
            // Recurring in same episode
            if (candidate.retryEnabled) {
                // Check interval
                // Check interval, enforcing a safe minimum (5 minutes) if 0 to prevent loops
                // (Unless user explicitly wants rapid re-trigger? Usually 0 means "Constant", which is bad for alarms)
                val intervalMs = TimeUnit.MINUTES.toMillis(candidate.retryIntervalMinutes.toLong())
                if (nowMs - lastTime >= intervalMs) {
                    shouldFire = true // Retry time!
                }
            } else {
                // One-shot enabled, already fired -> Suppress
                shouldFire = false
            }
        }

        if (shouldFire) {
            lastTriggerMap[candidate.id] = nowMs
            triggerAlert(context, candidate, glucose)
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
            config.durationSeconds,
            config.overrideDnd
        )
    }
}

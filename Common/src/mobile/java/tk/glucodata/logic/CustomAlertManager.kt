package tk.glucodata.logic

import android.content.Context
import android.util.Log
import tk.glucodata.Applic
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertRepository
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.Notify
import java.util.concurrent.TimeUnit

object CustomAlertManager {
    private const val TAG = "CustomAlertManager"

    private val lastTriggerMap = mutableMapOf<String, Long>()
    private val retryCountMap = mutableMapOf<String, Int>()
    private val dismissedMap = mutableSetOf<String>()

    fun checkAndTrigger(context: Context, glucose: Float, timestamp: Long) {
        val allAlerts = CustomAlertRepository.getAll()
        if (allAlerts.isEmpty()) return

        val readingMs = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        val readingMinutes = ((readingMs / 60000L) % (24 * 60)).toInt()

        val validConfigs = allAlerts.filter { config ->
            if (!config.enabled) return@filter false
            if (!config.isActiveTime(readingMinutes)) return@filter false
            if (readingMs < config.snoozedUntil) return@filter false
            true
        }

        val triggeredCandidates = validConfigs.filter { config ->
            if (config.type == CustomAlertType.HIGH) glucose >= config.threshold
            else glucose <= config.threshold
        }

        val activeIds = triggeredCandidates.map { it.id }.toSet()
        val staleIds = (lastTriggerMap.keys + retryCountMap.keys + dismissedMap).minus(activeIds)
        staleIds.forEach { id ->
            lastTriggerMap.remove(id)
            retryCountMap.remove(id)
            dismissedMap.remove(id)
        }

        if (triggeredCandidates.isEmpty()) return

        val sortedCandidates = triggeredCandidates.sortedWith(
            compareBy<CustomAlertConfig> { it.type == CustomAlertType.HIGH } // Low (false) comes before High (true)
                .thenBy { if (it.type == CustomAlertType.LOW) it.threshold else -it.threshold }
        )

        val candidate = sortedCandidates.firstOrNull() ?: return

        val lastTime = lastTriggerMap[candidate.id] ?: 0L
        var shouldFire = false

        if (dismissedMap.contains(candidate.id)) {
            shouldFire = false
        } else if (lastTime == 0L) {
            shouldFire = true
        } else {
            if (candidate.retryEnabled) {
                val intervalMs = if (candidate.retryIntervalMinutes <= 0) 0L
                else TimeUnit.MINUTES.toMillis(candidate.retryIntervalMinutes.toLong())
                val retryCount = retryCountMap[candidate.id] ?: 0
                val canRetry = candidate.retryCount == 0 || retryCount < candidate.retryCount
                val elapsed = readingMs - lastTime
                val passedInterval = if (intervalMs == 0L) elapsed > 0L else elapsed >= intervalMs
                if (canRetry && passedInterval) {
                    shouldFire = true
                }
            } else {
                shouldFire = false
            }
        }

        if (shouldFire) {
            if (lastTime != 0L) {
                retryCountMap[candidate.id] = (retryCountMap[candidate.id] ?: 0) + 1
            } else {
                retryCountMap[candidate.id] = 0
            }
            dismissedMap.remove(candidate.id)
            lastTriggerMap[candidate.id] = readingMs
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

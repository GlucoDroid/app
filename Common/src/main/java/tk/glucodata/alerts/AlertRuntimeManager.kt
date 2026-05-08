package tk.glucodata.alerts

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.SuperGattCallback

data class AlertRuntimeEvaluation(
    val standardGlucoseAlertHandled: Boolean = false,
    val standardGlucoseAlertStarted: Boolean = false
)

object AlertRuntimeManager {
    private const val LOG_ID = "AlertRuntimeManager"
    private const val CHECK_INTERVAL_MS = 15_000L
    private const val SENSOR_EXPIRY_WARNING_MS = 24L * 60L * 60L * 1000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var monitorTask: ScheduledFuture<*>? = null

    private var lastReadingTimeMs: Long = 0L
    private var lastDeliveredReadingTimeMs: Long = 0L
    private var lastGlucoseValue: Float = Float.NaN
    private var lastRate: Float = Float.NaN
    private var persistentHighStartedAtMs: Long = 0L

    private val standardGlucoseAlertTypes = listOf(
        AlertType.VERY_LOW,
        AlertType.LOW,
        AlertType.VERY_HIGH,
        AlertType.HIGH,
        AlertType.PRE_LOW,
        AlertType.PRE_HIGH
    )

    fun ensureMonitoring() {
        synchronized(lock) {
            bootstrapLastReadingLocked()
            ensureTaskLocked()
            evaluateLocked(System.currentTimeMillis())
        }
    }

    fun onNewReading(glucoseValue: Float, rate: Float, readingTimeMs: Long): AlertRuntimeEvaluation {
        return onNewReading(null, glucoseValue, rate, readingTimeMs, 0)
    }

    @JvmOverloads
    fun onNewReading(
        sensorId: String?,
        glucoseValue: Float,
        rate: Float,
        readingTimeMs: Long,
        sensorGen: Int = 0
    ): AlertRuntimeEvaluation {
        val snapshot = CurrentDisplaySource.resolveIncomingReading(
            liveNumericValue = glucoseValue,
            rate = rate,
            targetTimeMillis = readingTimeMs,
            preferredSensorId = sensorId,
            sensorGen = sensorGen
        )
        synchronized(lock) {
            lastReadingTimeMs = maxOf(lastReadingTimeMs, readingTimeMs)
            if (snapshot != null && snapshot.primaryValue.isFinite()) {
                lastGlucoseValue = snapshot.primaryValue
                lastRate = snapshot.rate
            } else {
                lastGlucoseValue = glucoseValue
                lastRate = rate
            }
            lastDeliveredReadingTimeMs = maxOf(lastDeliveredReadingTimeMs, readingTimeMs)
            ensureTaskLocked()
            return evaluateLocked(readingTimeMs)
        }
    }

    private fun ensureTaskLocked() {
        if (monitorTask == null || monitorTask?.isCancelled == true) {
            monitorTask = scheduler.scheduleAtFixedRate(
                {
                    synchronized(lock) {
                        evaluateLocked(System.currentTimeMillis())
                    }
                },
                CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun evaluateLocked(nowMs: Long): AlertRuntimeEvaluation {
        bootstrapLastReadingLocked()
        syncCurrentReadingLocked()

        val standardAlertEvaluation = evaluateStandardGlucoseAlertsLocked()
        evaluateMissedReadingLocked(nowMs)
        evaluatePersistentHighLocked(nowMs)
        evaluateSensorExpiryLocked(nowMs)
        return standardAlertEvaluation
    }

    private fun syncCurrentReadingLocked() {
        val latest = try {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)
        } catch (t: Throwable) {
            null
        } ?: return

        if (latest.timeMillis <= 0L || !latest.primaryValue.isFinite()) {
            return
        }

        if (latest.timeMillis >= lastReadingTimeMs || !lastGlucoseValue.isFinite()) {
            lastReadingTimeMs = latest.timeMillis
            lastGlucoseValue = latest.primaryValue
            lastRate = latest.rate
        }

        if (latest.timeMillis <= lastDeliveredReadingTimeMs) {
            return
        }

        lastDeliveredReadingTimeMs = latest.timeMillis
        if (latest.source == "callback") {
            return
        }

        try {
            SuperGattCallback.processExternalCurrentReading(
                latest.sensorId,
                latest.primaryValue,
                latest.rate,
                latest.timeMillis,
                latest.sensorGen
            )
            Log.i(LOG_ID, "Processed external reading source=${latest.source} time=${latest.timeMillis}")
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "syncCurrentReadingLocked", t)
        }
    }

    private fun evaluateStandardGlucoseAlertsLocked(): AlertRuntimeEvaluation {
        val glucoseValue = currentGlucoseValueLocked() ?: return AlertRuntimeEvaluation()
        val rate = currentRateLocked()
        val configs = standardGlucoseAlertTypes.associateWith { AlertRepository.loadConfig(it) }
        val activeType = resolveActiveStandardGlucoseAlert(glucoseValue, rate, configs)

        standardGlucoseAlertTypes.forEach { type ->
            if (type != activeType) {
                clearRuntimeAlert(type, "standard-condition-cleared")
            }
        }

        val type = activeType ?: return AlertRuntimeEvaluation()
        val config = configs[type] ?: return AlertRuntimeEvaluation()
        if (!config.isActiveNow()) {
            clearRuntimeAlert(type, "standard-time-inactive")
            return AlertRuntimeEvaluation()
        }
        if (SnoozeManager.isSnoozed(type)) {
            return AlertRuntimeEvaluation()
        }

        val message = Applic.app.getString(type.nameResId) + " " + Notify.glucosestr(glucoseValue)
        return AlertRuntimeEvaluation(
            standardGlucoseAlertHandled = true,
            standardGlucoseAlertStarted = triggerAlert(type, glucoseValue, rate, message)
        )
    }

    private fun resolveActiveStandardGlucoseAlert(
        glucoseValue: Float,
        rate: Float,
        configs: Map<AlertType, AlertConfig>
    ): AlertType? {
        fun threshold(type: AlertType): Float? {
            val config = configs[type] ?: return null
            if (!config.enabled) return null
            return config.threshold?.takeIf { it.isFinite() && it > 0f }
        }

        threshold(AlertType.VERY_LOW)?.let { if (glucoseValue <= it) return AlertType.VERY_LOW }
        threshold(AlertType.LOW)?.let { if (glucoseValue <= it) return AlertType.LOW }
        threshold(AlertType.VERY_HIGH)?.let { if (glucoseValue >= it) return AlertType.VERY_HIGH }
        threshold(AlertType.HIGH)?.let { if (glucoseValue >= it) return AlertType.HIGH }

        val projected = projectedGlucose(glucoseValue, rate)
        if (projected.isFinite()) {
            threshold(AlertType.PRE_LOW)?.let { if (projected <= it) return AlertType.PRE_LOW }
            threshold(AlertType.PRE_HIGH)?.let { if (projected >= it) return AlertType.PRE_HIGH }
        }

        return null
    }

    private fun projectedGlucose(glucoseValue: Float, rate: Float): Float {
        if (!glucoseValue.isFinite() || !rate.isFinite()) {
            return Float.NaN
        }
        return glucoseValue + rate * 0.4f * 180.0f
    }

    private fun evaluateMissedReadingLocked(nowMs: Long) {
        val type = AlertType.MISSED_READING
        val config = AlertRepository.loadConfig(type)
        val durationMs = (config.durationMinutes ?: 0) * 60_000L

        if (!config.enabled || durationMs <= 0L || lastReadingTimeMs <= 0L) {
            clearRuntimeAlert(type, "missed-reading-disabled")
            return
        }

        if (!config.isActiveNow()) {
            clearRuntimeAlert(type, "missed-reading-time-inactive")
            return
        }
        if (SnoozeManager.isSnoozed(type)) {
            return
        }

        val missed = nowMs - lastReadingTimeMs >= durationMs
        if (!missed) {
            clearRuntimeAlert(type, "new-reading-arrived")
            return
        }

        val glucoseValue = currentGlucoseValueLocked() ?: return
        val message = Applic.app.getString(R.string.alert_missed_reading) + " - " +
            Applic.app.getString(R.string.minutes_short_format, config.durationMinutes ?: 0)

        triggerAlert(type, glucoseValue, currentRateLocked(), message)
    }

    private fun evaluatePersistentHighLocked(nowMs: Long) {
        val type = AlertType.PERSISTENT_HIGH
        val config = AlertRepository.loadConfig(type)
        val threshold = config.threshold
        val durationMs = (config.durationMinutes ?: 0) * 60_000L
        val glucoseValue = currentGlucoseValueLocked()

        if (!config.enabled || threshold == null || durationMs <= 0L || glucoseValue == null) {
            persistentHighStartedAtMs = 0L
            clearRuntimeAlert(type, "persistent-high-disabled")
            return
        }

        if (glucoseValue <= threshold) {
            persistentHighStartedAtMs = 0L
            clearRuntimeAlert(type, "persistent-high-cleared")
            return
        }

        if (persistentHighStartedAtMs == 0L) {
            persistentHighStartedAtMs = lastReadingTimeMs.takeIf { it > 0L } ?: nowMs
        }

        if (!config.isActiveNow()) {
            persistentHighStartedAtMs = 0L
            clearRuntimeAlert(type, "persistent-high-time-inactive")
            return
        }
        if (SnoozeManager.isSnoozed(type)) {
            return
        }

        if (nowMs - persistentHighStartedAtMs < durationMs) {
            return
        }

        val message = Applic.app.getString(R.string.alert_persistent_high) + " " + Notify.glucosestr(glucoseValue)
        triggerAlert(type, glucoseValue, currentRateLocked(), message)
    }

    private fun evaluateSensorExpiryLocked(nowMs: Long) {
        val type = AlertType.SENSOR_EXPIRY
        val config = AlertRepository.loadConfig(type)
        if (!config.enabled) {
            clearRuntimeAlert(type, "sensor-expiry-disabled")
            return
        }

        val endTimeMs = try {
            Natives.getendtime()
        } catch (t: Throwable) {
            0L
        }

        if (endTimeMs <= 0L || endTimeMs - nowMs > SENSOR_EXPIRY_WARNING_MS) {
            clearRuntimeAlert(type, "sensor-expiry-not-due")
            return
        }

        if (!config.isActiveNow()) {
            clearRuntimeAlert(type, "sensor-expiry-time-inactive")
            return
        }
        if (SnoozeManager.isSnoozed(type)) {
            return
        }

        val glucoseValue = currentGlucoseValueLocked() ?: return
        val remainingHours = ((endTimeMs - nowMs).coerceAtLeast(0L) / 3_600_000L).toInt().coerceAtLeast(1)
        val message = Applic.app.getString(R.string.alert_sensor_expiry) + " - " +
            Applic.app.getString(R.string.hours_short, remainingHours)

        triggerAlert(type, glucoseValue, currentRateLocked(), message)
    }

    private fun triggerAlert(type: AlertType, glucoseValue: Float, rate: Float, message: String): Boolean {
        try {
            val triggered = Notify.triggerSupplementalGlucoseAlert(type.id, glucoseValue, rate, message)
            if (triggered) {
                Log.i(LOG_ID, "Triggered ${type.name}: $message")
            }
            return triggered
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "triggerAlert ${type.name}", t)
            return false
        }
    }

    private fun clearRuntimeAlert(type: AlertType, reason: String) {
        AlertStateTracker.resetState(type)
        Notify.cancelRetrySession(type.id, reason)
    }

    private fun bootstrapLastReadingLocked() {
        if (lastReadingTimeMs > 0L && lastGlucoseValue.isFinite()) {
            return
        }
        val latest = try {
            CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout)
        } catch (t: Throwable) {
            null
        } ?: return

        if (lastReadingTimeMs <= 0L) {
            lastReadingTimeMs = latest.timeMillis
        }
        if (lastDeliveredReadingTimeMs <= 0L) {
            lastDeliveredReadingTimeMs = latest.timeMillis
        }
        if (!lastGlucoseValue.isFinite()) {
            lastGlucoseValue = latest.primaryValue
        }
        if (!lastRate.isFinite()) {
            lastRate = latest.rate
        }
    }

    private fun currentGlucoseValueLocked(): Float? {
        if (lastGlucoseValue.isFinite()) {
            return lastGlucoseValue
        }
        bootstrapLastReadingLocked()
        return lastGlucoseValue.takeIf { it.isFinite() }
    }

    private fun currentRateLocked(): Float {
        if (lastRate.isFinite()) {
            return lastRate
        }
        bootstrapLastReadingLocked()
        return lastRate.takeIf { it.isFinite() } ?: Float.NaN
    }
}

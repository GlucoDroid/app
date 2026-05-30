package tk.glucodata.alerts

internal class SensorExpiryAlertState {
    private var baselineReady = false
    private var lastEndTimeMs = 0L
    private var wasInWarningWindow = false
    private var alertedForEndTimeMs = 0L

    fun reset() {
        baselineReady = false
        lastEndTimeMs = 0L
        wasInWarningWindow = false
        alertedForEndTimeMs = 0L
    }

    fun shouldTrigger(
        enabled: Boolean,
        activeNow: Boolean,
        snoozed: Boolean,
        endTimeMs: Long,
        nowMs: Long,
        warningMs: Long
    ): Boolean {
        if (!enabled || endTimeMs <= 0L || warningMs <= 0L) {
            reset()
            return false
        }

        if (endTimeMs != lastEndTimeMs) {
            baselineReady = false
            lastEndTimeMs = endTimeMs
            wasInWarningWindow = false
            alertedForEndTimeMs = 0L
        }

        if (!activeNow || snoozed) {
            return false
        }

        val inWarningWindow = endTimeMs - nowMs <= warningMs
        if (!baselineReady) {
            baselineReady = true
            wasInWarningWindow = inWarningWindow
            if (inWarningWindow) {
                alertedForEndTimeMs = endTimeMs
            }
            return false
        }

        if (!inWarningWindow) {
            wasInWarningWindow = false
            return false
        }

        val shouldTrigger = !wasInWarningWindow && alertedForEndTimeMs != endTimeMs
        wasInWarningWindow = true
        if (shouldTrigger) {
            alertedForEndTimeMs = endTimeMs
        }
        return shouldTrigger
    }
}

package tk.glucodata.drivers.sibionics

internal object SibionicsSessionPolicy {
    fun isConfirmedIndexRestart(
        index: Int,
        previousNextIndex: Int,
        isCurrentReading: Boolean,
        isRehydrating: Boolean,
    ): Boolean =
        !isRehydrating && isCurrentReading && index <= 1 && previousNextIndex > 1

    fun shouldRebaseNativeWindow(hadStartTime: Boolean, index: Int): Boolean =
        !hadStartTime && index >= 0

    fun shouldShowHistoryProgress(
        receivedCount: Int,
        totalCount: Int,
        hasReceivedLiveReading: Boolean,
    ): Boolean =
        !hasReceivedLiveReading && receivedCount > 0 && totalCount > receivedCount

    fun shouldRecoverSetupTimeout(
        isPending: Boolean,
        isStopped: Boolean,
        isPaused: Boolean,
    ): Boolean =
        isPending && !isStopped && !isPaused

    fun connectCallbackTimeoutDelayMs(
        requestedDelayMs: Long,
        callbackTimeoutMs: Long,
    ): Long =
        requestedDelayMs.coerceAtLeast(0L) + callbackTimeoutMs.coerceAtLeast(0L)

    fun shouldUseAdvertisementRecovery(
        failedDuringConnect: Boolean,
        isStopped: Boolean,
        isPaused: Boolean,
        hasKnownAddress: Boolean,
        recoveryAlreadyActive: Boolean,
    ): Boolean =
        failedDuringConnect &&
            !isStopped &&
            !isPaused &&
            hasKnownAddress &&
            !recoveryAlreadyActive
}

package tk.glucodata.drivers.sibionics

internal object SibionicsSessionPolicy {
    private const val GATT_CONNECTION_TIMEOUT = 147
    private val CONNECTION_TIMEOUT_BACKOFF_MS = longArrayOf(
        2_000L,
        4_000L,
        8_000L,
    )

    fun isConfirmedIndexRestart(
        index: Int,
        previousNextIndex: Int,
        isCurrentReading: Boolean,
        isRehydrating: Boolean,
    ): Boolean =
        !isRehydrating && isCurrentReading && index <= 1 && previousNextIndex > 1

    fun shouldShowHistoryProgress(
        receivedCount: Int,
        totalCount: Int,
        hasReceivedLiveReading: Boolean,
    ): Boolean =
        !hasReceivedLiveReading && receivedCount > 0 && totalCount > receivedCount

    fun isGattConnectionTimeout(status: Int): Boolean =
        status == GATT_CONNECTION_TIMEOUT

    fun reconnectDelayMs(
        status: Int,
        normalDelayMs: Long,
        consecutiveConnectionTimeouts: Int,
    ): Long {
        if (!isGattConnectionTimeout(status)) return normalDelayMs
        val index = (consecutiveConnectionTimeouts - 1)
            .coerceIn(0, CONNECTION_TIMEOUT_BACKOFF_MS.lastIndex)
        return CONNECTION_TIMEOUT_BACKOFF_MS[index]
    }

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
}

package tk.glucodata.drivers.sibionics

internal object SibionicsSessionPolicy {
    private const val GATT_CONNECTION_TIMEOUT = 147
    private const val TIMEOUT_RECOVERY_DELAY_MS = 500L

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

    fun reconnectDelayMs(status: Int, normalDelayMs: Long): Long =
        if (status == GATT_CONNECTION_TIMEOUT) TIMEOUT_RECOVERY_DELAY_MS else normalDelayMs
}

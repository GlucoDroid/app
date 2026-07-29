package tk.glucodata

import tk.glucodata.Log.doLog

/**
 * Calibration actions performed on the watch and executed on the phone, where
 * CalibrationManager lives. The watch can already enter a fingerstick value
 * (CALIBRATE_PATH); this covers the rest of the phone's calibration card so the
 * two surfaces offer the same controls.
 */
object WearCalibrationCommand {
    private const val LOG_ID = "WearCalibrationCmd"

    const val ENABLE = 1
    const val DISABLE = 2
    const val CLEAR = 3

    /** Watch side: ask the phone to perform [command]. */
    @JvmStatic
    fun send(command: Int) {
        if (!Applic.isWearable) return
        runCatching {
            MessageSender.sendSyncMessage(MessageSender.CALIBRATION_CMD_PATH, byteArrayOf(command.toByte()))
            if (doLog) Log.i(LOG_ID, "sent calibration command $command")
        }.onFailure { Log.stack(LOG_ID, "send($command)", it) }
    }

    /** Phone side: execute a command relayed from the watch. */
    @JvmStatic
    fun onCommand(data: ByteArray?) {
        if (Applic.isWearable || data == null || data.isEmpty()) return
        val command = data[0].toInt()
        val applied = runCatching {
            when (command) {
                ENABLE -> CalibrationAccess.setEnabled(true)
                DISABLE -> CalibrationAccess.setEnabled(false)
                CLEAR -> CalibrationAccess.clearAll()
                else -> false
            }
        }.onFailure { Log.stack(LOG_ID, "onCommand($command)", it) }.getOrDefault(false)
        Log.i(LOG_ID, "calibration command $command applied=$applied")
        if (applied) {
            UiRefreshBus.requestDataRefresh()
            WearSync2.onCalibrationChanged()
        }
    }
}

package tk.glucodata

import java.nio.ByteBuffer
import tk.glucodata.Log.doLog

/**
 * Calibration actions performed on the watch and executed on the phone, where
 * CalibrationManager lives. The watch can already enter a fingerstick value
 * (CALIBRATE_PATH); this covers the rest of the phone's calibration card so the
 * two surfaces offer the same controls.
 *
 * Wire format: [u8 command] followed, for the per-entry commands, by the
 * timestamp of the calibration being acted on (the watch never sees Room ids)
 * and — for [EDIT] — the new fingerstick value in mg/dL.
 */
object WearCalibrationCommand {
    private const val LOG_ID = "WearCalibrationCmd"

    const val ENABLE = 1
    const val DISABLE = 2
    const val CLEAR = 3
    const val DELETE = 4
    const val EDIT = 5
    const val ADD = 6

    /**
     * Watch side: ask the phone to perform [command].
     *
     * @return whether the message reached the transport. Reporting this matters:
     * the screens used to treat "attempted" as "saved" and closed on a command
     * that was never sent.
     */
    @JvmStatic
    @JvmOverloads
    fun send(command: Int, timestamp: Long = 0L, userValueMgdl: Float = Float.NaN): Boolean {
        if (!Applic.isWearable) return false
        return runCatching {
            val buf = ByteBuffer.allocate(13)
            buf.put(command.toByte())
            buf.putLong(timestamp)
            buf.putFloat(userValueMgdl)
            val sent = MessageSender.sendSyncMessage(MessageSender.CALIBRATION_CMD_PATH, buf.array())
            if (doLog) Log.i(LOG_ID, "calibration command $command ts=$timestamp value=$userValueMgdl sent=$sent")
            if (!sent) Log.w(LOG_ID, "no wear transport for calibration command $command")
            sent
        }.onFailure { Log.stack(LOG_ID, "send($command)", it) }.getOrDefault(false)
    }

    /** Phone side: execute a command relayed from the watch. */
    @JvmStatic
    fun onCommand(data: ByteArray?) {
        if (Applic.isWearable || data == null || data.isEmpty()) return
        val buf = ByteBuffer.wrap(data)
        val command = buf.get().toInt()
        val timestamp = if (buf.remaining() >= 8) buf.long else 0L
        val userValue = if (buf.remaining() >= 4) buf.float else Float.NaN

        val applied = runCatching {
            when (command) {
                ENABLE -> CalibrationAccess.setEnabled(true)
                DISABLE -> CalibrationAccess.setEnabled(false)
                CLEAR -> CalibrationAccess.clearAll()
                DELETE -> timestamp > 0L && CalibrationAccess.deleteCalibrationAt(timestamp)
                ADD -> GlucoseValuePlausibility.isPlausibleMgdl(userValue) &&
                    CalibrationAccess.addCalibration(userValue)
                EDIT -> timestamp > 0L &&
                    GlucoseValuePlausibility.isPlausibleMgdl(userValue) &&
                    CalibrationAccess.updateCalibrationUserValue(timestamp, userValue)
                else -> false
            }
        }.onFailure { Log.stack(LOG_ID, "onCommand($command)", it) }.getOrDefault(false)

        Log.i(LOG_ID, "calibration command $command ts=$timestamp applied=$applied")
        if (applied) {
            UiRefreshBus.requestDataRefresh()
            WearSync2.onCalibrationChanged()
        }
    }
}

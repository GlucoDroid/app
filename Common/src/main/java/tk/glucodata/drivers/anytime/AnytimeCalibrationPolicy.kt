package tk.glucodata.drivers.anytime

object AnytimeCalibrationPolicy {
    /** Blueberry/original-compatible rule: manual BG calibration is unavailable in the first 24h. */
    const val MANUAL_CALIBRATION_MIN_AGE_HOURS = 24

    /** Official Yuwell native status ids. Unknown means the loaded native path did not report one. */
    const val CALIBRATION_STATUS_UNKNOWN = -1
    const val CALIBRATION_STATUS_NOT = 0
    const val CALIBRATION_STATUS_CAN = 1
    const val CALIBRATION_STATUS_HOPE = 2
    const val CALIBRATION_STATUS_INIT = 3
    const val CALIBRATION_STATUS_MUST = 4

    fun canAcceptManualCalibration(sensorAgeHours: Int): Boolean =
        sensorAgeHours >= MANUAL_CALIBRATION_MIN_AGE_HOURS

    fun canAcceptAlgorithmCalibrationStatus(status: Int): Boolean =
        status == CALIBRATION_STATUS_UNKNOWN || status != CALIBRATION_STATUS_NOT

    fun calibrationStatusName(status: Int): String = when (status) {
        CALIBRATION_STATUS_UNKNOWN -> "UNKNOWN"
        CALIBRATION_STATUS_NOT -> "NOT"
        CALIBRATION_STATUS_CAN -> "CAN"
        CALIBRATION_STATUS_HOPE -> "HOPE"
        CALIBRATION_STATUS_INIT -> "INIT"
        CALIBRATION_STATUS_MUST -> "MUST"
        else -> "UNKNOWN($status)"
    }
}

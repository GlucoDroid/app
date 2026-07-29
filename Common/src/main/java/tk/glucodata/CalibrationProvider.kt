package tk.glucodata

interface CalibrationProvider {
    fun hasActiveCalibration(isRawMode: Boolean, sensorId: String?): Boolean

    fun getCalibratedValue(
        value: Float,
        timestamp: Long,
        isRawMode: Boolean,
        emitDiagnostics: Boolean,
        sensorId: String?,
    ): Float

    fun shouldHideInitialWhenCalibrated(): Boolean = false

    fun getActiveCalibrationAnchors(sensorId: String?, isRawMode: Boolean): DoubleArray =
        DoubleArray(0)

    fun shouldOverwriteSensorValues(): Boolean = false

    fun getRevision(): Long = 0L
}

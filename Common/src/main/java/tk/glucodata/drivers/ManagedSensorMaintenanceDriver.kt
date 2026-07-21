package tk.glucodata.drivers

interface ManagedSensorMaintenanceDriver {
    fun shouldDeleteLocalSensorDirectoryOnWipe(): Boolean = false

    fun supportsResetAction(): Boolean = false

    fun sendMaintenanceCommand(opCode: Int): Boolean = false

    fun resetSensor(): Boolean = false

    fun supportsAutoReset(): Boolean = false

    fun getAutoResetDays(): Int = 300

    fun setAutoResetDays(days: Int): Boolean = false

    fun supportsCustomAlgorithm(): Boolean = false

    fun isCustomAlgorithmEnabled(): Boolean = false

    fun setCustomAlgorithmEnabled(enabled: Boolean): Boolean = false

    fun getCustomAlgorithmMode(): Int = if (isCustomAlgorithmEnabled()) 2 else 0

    fun setCustomAlgorithmMode(mode: Int): Boolean = setCustomAlgorithmEnabled(mode == 2)

    fun supportsClearCalibrationAction(): Boolean = false

    fun clearSensorCalibration(): Boolean = false

    fun enableResetCompensation() {}

    fun disableResetCompensation() {}

    fun startNewSensor(): Boolean = false

    fun calibrateSensor(glucoseMgDl: Int): Boolean = false

    fun unpairSensor(): Boolean = false

    fun rePairSensor() {}
}

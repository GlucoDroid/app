package tk.glucodata

object GlucoseValuePlausibility {
    private const val MGDL_PER_MMOL = 18.0182f
    private const val MIN_GLUCOSE_MGDL = 20f
    private const val MAX_GLUCOSE_MGDL = 600f

    @JvmStatic
    fun isPlausibleMgdl(value: Float): Boolean =
        value.isFinite() && value in MIN_GLUCOSE_MGDL..MAX_GLUCOSE_MGDL

    @JvmStatic
    fun isPlausibleDisplayValue(value: Float, isMmol: Boolean): Boolean {
        if (!value.isFinite()) return false
        val mgdl = if (isMmol) value * MGDL_PER_MMOL else value
        return isPlausibleMgdl(mgdl)
    }
}

package tk.glucodata.drivers.sibionics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Safety envelope for the optional calibration-integrated Sibionics output.
 * The exact QR-aware vendor algorithm remains the base; user calibration can
 * correct it, but cannot replace it with an unbounded regression result.
 */
object SibionicsAdaptiveCalibration {
    private const val MIN_TARGET_MGDL = 20f
    private const val MAX_TARGET_MGDL = 600f
    private const val MIN_CORRECTION_MGDL = 18f
    private const val MAX_CORRECTION_MGDL = 72f
    private const val MAX_RELATIVE_CORRECTION = 0.35f

    fun fuseMgdl(stockMgdl: Float, calibratedTargetMgdl: Float): Float {
        if (!stockMgdl.isFinite() || stockMgdl <= 0f) return stockMgdl
        if (!calibratedTargetMgdl.isFinite() || calibratedTargetMgdl !in MIN_TARGET_MGDL..MAX_TARGET_MGDL) {
            return stockMgdl
        }
        val maxCorrection = max(
            MIN_CORRECTION_MGDL,
            min(MAX_CORRECTION_MGDL, stockMgdl * MAX_RELATIVE_CORRECTION),
        )
        val delta = calibratedTargetMgdl - stockMgdl
        if (abs(delta) < 0.01f) return stockMgdl
        return stockMgdl + delta.coerceIn(-maxCorrection, maxCorrection)
    }
}

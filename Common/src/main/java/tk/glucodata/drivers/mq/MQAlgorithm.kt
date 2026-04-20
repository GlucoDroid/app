// MQAlgorithm.kt — Glutec glucose calculation.
//
// Direct port of com.ruapp.glutec.RuGlutec_GlucoseCalculationArrayNew.GlucoseCalculation(double[])
// from the decompiled v1.0.1.6 app.
//
// The pipeline is:
//   1. Warmup correction (first ~'warmupParam' minutes after the raw current
//      crosses 19 uA): blend the processed current with a linearly decaying
//      multiplier so early-life values don't over-read.
//   2. Reference clamping: once the sensor has settled and the user has a
//      fingerstick reference BG, clamp the calculated glucose to (refBG+B)±5%
//      (and force it to refBG+B when the raw value is clearly too low).
//   3. Offset subtraction: subtract B (if sensitivity==0) or a fixed 2.0
//      baseline. Floor at 3.0 mmol/L.
//   4. mg/dL convert: final_mgdl_times10 = ((glucose / sensitivity) + 0.05) * 10
//      clamped to [17, 400] mg/dL × 10 units.
//
// Output array mirrors the app exactly — its row layout feeds the Glutec
// BloodGlucoseData table:
//   [0] rawCurrent
//   [1] processed
//   [2] final glucose (mmol/L, int-floored)
//   [3] referenceBG
//   [4] sensitivity (rawDivisor / 10)
//   [5] rawDivisor
//   [6] offsetApplied (B value, or 2.0 in the sensitivity>0 branch)
//   [7] finalGlucose_mgdl_x10 (int, clamped to [17, 400])

package tk.glucodata.drivers.mq

import java.math.BigDecimal
import java.math.RoundingMode

object MQAlgorithm {

    /**
     * Inputs map to the decompiled dArr[] indices:
     *   dArr[0]  packetCount   — time/packet index in session
     *   dArr[1]  rawCurrent    — raw current from transmitter
     *   dArr[2]  processed     — processed current (raw-divisor adjusted)
     *   dArr[4]  referenceBG   — fingerstick calibration reference
     *   dArr[5]  kValue        — calibration slope flag (non-zero means calibrated)
     *   dArr[6]  rawDivisor    — /10 = sensitivity
     *   dArr[7]  bValue        — calibration offset
     *   dArr[9]  warmupParam   — warmup correction parameter
     *   dArr[10] multiplier    — warmup correction multiplier
     */
    @JvmStatic
    fun calculate(
        packetCount: Double,
        rawCurrent: Double,
        processed: Double,
        referenceBG: Double,
        kValue: Double,
        rawDivisor: Double,
        bValue: Double,
        warmupParam: Double,
        multiplier: Double,
    ): DoubleArray {
        val safeBValue = if (bValue != 0.0) bValue else 0.0
        val sensitivity = if (rawDivisor != 0.0) rawDivisor / 10.0 else 0.0
        var offsetApplied = safeBValue

        // Step 1: warmup correction
        var glucose = processed
        if (rawCurrent >= MQConstants.ALGO_WARMUP_CURRENT_THRESHOLD) {
            val threshold = warmupParam + MQConstants.ALGO_WARMUP_CURRENT_THRESHOLD
            glucose = if (rawCurrent <= threshold) {
                val factor = multiplier - (((multiplier - 1.0) / threshold) *
                    (rawCurrent - MQConstants.ALGO_WARMUP_CURRENT_THRESHOLD))
                factor * processed
            } else {
                processed
            }
        }

        // Step 2: reference clamping (only when we have calibration & enough packets)
        if (rawCurrent > 8.0 && kValue != 0.0 && safeBValue != 0.0 && packetCount > 9.0) {
            if (glucose < 5.0) {
                glucose = referenceBG + bValue
            } else {
                val target = referenceBG + bValue
                val tolerance = target * 0.05
                val upper = target + tolerance + 0.5
                val lower = target - tolerance - 0.5
                if (glucose > upper) glucose = upper
                else if (glucose < lower) glucose = lower
            }
        }

        // Step 3: offset subtraction with floor
        var postOffset = MQConstants.ALGO_MMOL_FLOOR
        if (sensitivity == 0.0) {
            if (glucose > safeBValue) glucose -= safeBValue
            if (glucose >= MQConstants.ALGO_MMOL_FLOOR || safeBValue == 0.0) {
                postOffset = glucose
            }
        } else {
            offsetApplied = 2.0
            if (glucose > 2.0) glucose -= 2.0
            // Match the original: enter the second branch (same result), else floor.
            postOffset = if (glucose < MQConstants.ALGO_MMOL_FLOOR) MQConstants.ALGO_MMOL_FLOOR else glucose
        }

        // Step 4: sensitivity conversion & mg/dL clamp
        val mgdlTimes10: Double = if (sensitivity > 0.0) {
            val raw = ((postOffset / sensitivity) + 0.05) * 10.0
            when {
                raw <= MQConstants.ALGO_MGDL_MIN_TIMES10 -> MQConstants.ALGO_MGDL_MIN_TIMES10
                raw >= MQConstants.ALGO_MGDL_MAX_TIMES10 -> MQConstants.ALGO_MGDL_MAX_TIMES10
                else -> raw
            }
        } else 0.0

        val roundedSensitivity = BigDecimal(sensitivity).setScale(2, RoundingMode.DOWN).toDouble()
        val roundedOffset = BigDecimal(offsetApplied).setScale(2, RoundingMode.DOWN).toDouble()

        return doubleArrayOf(
            rawCurrent.toInt().toDouble(),
            processed.toInt().toDouble(),
            (postOffset + 0.5).toInt().toDouble(),
            referenceBG.toInt().toDouble(),
            roundedSensitivity,
            rawDivisor,
            roundedOffset,
            mgdlTimes10.toInt().toDouble(),
        )
    }

    /** Decoded glucose reading suitable for feeding the app's reading pipeline. */
    data class Result(
        val mgdlTimes10: Int,
        val glucoseMmol: Double,
        val rawCurrent: Double,
        val processed: Double,
        val sensitivity: Double,
    ) {
        /** True mg/dL value (divides by 10). */
        val mgdl: Float get() = mgdlTimes10 / 10.0f

        /** True mmol/L value for unit-agnostic logging. */
        val mmol: Float get() = glucoseMmol.toFloat()
    }

    @JvmStatic
    fun calculateResult(
        packetCount: Double,
        rawCurrent: Double,
        processed: Double,
        referenceBG: Double,
        kValue: Double,
        rawDivisor: Double,
        bValue: Double,
        warmupParam: Double,
        multiplier: Double,
    ): Result {
        val arr = calculate(
            packetCount, rawCurrent, processed, referenceBG, kValue,
            rawDivisor, bValue, warmupParam, multiplier,
        )
        return Result(
            mgdlTimes10 = arr[7].toInt(),
            glucoseMmol = arr[2],
            rawCurrent = arr[0],
            processed = arr[1],
            sensitivity = arr[4],
        )
    }
}

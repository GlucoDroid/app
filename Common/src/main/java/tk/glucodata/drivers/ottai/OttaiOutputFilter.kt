package tk.glucodata.drivers.ottai

import kotlin.math.abs

/**
 * Final output gate before Ottai readings are allowed into current publishing or storage.
 *
 * The parser/formula should stay literal. This gate handles vendor output validity and
 * single-sample electrode-current excursions observed on live hardware.
 */
object OttaiOutputFilter {
    const val MIN_RAW_CURRENT = 1_000
    const val MAX_TEMPERATURE_C = 45.0
    const val MAX_GLUCOSE_MMOL = 40.0f

    // A one-minute CGM point moving this far while the electrode current jumps this much
    // is treated as sensor noise. The next normal sample is accepted against the last
    // accepted baseline; no replacement value is fabricated.
    const val SINGLE_SAMPLE_DELTA_MMOL = 1.5f
    const val RAW_EXCURSION_RATIO = 0.18f

    fun hardRejectReason(record: OttaiRecord, mmol: Float): String? {
        if (!mmol.isFinite() || mmol <= 0f) return "glucose=$mmol"
        if (mmol > MAX_GLUCOSE_MMOL) return "glucose=$mmol"
        if (record.rawCurrent < MIN_RAW_CURRENT) return "raw=${record.rawCurrent}"
        if (!record.temperatureC.isFinite() || record.temperatureC > MAX_TEMPERATURE_C) {
            return "temp=${record.temperatureC}"
        }
        return null
    }

    fun isOneMinuteRawExcursion(
        candidateMmol: Float,
        candidateRaw: Int,
        baselineMmol: Float,
        baselineRaw: Int,
    ): Boolean {
        if (!candidateMmol.isFinite() || !baselineMmol.isFinite()) return false
        if (candidateMmol <= 0f || baselineMmol <= 0f) return false
        if (candidateRaw <= 0 || baselineRaw <= 0) return false

        val glucoseDelta = abs(candidateMmol - baselineMmol)
        val rawDeltaRatio = abs(candidateRaw - baselineRaw).toFloat() / baselineRaw
        return glucoseDelta >= SINGLE_SAMPLE_DELTA_MMOL &&
            rawDeltaRatio >= RAW_EXCURSION_RATIO
    }
}

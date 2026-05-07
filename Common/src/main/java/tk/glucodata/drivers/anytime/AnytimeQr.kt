// AnytimeQr.kt — Pure-Kotlin QR-code calibration parser.
//
// The sensor packaging carries a printable-ASCII QR sticker. The Anytime
// algorithm SDK extracts it via `AlgorithmTools.decodeCT(char[])` in C, which
// matches one of three regex patterns from libalgorithm-jni.so:
//
//   Format A (older):     ^[A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{4,5}[0-9A-Z]{3}$
//   Format B (CT3+, 20c): ^[1-9A-Z][1-9][A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{6}[0-9A-Z]{3}$
//   Format C (00 prefix): ^00[A-Z0-9][0-9](month)(K_3d)(R_3d)[0-9]{6}[0-9A-Z]{3}$
//
// Where:
//   month  = (0[1-9]|[1-9][0-9]|[A-Z][1-9A-Z])     ← 3 chars: numeric month or alpha unit code
//   K_3d   = (00[1-9]|0[1-9][0-9]|[1-9][0-9][0-9]) ← 3 digits in [001..999], scaled to K = nnn/100
//   R_3d   = same shape, scaled to R = nnn/10  (the official app's `Tool.getDecimalOneNumber`)
//
// On a real device we cross-validate this parse against the JNI `decodeCT()`
// from the vendor `.so` (see AnytimeAlgorithm). Either source produces the
// `KRDecodeData` we feed into `algorithmLatestGlucose`.

package tk.glucodata.drivers.anytime

/**
 * Calibration data extracted from the QR sticker. Mirrors `ist.com.sdk.KRDecodeData`.
 * `electrodeType` / `*TecNo` / `marketNo` are the chemistry IDs that select the
 * algorithm sub-routine inside libalgorithm-jni.so.
 */
data class AnytimeQrCalibration(
    val rawQr: String,
    val format: Format,

    /** Calibration slope (mmol/L per nA, before chemistry corrections). */
    val k: Float,
    /** Calibration intercept. */
    val r: Float,

    /** Sensor lifetime in days. Inferred from `marketNo` for known prefixes. */
    val lifeTime: Int,
    val productMonth: Int,
    val productYear: Int,

    val electrodeType: String,
    val electrodeTecNo: String,
    val enzymeTecNo: String,
    val membraneTecNo: String,
    val marketNo: String,
    val serialNo: String,
    val sensorNo: String,
    val unitOrder: Int,
    val voltageFlag: Int,
    val calibrationCount: Int,
) {
    enum class Format { A, B, C }

    companion object {
        // ---- Regex (verbatim from libalgorithm-jni.so .rodata) ----

        private val MONTH = "(0[1-9]|[1-9][0-9]|[A-Z][1-9A-Z])"
        private val THREE_DIGIT = "(00[1-9]|0[1-9][0-9]|[1-9][0-9][0-9])"

        @JvmField
        val PATTERN_A: Regex = Regex(
            "^([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{4,5})([0-9A-Z]{3})$"
        )
        @JvmField
        val PATTERN_B: Regex = Regex(
            "^([1-9A-Z])([1-9])([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{6})([0-9A-Z]{3})$"
        )
        @JvmField
        val PATTERN_C: Regex = Regex(
            "^00([A-Z0-9])([0-9])$MONTH$THREE_DIGIT$THREE_DIGIT([0-9]{6})([0-9A-Z]{3})$"
        )
    }
}

object AnytimeQr {

    /**
     * Parse a QR string into a calibration record. Returns null if the input
     * matches none of the three canonical formats.
     */
    @JvmStatic
    fun parse(qr: String?): AnytimeQrCalibration? {
        val trimmed = qr?.trim()?.uppercase() ?: return null
        if (trimmed.isEmpty()) return null

        AnytimeQrCalibration.PATTERN_B.matchEntire(trimmed)?.let { return parseFormatB(trimmed, it) }
        AnytimeQrCalibration.PATTERN_C.matchEntire(trimmed)?.let { return parseFormatC(trimmed, it) }
        AnytimeQrCalibration.PATTERN_A.matchEntire(trimmed)?.let { return parseFormatA(trimmed, it) }
        return null
    }

    private fun parseFormatA(qr: String, m: MatchResult): AnytimeQrCalibration {
        // Groups: 1=electrodeType, 2=year, 3=month, 4=K, 5=R, 6=serial, 7=marketNo
        val electrodeType = m.groupValues[1]
        val yearChar = m.groupValues[2]
        val month = m.groupValues[3]
        val kRaw = m.groupValues[4].toInt()
        val rRaw = m.groupValues[5].toInt()
        val serial = m.groupValues[6]
        val marketNo = m.groupValues[7]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.A,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (yearChar.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[4],
            membraneTecNo = m.groupValues[5],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = (electrodeType.firstOrNull()?.digitToIntOrNull() ?: 0),
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = 0,
        )
    }

    private fun parseFormatB(qr: String, m: MatchResult): AnytimeQrCalibration {
        // Groups: 1=unitOrder, 2=year, 3=electrodeType, 4=productMonthDigit,
        //         5=month, 6=K, 7=R, 8=serial(6), 9=marketNo(3)
        val unit = m.groupValues[1]
        val year = m.groupValues[2]
        val electrodeType = m.groupValues[3]
        val productMonthDigit = m.groupValues[4]
        val month = m.groupValues[5]
        val kRaw = m.groupValues[6].toInt()
        val rRaw = m.groupValues[7].toInt()
        val serial = m.groupValues[8]
        val marketNo = m.groupValues[9]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.B,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (year.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[6],
            membraneTecNo = m.groupValues[7],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = unit.firstOrNull()?.digitToIntOrNull() ?: 0,
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = productMonthDigit.toIntOrNull() ?: 0,
        )
    }

    private fun parseFormatC(qr: String, m: MatchResult): AnytimeQrCalibration {
        // Groups: 1=electrodeType, 2=year, 3=month, 4=K, 5=R, 6=serial(6), 7=marketNo(3)
        val electrodeType = m.groupValues[1]
        val year = m.groupValues[2]
        val month = m.groupValues[3]
        val kRaw = m.groupValues[4].toInt()
        val rRaw = m.groupValues[5].toInt()
        val serial = m.groupValues[6]
        val marketNo = m.groupValues[7]
        return AnytimeQrCalibration(
            rawQr = qr,
            format = AnytimeQrCalibration.Format.C,
            k = kRaw / 100f,
            r = rRaw / 10f,
            lifeTime = inferLifeTimeDays(marketNo),
            productMonth = parseMonth(month),
            productYear = 2000 + (year.toIntOrNull() ?: 0) + 10,
            electrodeType = electrodeType,
            electrodeTecNo = month,
            enzymeTecNo = m.groupValues[4],
            membraneTecNo = m.groupValues[5],
            marketNo = marketNo,
            serialNo = serial,
            sensorNo = qr.take(2),
            unitOrder = 0,
            voltageFlag = inferVoltageFlag(qr),
            calibrationCount = 0,
        )
    }

    private fun parseMonth(token: String): Int {
        if (token.isEmpty()) return 0
        token.toIntOrNull()?.let { return it }
        // Alpha month code: A=10, B=11, C=12. (Limited support — the SDK accepts
        // [A-Z][1-9A-Z] for OEM batches; we just take the first alpha as 10+.)
        val first = token.firstOrNull() ?: return 0
        if (first in 'A'..'Z') return 10 + (first - 'A')
        return 0
    }

    /**
     * Heuristic lifetime inference from `marketNo` codes. The SDK's `EDevice`
     * lookup pairs sensor prefix + chemistry to lifetime; we approximate from
     * the chemistry suffix alone since we may not always know the prefix.
     * Anytime CT3 sensors are 14, 15 or 16 days.
     */
    private fun inferLifeTimeDays(marketNo: String): Int = when {
        marketNo.startsWith("44") -> 16
        marketNo.startsWith("55") -> 15
        marketNo.startsWith("33") -> 14
        else -> AnytimeConstants.DEFAULT_RATED_LIFETIME_DAYS
    }

    /** Voltage flag: official app uses `Integer.parseInt(qr.substring(0,1))`. */
    private fun inferVoltageFlag(qr: String): Int =
        qr.firstOrNull()?.digitToIntOrNull() ?: 0
}

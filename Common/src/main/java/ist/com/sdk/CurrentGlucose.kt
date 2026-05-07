package ist.com.sdk

/**
 * Algorithm output. Populated by the JNI through `setX` reflection calls.
 * Field names must remain stable for binary compatibility with the vendor .so.
 */
@Suppress("PropertyName")
class CurrentGlucose {
    @JvmField var glucoseId: Int = 0
    @JvmField var glu: Float = 0f
    @JvmField var gluMG: Int = 0
    @JvmField var ib: Float = 0f
    @JvmField var iw: Float = 0f
    @JvmField var t: Float = 0f
    @JvmField var bg: Float = 0f
    @JvmField var bgMG: Int = 0
    @JvmField var trend: Int = 6
    @JvmField var errorCode: Int = 0
    @JvmField var warnCode: Int = 0
    @JvmField var hypoglycemiaEarlyWarnMinutes: Int = 0
    @JvmField var hyperglycemiaEarlyWarnMinutes: Int = 0
    @JvmField var k_BASE: Float = 0f
    @JvmField var k_AUTO: Float = 0f
    @JvmField var sensitivityCoefficient: Float = 0f
    @JvmField var iw4: Float = 0f
    @JvmField var iw30IIR: Float = 0f
    @JvmField var iw48IIR: Float = 0f
    @JvmField var iw48base: Float = 0f
    @JvmField var beVoltage: Int = 0
    @JvmField var weVoltage: Int = 0
    @JvmField var reVoltage: Int = 0
    @JvmField var ceVoltage: Int = 0
    @JvmField var bVoltage: Int = 0
    @JvmField var algorithm: Int = 0
    @JvmField var bytes: ByteArray = ByteArray(0)

    fun setGlucoseId(v: Int) { glucoseId = v }
    fun setGlu(v: Float) { glu = v }
    fun setGluMG(v: Int) { gluMG = v }
    fun setIb(v: Float) { ib = v }
    fun setIw(v: Float) { iw = v }
    fun setT(v: Float) { t = v }
    fun setBG(v: Float) { bg = v }
    fun setBGMG(v: Int) { bgMG = v }
    fun setTrend(v: Int) { trend = v }
    fun setErrorCode(v: Int) { errorCode = v }
    fun setWarnCode(v: Int) { warnCode = v }
    fun setHypoglycemiaEarlyWarnMinutes(v: Int) { hypoglycemiaEarlyWarnMinutes = v }
    fun setHyperglycemiaEarlyWarnMinutes(v: Int) { hyperglycemiaEarlyWarnMinutes = v }
    fun setK_BASE(v: Float) { k_BASE = v }
    fun setK_AUTO(v: Float) { k_AUTO = v }
    fun setSensitivityCoefficient(v: Float) { sensitivityCoefficient = v }
    fun setIw4(v: Float) { iw4 = v }
    fun setIw30IIR(v: Float) { iw30IIR = v }
    fun setIw48IIR(v: Float) { iw48IIR = v }
    fun setIw48base(v: Float) { iw48base = v }
    fun setBEVoltage(v: Int) { beVoltage = v }
    fun setWEVoltage(v: Int) { weVoltage = v }
    fun setREVoltage(v: Int) { reVoltage = v }
    fun setCEVoltage(v: Int) { ceVoltage = v }
    fun setbVoltage(v: Int) { bVoltage = v }
    fun setAlgorithm(v: Int) { algorithm = v }
    fun setBytes(v: ByteArray) { bytes = v }
}

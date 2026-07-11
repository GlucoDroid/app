package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SibionicsAdaptiveCalibrationTest {
    @Test
    fun keepsExactOutputWhenCalibrationIsMissingOrInvalid() {
        assertEquals(100f, SibionicsAdaptiveCalibration.fuseMgdl(100f, Float.NaN), 0.001f)
        assertEquals(100f, SibionicsAdaptiveCalibration.fuseMgdl(100f, 0f), 0.001f)
    }

    @Test
    fun acceptsNormalFingerstickCorrection() {
        assertEquals(91f, SibionicsAdaptiveCalibration.fuseMgdl(100f, 91f), 0.001f)
        assertEquals(118f, SibionicsAdaptiveCalibration.fuseMgdl(100f, 118f), 0.001f)
    }

    @Test
    fun boundsRegressionOutliersAroundQrAwareStockOutput() {
        assertEquals(65f, SibionicsAdaptiveCalibration.fuseMgdl(100f, 20f), 0.001f)
        assertEquals(135f, SibionicsAdaptiveCalibration.fuseMgdl(100f, 600f), 0.001f)
    }

    @Test
    fun lifetimesMatchEachSibionicsSeries() {
        val day = SibionicsConstants.DAY_MS
        assertEquals(14L * day, SibionicsConstants.Variant.EU.officialLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.HEMATONIX.officialLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.CHINESE.officialLifetimeMs)
        assertEquals(22L * day, SibionicsConstants.Variant.SIBIONICS2.officialLifetimeMs)
        assertEquals(23L * day, SibionicsConstants.Variant.SIBIONICS2.expectedLifetimeMs)
        assertEquals(14L * day, SibionicsConstants.Variant.GS3.officialLifetimeMs)
        assertEquals(14L * day + 36L * 60L * 60L * 1000L, SibionicsConstants.Variant.GS3.expectedLifetimeMs)
    }

    @Test
    fun gs1ResetUsesObservedFf32Payload() {
        assertArrayEquals(
            byteArrayOf(0x24, 0xE7.toByte(), 0x6F, 0x34),
            SibionicsProtocol.buildGs1ResetPacket(),
        )
    }

    @Test
    fun chineseHistoryProgressCountsRecordsRatherThanPacketIndex() {
        val first = chineseEntry(index = 700, unreceived = 12_340)
        assertEquals(12_341, SibionicsProtocol.estimateChineseHistoryTotal(0, 1, listOf(first)))

        val batch = (0 until 10).map { offset ->
            chineseEntry(index = 701 + offset, unreceived = 12_339 - offset)
        }
        assertEquals(12_341, SibionicsProtocol.estimateChineseHistoryTotal(12_341, 11, batch))
    }

    private fun chineseEntry(index: Int, unreceived: Int) = SibionicsProtocol.ChineseEntry(
        index = index,
        rawTemperature = 0,
        rawImpedance = 0,
        rawGlucose = 0,
        status = 0,
        numOfUnreceived = unreceived,
        addTimeSeconds = 0,
    )
}

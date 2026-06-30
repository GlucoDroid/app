package tk.glucodata.drivers.ottai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiOutputFilterTests {

    private fun record(raw: Int = 12_000, temp: Double = 32.0) =
        OttaiRecord(
            dataNo = 100,
            voltage = 0,
            runtimeSec = 6_000,
            rawCurrent = raw,
            temperatureC = temp,
            recordBytes = ByteArray(OttaiParser.PARSER_RECORD_SIZE),
        )

    @Test
    fun hardGate_matchesVendorOutputLimits() {
        assertNull(OttaiOutputFilter.hardRejectReason(record(raw = 12_000, temp = 32.0), 7.4f))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(raw = 999), 7.4f)!!.startsWith("raw="))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(temp = 45.1), 7.4f)!!.startsWith("temp="))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(), 40.1f)!!.startsWith("glucose="))
        assertTrue(OttaiOutputFilter.hardRejectReason(record(), 0f)!!.startsWith("glucose="))
    }

    @Test
    fun oneMinuteRawExcursion_rejectsObservedOttaiSpikes() {
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 14.1f,
                candidateRaw = 26_856,
                baselineMmol = 7.8f,
                baselineRaw = 14_534,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 9.9f,
                candidateRaw = 18_053,
                baselineMmol = 8.4f,
                baselineRaw = 15_032,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 9.3f,
                candidateRaw = 17_026,
                baselineMmol = 7.5f,
                baselineRaw = 13_707,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 13.5f,
                candidateRaw = 25_649,
                baselineMmol = 4.5f,
                baselineRaw = 7_706,
            )
        )
        assertTrue(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 13.5f,
                candidateRaw = 25_649,
                baselineMmol = 5.9f,
                baselineRaw = 10_598,
            )
        )
    }

    @Test
    fun oneMinuteRawExcursion_allowsSmallOrRawStableMovement() {
        assertFalse(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 8.0f,
                candidateRaw = 14_519,
                baselineMmol = 7.5f,
                baselineRaw = 13_707,
            )
        )
        assertFalse(
            OttaiOutputFilter.isOneMinuteRawExcursion(
                candidateMmol = 9.6f,
                candidateRaw = 15_500,
                baselineMmol = 8.0f,
                baselineRaw = 15_000,
            )
        )
    }
}

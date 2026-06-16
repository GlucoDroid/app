package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the BLE payload framing + 12-byte record field extraction. */
class OttaiParserTests {

    private fun rec12(
        dataNo: Int, voltage: Int, b5: Int, b6: Int, b7: Int,
        curLo: Int, curHi: Int, tempLo: Int, tempHi: Int,
    ) = byteArrayOf(
        0, 0,
        (dataNo and 0xFF).toByte(), ((dataNo ushr 8) and 0xFF).toByte(),
        voltage.toByte(), b5.toByte(), b6.toByte(), b7.toByte(),
        curLo.toByte(), curHi.toByte(), tempLo.toByte(), tempHi.toByte(),
    )

    @Test
    fun parseRecord_fieldsAndMixedEndianRuntime() {
        // runtime = (b5<<16)|(b7<<8)|b6 ; choose b5=0x01,b6=0x03,b7=0x02 -> 0x010203
        val rec = rec12(
            dataNo = 300, voltage = 5, b5 = 0x01, b6 = 0x03, b7 = 0x02,
            curLo = 0x6D, curHi = 0x00, tempLo = 0xAC, tempHi = 0x0D,
        )
        val r = OttaiParser.parseRecord(rec)
        assertEquals(300, r.dataNo)
        assertEquals(5, r.voltage)
        assertEquals(0x010203, r.runtimeSec) // 66051
        assertEquals(109, r.rawCurrent)       // 0x006D
        assertEquals(35.0, r.temperatureC, 1e-9) // 0x0DAC=3500 /100
    }

    @Test
    fun frameRecords_injectsSequentialDataNo() {
        // header: 0..3 prefix, 4..5 frontDataNo=5 (LE), 6..7 marker, then 2x 8-byte records
        val payload = byteArrayOf(
            0, 0, 0, 0,
            5, 0,            // frontDataNo = 5
            2, 0,            // marker/count
            // record 0
            1, 11, 12, 13, 14, 15, 16, 17,
            // record 1
            2, 21, 22, 23, 24, 25, 26, 27,
        )
        val recs = OttaiParser.frameRecords(payload)
        assertEquals(2, recs.size)
        // dataNo injected at [2..3] = frontDataNo + index
        assertEquals(5, OttaiParser.parseRecord(recs[0]).dataNo)
        assertEquals(6, OttaiParser.parseRecord(recs[1]).dataNo)
        // voltage = record byte 0 lands at parser[4]
        assertEquals(1, OttaiParser.parseRecord(recs[0]).voltage)
        assertEquals(2, OttaiParser.parseRecord(recs[1]).voltage)
    }

    @Test
    fun isRecordSane_gate() {
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        assertFalse(OttaiParser.isRecordSane(mk(65535, 0)))
        assertTrue(OttaiParser.isRecordSane(mk(100, 6000)))   // |100 - 100| = 0
        assertTrue(OttaiParser.isRecordSane(mk(100, 0)))      // |100 - 0| = 100 <= 120
        assertFalse(OttaiParser.isRecordSane(mk(200, 0)))     // |200 - 0| = 200 > 120
        assertTrue(OttaiParser.isRecordSane(mk(50, 0)))       // dataNo < 60 -> skew check skipped
    }

    @Test
    fun averageTick_rule() {
        fun mk(dataNo: Int, runtime: Int) = OttaiRecord(dataNo, 5, runtime, 100, 35.0, ByteArray(12))
        assertTrue(OttaiParser.isAverageTick(mk(10, 3200)))
        assertFalse(OttaiParser.isAverageTick(mk(10, 3000)))  // below warmup
        assertFalse(OttaiParser.isAverageTick(mk(7, 4000)))   // not multiple of 5
    }

    @Test
    fun parseLive_endToEnd_decryptFrameFormula() {
        val sessionKey = "0123456789abcdef0123456789abcdef"
        // one 8-byte record: voltage=5, runtime bytes, current LE16=200 (0xC8,0x00), temp 0x0DAC
        val payload = byteArrayOf(
            0, 0, 0, 0,
            7, 0,            // frontDataNo = 7
            1, 0,            // marker
            5, 0x01, 0x03, 0x02, 0xC8.toByte(), 0x00, 0xAC.toByte(), 0x0D,
        )
        val cipher = OttaiCrypto.encryptPayload(payload, sessionKey)!!
        // method: current * coeff0  => 200 * 0.025 = 5.0
        val reading = OttaiParser.parseLive(
            cipher = cipher,
            sessionKeyHex = sessionKey,
            method = "V0 C0 ML",
            coefficients = listOf(0.025),
            activeTimeMs = 1_700_000_000_000L,
        )
        assertNotNull(reading)
        assertEquals(7, reading!!.record.dataNo)
        assertEquals(200, reading.record.rawCurrent)
        assertEquals(5.0, reading.adjustGlucose, 1e-9)
        assertEquals(1_700_000_000_000L + reading.record.runtimeSec * 1000L, reading.monitorTimeMs)
        assertTrue(reading.valid || reading.record.dataNo < 60)
    }
}

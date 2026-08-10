package tk.glucodata.NovoPen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser turns pen bytes into insulin the journal will count as delivered, so the cases
 * that matter are the ones where a wrong answer invents or loses therapy.
 */
class PenDoseParserTests {

    private val now = 1_700_000_000L
    private val reference = now - 3_600L

    /** 12 byte record: relative time BE, FF 00 marker, tenths BE, 08 00 00 marker, flags. */
    private fun record(relativeSeconds: Long, tenths: Int, flags: Int = 0): ByteArray = byteArrayOf(
        (relativeSeconds shr 24).toByte(),
        (relativeSeconds shr 16).toByte(),
        (relativeSeconds shr 8).toByte(),
        relativeSeconds.toByte(),
        0xFF.toByte(), 0x00,
        (tenths shr 8).toByte(), tenths.toByte(),
        0x08, 0x00, 0x00, flags.toByte(),
    )

    private fun records(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { part ->
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    @Test
    fun decodesTimeAndUnits() {
        val doses = PenDoseParser.parse(reference, record(relativeSeconds = 600, tenths = 85), now)

        assertEquals(1, doses.size)
        assertEquals(reference + 600, doses[0].timestampSeconds)
        assertEquals(8.5f, doses[0].units, 0.001f)
    }

    @Test
    fun keepsPenStatusFlags() {
        val doses = PenDoseParser.parse(reference, record(600, 20, flags = 0x40), now)

        assertEquals(0x40, doses[0].flags)
    }

    @Test
    fun rejectsRecordWithWrongMarker() {
        val broken = record(600, 85).also { it[4] = 0x00 }

        assertTrue(PenDoseParser.parse(reference, broken, now).isEmpty())
    }

    @Test
    fun rejectsRecordWithWrongTrailer() {
        val broken = record(600, 85).also { it[8] = 0x09 }

        assertTrue(PenDoseParser.parse(reference, broken, now).isEmpty())
    }

    @Test
    fun rejectsImplausiblyLargeDose() {
        // 61.0 U: past what any NovoPen delivers, so it is a decode error, not a bolus.
        assertTrue(PenDoseParser.parse(reference, record(600, 610), now).isEmpty())
    }

    @Test
    fun rejectsZeroDose() {
        assertTrue(PenDoseParser.parse(reference, record(600, 0), now).isEmpty())
    }

    @Test
    fun rejectsDoseInTheFuture() {
        val future = PenDoseParser.parse(reference, record(7_200, 50), now)

        assertTrue(future.isEmpty())
    }

    @Test
    fun rejectsDoseBeyondTheAgeLimit() {
        val ancient = PenDoseParser.parse(
            reference - PenDoseParser.MAX_AGE_SECONDS - 60L,
            record(0, 50),
            now,
        )

        assertTrue(ancient.isEmpty())
    }

    @Test
    fun ignoresTrailingPartialRecord() {
        val raw = records(record(600, 50), byteArrayOf(0x00, 0x01, 0x02))

        assertEquals(1, PenDoseParser.parse(reference, raw, now).size)
    }

    @Test
    fun returnsDosesOldestFirst() {
        val raw = records(record(1_200, 30), record(600, 50))

        val doses = PenDoseParser.parse(reference, raw, now)

        assertEquals(listOf(reference + 600, reference + 1_200), doses.map { it.timestampSeconds })
    }

    @Test
    fun flagsSmallDoseShortlyBeforeARealOneAsPriming() {
        val raw = records(record(600, 20), record(630, 80))

        val doses = PenDoseParser.parse(reference, raw, now)

        assertTrue(doses[0].priming)
        assertFalse(doses[1].priming)
    }

    @Test
    fun doesNotFlagSmallDoseStandingOnItsOwn() {
        // Same 2 U, but nothing follows within a minute: a real correction, not an air shot.
        val raw = records(record(600, 20), record(1_200, 80))

        val doses = PenDoseParser.parse(reference, raw, now)

        assertFalse(doses[0].priming)
    }

    @Test
    fun doesNotFlagLargeDoseFollowedClosely() {
        val raw = records(record(600, 30), record(630, 80))

        assertFalse(PenDoseParser.parse(reference, raw, now)[0].priming)
    }

    @Test
    fun mergeDropsDosesTheSegmentsOverlapOn() {
        val first = PenDoseParser.parse(reference, records(record(600, 50), record(1_200, 30)), now)
        val second = PenDoseParser.parse(reference, records(record(1_200, 30), record(1_800, 40)), now)

        val merged = PenDoseParser.merge(listOf(first, second))

        assertEquals(3, merged.size)
        assertEquals(
            listOf(reference + 600, reference + 1_200, reference + 1_800),
            merged.map { it.timestampSeconds },
        )
    }

    @Test
    fun mergeReclassifiesPrimingAcrossChunkBoundaries() {
        // The air shot and the bolus it precedes arrived in different segments; only the
        // merged view can see that they belong together.
        val first = PenDoseParser.parse(reference, record(600, 20), now)
        val second = PenDoseParser.parse(reference, record(640, 90), now)

        val merged = PenDoseParser.merge(listOf(first, second))

        assertTrue(merged[0].priming)
    }

    @Test
    fun handlesNullPayload() {
        assertTrue(PenDoseParser.parse(reference, null, now).isEmpty())
    }
}

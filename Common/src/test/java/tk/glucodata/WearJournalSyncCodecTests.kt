package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal crosses the Data Layer as packed bytes, and the encoder lives on
 * the phone while the decoder runs on the watch, so nothing else would catch the
 * two drifting apart.
 */
class WearJournalSyncCodecTests {

    private fun payload(
        enabled: Boolean,
        entries: List<Triple<Long, Long, Pair<Int, Float>>>,
        titles: List<String>,
        presets: List<Pair<Long, String>> = emptyList(),
    ): ByteArray {
        val titleBytes = titles.map { it.toByteArray(StandardCharsets.UTF_8) }
        val presetBytes = presets.map { it.second.toByteArray(StandardCharsets.UTF_8) }
        val size = 1 + 1 + 2 +
            entries.indices.sumOf { 8 + 8 + 1 + 4 + 1 + titleBytes[it].size } +
            2 + presetBytes.sumOf { 8 + 4 + 1 + it.size }
        val buffer = ByteBuffer.allocate(size)
        buffer.put(WearJournalSync.VERSION.toByte())
        buffer.put(if (enabled) 1 else 0)
        buffer.putShort(entries.size.toShort())
        entries.forEachIndexed { index, (timestamp, id, typeAmount) ->
            buffer.putLong(timestamp)
            buffer.putLong(id)
            buffer.put(typeAmount.first.toByte())
            buffer.putFloat(typeAmount.second)
            buffer.put(titleBytes[index].size.toByte())
            buffer.put(titleBytes[index])
        }
        buffer.putShort(presets.size.toShort())
        presets.forEachIndexed { index, (id, _) ->
            buffer.putLong(id)
            buffer.putFloat(Float.NaN)
            buffer.put(presetBytes[index].size.toByte())
            buffer.put(presetBytes[index])
        }
        return buffer.array()
    }

    @Test
    fun decodesEntriesNewestFirst() {
        val data = payload(
            enabled = true,
            entries = listOf(
                Triple(1_000L, 7L, WearJournalSync.TYPE_CARBS to 30f),
                Triple(9_000L, 8L, WearJournalSync.TYPE_INSULIN to 2.5f),
            ),
            titles = listOf("Carbs 30g", "Insulin 2.5U"),
            presets = listOf(3L to "Rapid"),
        )

        val journal = WearJournalSync.decode(data)

        assertTrue(journal.enabled)
        assertEquals(2, journal.entries.size)
        // Newest first, so the watch list needs no further sorting.
        assertEquals(9_000L, journal.entries[0].timestampMs)
        assertEquals("Insulin 2.5U", journal.entries[0].title)
        assertEquals(WearJournalSync.TYPE_INSULIN, journal.entries[0].type)
        assertEquals(2.5f, journal.entries[0].amount, 0.0001f)
        assertEquals(8L, journal.entries[0].id)
        assertEquals(WearJournalSync.TYPE_CARBS, journal.entries[1].type)
        assertEquals(1, journal.presets.size)
        assertEquals("Rapid", journal.presets[0].name)
    }

    @Test
    fun disabledJournalDecodesAsDisabledAndEmpty() {
        val journal = WearJournalSync.decode(payload(false, emptyList(), emptyList()))

        assertFalse(journal.enabled)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun futureVersionIsIgnoredRatherThanMisread() {
        val data = payload(true, emptyList(), emptyList())
        data[0] = (WearJournalSync.VERSION + 1).toByte()

        val journal = WearJournalSync.decode(data)

        assertFalse(journal.enabled)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun truncatedPayloadDoesNotYieldPartialEntries() {
        val full = payload(
            enabled = true,
            entries = listOf(Triple(1_000L, 1L, WearJournalSync.TYPE_CARBS to 12f)),
            titles = listOf("Carbs 12g"),
        )
        // Cut inside the title: a short Data Layer message must not surface an
        // entry with a mangled label.
        val truncated = full.copyOf(full.size - 4)

        val journal = WearJournalSync.decode(truncated)

        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun commandRoundTripsThroughItsOwnFormat() {
        val data = ByteBuffer.allocate(1 + 1 + 8 + 8 + 1 + 4 + 8)
            .put(WearJournalSync.VERSION.toByte())
            .put(WearJournalSync.CMD_ADD.toByte())
            .putLong(4_321L)
            .putLong(0L)
            .put(WearJournalSync.TYPE_INSULIN.toByte())
            .putFloat(3.5f)
            .putLong(11L)
            .array()

        val command = WearJournalSync.decodeCommand(data)

        requireNotNull(command)
        assertEquals(WearJournalSync.CMD_ADD, command.command)
        assertEquals(4_321L, command.timestampMs)
        assertEquals(WearJournalSync.TYPE_INSULIN, command.type)
        assertEquals(3.5f, command.amount, 0.0001f)
        assertEquals(11L, command.presetId)
    }

    @Test
    fun shortCommandIsRejected() {
        assertNull(WearJournalSync.decodeCommand(byteArrayOf(WearJournalSync.VERSION.toByte(), 1)))
    }
}

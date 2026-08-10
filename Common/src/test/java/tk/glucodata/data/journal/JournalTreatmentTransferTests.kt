package tk.glucodata.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JournalTreatmentTransferTests {
    @Test
    fun xdripMbgEntryBecomesNightscoutFingerstickJournalEntry() {
        val document = JSONObject()
            .put("_id", "xdrip-mbg-id")
            .put("device", "xDrip-DexcomG5")
            .put("type", "mbg")
            .put("date", 1_718_928_000_000L)
            .put("dateString", "2024-06-21T00:00:00.000Z")
            .put("mbg", 95)

        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "nightscout:NSF-TEST",
            insulinPresets = emptyList(),
            stringResource = { "Fingerstick" },
        )

        assertNotNull(parsed)
        val entry = parsed!!.inputs.single()
        assertEquals(JournalEntryType.FINGERSTICK, entry.type)
        assertEquals(95f, entry.glucoseValueMgDl!!, 0f)
        assertEquals(1_718_928_000_000L, entry.timestamp)
        assertEquals(JournalEntrySource.NIGHTSCOUT, entry.source)
        assertEquals("nightscout:NSF-TEST:xdrip-mbg-id:fingerstick", entry.sourceRecordId)
        assertEquals("xdrip-mbg-id", entry.nsRemoteId)
    }
}

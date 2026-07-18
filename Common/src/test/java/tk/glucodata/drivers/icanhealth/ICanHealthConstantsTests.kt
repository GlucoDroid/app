package tk.glucodata.drivers.icanhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ICanHealthConstantsTests {

    @Test
    fun canonicalSensorId_normalizesHexIdsToUppercase() {
        assertEquals(
            "8760080A00070000",
            ICanHealthConstants.canonicalSensorId("8760080a00070000")
        )
    }

    @Test
    fun nativeShortSensorAlias_returnsTrailingNativeAliasForCanonicalId() {
        assertEquals(
            "80A00070000",
            ICanHealthConstants.nativeShortSensorAlias("8760080A00070000")
        )
    }

    @Test
    fun matchesCanonicalOrKnownNativeAlias_acceptsCanonicalAndShortAlias() {
        assertTrue(
            ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(
                "8760080A00070000",
                "80A00070000"
            )
        )
    }

    @Test
    fun matchesCanonicalOrKnownNativeAlias_rejectsUnrelatedIds() {
        assertFalse(
            ICanHealthConstants.matchesCanonicalOrKnownNativeAlias(
                "8760080A00070000",
                "X-222227JR7C"
            )
        )
    }

    @Test
    fun isEndedStatusSequenceCap_onlyMatchesEndedStateAtVendorCap() {
        assertFalse(
            ICanHealthConstants.isEndedStatusSequenceCap(
                ICanHealthConstants.LAUNCHER_STATE_ENDED,
                ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES - 1
            )
        )
        assertFalse(
            ICanHealthConstants.isEndedStatusSequenceCap(
                ICanHealthConstants.LAUNCHER_STATE_RUNNING,
                ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES
            )
        )
        assertTrue(
            ICanHealthConstants.isEndedStatusSequenceCap(
                ICanHealthConstants.LAUNCHER_STATE_ENDED,
                ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES
            )
        )
    }

    @Test
    fun endedStatusEndTimestamp_usesObservedStatusSequence() {
        val sessionStart = 1_000_000L
        val cap = ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES

        assertEquals(
            sessionStart + cap * 60_000L,
            ICanHealthConstants.endedStatusEndTimestampMs(sessionStart, cap)
        )
        assertEquals(
            sessionStart + (cap + 3) * 60_000L,
            ICanHealthConstants.endedStatusEndTimestampMs(sessionStart, cap + 3)
        )
        assertEquals(
            null,
            ICanHealthConstants.endedStatusEndTimestampMs(sessionStart, cap - 1)
        )
    }

    @Test
    fun hasCompleteEndedStatusHistory_requiresTailAtObservedEnd() {
        val sessionStart = 1_000_000L
        val cap = ICanHealthConstants.LAUNCHER_ENDED_STATUS_SEQUENCE_CAP_MINUTES
        val end = sessionStart + cap * 60_000L
        val tolerance = 2 * 60_000L

        assertTrue(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = sessionStart,
                sequenceNumber = cap,
                tailTimestampMs = end,
                toleranceMs = tolerance,
            )
        )
        assertTrue(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = sessionStart,
                sequenceNumber = cap,
                tailTimestampMs = end - tolerance,
                toleranceMs = tolerance,
            )
        )
        assertFalse(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = sessionStart,
                sequenceNumber = cap,
                tailTimestampMs = end - tolerance - 1L,
                toleranceMs = tolerance,
            )
        )
        assertFalse(
            ICanHealthConstants.hasCompleteEndedStatusHistory(
                sessionStartEpochMs = 0L,
                sequenceNumber = cap,
                tailTimestampMs = end,
                toleranceMs = tolerance,
            )
        )
    }
}

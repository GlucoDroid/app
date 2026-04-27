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
}

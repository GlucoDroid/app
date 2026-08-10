package tk.glucodata.drivers.sibionics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsVariantAttributionTest {
    private val eu = SibionicsConstants.Variant.EU
    private val sibionics2 = SibionicsConstants.Variant.SIBIONICS2
    private val hematonix = SibionicsConstants.Variant.HEMATONIX

    @Test
    fun aRetryWinNeverRewritesTheRecordedVariant() {
        // The ACK that arrives after an auth timeout may belong to the key that timed out.
        val attribution = SibionicsVariantAttribution.attribute(
            recordedVariant = sibionics2,
            acceptedVariant = eu,
            attemptIndex = 1,
        )
        assertFalse(attribution.persistVariant)
        assertEquals(eu, attribution.keyHint)
    }

    @Test
    fun aFirstAttemptWinIsUnambiguousAndMayRewriteTheVariant() {
        val attribution = SibionicsVariantAttribution.attribute(
            recordedVariant = sibionics2,
            acceptedVariant = eu,
            attemptIndex = 0,
        )
        assertTrue(attribution.persistVariant)
        assertEquals(eu, attribution.keyHint)
    }

    @Test
    fun confirmingTheRecordedVariantIsAlwaysAllowed() {
        listOf(0, 1, 3).forEach { attempt ->
            val attribution = SibionicsVariantAttribution.attribute(
                recordedVariant = sibionics2,
                acceptedVariant = sibionics2,
                attemptIndex = attempt,
            )
            assertTrue("attempt $attempt", attribution.persistVariant)
            assertEquals(sibionics2, attribution.keyHint)
        }
    }

    @Test
    fun aMisdeclaredSensorStillSelfCorrectsOnTheFollowingConnection() {
        // Connection 1: recorded EU, real hardware answers the Sibionics 2 key on a retry.
        val ambiguous = SibionicsVariantAttribution.attribute(
            recordedVariant = eu,
            acceptedVariant = sibionics2,
            attemptIndex = 2,
        )
        assertFalse(ambiguous.persistVariant)

        // Connection 2 leads with the hint, so its win is a first-attempt win.
        val order = SibionicsVariantAttribution.keyOrder(eu, ambiguous.keyHint)
        assertEquals(sibionics2, order.first())
        val confirmed = SibionicsVariantAttribution.attribute(
            recordedVariant = eu,
            acceptedVariant = order.first(),
            attemptIndex = 0,
        )
        assertTrue(confirmed.persistVariant)
    }

    @Test
    fun aStaleHintCostsOneRetryAndThenSettlesBackOnTheRecordedVariant() {
        // Hint points at the wrong key: it times out, the recorded variant wins on attempt 1.
        val order = SibionicsVariantAttribution.keyOrder(eu, sibionics2)
        assertEquals(listOf(sibionics2, eu), order.take(2))
        val settled = SibionicsVariantAttribution.attribute(
            recordedVariant = eu,
            acceptedVariant = eu,
            attemptIndex = 1,
        )
        assertTrue(settled.persistVariant)
        assertEquals(eu, settled.keyHint)
        assertEquals(eu, SibionicsVariantAttribution.keyOrder(eu, settled.keyHint).first())
    }

    @Test
    fun everyKeyGroupStaysReachableWithoutBeingTriedTwice() {
        val order = SibionicsVariantAttribution.keyOrder(hematonix, sibionics2)
        assertEquals(sibionics2, order.first())
        assertEquals(hematonix, order[1])
        assertEquals(
            order.size,
            order.distinctBy { it.appId + it.registrationKeyHex }.size,
        )
        SibionicsConstants.Variant.entries
            .filter { it != SibionicsConstants.Variant.CHINESE }
            .forEach { assertTrue(it.id, order.contains(it)) }
    }

    @Test
    fun withoutAHintTheRecordedVariantIsStillTriedFirst() {
        assertEquals(sibionics2, SibionicsVariantAttribution.keyOrder(sibionics2, null).first())
        assertEquals(eu, SibionicsVariantAttribution.keyOrder(eu, null).first())
    }
}

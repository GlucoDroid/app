package tk.glucodata.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDismissalStateTests {

    private val ceiling = 30L * 60_000L

    @Test
    fun `dismissal holds until the ceiling passes`() {
        val state = EpisodeDismissalState<String>()
        state.dismiss("LOW", 0L)

        assertTrue(state.isDismissed("LOW"))
        assertFalse(state.consumeExpired("LOW", ceiling, ceiling - 1))
        assertTrue(state.isDismissed("LOW"))
    }

    @Test
    fun `dismissal expires exactly once at the ceiling`() {
        val state = EpisodeDismissalState<String>()
        state.dismiss("LOW", 0L)

        assertTrue(state.consumeExpired("LOW", ceiling, ceiling))
        assertFalse(state.isDismissed("LOW"))
        // Re-arming is a one-shot: a later tick must not report expiry again.
        assertFalse(state.consumeExpired("LOW", ceiling, ceiling * 4))
    }

    @Test
    fun `a type that was never dismissed never expires`() {
        val state = EpisodeDismissalState<String>()

        assertFalse(state.consumeExpired("LOW", ceiling, ceiling * 10))
    }

    @Test
    fun `a cleared dismissal cannot expire`() {
        val state = EpisodeDismissalState<String>()
        state.dismiss("LOW", 0L)
        state.clear("LOW")

        assertFalse(state.isDismissed("LOW"))
        assertFalse(state.consumeExpired("LOW", ceiling, ceiling * 10))
    }

    @Test
    fun `a ceiling of zero never expires`() {
        val state = EpisodeDismissalState<String>()
        state.dismiss("HIGH", 0L)

        assertFalse(state.consumeExpired("HIGH", 0L, Long.MAX_VALUE / 2))
        assertTrue(state.isDismissed("HIGH"))
    }

    @Test
    fun `a backwards clock rebaselines instead of stranding the dismissal`() {
        val state = EpisodeDismissalState<String>()
        state.dismiss("LOW", 10_000_000L)

        // Clock jumped back: without a rebaseline the elapsed time stays negative and the
        // dismissal would outlive its ceiling by however far the clock moved.
        assertFalse(state.consumeExpired("LOW", ceiling, 0L))
        assertTrue(state.consumeExpired("LOW", ceiling, ceiling))
    }

    @Test
    fun `dismissals are tracked per key`() {
        val state = EpisodeDismissalState<String>()
        state.dismiss("LOW", 0L)
        state.dismiss("VERY_LOW", ceiling)

        assertTrue(state.consumeExpired("LOW", ceiling, ceiling))
        assertFalse(state.consumeExpired("VERY_LOW", ceiling, ceiling))
        assertTrue(state.isDismissed("VERY_LOW"))
    }
}

package tk.glucodata.drivers.aidex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDexManagedSensorIdentityAdapterTests {

    @Test
    fun nativeAlias_stripsManagedPrefix() {
        assertEquals("222227JR7C", AiDexManagedSensorIdentityAdapter.nativeAlias("X-222227JR7C"))
    }

    @Test
    fun resolveCanonicalSensorId_normalizesManagedIdsToUppercase() {
        assertEquals(
            "X-222227JR7C",
            AiDexManagedSensorIdentityAdapter.resolveCanonicalSensorId("x-222227jr7c")
        )
    }

    @Test
    fun matchesCallbackId_acceptsNativeAliasForManagedCallback() {
        assertTrue(
            AiDexManagedSensorIdentityAdapter.matchesCallbackId("X-222227JR7C", "222227JR7C")
        )
    }
}

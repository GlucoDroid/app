package tk.glucodata.ui.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SibionicsSetupPolicyTest {
    @Test
    fun `sibionics 2 accepts only transmitter-style names`() {
        assertTrue(SibionicsType.SIBIONICS2.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.SIBIONICS2.acceptsBleSetupDevice("LT260346HU"))
        assertFalse(SibionicsType.SIBIONICS2.acceptsBleSetupDevice(null))
    }

    @Test
    fun `first generation variants keep generic ff30 discovery`() {
        assertTrue(SibionicsType.EU.acceptsBleSetupDevice("LT260346HU"))
        assertTrue(SibionicsType.HEMATONIX.acceptsBleSetupDevice("LT260346HU"))
        assertTrue(SibionicsType.CHINESE.acceptsBleSetupDevice("LT260346HU"))
        assertTrue(SibionicsType.EU.acceptsBleSetupDevice("HEMATONIX42"))
        assertFalse(SibionicsType.EU.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.HEMATONIX.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.CHINESE.acceptsBleSetupDevice("P225043JMV"))
        assertFalse(SibionicsType.CHINESE.acceptsBleSetupDevice(null))
    }

    @Test
    fun `gs3 stays out of public setup choices`() {
        assertFalse(SibionicsType.GS3.setupVisible)
        assertTrue(SibionicsType.entries.filter { it.setupVisible }.none { it == SibionicsType.GS3 })
    }

    @Test
    fun `bluetooth-only setup creates a stable native sensor identity`() {
        val payload = requireNotNull(constructBleOnlySibionicsQr("P225043JMV"))
        val nativeLongName = payload.substring(payload.length - 17, payload.length - 1)

        assertEquals(70, payload.length)
        assertEquals("000003JMVP225043", nativeLongName)
        assertEquals("3JMVP225043", nativeLongName.takeLast(11))
    }
}

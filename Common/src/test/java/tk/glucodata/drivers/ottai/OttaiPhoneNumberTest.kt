package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OttaiPhoneNumberTest {
    @Test
    fun acceptsMainlandMobileSubscriberDigits() {
        assertEquals("13800138000", normalizeOttaiCnPhone("13800138000"))
        assertEquals("19912345678", normalizeOttaiCnPhone("19912345678"))
    }

    @Test
    fun rejectsInvalidMainlandSubscriberDigits() {
        assertNull(normalizeOttaiCnPhone("12800138000"))
        assertNull(normalizeOttaiCnPhone("1380013800"))
        assertNull(normalizeOttaiCnPhone("13800138000x"))
        assertNull(normalizeOttaiCnPhone("+8613800138000"))
    }
}

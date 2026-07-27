package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Test

class OttaiCloudHeadersTests {
    @Test
    fun syaiGetUserIncludesRequiredDeviceIdentity() {
        val headers = OttaiCloudClient.webGetUserHeaders(
            webBase = "https://www.syai.com/api/cgm/web",
            ts = 123L,
            accessToken = "test-token",
        )

        assertEquals("cgm", headers["appName"])
        assertEquals("5", headers["versionCode"])
        assertEquals("8", headers["deviceId"])
        assertEquals("Bearer test-token", headers["Authorization"])
    }

    @Test
    fun ottaiGetUserRetainsExistingDeviceIdentity() {
        val headers = OttaiCloudClient.webGetUserHeaders(
            webBase = "https://www.ottai.com/api/cgm/web",
            ts = 123L,
            accessToken = "test-token",
        )

        assertEquals("ottai-seas", headers["appName"])
        assertEquals("253201", headers["versionCode"])
        assertEquals("8", headers["deviceId"])
        assertEquals("Bearer test-token", headers["Authorization"])
    }
}

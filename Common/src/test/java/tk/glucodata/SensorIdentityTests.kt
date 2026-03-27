package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorIdentityTests {
    @Test
    fun resolveAvailableMainSensor_prefersSelectedMainWhenStillActive() {
        assertEquals(
            "current-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = "current-sensor",
                preferredSensorId = "replacement-sensor",
                activeSensors = arrayOf("current-sensor", "replacement-sensor", "other-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_prefersPreferredWhenSelectedMainIsBlank() {
        assertEquals(
            "replacement-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "replacement-sensor",
                activeSensors = arrayOf("replacement-sensor", "other-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_fallsBackToFirstActiveWhenCachedSensorIsGone() {
        assertEquals(
            "replacement-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "stale-sensor",
                activeSensors = arrayOf("replacement-sensor", "other-sensor", "third-sensor")
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_keepsPreferredWhenNoActiveSensorsRemain() {
        assertEquals(
            "historical-sensor",
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = "historical-sensor",
                activeSensors = emptyArray()
            )
        )
    }

    @Test
    fun resolveAvailableMainSensor_returnsNullWhenNothingIsKnown() {
        assertNull(
            SensorIdentity.resolveAvailableMainSensor(
                selectedMain = null,
                preferredSensorId = null,
                activeSensors = emptyArray()
            )
        )
    }
}

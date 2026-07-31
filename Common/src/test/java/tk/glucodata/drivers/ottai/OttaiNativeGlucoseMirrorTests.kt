package tk.glucodata.drivers.ottai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiNativeGlucoseMirrorTests {

    @Test
    fun liveReadingUsesNativeUnitsAndWakesNightscoutAfterWrite() {
        var written: NativeWrite? = null
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { timestampSec, glucose, temperature, sensorId ->
                written = NativeWrite(timestampSec, glucose, temperature, sensorId)
                true
            },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            timestampMs = 180_000L,
            glucoseMgdl = 126f,
            temperatureC = 32f,
        )

        assertTrue(stored)
        assertEquals(NativeWrite(180L, 12.6f, 32f, "AABBCCDDEEFF"), written)
        assertEquals(listOf("ottai" to 180_000L), wakes)
    }

    @Test
    fun failedLiveWriteDoesNotWakeNightscout() {
        val wakes = mutableListOf<Pair<String, Long>>()
        val mirror = OttaiNativeGlucoseMirror(
            writeNative = { _, _, _, _ -> false },
            wakeNightscout = { source, timestampMs -> wakes += source to timestampMs },
        )

        val stored = mirror.mirrorLive(
            sensorId = "AABBCCDDEEFF",
            timestampMs = 180_000L,
            glucoseMgdl = 126f,
            temperatureC = 32f,
        )

        assertTrue(!stored)
        assertTrue(wakes.isEmpty())
    }

    private data class NativeWrite(
        val timestampSec: Long,
        val glucose: Float,
        val temperatureC: Float,
        val sensorId: String,
    )
}

package tk.glucodata.drivers.ottai

internal class OttaiNativeGlucoseMirror(
    private val writeNative: (Long, Float, Float, String) -> Boolean,
    private val wakeNightscout: (String, Long) -> Unit,
) {
    fun mirrorLive(
        sensorId: String,
        timestampMs: Long,
        glucoseMgdl: Float,
        temperatureC: Float,
    ): Boolean {
        val stored = writeNative(
            timestampMs / 1000L,
            glucoseMgdl / 10f,
            temperatureC,
            sensorId,
        )
        if (stored) wakeNightscout("ottai", timestampMs)
        return stored
    }
}

package tk.glucodata

object SensorIdentity {
    @JvmStatic
    fun resolveMainSensor(): String? {
        val main = Natives.lastsensorname()
        if (!main.isNullOrBlank()) {
            return main
        }
        return Natives.activeSensors()?.firstOrNull { !it.isNullOrBlank() }
    }

    @JvmStatic
    fun matches(candidate: String?, expected: String?): Boolean {
        if (expected.isNullOrBlank()) {
            return true
        }
        val left = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val right = expected.trim().takeIf { it.isNotEmpty() } ?: return false
        return left.equals(right, ignoreCase = true)
    }
}

package tk.glucodata

import android.util.Log

/**
 * Shared/main-code bridge to the mobile Room history repository.
 *
 * This is not a separate source of truth. It is only an access adapter so
 * current/display/export code in `src/main` can read the same per-sensor Room
 * history as the dashboard without importing mobile-only classes directly.
 */
object HistoryRepositoryAccess {
    private const val TAG = "HistoryRepoAccess"
    private const val REPOSITORY_CLASS_NAME = "tk.glucodata.data.HistoryRepository"

    private val repositoryHolder by lazy { runCatching { Class.forName(REPOSITORY_CLASS_NAME) }.getOrNull() }
    private val historyForSensorMethod by lazy {
        runCatching {
            repositoryHolder?.getMethod(
                "getHistoryForNotificationForSensor",
                String::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
    }

    @JvmStatic
    fun getHistoryForSensor(
        sensorSerial: String?,
        startTimeMs: Long,
        isMmol: Boolean
    ): List<GlucosePoint>? {
        val method = historyForSensorMethod ?: return null
        val resolvedSerial = sensorSerial?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, resolvedSerial, startTimeMs, isMmol) as? List<GlucosePoint>
        }.onFailure {
            Log.w(TAG, "getHistoryForSensor($resolvedSerial, $startTimeMs, isMmol=$isMmol) failed", it)
        }.getOrNull()
    }
}

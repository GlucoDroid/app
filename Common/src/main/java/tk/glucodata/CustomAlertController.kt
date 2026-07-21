package tk.glucodata

import android.content.Context

/**
 * The app's custom-alert engine, handed to [CustomAlertAccess] once at startup.
 *
 * The engine itself lives in the mobile source set, which the shared code cannot reference at
 * compile time. Registering it through this interface keeps every call an ordinary interface
 * invoke, which R8 renames on both sides. Looking it up by name at runtime does not survive
 * minification: see [CustomAlertAccess].
 */
interface CustomAlertController {
    fun checkAndTrigger(
        context: Context,
        glucose: Float,
        rate: Float,
        timestampMillis: Long,
        sensorId: String?,
        sensorGen: Int
    )

    fun dismissAlert(alertId: String)
}

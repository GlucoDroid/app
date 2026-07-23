package tk.glucodata

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.drivers.ManagedSensorRuntime

/** Watch-only, process-local ownership claim for direct managed-sensor routing. */
object WearSensorClaim {
    private const val LOG_ID = "WearSensorClaim"
    private const val CLAIM_TIMEOUT_MS = 3L * 60L * 1000L
    private const val READING_MAX_AGE_MS = 2L * 60L * 1000L
    private const val POLL_MS = 2_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "WearSensorClaim").apply { isDaemon = true }
    }
    @Volatile private var directRequested = false
    @Volatile private var claimed = false
    @Volatile private var requestedAtMs = 0L
    private var monitor: ScheduledFuture<*>? = null

    @JvmStatic
    @Synchronized
    fun setDirectRequested(enabled: Boolean) {
        if (!Applic.isWearable) return
        directRequested = enabled
        requestedAtMs = if (enabled) System.currentTimeMillis() else 0L
        setClaimed(false, if (enabled) "waiting for first accepted reading" else "direct mode disabled")
        monitor?.cancel(false)
        monitor = if (enabled) {
            executor.scheduleWithFixedDelay(::checkClaim, 0L, POLL_MS, TimeUnit.MILLISECONDS)
        } else {
            null
        }
        MessageSender.sendnetinfo()
    }

    @JvmStatic
    fun netInfoValue(): Int = if (Applic.isWearable && directRequested && claimed) 1 else -1

    private fun checkClaim() {
        if (!directRequested) return
        val now = System.currentTimeMillis()
        if (now - requestedAtMs >= CLAIM_TIMEOUT_MS) {
            synchronized(this) {
                directRequested = false
                requestedAtMs = 0L
                monitor?.cancel(false)
                monitor = null
            }
            setClaimed(false, "scan timeout")
            MessageSender.sendnetinfo()
            return
        }
        val sensorId = runCatching { SensorIdentity.resolveMainSensor() }.getOrNull() ?: return
        val driver = ManagedSensorRuntime.resolveDriver(sensorId) ?: return
        val hasAcceptedReading = runCatching {
            val reading = driver.getManagedCurrentSnapshot(READING_MAX_AGE_MS)
            reading != null && reading.timeMillis >= requestedAtMs
        }.getOrDefault(false)
        val connected = runCatching {
            driver.getManagedUiSnapshot(sensorId)?.isVendorConnected == true
        }.getOrDefault(false)
        if (hasAcceptedReading && connected) {
            synchronized(this) {
                monitor?.cancel(false)
                monitor = null
            }
            setClaimed(true, "connected with accepted reading")
            MessageSender.sendnetinfo()
        }
    }

    @Synchronized
    private fun setClaimed(value: Boolean, reason: String) {
        if (claimed == value) return
        claimed = value
        Log.i(LOG_ID, "watch sensor claim=$value: $reason")
    }
}

package tk.glucodata

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Phone side of the handoff: stop scanning once the watch is actually reading the
 * sensor, and start again the moment that stops being true.
 *
 * The phone used to keep its own connection after a handoff. Managed Kotlin
 * drivers are not part of native's routing, so the netinfo exchange that releases
 * phone Bluetooth for Libre/Dexcom never applied to them and both devices held
 * the sensor.
 *
 * Deliberately fail-safe. The phone gives up Bluetooth only while it keeps
 * hearing that the watch is connected, and resumes on anything else — a watch
 * that says it lost the sensor, or a watch that goes quiet because it went out of
 * range, lost power or had its app killed. The worst case is both devices
 * connected, which is today's behaviour; never neither.
 */
object WearPhoneBleRelease {
    private const val LOG_ID = "WearPhoneBleRelease"

    /**
     * How long a "watch is connected" report keeps the phone off the sensor.
     * The watch re-publishes while it owns the sensor
     * ([WearSensorClaim.OWNERSHIP_HEARTBEAT_MS]); this allows several missed
     * heartbeats before the phone takes over again.
     */
    private const val RELEASE_GRACE_MS = 4L * 60L * 1000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "WearPhoneBleRelease").apply { isDaemon = true }
    }

    @Volatile private var released = false
    @Volatile private var watchdog: ScheduledFuture<*>? = null

    /** Called for every claim status the watch reports. */
    @JvmStatic
    fun onWatchClaim(state: WearSensorClaimState) {
        if (Applic.isWearable) return
        if (WearPhoneBleReleasePolicy.phoneShouldHoldSensor(state, 0L, RELEASE_GRACE_MS)) {
            resumePhoneBle("watch reports $state")
        } else {
            releasePhoneBle()
            armWatchdog()
        }
    }

    /** The watch is gone as far as the transport is concerned. */
    @JvmStatic
    fun onWatchUnreachable() {
        if (Applic.isWearable) return
        resumePhoneBle("watch unreachable")
    }

    private fun releasePhoneBle() {
        if (released) return
        released = true
        Log.i(LOG_ID, "watch is reading the sensor: stopping phone Bluetooth")
        setBluetooth(false)
    }

    private fun resumePhoneBle(reason: String) {
        cancelWatchdog()
        if (!released) return
        released = false
        Log.i(LOG_ID, "resuming phone Bluetooth: $reason")
        setBluetooth(true)
    }

    private fun armWatchdog() {
        cancelWatchdog()
        watchdog = runCatching {
            executor.schedule(
                { resumePhoneBle("no word from the watch in ${RELEASE_GRACE_MS / 60_000} min") },
                RELEASE_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
        }.getOrNull()
    }

    private fun cancelWatchdog() {
        watchdog?.cancel(false)
        watchdog = null
    }

    /**
     * Tearing down GATTs touches app-wide state, so keep it on the main thread —
     * claim reports arrive on the Data Layer's callback thread.
     */
    private fun setBluetooth(on: Boolean) {
        val context = MainActivity.thisone ?: Applic.app ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching { Applic.setbluetooth(context, on) }
                .onFailure { Log.stack(LOG_ID, "setbluetooth($on)", it) }
        }
    }
}

/**
 * When the phone should be reading the sensor itself. Pure, because getting it
 * wrong in the unsafe direction means neither device is reading a CGM.
 */
internal object WearPhoneBleReleasePolicy {
    fun phoneShouldHoldSensor(
        lastReportedState: WearSensorClaimState?,
        msSinceLastConnectedReport: Long,
        graceMs: Long,
    ): Boolean {
        // No word from the watch at all: the phone reads.
        val state = lastReportedState ?: return true
        // Anything short of a live connection on the watch: the phone reads.
        if (state != WearSensorClaimState.CONNECTED) return true
        // Connected, but the reports have dried up — assume the watch is gone.
        return msSinceLastConnectedReport > graceMs
    }
}

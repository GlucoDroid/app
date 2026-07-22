package tk.glucodata.alerts

import tk.glucodata.Natives
import tk.glucodata.SensorBluetooth
import tk.glucodata.SensorIdentity
import tk.glucodata.drivers.ManagedBluetoothSensorDriver

/**
 * Pick the expiry-relevant sensor end among per-sensor candidates
 * (serial -> official end, ms). The sensor currently on the display wins;
 * otherwise the farthest end, since the running sensor outlives finished ones.
 *
 * Ends not in the future are rejected: 0 means unknown, a past end means the
 * sensor is already expired. This also keeps a clamped source out of the latch —
 * Natives.getendtime() returns the chart's data range end, capped at "now"
 * while the sensor runs, which made every window look open on every tick and
 * silenced all expiry warnings.
 */
internal fun selectSensorExpiryEndMs(
    candidates: List<Pair<String?, Long>>,
    preferredSensorId: String?,
    nowMs: Long
): Long {
    val plausible = candidates.filter { it.second > nowMs }
    if (plausible.isEmpty()) return 0L
    if (preferredSensorId != null) {
        plausible.firstOrNull { SensorIdentity.matches(it.first, preferredSensorId) }
            ?.let { return it.second }
    }
    return plausible.maxOf { it.second }
}

/**
 * Resolve the official end (start + wear duration) of the relevant sensor from
 * the live gatt registry. Kotlin-managed drivers without native backing fall
 * back to their UI snapshot.
 */
internal fun resolveSensorExpiryEndMs(preferredSensorId: String?, nowMs: Long): Long {
    val gatts = try {
        SensorBluetooth.mygatts()
    } catch (t: Throwable) {
        null
    } ?: return 0L
    val candidates = gatts.map { gatt ->
        val nativeEnd = runCatching { Natives.getSensorEndTime(gatt.dataptr, true) }.getOrDefault(0L)
        val end = if (nativeEnd > 0L) {
            nativeEnd
        } else {
            runCatching {
                (gatt as? ManagedBluetoothSensorDriver)?.getManagedUiSnapshot()?.officialEndMs ?: 0L
            }.getOrDefault(0L)
        }
        gatt.SerialNumber to end
    }
    return selectSensorExpiryEndMs(candidates, preferredSensorId, nowMs)
}

/**
 * Durable memory of which expiry thresholds already warned, keyed by the
 * sensor's end time. Production backs this with SharedPreferences
 * ([AlertRepository.sensorExpiryWarnedStore]); tests inject a fake.
 */
internal interface ExpiryWarnedStore {
    /** Thresholds (minutes) already warned for the sensor ending at [endTimeMs]. */
    fun load(endTimeMs: Long): Set<Int>

    /** Replace the stored set with [thresholds] for [endTimeMs], dropping other sensors' entries. */
    fun save(endTimeMs: Long, thresholds: Set<Int>)
}

/**
 * Edge-triggered latch for the sensor-expiry pre-warnings, one latch per
 * configured threshold. Each threshold fires exactly once per sensor when the
 * clock first crosses into its warning window (`endTimeMs - now <= threshold`).
 *
 * Preserved semantics from the original single-threshold version, now per
 * threshold:
 *  - **Edge-triggered:** fire on window entry, not on every 15s tick.
 *  - **New sensor** (`endTimeMs` changes): rearm every threshold.
 *  - **Baseline:** the first active pass reloads the persisted warned-set for
 *    the current sensor. Already-warned windows (from this or an earlier
 *    process run) stay silent; open windows that were never warned are due
 *    now, so a restart between window entry and the next tick cannot swallow
 *    a warning. The cascade guard caps the catch-up at one alert.
 *  - **Newly enabled thresholds** whose window is already open are adopted
 *    (marked warned, never fired retroactively). Mid-process that happens
 *    here; across a restart [AlertRepository.saveConfig] persists the
 *    adoption at save time.
 *  - **Cascade guard:** if several thresholds come due in the same tick (e.g. the
 *    app resumes deep inside multiple windows), fire only the smallest (most
 *    urgent) and silently mark the rest as warned.
 */
internal class SensorExpiryAlertState(private val store: ExpiryWarnedStore) {
    private var baselineReady = false
    private var lastEndTimeMs = 0L
    private val wasInWindow = mutableMapOf<Int, Boolean>()   // threshold -> inside window last tick
    private val alertedForEnd = mutableMapOf<Int, Long>()    // threshold -> endTime already warned for

    fun reset() {
        baselineReady = false
        lastEndTimeMs = 0L
        wasInWindow.clear()
        alertedForEnd.clear()
    }

    /**
     * @return the thresholds (in minutes) that should fire an alert right now.
     *   At most one element (cascade guard); empty means do nothing.
     */
    fun triggeredThresholds(
        enabled: Boolean,
        activeNow: Boolean,
        snoozed: Boolean,
        endTimeMs: Long,
        nowMs: Long,
        thresholdsMinutes: Set<Int>
    ): Set<Int> {
        if (!enabled || endTimeMs <= 0L || thresholdsMinutes.isEmpty()) {
            reset()
            return emptySet()
        }

        // New sensor -> new episode: rearm everything, then adopt what an
        // earlier process run already warned for this end time.
        if (endTimeMs != lastEndTimeMs) {
            baselineReady = false
            lastEndTimeMs = endTimeMs
            wasInWindow.clear()
            alertedForEnd.clear()
            for (t in store.load(endTimeMs)) {
                alertedForEnd[t] = endTimeMs
            }
        }

        // Forget thresholds the user has removed so the maps stay bounded.
        wasInWindow.keys.retainAll(thresholdsMinutes)
        alertedForEnd.keys.retainAll(thresholdsMinutes)

        if (!activeNow || snoozed) {
            return emptySet()
        }

        fun inWindow(minutes: Int): Boolean =
            endTimeMs - nowMs <= minutes.toLong() * 60_000L

        val newlyDue = mutableListOf<Int>()
        if (!baselineReady) {
            // First active pass: open windows that were never warned - not in
            // this process, not in an earlier one - are due now.
            baselineReady = true
            for (t in thresholdsMinutes) {
                val inside = inWindow(t)
                wasInWindow[t] = inside
                if (inside && alertedForEnd[t] != endTimeMs) {
                    newlyDue.add(t)
                }
            }
        } else {
            for (t in thresholdsMinutes) {
                val inside = inWindow(t)
                val known = wasInWindow.containsKey(t)
                val prev = wasInWindow[t] ?: false
                wasInWindow[t] = inside
                if (!known) {
                    // Threshold enabled mid-episode: adopt like the baseline, never
                    // fire retroactively for a window that is already open.
                    if (inside && alertedForEnd[t] != endTimeMs) {
                        alertedForEnd[t] = endTimeMs
                        persistWarned()
                    }
                    continue
                }
                if (inside && !prev && alertedForEnd[t] != endTimeMs) {
                    newlyDue.add(t)
                }
            }
        }

        if (newlyDue.isEmpty()) {
            return emptySet()
        }

        // Cascade guard: fire only the most urgent (smallest lead time); mark the
        // rest as warned so they never fire late.
        val fire = newlyDue.min()
        for (t in newlyDue) {
            alertedForEnd[t] = endTimeMs
        }
        persistWarned()
        return setOf(fire)
    }

    /** Union with the stored set so a concurrent settings-save adoption is not lost. */
    private fun persistWarned() {
        val warned = alertedForEnd.filterValues { it == lastEndTimeMs }.keys
        store.save(lastEndTimeMs, store.load(lastEndTimeMs) + warned)
    }
}

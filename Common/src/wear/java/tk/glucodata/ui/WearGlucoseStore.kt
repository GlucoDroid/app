package tk.glucodata.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.GlucosePoint
import tk.glucodata.Log
import tk.glucodata.NotificationHistorySource
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One shared, off-main-thread copy of the history the watch screens draw from.
 *
 * Every screen used to read the store itself, on the main thread, and re-read it
 * on each refresh: the chart pulled the whole 14-day horizon on init, on every
 * range change, on every arriving sync chunk and once a minute, while the home
 * list pulled six hours of its own and each reading row asked native for the
 * calibration anchors separately. A backfill of 26 chunks therefore meant 26
 * full-horizon reads with a Room/native merge each, all blocking frames.
 *
 * Now a single loader owns it: reads are coalesced, run on a background
 * dispatcher, cover only the horizon something is actually showing, and grow
 * only when the user pans further back.
 */
object WearGlucoseStore {
    private const val TAG = "WearGlucoseStore"
    private const val HOUR_MS = 3_600_000L

    /** Enough for the default view plus a little scrollback, and cheap to read. */
    const val DEFAULT_HORIZON_MS = 24L * HOUR_MS
    const val MAX_HORIZON_MS = 14L * 24L * HOUR_MS

    /** Read on a cold open, before the full horizon, so the UI fills at once. */
    private const val FIRST_PASS_HORIZON_MS = 3L * HOUR_MS

    /** Refreshes arriving faster than this are folded into one reload. */
    private const val MIN_RELOAD_INTERVAL_MS = 4_000L
    private const val TICK_MS = 60_000L

    data class Snapshot(
        /** Ascending, oldest first, covering [horizonStartMs] to now. */
        val points: List<GlucosePoint> = emptyList(),
        /** Calibration anchors as [sensorMgdl, userMgdl, timestampMs] triples. */
        val anchors: DoubleArray = DoubleArray(0),
        val horizonStartMs: Long = 0L,
        val isMmol: Boolean = false,
        val isRawMode: Boolean = false,
        val sensorId: String? = null,
        val loadedAtMs: Long = 0L,
    ) {
        val isLoaded: Boolean get() = loadedAtMs > 0L

        // Generated equals/hashCode would compare the anchor array by identity,
        // and every reload allocates a new one; Compose would then treat each
        // snapshot as changed even when nothing moved.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return loadedAtMs == other.loadedAtMs &&
                horizonStartMs == other.horizonStartMs &&
                isMmol == other.isMmol &&
                isRawMode == other.isRawMode &&
                sensorId == other.sensorId &&
                points === other.points
        }

        override fun hashCode(): Int = loadedAtMs.hashCode() * 31 + horizonStartMs.hashCode()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot = _snapshot.asStateFlow()

    private val started = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    @Volatile private var reloadPending = false
    @Volatile private var lastLoadStartedAt = 0L
    @Volatile private var requestedHorizonMs = DEFAULT_HORIZON_MS

    /** Starts the single collector that keeps the snapshot current. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            launch { UiRefreshBus.revision.collect { refresh() } }
            launch {
                while (true) {
                    delay(TICK_MS)
                    refresh()
                }
            }
        }
        refresh(force = true)
    }

    /**
     * Asks for at least [horizonMs] of history. Panning back past what is loaded
     * calls this; nothing re-reads the deep horizon until something needs it.
     */
    fun ensureHorizon(horizonMs: Long) {
        val wanted = horizonMs.coerceIn(DEFAULT_HORIZON_MS, MAX_HORIZON_MS)
        if (wanted <= requestedHorizonMs) return
        requestedHorizonMs = wanted
        refresh(force = true)
    }

    /** Re-reads on the next opportunity; bursts collapse into one pass. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadStartedAt < MIN_RELOAD_INTERVAL_MS) {
            reloadPending = true
            return
        }
        if (!loading.compareAndSet(false, true)) {
            reloadPending = true
            return
        }
        lastLoadStartedAt = now
        scope.launch {
            try {
                load()
            } catch (t: Throwable) {
                Log.stack(TAG, "load", t)
            } finally {
                loading.set(false)
                if (reloadPending) {
                    reloadPending = false
                    delay(MIN_RELOAD_INTERVAL_MS)
                    refresh(force = true)
                }
            }
        }
    }

    private fun load() {
        // First pass reads a couple of hours so the screen has numbers on it
        // straight away, then the full horizon lands behind it. A cold open used
        // to draw an empty chart until the deep read finished.
        if (!_snapshot.value.isLoaded && requestedHorizonMs > FIRST_PASS_HORIZON_MS) {
            loadHorizon(FIRST_PASS_HORIZON_MS)
        }
        loadHorizon(requestedHorizonMs)
    }

    private fun loadHorizon(horizonMs: Long) {
        val now = System.currentTimeMillis()
        val isMmol = runCatching { Applic.unit == 1 }.getOrDefault(false)
        val isRawMode = currentRawMode()
        val horizonStart = now - horizonMs
        val sensor = runCatching { NotificationHistorySource.resolveSensorSerial() }.getOrNull()
        val points = runCatching {
            NotificationHistorySource.getDisplayHistory(horizonStart, isMmol, sensor)
        }.getOrDefault(emptyList())
            .filter { it.timestamp in horizonStart..now && it.value.isFinite() && it.value > 0f }
        val anchors = runCatching {
            CalibrationAccess.getActiveCalibrationAnchors(
                sensor ?: runCatching { SensorIdentity.resolveMainSensor() }.getOrNull(),
                isRawMode,
            )
        }.getOrDefault(DoubleArray(0))

        _snapshot.value = Snapshot(
            points = points,
            anchors = anchors,
            horizonStartMs = horizonStart,
            isMmol = isMmol,
            isRawMode = isRawMode,
            sensorId = sensor,
            loadedAtMs = now,
        )
    }

    private fun currentRawMode(): Boolean {
        val mode = runCatching {
            val sensor = NotificationHistorySource.resolveSensorSerial()
            CurrentDisplaySource.resolveViewModeForSensor(sensor).coerceIn(0, 3)
        }.getOrDefault(0)
        return mode == 1 || mode == 3
    }

    /** The view mode the snapshot was loaded with, for screens that show it. */
    fun viewMode(): Int = runCatching {
        val sensor = NotificationHistorySource.resolveSensorSerial()
        CurrentDisplaySource.resolveViewModeForSensor(sensor).coerceIn(0, 3)
    }.getOrDefault(0)

    /** Newest first, for the reading lists. */
    fun recent(count: Int, withinMs: Long = 6L * HOUR_MS): List<GlucosePoint> {
        val snap = _snapshot.value
        if (snap.points.isEmpty()) return emptyList()
        val cutoff = System.currentTimeMillis() - withinMs
        val result = ArrayList<GlucosePoint>(count)
        for (index in snap.points.indices.reversed()) {
            val point = snap.points[index]
            if (point.timestamp < cutoff) break
            result.add(point)
            if (result.size >= count) break
        }
        return result
    }
}

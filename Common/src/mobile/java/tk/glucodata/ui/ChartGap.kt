package tk.glucodata.ui

import androidx.compose.ui.geometry.Offset

/**
 * How far apart two readings may be before the chart stops connecting them.
 *
 * The threshold has to stay strictly above the coarsest cadence the app can produce,
 * which is the 15-minute Libre 1/2 NFC history record. It used to be exactly 15 minutes,
 * and that broke the line *between* consecutive history points: a slot's stored timestamp
 * is `scanTime - (currentId - slotId) * 60`, so adjacent slots written by different scans
 * land 15 minutes apart give or take the scans' second-offsets. Stretches covered only by
 * NFC history — every BLE streaming outage — therefore rendered as a row of one-point
 * sub-paths, which a Stroke draws as nothing at all.
 *
 * At 20 minutes a break means at least one 15-minute history slot is genuinely missing,
 * which is what a gap in the curve should mean.
 */
internal object ChartGap {
    const val THRESHOLD_MS = 20L * 60L * 1000L
}

/**
 * Tracks one polyline run so a run holding a single point stays visible.
 *
 * `Path.moveTo` with no following `lineTo` strokes nothing, so a reading with no
 * neighbour close enough to connect to would silently disappear. Runs of length one are
 * collected here and drawn as dots instead.
 */
internal class ChartLineRun {
    private var startX = 0f
    private var startY = 0f
    private var open = false
    private var connected = false

    val isolatedPoints = ArrayList<Offset>()

    /** Starts a new run at a `moveTo`, closing whatever run was open. */
    fun begin(x: Float, y: Float) {
        flush()
        startX = x
        startY = y
        open = true
        connected = false
    }

    /** Records that the open run got a `lineTo`, so it strokes on its own. */
    fun extend() {
        connected = true
    }

    /** Closes the open run; call once more after the last point. */
    fun flush() {
        if (open && !connected) {
            isolatedPoints.add(Offset(startX, startY))
        }
        open = false
    }
}

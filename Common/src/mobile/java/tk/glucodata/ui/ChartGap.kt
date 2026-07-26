package tk.glucodata.ui

import androidx.compose.ui.geometry.Offset

/**
 * How far apart two readings may be before the chart stops connecting them.
 *
 * Deliberately 15 minutes — a missing reading has to look missing. Raising it would draw
 * a straight line across a real hole in the data, which is worse than an honest gap.
 *
 * The consequence is that a stretch covered only by 15-minute Libre NFC history splits
 * into one-point runs, because a slot's stored timestamp is
 * `scanTime - (currentId - slotId) * 60` and slots written by different scans land 15
 * minutes apart give or take those scans' second-offsets. [ChartLineRun] is what keeps
 * those points visible: a lone point becomes a dot instead of nothing, so coarse
 * scan-recovered data reads as dotted rather than as a blank stretch.
 */
internal object ChartGap {
    const val THRESHOLD_MS = 15L * 60L * 1000L
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

package tk.glucodata.ui.screens

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CalibrationAccess
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.TrendAccess
import tk.glucodata.UiRefreshBus

internal val CHART_RANGES = intArrayOf(3, 6, 12, 24)

private const val HOUR_MS = 3_600_000L
private const val MAX_HISTORY_HOURS = 24
private const val RIGHT_GAP_FRACTION = 0.09f
private const val MIN_VIEWPORT_MS = 45 * 60_000L
private const val CHART_PREFS = "tk.glucodata_preferences"
private const val SHOW_RAW_KEY = "wear_chart_show_raw"

internal data class ChartThresholds(val low: Float, val high: Float, val veryLow: Float, val veryHigh: Float)
internal data class CalibrationMark(val timestamp: Long, val value: Float)
internal data class WearChartData(
    val points: List<GlucosePoint>,
    val calibrations: List<CalibrationMark>,
    val thresholds: ChartThresholds,
    val start: Long,
    val end: Long,
    val historyStart: Long,
    val isMmol: Boolean,
)

private fun thresholds(isMmol: Boolean): ChartThresholds {
    fun nativeOrDefault(value: () -> Float, fallback: Float) =
        runCatching(value).getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: fallback
    return ChartThresholds(
        nativeOrDefault(Natives::targetlow, GlucoseRangeColors.defaultLow(isMmol)),
        nativeOrDefault(Natives::targethigh, GlucoseRangeColors.defaultHigh(isMmol)),
        nativeOrDefault(Natives::alarmverylow, GlucoseRangeColors.defaultVeryLow(isMmol)),
        nativeOrDefault(Natives::alarmveryhigh, GlucoseRangeColors.defaultVeryHigh(isMmol)),
    )
}

internal fun loadChart(hours: Int, isMmol: Boolean): WearChartData {
    val now = System.currentTimeMillis()
    val duration = hours * HOUR_MS
    val start = now - duration
    val end = now + (duration * RIGHT_GAP_FRACTION).toLong()
    val historyStart = now - MAX_HISTORY_HOURS * HOUR_MS
    val sensor = NotificationHistorySource.resolveSensorSerial()
    val points = runCatching { NotificationHistorySource.getDisplayHistory(historyStart, isMmol, sensor) }.getOrDefault(emptyList())
        .filter { it.timestamp in historyStart..now && it.value.isFinite() && it.value > 0f }
    val anchors = CalibrationAccess.getActiveCalibrationAnchors(sensor, false)
    val conversion = if (isMmol) 18.0182f else 1f
    val marks = anchors.indices.step(3).mapNotNull { offset ->
        if (offset + 2 >= anchors.size) return@mapNotNull null
        CalibrationMark(anchors[offset + 2].toLong(), anchors[offset + 1].toFloat() / conversion)
            .takeIf { it.timestamp in historyStart..now && it.value.isFinite() && it.value > 0f }
    }
    return WearChartData(points, marks, thresholds(isMmol), start, end, historyStart, isMmol)
}

private fun clampedViewport(data: WearChartData, start: Long, end: Long): Pair<Long, Long> {
    val availableDuration = (data.end - data.historyStart).coerceAtLeast(MIN_VIEWPORT_MS)
    val duration = (end - start).coerceIn(MIN_VIEWPORT_MS, availableDuration)
    val clampedStart = start.coerceIn(data.historyStart, data.end - duration)
    return clampedStart to (clampedStart + duration)
}

@Composable
fun ChartScreen() {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var rangeIndex by remember { mutableIntStateOf(1) }
    var data by remember { mutableStateOf(loadChart(CHART_RANGES[rangeIndex], isMmol)) }
    var viewportStart by remember { mutableLongStateOf(data.start) }
    var viewportEnd by remember { mutableLongStateOf(data.end) }
    var selected by remember { mutableStateOf<GlucosePoint?>(null) }
    val requester = remember { FocusRequester() }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(CHART_PREFS, Context.MODE_PRIVATE) }
    var showRaw by remember { mutableStateOf(prefs.getBoolean(SHOW_RAW_KEY, false)) }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    fun resetViewport(nextData: WearChartData = data) {
        viewportStart = nextData.start
        viewportEnd = nextData.end
        selected = null
    }

    fun updateData() {
        val wasAtNow = abs(viewportEnd - data.end) < 2 * 60_000L
        val next = loadChart(CHART_RANGES[rangeIndex], isMmol)
        data = next
        if (wasAtNow) {
            resetViewport(next)
        } else {
            clampedViewport(next, viewportStart, viewportEnd).let {
                viewportStart = it.first
                viewportEnd = it.second
            }
        }
    }

    fun zoomViewport(zoomFactor: Float, focusFraction: Float = 0.5f) {
        val oldDuration = (viewportEnd - viewportStart).coerceAtLeast(1L)
        val maxDuration = data.end - data.historyStart
        val duration = (oldDuration / zoomFactor).toLong().coerceIn(MIN_VIEWPORT_MS, maxDuration)
        val focus = viewportStart + (oldDuration * focusFraction).toLong()
        val start = focus - (duration * focusFraction).toLong()
        clampedViewport(data, start, start + duration).let {
            viewportStart = it.first
            viewportEnd = it.second
        }
        selected = null
    }

    LaunchedEffect(rangeIndex) {
        val next = loadChart(CHART_RANGES[rangeIndex], isMmol)
        data = next
        resetViewport(next)
    }
    LaunchedEffect(Unit) {
        launch { UiRefreshBus.revision.collect { updateData() } }
        launch { while (true) { kotlinx.coroutines.delay(60_000L); updateData() } }
        requester.requestFocus()
    }

    val lineColor = data.points.lastOrNull()?.let { rangeColor(it.value, isMmol) }
        ?: Color(GlucoseRangeColors.inRange(true))
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val targetColor = Color(GlucoseRangeColors.inRange(true))
    val alarmColor = MaterialTheme.colorScheme.error
    val selectionColor = MaterialTheme.colorScheme.primary

    ScreenScaffold(timeText = { TimeText() }) {
        Box(
            Modifier.fillMaxSize()
                .onRotaryScrollEvent { event ->
                    zoomViewport(if (event.verticalScrollPixels < 0f) 1.16f else 1f / 1.16f)
                    true
                }
                .focusRequester(requester).focusable(),
        ) {
            WearChart(
                data = data,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                lineColor = lineColor,
                rawColor = labelColor.copy(alpha = 0.52f),
                showRaw = showRaw,
                targetColor = targetColor,
                alarmColor = alarmColor,
                gridColor = gridColor,
                labelColor = labelColor,
                selected = selected,
                selectionColor = selectionColor,
                formatTime = { timeFormat.format(Date(it)) },
                onSelect = { selected = it },
                onViewportChange = { start, end ->
                    clampedViewport(data, start, end).let {
                        viewportStart = it.first
                        viewportEnd = it.second
                    }
                    selected = null
                },
                onReset = { resetViewport() },
                modifier = Modifier.fillMaxSize().padding(top = 30.dp, bottom = 29.dp, start = 10.dp, end = 10.dp),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Text(
                    "${CHART_RANGES[rangeIndex]}h",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures { rangeIndex = (rangeIndex + 1) % CHART_RANGES.size }
                    }.padding(horizontal = 11.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.raw),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showRaw) MaterialTheme.colorScheme.primary else labelColor,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures {
                            showRaw = !showRaw
                            prefs.edit().putBoolean(SHOW_RAW_KEY, showRaw).apply()
                        }
                    }.padding(horizontal = 11.dp, vertical = 4.dp),
                )
            }
            val headline = selected?.let {
                val raw = it.rawValue.takeIf { value -> showRaw && value.isFinite() && value > 0f }
                val values = if (raw != null) {
                    "${formatWearGlucose(it.value, isMmol)} / ${formatWearGlucose(raw, isMmol)}"
                } else {
                    formatWearGlucose(it.value, isMmol)
                }
                "$values  ${timeFormat.format(Date(it.timestamp))}"
            } ?: data.points.lastOrNull()?.let {
                val velocity = TrendAccess.calculateVelocity(data.points.takeLast(8), false, isMmol)
                "${formatWearGlucose(it.value, isMmol)} ${trendArrow(velocity)}"
            }
            headline?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp))
            }
            if (data.points.isEmpty()) {
                Text(
                    stringResource(R.string.nodata),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
internal fun WearChart(
    data: WearChartData,
    viewportStart: Long = data.start,
    viewportEnd: Long = data.end,
    lineColor: Color,
    rawColor: Color = Color.Transparent,
    showRaw: Boolean = false,
    targetColor: Color,
    alarmColor: Color,
    gridColor: Color,
    labelColor: Color,
    selected: GlucosePoint?,
    selectionColor: Color,
    formatTime: (Long) -> String,
    onSelect: (GlucosePoint?) -> Unit,
    onViewportChange: ((Long, Long) -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    modifier: Modifier,
) {
    val selectedState = rememberUpdatedState(selected)
    val viewportPoints = remember(data.points, viewportStart, viewportEnd) {
        data.points.filter { it.timestamp in viewportStart..viewportEnd }
    }
    fun pointAt(x: Float, width: Int): GlucosePoint? {
        if (width <= 0) return null
        val timestamp = viewportStart + ((x / width) * (viewportEnd - viewportStart)).toLong()
        return viewportPoints.minByOrNull { abs(it.timestamp - timestamp) }
    }
    val gestures = if (onViewportChange == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(data.historyStart, data.end, viewportStart, viewportEnd) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val oldDuration = viewportEnd - viewportStart
                    val duration = (oldDuration / zoom).toLong()
                    val focusFraction = (centroid.x / width).coerceIn(0f, 1f)
                    val focus = viewportStart + (oldDuration * focusFraction).toLong()
                    val zoomedStart = focus - (duration * focusFraction).toLong()
                    val panMillis = (-(pan.x / width) * duration).toLong()
                    onViewportChange(zoomedStart + panMillis, zoomedStart + panMillis + duration)
                }
            }
            .pointerInput(viewportPoints, viewportStart, viewportEnd) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onSelect(pointAt(it.x, size.width)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onSelect(pointAt(change.position.x, size.width))
                    },
                )
            }
            .pointerInput(viewportPoints, viewportStart, viewportEnd) {
                detectTapGestures(
                    onDoubleTap = { onReset?.invoke() },
                    onTap = { onSelect(pointAt(it.x, size.width)) },
                )
            }
    }
    Box(
        modifier.then(gestures).drawWithCache {
            val floor = if (data.isMmol) 2.2f else 40f
            var minValue = minOf(data.thresholds.veryLow, viewportPoints.minOfOrNull { it.value } ?: data.thresholds.low)
            var maxValue = maxOf(data.thresholds.veryHigh, viewportPoints.maxOfOrNull { it.value } ?: data.thresholds.high)
            if (showRaw) {
                viewportPoints.forEach { point ->
                    if (point.rawValue.isFinite() && point.rawValue > 0f) {
                        minValue = minOf(minValue, point.rawValue)
                        maxValue = maxOf(maxValue, point.rawValue)
                    }
                }
            }
            val padding = ((maxValue - minValue) * 0.08f).coerceAtLeast(if (data.isMmol) 0.3f else 6f)
            minValue = (minValue - padding).coerceAtLeast(floor)
            maxValue += padding
            val valueRange = (maxValue - minValue).coerceAtLeast(0.1f)
            val timeRange = (viewportEnd - viewportStart).toFloat().coerceAtLeast(1f)
            fun x(time: Long) = ((time - viewportStart).toFloat() / timeRange) * size.width
            fun y(value: Float) = size.height - ((value - minValue) / valueRange) * size.height

            fun buildCurve(raw: Boolean): Path {
                val curve = Path()
                var previous: Offset? = null
                viewportPoints.forEach { point ->
                    val value = if (raw) point.rawValue else point.value
                    if (!value.isFinite() || value <= 0f) {
                        previous = null
                    } else {
                        val current = Offset(x(point.timestamp), y(value))
                        val last = previous
                        if (last == null) {
                            curve.moveTo(current.x, current.y)
                        } else {
                            val controlX = (last.x + current.x) / 2f
                            curve.cubicTo(controlX, last.y, controlX, current.y, current.x, current.y)
                        }
                        previous = current
                    }
                }
                return curve
            }

            val curve = buildCurve(false)
            val rawCurve = if (showRaw) buildCurve(true) else null
            val alarmDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
            val calibrationDrops = data.calibrations.mapNotNull { mark ->
                if (mark.timestamp !in viewportStart..viewportEnd) return@mapNotNull null
                val center = Offset(x(mark.timestamp), y(mark.value))
                Path().apply {
                    moveTo(center.x, center.y - 6.dp.toPx())
                    cubicTo(center.x - 5.dp.toPx(), center.y, center.x - 4.dp.toPx(), center.y + 5.dp.toPx(), center.x, center.y + 5.dp.toPx())
                    cubicTo(center.x + 4.dp.toPx(), center.y + 5.dp.toPx(), center.x + 5.dp.toPx(), center.y, center.x, center.y - 6.dp.toPx())
                    close()
                }
            }
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb((labelColor.alpha * 255).toInt(), (labelColor.red * 255).toInt(), (labelColor.green * 255).toInt(), (labelColor.blue * 255).toInt())
                textSize = 9.dp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            val yStep = if (data.isMmol) 2f else 50f
            val yLabels = buildList {
                var value = ceil(minValue / yStep) * yStep
                while (value < maxValue) { add(value); value += yStep }
            }
            val yLabelTexts = yLabels.map { value -> value to formatWearGlucose(value, data.isMmol) }
            val bandColor = targetColor.copy(alpha = 0.13f)
            val lowAlarmColor = alarmColor.copy(alpha = 0.56f)
            val selectedLineColor = selectionColor.copy(alpha = 0.6f)
            val quarterTimes = longArrayOf(
                viewportStart + (viewportEnd - viewportStart) / 4,
                viewportStart + (viewportEnd - viewportStart) * 3 / 4,
            )
            val quarterLabels = quarterTimes.map { timestamp -> timestamp to formatTime(timestamp) }
            onDrawBehind {
                val bandTop = y(data.thresholds.high)
                val bandBottom = y(data.thresholds.low)
                drawRect(bandColor, Offset(0f, bandTop), Size(size.width, bandBottom - bandTop))
                yLabelTexts.forEach { (value, text) ->
                    val lineY = y(value)
                    drawLine(gridColor, Offset(0f, lineY), Offset(size.width, lineY), 1f)
                    textPaint.textAlign = android.graphics.Paint.Align.LEFT
                    drawContext.canvas.nativeCanvas.drawText(text, 14.dp.toPx(), lineY - 2.dp.toPx(), textPaint)
                }
                drawLine(lowAlarmColor, Offset(0f, y(data.thresholds.veryLow)), Offset(size.width, y(data.thresholds.veryLow)), 1.dp.toPx(), pathEffect = alarmDash)
                drawLine(lowAlarmColor, Offset(0f, y(data.thresholds.veryHigh)), Offset(size.width, y(data.thresholds.veryHigh)), 1.dp.toPx(), pathEffect = alarmDash)
                quarterLabels.forEach { (timestamp, text) ->
                    val lineX = x(timestamp)
                    drawLine(gridColor, Offset(lineX, 0f), Offset(lineX, size.height), 1f)
                    textPaint.textAlign = android.graphics.Paint.Align.CENTER
                    drawContext.canvas.nativeCanvas.drawText(text, lineX, size.height - 2.dp.toPx(), textPaint)
                }
                rawCurve?.let { drawPath(it, rawColor, style = Stroke(1.35.dp.toPx())) }
                if (viewportPoints.size >= 2) drawPath(curve, lineColor, style = Stroke(2.6.dp.toPx()))
                calibrationDrops.forEach { drawPath(it, selectionColor) }
                selectedState.value?.takeIf { it.timestamp in viewportStart..viewportEnd }?.let {
                    val sx = x(it.timestamp)
                    drawLine(selectedLineColor, Offset(sx, 0f), Offset(sx, size.height), 1.dp.toPx())
                    drawCircle(selectionColor, 4.dp.toPx(), Offset(sx, y(it.value)))
                }
            }
        },
    )
}

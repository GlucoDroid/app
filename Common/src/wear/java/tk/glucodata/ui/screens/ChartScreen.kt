package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.positionChanged
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
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.UiRefreshBus

internal val CHART_RANGES = intArrayOf(3, 6, 12, 24)

private const val HOUR_MS = 3_600_000L
private const val MAX_HISTORY_HOURS = 24
private const val RIGHT_GAP_FRACTION = 0.09f
private const val MIN_VIEWPORT_MS = 45 * 60_000L

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
    ScreenScaffold(timeText = { TimeText() }) {
        InteractiveWearChartPanel(
            modifier = Modifier.fillMaxSize().padding(top = 22.dp),
        )
    }
}

@Composable
internal fun InteractiveWearChartPanel(
    modifier: Modifier = Modifier,
    initialRangeIndex: Int = 1,
    requestInitialFocus: Boolean = true,
    rangeIndexOverride: Int? = null,
    showRangeOverlay: Boolean = true,
    headlineTopPadding: androidx.compose.ui.unit.Dp = 3.dp,
) {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var rangeIndex by remember { mutableIntStateOf(initialRangeIndex.coerceIn(CHART_RANGES.indices)) }
    var data by remember { mutableStateOf(loadChart(CHART_RANGES[rangeIndex], isMmol)) }
    var viewportStart by remember { mutableLongStateOf(data.start) }
    var viewportEnd by remember { mutableLongStateOf(data.end) }
    var selected by remember { mutableStateOf<GlucosePoint?>(null) }
    val requester = remember { FocusRequester() }
    val context = LocalContext.current
    var viewMode by remember { mutableIntStateOf(currentWearViewMode()) }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    fun resetViewport(nextData: WearChartData = data) {
        viewportStart = nextData.start
        viewportEnd = nextData.end
        selected = null
    }

    fun updateData() {
        viewMode = currentWearViewMode()
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
    LaunchedEffect(rangeIndexOverride) {
        rangeIndexOverride?.let { rangeIndex = it.coerceIn(CHART_RANGES.indices) }
    }
    LaunchedEffect(Unit) {
        launch { UiRefreshBus.revision.collect { updateData() } }
        launch { while (true) { kotlinx.coroutines.delay(60_000L); updateData() } }
        if (requestInitialFocus) requester.requestFocus()
    }

    val primaryRaw = viewMode == 1 || viewMode == 3
    val showSecondary = viewMode == 2 || viewMode == 3
    val lineColor = data.points.lastOrNull()?.let {
        rangeColor(if (primaryRaw && it.rawValue > 0f) it.rawValue else it.value, isMmol)
    }
        ?: Color(GlucoseRangeColors.inRange(true))
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val targetColor = Color(GlucoseRangeColors.inRange(true))
    val alarmColor = MaterialTheme.colorScheme.error
    val selectionColor = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .onRotaryScrollEvent { event ->
                zoomViewport(if (event.verticalScrollPixels < 0f) 1.16f else 1f / 1.16f)
                true
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    requester.requestFocus()
                    var hasPressedPointers: Boolean
                    do {
                        hasPressedPointers = awaitPointerEvent().changes.any { it.pressed }
                    } while (hasPressedPointers)
                }
            }
            .focusRequester(requester)
            .focusable(),
    ) {
            WearChart(
                data = data,
                viewportStart = viewportStart,
                viewportEnd = viewportEnd,
                lineColor = lineColor,
                rawColor = labelColor.copy(alpha = 0.52f),
                primaryRaw = primaryRaw,
                showSecondary = showSecondary,
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
                modifier = Modifier.fillMaxSize().padding(top = 24.dp, bottom = 25.dp),
            )
            if (showRangeOverlay) WearChartRangeChip(
                rangeIndex = rangeIndex,
                onClick = { rangeIndex = (rangeIndex + 1) % CHART_RANGES.size },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
            val headline = selected?.let {
                val raw = it.rawValue.takeIf { value -> value.isFinite() && value > 0f }
                val primary = if (primaryRaw && raw != null) raw else it.value
                val secondary = if (showSecondary) {
                    if (primaryRaw) it.value else raw
                } else null
                val values = if (secondary != null) {
                    "${formatWearGlucose(primary, isMmol)} / ${formatWearGlucose(secondary, isMmol)}"
                } else {
                    formatWearGlucose(primary, isMmol)
                }
                "$values  ${timeFormat.format(Date(it.timestamp))}"
            }
            headline?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.TopCenter).padding(top = headlineTopPadding))
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

@Composable
internal fun WearChartRangeChip(
    rangeIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        "${CHART_RANGES[rangeIndex.coerceIn(CHART_RANGES.indices)]}h",
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), CircleShape)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 11.dp, vertical = 4.dp),
    )
}

private fun currentWearViewMode(): Int {
    val sensor = NotificationHistorySource.resolveSensorSerial()
    return CurrentDisplaySource.resolveViewModeForSensor(sensor).coerceIn(0, 3)
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectChartTransforms(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedPan = Offset.Zero
        var chartOwnsGesture = false
        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount == 0) break
            if (!chartOwnsGesture && event.changes.any { it.isConsumed }) break

            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            accumulatedPan += pan
            if (!chartOwnsGesture) {
                chartOwnsGesture = pressedCount >= 2 ||
                    (accumulatedPan.getDistance() > viewConfiguration.touchSlop &&
                        abs(accumulatedPan.x) > abs(accumulatedPan.y))
                if (!chartOwnsGesture &&
                    accumulatedPan.getDistance() > viewConfiguration.touchSlop &&
                    abs(accumulatedPan.y) >= abs(accumulatedPan.x)
                ) {
                    break
                }
            }
            if (chartOwnsGesture) {
                onTransform(event.calculateCentroid(), pan, zoom)
                event.changes.forEach { change ->
                    if (change.positionChanged()) change.consume()
                }
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
    primaryRaw: Boolean = false,
    showSecondary: Boolean = false,
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
                detectChartTransforms { centroid, pan, zoom ->
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
            // Fit the value range to the visible data, not the alarm limits —
            // forcing veryLow..veryHigh into view squashed a flat curve into a
            // sliver. Threshold/target lines simply clip when out of range.
            fun primaryValue(point: GlucosePoint) =
                if (primaryRaw && point.rawValue.isFinite() && point.rawValue > 0f) point.rawValue else point.value
            var minValue = viewportPoints.minOfOrNull(::primaryValue) ?: data.thresholds.low
            var maxValue = viewportPoints.maxOfOrNull(::primaryValue) ?: data.thresholds.high
            val minSpan = if (data.isMmol) 3f else 54f
            if (maxValue - minValue < minSpan) {
                val mid = (maxValue + minValue) / 2f
                minValue = mid - minSpan / 2f
                maxValue = mid + minSpan / 2f
            }
            if (showSecondary) {
                viewportPoints.forEach { point ->
                    val value = if (primaryRaw) point.value else point.rawValue
                    if (value.isFinite() && value > 0f) {
                        minValue = minOf(minValue, value)
                        maxValue = maxOf(maxValue, value)
                    }
                }
            }
            val padding = ((maxValue - minValue) * 0.12f).coerceAtLeast(if (data.isMmol) 0.4f else 8f)
            minValue = (minValue - padding).coerceAtLeast(floor)
            maxValue += padding
            val valueRange = (maxValue - minValue).coerceAtLeast(0.1f)
            val timeRange = (viewportEnd - viewportStart).toFloat().coerceAtLeast(1f)
            val plotTop = 8.dp.toPx()
            val plotBottom = (size.height - 10.dp.toPx()).coerceAtLeast(plotTop + 1f)
            val plotHeight = plotBottom - plotTop
            fun x(time: Long) = ((time - viewportStart).toFloat() / timeRange) * size.width
            fun y(value: Float) = plotBottom - ((value - minValue) / valueRange) * plotHeight

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

            val curve = buildCurve(primaryRaw)
            val secondaryCurve = if (showSecondary) buildCurve(!primaryRaw) else null
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
            val bandColor = targetColor.copy(alpha = 0.06f)
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
                secondaryCurve?.let { drawPath(it, rawColor, style = Stroke(1.35.dp.toPx())) }
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

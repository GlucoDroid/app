package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.UiRefreshBus

// Compact adaptation of the phone chart's render core (DashboardChart.kt):
// same target-band + threshold-colored points visual language, with watch
// inputs — rotary crown zooms the time range, tap inspects a point.

private val RANGES_H = intArrayOf(3, 6, 12, 24)

private class ChartThresholds(val low: Float, val high: Float, val veryLow: Float, val veryHigh: Float)

private fun loadThresholds(isMmol: Boolean): ChartThresholds {
    var low = GlucoseRangeColors.defaultLow(isMmol)
    var high = GlucoseRangeColors.defaultHigh(isMmol)
    var veryLow = GlucoseRangeColors.defaultVeryLow(isMmol)
    var veryHigh = GlucoseRangeColors.defaultVeryHigh(isMmol)
    runCatching {
        Natives.targetlow().takeIf { it > 0 }?.let { low = it }
        Natives.targethigh().takeIf { it > 0 }?.let { high = it }
        Natives.alarmverylow().takeIf { it > 0 }?.let { veryLow = it }
        Natives.alarmveryhigh().takeIf { it > 0 }?.let { veryHigh = it }
    }
    return ChartThresholds(low, high, veryLow, veryHigh)
}

private fun loadPoints(rangeHours: Int, isMmol: Boolean): List<GlucosePoint> =
    runCatching {
        NotificationHistorySource.getDisplayHistory(
            System.currentTimeMillis() - rangeHours * 3_600_000L,
            isMmol,
            null,
        )
    }.getOrDefault(emptyList())

@Composable
fun ChartScreen() {
    val isMmol = remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    var rangeIdx by remember { mutableIntStateOf(1) } // 6h default
    var points by remember { mutableStateOf(loadPoints(RANGES_H[rangeIdx], isMmol)) }
    var selected by remember { mutableStateOf<GlucosePoint?>(null) }
    var rotaryAccum by remember { mutableStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val thresholds = remember { loadThresholds(isMmol) }

    LaunchedEffect(rangeIdx) {
        points = loadPoints(RANGES_H[rangeIdx], isMmol)
        selected = null
    }
    LaunchedEffect(Unit) {
        launch {
            UiRefreshBus.revision.collect { points = loadPoints(RANGES_H[rangeIdx], isMmol) }
        }
        focusRequester.requestFocus()
    }

    val inRange = Color(GlucoseRangeColors.inRange(true))
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }

    ScreenScaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    rotaryAccum += event.verticalScrollPixels
                    if (abs(rotaryAccum) > 60f) {
                        rangeIdx = (rangeIdx + if (rotaryAccum > 0) 1 else -1)
                            .coerceIn(0, RANGES_H.size - 1)
                        rotaryAccum = 0f
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            val rangeMs = RANGES_H[rangeIdx] * 3_600_000L

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 28.dp, bottom = 26.dp, start = 8.dp, end = 8.dp)
                    .pointerInput(points, rangeIdx) {
                        detectTapGestures { offset ->
                            val now = System.currentTimeMillis()
                            val start = now - RANGES_H[rangeIdx] * 3_600_000L
                            selected = points.minByOrNull {
                                abs(it.timestamp - (start + (offset.x / size.width.toFloat()) * (now - start)))
                            }.takeIf { points.isNotEmpty() }
                        }
                    },
            ) {
                val now = System.currentTimeMillis()
                val start = now - rangeMs
                drawChart(
                    points = points,
                    startTime = start,
                    endTime = now,
                    thresholds = thresholds,
                    isMmol = isMmol,
                    bandColor = inRange.copy(alpha = 0.14f),
                    gridColor = gridColor,
                    labelColor = labelColor,
                    selected = selected,
                    selectedColor = selColor,
                    density = density,
                    formatTime = { timeFormat.format(java.util.Date(it)) },
                )
            }

            // Range chip; tap cycles as a rotary fallback.
            Text(
                text = "${RANGES_H[rangeIdx]}h",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        CircleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { rangeIdx = (rangeIdx + 1) % RANGES_H.size }
                    },
            )

            selected?.let { sel ->
                Text(
                    text = "${formatValue(sel.value, isMmol)}  ${timeFormat.format(java.util.Date(sel.timestamp))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp),
                )
            }
        }
    }
}

// Display-unit float → label; values arrive already converted from
// getDisplayHistory, so this is formatting only.
private fun formatValue(v: Float, isMmol: Boolean): String =
    if (isMmol) String.format(java.util.Locale.US, "%.1f", v) else v.toInt().toString()

private fun DrawScope.drawChart(
    points: List<GlucosePoint>,
    startTime: Long,
    endTime: Long,
    thresholds: ChartThresholds,
    isMmol: Boolean,
    bandColor: Color,
    gridColor: Color,
    labelColor: Color,
    selected: GlucosePoint?,
    selectedColor: Color,
    density: Float,
    formatTime: (Long) -> String,
) {
    val w = size.width
    val h = size.height

    // Y range: data + target band, padded (mirrors the phone chart's clamping).
    val floor = if (isMmol) 2.2f else 40f
    var yMin = minOf(thresholds.low, points.minOfOrNull { it.value } ?: thresholds.low)
    var yMax = maxOf(thresholds.high, points.maxOfOrNull { it.value } ?: thresholds.high)
    val pad = (yMax - yMin) * 0.12f
    yMin = maxOf(floor, yMin - pad)
    yMax += pad
    val yRange = (yMax - yMin).coerceAtLeast(0.001f)

    fun valToY(v: Float) = h - ((v - yMin) / yRange) * h
    fun timeToX(t: Long) = ((t - startTime).toFloat() / (endTime - startTime).toFloat()) * w

    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(
            (labelColor.alpha * 255).toInt(),
            (labelColor.red * 255).toInt(),
            (labelColor.green * 255).toInt(),
            (labelColor.blue * 255).toInt(),
        )
        textSize = 10f * density
        isAntiAlias = true
    }

    // Y grid (integer steps, same adaptive step rule as the phone chart).
    // Label inset clears the circular display edge.
    val yLabelInset = 18f * density
    val yStep = if (yRange < 25f) 2f else 50f
    var yVal = ceil(yMin / yStep) * yStep
    while (yVal < yMax) {
        val y = valToY(yVal)
        drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
        drawContext.canvas.nativeCanvas.drawText(formatValue(yVal, isMmol), yLabelInset, y - 2f * density, textPaint)
        yVal += yStep
    }

    // X grid: quarter marks with time labels at 1/4 and 3/4.
    for (q in 1..3) {
        val t = startTime + (endTime - startTime) * q / 4
        val x = timeToX(t)
        drawLine(gridColor, Offset(x, 0f), Offset(x, h), 1f)
        if (q != 2) {
            val label = formatTime(t)
            textPaint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(label, x, h - 2f * density, textPaint)
            textPaint.textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    // Target range band.
    val yHigh = valToY(thresholds.high)
    val yLow = valToY(thresholds.low)
    if (yHigh.isFinite() && yLow.isFinite()) {
        drawRect(bandColor, topLeft = Offset(0f, yHigh), size = Size(w, yLow - yHigh))
    }

    // Threshold-colored glucose dots (shared color mapping).
    val dotRadius = 1.8f * density
    val fallback = GlucoseRangeColors.inRange(true)
    points.forEach { p ->
        if (p.timestamp < startTime || p.value <= 0f) return@forEach
        val argb = runCatching {
            GlucoseRangeColors.colorForValue(
                p.value, thresholds.low, thresholds.high,
                thresholds.veryLow, thresholds.veryHigh,
                fallback, true, isMmol,
            )
        }.getOrDefault(fallback)
        drawCircle(Color(argb), dotRadius, Offset(timeToX(p.timestamp), valToY(p.value)))
    }

    selected?.let { sel ->
        val x = timeToX(sel.timestamp)
        drawLine(selectedColor.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, h), 1.5f * density)
        drawCircle(selectedColor, 3.2f * density, Offset(x, valToY(sel.value)))
    }
}

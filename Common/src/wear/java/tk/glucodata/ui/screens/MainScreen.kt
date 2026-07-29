package tk.glucodata.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.TrendAccess
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.components.TrendArrowCanvas

private const val TICK_MS = 30_000L

private fun currentSnapshot(): CurrentDisplaySource.Snapshot? =
    runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()

private fun sensorPresent(): Boolean = runCatching {
    Natives.activeSensors()?.isNotEmpty() == true
}.getOrDefault(false)

internal fun glucoseColor(snapshot: CurrentDisplaySource.Snapshot): Color {
    val fallback = GlucoseRangeColors.inRange(true)
    return Color(runCatching {
        GlucoseRangeColors.colorForValue(
            snapshot.primaryValue, Natives.targetlow(), Natives.targethigh(),
            Natives.alarmverylow(), Natives.alarmveryhigh(), fallback, true, snapshot.isMmol,
        )
    }.getOrDefault(fallback))
}

internal fun trendArrow(rate: Float): String = runCatching {
    when (Natives.getxDripTrendName(rate)) {
        "DoubleUp" -> "↑↑"
        "SingleUp" -> "↑"
        "FortyFiveUp" -> "↗"
        "Flat" -> "→"
        "FortyFiveDown" -> "↘"
        "SingleDown" -> "↓"
        "DoubleDown" -> "↓↓"
        else -> ""
    }
}.getOrDefault("")

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenSensor: () -> Unit,
    onOpenReadings: () -> Unit,
    onOpenCalibrations: () -> Unit,
) {
    var snapshot by remember { mutableStateOf(currentSnapshot()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var chartRangeIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        launch { UiRefreshBus.revision.collect { snapshot = currentSnapshot(); now = System.currentTimeMillis() } }
        while (true) { delay(TICK_MS); snapshot = currentSnapshot(); now = System.currentTimeMillis() }
    }

    val isMmol = snapshot?.isMmol
        ?: remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    val recent = remember(snapshot, now / 60_000L) {
        runCatching {
            NotificationHistorySource.getDisplayHistory(now - 6 * 3_600_000L, isMmol, snapshot?.sensorId)
                .asReversed().take(6)
        }.getOrDefault(emptyList())
    }
    val newestReading = recent.firstOrNull()

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(contentPadding = PaddingValues(top = 34.dp, bottom = 28.dp)) {
            item {
                val snap = snapshot
                val status = DisplayDataState.resolve(
                    sensorPresent = sensorPresent() || snap != null || newestReading != null,
                    currentTimestampMillis = newestReading?.timestamp ?: 0L,
                    latestHistoryTimestampMillis = 0L,
                    nowMillis = now,
                )
                // The chart IS the first screen: it takes the entire viewport
                // and the hero floats over it instead of stacking above.
                Box(Modifier.fillParentMaxHeight(0.94f).fillMaxWidth()) {
                    InteractiveWearChartPanel(
                        initialRangeIndex = 0,
                        requestInitialFocus = false,
                        rangeIndexOverride = chartRangeIndex,
                        showRangeOverlay = true,
                        onRangeIndexChange = { chartRangeIndex = it },
                        headlineTopPadding = 58.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (newestReading != null && status.hasData) {
                        HeroCard(
                            point = newestReading,
                            isMmol = isMmol,
                            stale = status.isStale,
                            sensorId = snap?.sensorId,
                            // Sits as high as the clock allows so the big value
                            // overlaps as little of the curve as possible.
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-18).dp),
                        )
                    } else {
                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 32.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(if (status.kind == DisplayDataState.Kind.NO_SENSOR) R.string.no_sensor_title else R.string.nodata),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                items(recent, key = { it.timestamp }) { point ->
                    ReadingRow(
                        point,
                        isMmol,
                        onClick = onOpenReadings,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
            item {
                SensorCard(
                    snapshot?.sensorId,
                    now,
                    onOpenSensor,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    WearNavigationRow(stringResource(R.string.calibration), onClick = onOpenCalibrations)
                }
            }
            item {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    WearNavigationRow(stringResource(R.string.settings), onClick = onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun SensorCard(
    sensorId: String?,
    now: Long,
    onOpenSensor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = sensorId
        ?: runCatching { Natives.lastsensorname() }.getOrNull().takeUnless { it.isNullOrEmpty() }
        ?: return
    val sensor = remember(id, now / 60_000L) { loadWearSensorPresentation(id, now) }
    val progressColor = when {
        (sensor.lifecycleProgress ?: 0f) >= 0.95f -> MaterialTheme.colorScheme.error
        (sensor.lifecycleProgress ?: 0f) >= 0.80f -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF66BB6A)
    }
    val edgeTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val signalText = sensor.rssi?.let { "$it dBm" }
        ?: compactReadingAge(sensor.lastReadingMs, now)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                val edgeWidth = 5.dp.toPx()
                drawRect(edgeTrackColor, size = Size(edgeWidth, size.height))
                sensor.lifecycleProgress?.let { progress ->
                    val fillHeight = size.height * progress.coerceIn(0f, 1f)
                    drawRect(
                        progressColor,
                        topLeft = Offset(0f, size.height - fillHeight),
                        size = Size(edgeWidth, fillHeight),
                    )
                }
            }
            .clickable(onClick = onOpenSensor)
            .padding(start = 17.dp, end = 13.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sensor.serial, style = MaterialTheme.typography.labelLarge)
            sensor.dayValueText.takeIf { it.isNotEmpty() }?.let { dayValue ->
                Text(
                    stringResource(R.string.wear_sensor_day_format, dayValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            signalText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadingRow(
    point: GlucosePoint,
    isMmol: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = rangeColor(point.value, isMmol)
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(color.copy(alpha = 0.13f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatter.format(Date(point.timestamp)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(formatWearGlucose(point.value, isMmol), style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
private fun HeroCard(
    point: GlucosePoint,
    isMmol: Boolean,
    stale: Boolean,
    sensorId: String?,
    modifier: Modifier = Modifier,
) {
    // The big number must be the same value the readings row below shows: the
    // hero used to resolve its own snapshot through CurrentDisplaySource while
    // the list came from NotificationHistorySource, so the two disagreed and
    // the hero looked stale against its own list.
    val rangeColor = rangeColor(point.value, isMmol)
    val points = remember(point.timestamp, isMmol, sensorId) {
        NotificationHistorySource.getDisplayHistory(
            point.timestamp - 35 * 60_000L,
            isMmol,
            sensorId,
        )
    }
    val velocity = remember(points, isMmol) {
        TrendAccess.calculateVelocity(points, false, isMmol)
            .takeIf { points.size >= 2 && it.isFinite() } ?: 0f
    }
    // Floating pill over the chart: wraps content, translucent scrim so the
    // curve stays visible behind it.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.60f))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatWearGlucose(point.value, isMmol),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold),
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else rangeColor,
        )
        TrendArrowCanvas(
            velocity = velocity,
            pulseKey = point.timestamp,
            modifier = Modifier.size(28.dp).padding(start = 4.dp),
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else rangeColor,
        )
    }
}

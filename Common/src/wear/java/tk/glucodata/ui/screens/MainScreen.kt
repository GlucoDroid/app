package tk.glucodata.ui.screens

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.CalibrationAccess
import tk.glucodata.DisplayDataState
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.TrendAccess
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorRuntime
import tk.glucodata.drivers.ManagedSensorStatusPolicy
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle
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

internal fun displaySensorName(id: String): String = id.replace(Regex("^[A-Z0-9]{2,6}:"), "")

private fun sensorLifecycleText(sensorId: String?, nowMs: Long): String? = runCatching {
    val name = sensorId ?: Natives.lastsensorname() ?: return null
    // Phone parity (DashboardViewModel): the driver's managed session state is
    // the authority on lifetime. The native shell's end-times are a synthetic
    // 15-day default on the watch — trusting them showed "15" for a 24-day
    // Sibionics.
    runCatching { ManagedSensorRuntime.resolveUiSnapshot(name, name) }.getOrNull()?.let { managed ->
        if (managed.startTimeMs > 0L || managed.sensorAgeHours >= 0) {
            return ManagedSensorStatusPolicy.resolveLifecycleSummary(
                startTimeMs = managed.startTimeMs,
                officialEndMs = managed.officialEndMs,
                expectedEndMs = managed.expectedEndMs,
                sensorRemainingHours = managed.sensorRemainingHours,
                sensorAgeHours = managed.sensorAgeHours,
                nowMs = nowMs,
            ).daysText.takeIf { it.isNotEmpty() }
        }
    }
    val snap = Natives.getSensorUiSnapshot(name) ?: return null
    if (snap.size < 5 || snap[2] <= 0L) return null
    // Native end-times on the watch are fabricated defaults — show honest age
    // only, never a made-up total.
    "${((nowMs - snap[2]) / 86_400_000L).coerceAtLeast(0L)}d"
}.getOrNull()

private fun displayValue(value: Float, isMmol: Boolean): String =
    if (isMmol) String.format(Locale.getDefault(), "%.1f", value) else String.format(Locale.getDefault(), "%.0f", value)

private data class HeroVariant(val label: String, val value: String, val primary: Boolean)

private fun heroVariants(
    snapshot: CurrentDisplaySource.Snapshot,
    autoLabel: String,
    rawLabel: String,
    calibratedLabel: String,
    glucoseLabel: String,
): List<HeroVariant> {
    val values = ArrayList<HeroVariant>(3)
    val auto = snapshot.autoValue.takeIf { it.isFinite() && it > 0f }
    val raw = snapshot.rawValue.takeIf { it.isFinite() && it > 0f }
    val rawMode = snapshot.viewMode == 1 || snapshot.viewMode == 3
    val calibrated = snapshot.primaryValue.takeIf {
        it.isFinite() && it > 0f && CalibrationAccess.hasActiveCalibration(rawMode, snapshot.sensorId)
    }
    calibrated?.let { values += HeroVariant(calibratedLabel, displayValue(it, snapshot.isMmol), true) }
    auto?.let { values += HeroVariant(autoLabel, displayValue(it, snapshot.isMmol), calibrated == null && !rawMode) }
    raw?.let { values += HeroVariant(rawLabel, displayValue(it, snapshot.isMmol), calibrated == null && rawMode) }
    if (values.none { it.primary }) {
        values.add(0, HeroVariant(glucoseLabel, snapshot.primaryStr, true))
    }
    return values.distinctBy { it.label }
}

@Composable
fun MainScreen(
    onOpenChart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSensor: () -> Unit,
    onOpenReadings: () -> Unit,
    onOpenCalibrations: () -> Unit,
) {
    var snapshot by remember { mutableStateOf(currentSnapshot()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

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
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }

    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(contentPadding = PaddingValues(top = 34.dp, bottom = 28.dp, start = 18.dp, end = 18.dp)) {
            item {
                val snap = snapshot
                val status = DisplayDataState.resolve(
                    sensorPresent = sensorPresent() || snap != null,
                    currentTimestampMillis = snap?.timeMillis ?: 0L,
                    latestHistoryTimestampMillis = 0L,
                    nowMillis = now,
                )
                if (snap != null && status.hasData) {
                    HeroCard(snap, status.isStale, now, onOpenChart)
                } else {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(32.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(if (status.kind == DisplayDataState.Kind.NO_SENSOR) R.string.no_sensor_title else R.string.nodata),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        Text(stringResource(R.string.novalue), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { MiniChartCard(isMmol = isMmol, refreshKey = now / 60_000L, timeFormat = timeFormat, onOpenChart = onOpenChart) }
            if (recent.isNotEmpty()) {
                item { WearSectionTitle(stringResource(R.string.readings)) }
                items(recent, key = { it.timestamp }) { point ->
                    ReadingRow(point, isMmol, onClick = onOpenReadings)
                }
            }
            item { SensorCard(snapshot?.sensorId, now, onOpenSensor) }
            item { WearNavigationRow(stringResource(R.string.calibration), onClick = onOpenCalibrations) }
            item { WearNavigationRow(stringResource(R.string.settings), onClick = onOpenSettings) }
        }
    }
}

@Composable
private fun SensorCard(sensorId: String?, now: Long, onOpenSensor: () -> Unit) {
    val id = sensorId
        ?: runCatching { Natives.lastsensorname() }.getOrNull().takeUnless { it.isNullOrEmpty() }
        ?: return
    val lifecycle = remember(id, now / 600_000L) { sensorLifecycleText(id, now) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpenSensor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(displaySensorName(id), style = MaterialTheme.typography.labelLarge)
            lifecycle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MiniChartCard(
    isMmol: Boolean,
    refreshKey: Long,
    timeFormat: java.text.DateFormat,
    onOpenChart: () -> Unit,
) {
    val data = remember(refreshKey, isMmol) { loadChart(3, isMmol) }
    if (data.points.isEmpty()) return
    val lineColor = data.points.lastOrNull()?.let { rangeColor(it.value, isMmol) }
        ?: Color(GlucoseRangeColors.inRange(true))
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val targetColor = Color(GlucoseRangeColors.inRange(true))
    val alarmColor = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        WearChart(
            data = data,
            lineColor = lineColor,
            targetColor = targetColor,
            alarmColor = alarmColor,
            gridColor = gridColor,
            labelColor = labelColor,
            selected = null,
            selectionColor = MaterialTheme.colorScheme.primary,
            formatTime = { timeFormat.format(Date(it)) },
            onSelect = {},
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
        )
        // WearChart's own tap handler would swallow clicks; this overlay makes
        // the whole card open the full chart instead.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenChart,
                ),
        )
    }
}

@Composable
private fun ReadingRow(point: GlucosePoint, isMmol: Boolean, onClick: () -> Unit) {
    val color = rangeColor(point.value, isMmol)
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }
    Row(
        Modifier
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
    snapshot: CurrentDisplaySource.Snapshot,
    stale: Boolean,
    now: Long,
    onOpenChart: () -> Unit,
) {
    val rangeColor = glucoseColor(snapshot)
    val points = remember(snapshot.timeMillis) {
        NotificationHistorySource.getDisplayHistory(snapshot.timeMillis - 35 * 60_000L, snapshot.isMmol, snapshot.sensorId)
    }
    val velocity = remember(points, snapshot.viewMode, snapshot.isMmol) {
        TrendAccess.calculateVelocity(points, snapshot.viewMode == 1 || snapshot.viewMode == 3, snapshot.isMmol)
            .takeIf { points.size >= 2 && it.isFinite() } ?: snapshot.rate
    }
    val autoLabel = stringResource(R.string.auto)
    val rawLabel = stringResource(R.string.raw)
    val calibratedLabel = stringResource(R.string.calibrated)
    val glucoseLabel = stringResource(R.string.glucose)
    val variants = remember(snapshot, autoLabel, rawLabel, calibratedLabel, glucoseLabel) {
        heroVariants(snapshot, autoLabel, rawLabel, calibratedLabel, glucoseLabel)
    }
    // Two values, the arrow, and a minutes-since counter. No labels, no units;
    // staleness reads as dimmed color.
    val primary = variants.first { it.primary }
    val secondary = variants.firstOrNull { !it.primary }
    val ageMin = ((now - snapshot.timeMillis) / 60_000L).coerceAtLeast(0L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(34.dp))
            .background(if (stale) MaterialTheme.colorScheme.surfaceContainer else rangeColor.copy(alpha = 0.20f))
            .clickable(onClick = onOpenChart)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                primary.value,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp, fontWeight = FontWeight.SemiBold),
                color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            )
            secondary?.let { variant ->
                Text(
                    variant.value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${ageMin}m",
                style = MaterialTheme.typography.labelMedium,
                color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TrendArrowCanvas(
                velocity = velocity,
                pulseKey = snapshot.timeMillis,
                modifier = Modifier.size(34.dp).padding(top = 2.dp),
                color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else rangeColor,
            )
        }
    }
}

package tk.glucodata.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.ManagedSensorStatusPolicy

private const val TICK_MS = 30_000L

// All value formatting comes from CurrentDisplaySource/DisplayValueResolver
// (shared with phone + notifications) — no unit math on the watch.
private fun currentSnapshot(): CurrentDisplaySource.Snapshot? =
    runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()

private fun sensorPresent(): Boolean = runCatching {
    Natives.activeSensors()?.isNotEmpty() == true
}.getOrDefault(false)

internal fun glucoseColor(snapshot: CurrentDisplaySource.Snapshot): Color {
    val fallback = GlucoseRangeColors.inRange(true)
    val argb = runCatching {
        GlucoseRangeColors.colorForValue(
            snapshot.primaryValue,
            Natives.targetlow(),
            Natives.targethigh(),
            Natives.alarmverylow(),
            Natives.alarmveryhigh(),
            fallback,
            true,
            snapshot.isMmol,
        )
    }.getOrDefault(fallback)
    return Color(argb)
}

// Trend arrow derives from the shared xDrip trend-name mapping so the watch
// agrees with every other surface about what "rising" means.
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

// Managed drivers key native records with a canonical prefix ("SIBI:", …);
// the phone strips it for display and so do we. Display-only — never feed
// the stripped form back into lookups.
internal fun displaySensorName(id: String): String =
    id.replace(Regex("^[A-Z0-9]{2,6}:"), "")

// Lifecycle from the same native ui-snapshot the phone dashboard uses
// ([2]=startMs [3]=expectedEnd [4]=officialEnd). On the watch the record is
// whatever the mirror synced; when it carries no real end-times the shared
// policy would invent a default total, so show plain age instead of a
// fabricated "x / y".
private fun sensorLifecycleText(sensorId: String?, nowMs: Long): String? =
    runCatching {
        val name = sensorId ?: Natives.lastsensorname() ?: return null
        val snap = Natives.getSensorUiSnapshot(name) ?: return null
        if (snap.size < 5 || snap[2] <= 0L) return null
        val hasRealEnd = snap[3] > snap[2] || snap[4] > snap[2]
        if (hasRealEnd) {
            ManagedSensorStatusPolicy.resolveLifecycleSummary(
                startTimeMs = snap[2],
                officialEndMs = snap[4],
                expectedEndMs = snap[3],
                nowMs = nowMs,
            ).daysText.takeIf { it.isNotEmpty() }
        } else {
            val days = ((nowMs - snap[2]) / 86_400_000L).coerceAtLeast(0L)
            "${days}d"
        }
    }.getOrNull()

@Composable
fun MainScreen(
    onOpenChart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSensor: () -> Unit,
) {
    var snapshot by remember { mutableStateOf(currentSnapshot()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        launch {
            UiRefreshBus.revision.collect {
                snapshot = currentSnapshot()
                now = System.currentTimeMillis()
            }
        }
        while (true) {
            delay(TICK_MS)
            snapshot = currentSnapshot()
            now = System.currentTimeMillis()
        }
    }

    ScreenScaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val snap = snapshot
            val status = DisplayDataState.resolve(
                sensorPresent = sensorPresent() || snap != null,
                currentTimestampMillis = snap?.timeMillis ?: 0L,
                latestHistoryTimestampMillis = 0L,
                nowMillis = now,
            )

            if (snap != null && status.hasData) {
                val rangeColor = glucoseColor(snap)
                val stale = status.isStale
                // Hero value pill — the phone dashboard card, wear-sized:
                // range-tinted container, big auto value · smaller raw, trend.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (stale) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            } else {
                                rangeColor.copy(alpha = 0.24f)
                            },
                            RoundedCornerShape(30.dp),
                        )
                        .clickable(onClick = onOpenChart)
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = snap.primaryStr,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 50.sp),
                        color = if (stale) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                    snap.secondaryStr?.let { raw ->
                        Text(
                            text = " · $raw",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val arrow = trendArrow(snap.rate)
                    if (arrow.isNotEmpty() && !stale) {
                        Text(
                            text = arrow,
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        snap.timeMillis, now, DateUtils.MINUTE_IN_MILLIS,
                    ).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // Sensor chip — serial + day progress, phone's status card in
                // one line; tap for details.
                val sensorId = snap.sensorId
                val lifecycleText = remember(sensorId, now / 600_000L) {
                    sensorLifecycleText(sensorId, now)
                }
                if (sensorId != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(14.dp),
                            )
                            .clickable(onClick = onOpenSensor)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Spacer(
                            Modifier
                                .size(6.dp)
                                .background(
                                    if (stale) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                    CircleShape,
                                ),
                        )
                        Text(
                            text = displaySensorName(sensorId),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                        lifecycleText?.let { days ->
                            Text(
                                text = "  $days",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(
                        if (status.kind == DisplayDataState.Kind.NO_SENSOR) {
                            R.string.no_sensor_title
                        } else {
                            R.string.nodata
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onOpenChart, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.ShowChart,
                        contentDescription = stringResource(R.string.historyname),
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            }
        }
    }
}

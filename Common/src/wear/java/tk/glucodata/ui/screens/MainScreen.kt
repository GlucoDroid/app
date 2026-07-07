package tk.glucodata.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.UiRefreshBus

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

@Composable
fun MainScreen(
    onOpenChart: () -> Unit,
    onOpenSettings: () -> Unit,
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
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = snap.primaryStr,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp),
                        color = if (status.isStale) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            glucoseColor(snap)
                        },
                    )
                    val arrow = trendArrow(snap.rate)
                    if (arrow.isNotEmpty() && !status.isStale) {
                        Text(
                            text = arrow,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 4.dp),
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
                )
                snap.secondaryStr?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

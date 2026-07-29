package tk.glucodata.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.VerticalPageIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.R
import tk.glucodata.UiRefreshBus

private const val PAGE_TICK_MS = 30_000L

/**
 * The watch's home: one page per subject, swiped vertically.
 *
 * The chart used to be an item inside a vertically scrolling list, which meant
 * every drag had to be arbitrated between panning the curve and scrolling the
 * page — it never felt right in either direction. Paging vertically leaves the
 * horizontal axis entirely to the chart, so panning is just panning.
 */
@Composable
fun GlucosePages(
    onOpenSettings: () -> Unit,
    onOpenCalibrate: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    Box(Modifier.fillMaxSize()) {
        VerticalPager(state = pagerState) { page ->
            when (page) {
                0 -> ChartPage()
                1 -> RecentReadingsScreen()
                2 -> CalibrationScreen(onCalibrate = onOpenCalibrate)
                else -> SensorScreen(onCalibrate = onOpenCalibrate, onOpenSettings = onOpenSettings)
            }
        }
        VerticalPageIndicator(pagerState = pagerState)
    }
}

/** Full-screen chart with the current value floating over it. */
@Composable
private fun ChartPage() {
    var snapshot by remember { mutableStateOf(runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        launch {
            UiRefreshBus.revision.collect {
                snapshot = runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()
                now = System.currentTimeMillis()
            }
        }
        while (true) {
            delay(PAGE_TICK_MS)
            snapshot = runCatching { CurrentDisplaySource.resolveCurrent() }.getOrNull()
            now = System.currentTimeMillis()
        }
    }

    val isMmol = snapshot?.isMmol
        ?: remember { runCatching { Applic.unit == 1 }.getOrDefault(false) }
    val newest = remember(snapshot, now / 60_000L) {
        runCatching {
            NotificationHistorySource.getDisplayHistory(now - 3_600_000L, isMmol, snapshot?.sensorId)
                .maxByOrNull { it.timestamp }
        }.getOrNull()
    }
    val status = DisplayDataState.resolve(
        sensorPresent = runCatching { Natives.activeSensors()?.isNotEmpty() == true }.getOrDefault(false) ||
            snapshot != null || newest != null,
        currentTimestampMillis = newest?.timestamp ?: 0L,
        latestHistoryTimestampMillis = 0L,
        nowMillis = now,
    )

    ScreenScaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            InteractiveWearChartPanel(
                initialRangeIndex = 0,
                requestInitialFocus = true,
                headlineTopPadding = 58.dp,
                modifier = Modifier.fillMaxSize(),
            )
            if (newest != null && status.hasData) {
                HeroCard(
                    point = newest,
                    isMmol = isMmol,
                    stale = status.isStale,
                    sensorId = snapshot?.sensorId,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 34.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(
                            if (status.kind == DisplayDataState.Kind.NO_SENSOR) R.string.no_sensor_title
                            else R.string.nodata,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

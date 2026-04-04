@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.stats.StatsDateRange
import tk.glucodata.ui.stats.StatsDateRangePickerHeadline
import tk.glucodata.ui.stats.StatsRangeSelectorControl
import tk.glucodata.ui.stats.StatsTimeRange
import tk.glucodata.ui.stats.clampStatsDateRangeToAvailable
import tk.glucodata.ui.stats.pickerUtcDateMillisToLocalEnd
import tk.glucodata.ui.stats.pickerUtcDateMillisToLocalStart
import tk.glucodata.ui.stats.toPickerUtcDateMillis
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private data class HistoryDateSection(
    val date: LocalDate,
    val label: String,
    val points: List<GlucosePoint>
)

private fun List<GlucosePoint>.sliceByTimestampRange(startMillis: Long, endMillis: Long): List<GlucosePoint> {
    if (isEmpty()) return emptyList()
    val startIndex = binarySearchBy(startMillis) { it.timestamp }
        .let { if (it >= 0) it else (-it - 1).coerceAtLeast(0) }
    val endInsertionPoint = binarySearchBy(endMillis) { it.timestamp }
        .let { if (it >= 0) it + 1 else (-it - 1) }
        .coerceAtMost(size)
    if (startIndex >= endInsertionPoint) return emptyList()
    return subList(startIndex, endInsertionPoint)
}

private fun resolveHistoryActiveRange(
    selectedRange: StatsTimeRange?,
    customRange: StatsDateRange?,
    availableRange: StatsDateRange?
): StatsDateRange? {
    val boundedAvailableRange = availableRange ?: return customRange
    return when {
        selectedRange == null -> clampStatsDateRangeToAvailable(customRange, boundedAvailableRange)
        selectedRange == StatsTimeRange.DAY_ALL -> boundedAvailableRange
        else -> {
            val endMillis = boundedAvailableRange.endMillis
            val startMillis = endMillis - (selectedRange.days * 24L * 60L * 60L * 1000L) + 1L
            clampStatsDateRangeToAvailable(
                StatsDateRange(startMillis = startMillis, endMillis = endMillis),
                boundedAvailableRange
            )
        }
    }
}

private fun defaultViewportPoints(
    points: List<GlucosePoint>,
    selectedRange: TimeRange
): List<GlucosePoint> {
    if (points.isEmpty()) return emptyList()
    val endMillis = points.last().timestamp
    val startMillis = endMillis - (selectedRange.hours * 60L * 60L * 1000L)
    return points.sliceByTimestampRange(startMillis, endMillis)
}

private fun buildHistorySections(points: List<GlucosePoint>): List<HistoryDateSection> {
    if (points.isEmpty()) return emptyList()
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    val sections = ArrayList<HistoryDateSection>()
    var currentDate: LocalDate? = null
    var currentPoints = ArrayList<GlucosePoint>()

    fun flushSection() {
        val date = currentDate ?: return
        if (currentPoints.isEmpty()) return
        sections.add(
            HistoryDateSection(
                date = date,
                label = formatter.format(Date(currentPoints.first().timestamp)),
                points = currentPoints.toList()
            )
        )
    }

    for (point in points.sortedByDescending { it.timestamp }) {
        val pointDate = Instant.ofEpochMilli(point.timestamp).atZone(zone).toLocalDate()
        if (currentDate == null || pointDate != currentDate) {
            flushSection()
            currentDate = pointDate
            currentPoints = ArrayList()
        }
        currentPoints.add(point)
    }
    flushSection()
    return sections
}

@Composable
fun HistoryBrowseScreen(
    glucoseHistory: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    sensorId: String,
    targetLow: Float,
    targetHigh: Float,
    graphSmoothingMinutes: Int,
    collapseSmoothedData: Boolean,
    previewWindowMode: Int,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity>,
    onBack: () -> Unit,
    onPointClick: ((GlucosePoint) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.sortedBy { it.timestamp } }
    val availableRange = remember(sortedHistory) {
        if (sortedHistory.isEmpty()) {
            null
        } else {
            StatsDateRange(
                startMillis = sortedHistory.first().timestamp,
                endMillis = sortedHistory.last().timestamp
            )
        }
    }

    var selectedHistoryRange by rememberSaveable { mutableStateOf<StatsTimeRange?>(StatsTimeRange.DAY_30) }
    var customRangeStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var customRangeEndMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.H24) }
    var showDateRangePicker by rememberSaveable { mutableStateOf(false) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    var viewportSnapshot by remember { mutableStateOf<ChartViewportSnapshot?>(null) }

    val customRange = remember(customRangeStartMillis, customRangeEndMillis) {
        val startMillis = customRangeStartMillis
        val endMillis = customRangeEndMillis
        if (startMillis != null && endMillis != null) {
            StatsDateRange(startMillis = startMillis, endMillis = endMillis)
        } else {
            null
        }
    }
    val activeRange = remember(selectedHistoryRange, customRange, availableRange) {
        resolveHistoryActiveRange(selectedHistoryRange, customRange, availableRange)
    }
    val activeHistory = remember(sortedHistory, activeRange) {
        activeRange?.let { sortedHistory.sliceByTimestampRange(it.startMillis, it.endMillis) } ?: sortedHistory
    }
    val initialViewportPoints = remember(activeHistory, selectedChartRange) {
        defaultViewportPoints(activeHistory, selectedChartRange)
    }
    val viewportPoints = viewportSnapshot?.visiblePoints ?: initialViewportPoints
    val visibleSections = remember(viewportPoints) { buildHistorySections(viewportPoints) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val result = tk.glucodata.data.HistoryExporter.importFromCsv(context, uri)
                if (result.success) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.imported_readings_count, result.successCount),
                        Toast.LENGTH_LONG
                    ).show()
                    UiRefreshBus.requestDataRefresh()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed_with_error, result.errorMessage ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.historyname),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showExportSheet = true }) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = stringResource(R.string.export_data)
                        )
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("text/csv", "text/plain", "*/*")) }) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = stringResource(R.string.import_data)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (sortedHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_data_available),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "history-range-selector") {
                Box(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
                    StatsRangeSelectorControl(
                        selectedRange = selectedHistoryRange,
                        activeRange = activeRange,
                        isLoading = false,
                        hasData = activeHistory.isNotEmpty(),
                        readingCount = activeHistory.size,
                        countLabelResId = R.string.readings,
                        onRangeSelected = { range ->
                            selectedHistoryRange = range
                            viewportSnapshot = null
                        },
                        onCustomRangeClick = { showDateRangePicker = true }
                    )
                }
            }

            item(key = "history-chart") {
                Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
                    DashboardChartSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                        glucoseHistory = activeHistory,
                        graphSmoothingMinutes = graphSmoothingMinutes,
                        collapseSmoothedData = collapseSmoothedData,
                        previewWindowMode = previewWindowMode,
                        targetLow = targetLow,
                        targetHigh = targetHigh,
                        unit = unit,
                        viewMode = viewMode,
                        calibrations = calibrations,
                        onTimeRangeSelected = { selectedChartRange = it },
                        selectedTimeRange = selectedChartRange,
                        isExpanded = false,
                        expandedProgress = 0f,
                        onToggleExpanded = null,
                        onPointClick = onPointClick,
                        onCalibrationClick = null,
                        onViewportSnapshotChanged = { viewportSnapshot = it }
                    )
                }
            }

            if (visibleSections.isEmpty()) {
                item(key = "history-empty-window") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_data_available),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item(key = "history-list-gap") {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                visibleSections.forEachIndexed { sectionIndex, section ->
                    item(key = "history-date-${section.date.toEpochDay()}") {
                        HistoryDateMarker(
                            label = section.label,
                            modifier = Modifier
                                .padding(start = 32.dp, top = if (sectionIndex == 0) 0.dp else 12.dp, end = 16.dp, bottom = 8.dp)
                                .animateItem()
                        )
                    }

                    itemsIndexed(
                        items = section.points,
                        key = { _, item -> item.timestamp }
                    ) { index, item ->
                        ReadingRow(
                            point = item,
                            unit = unit,
                            viewMode = viewMode,
                            index = index,
                            totalCount = section.points.size,
                            history = section.points,
                            sensorId = sensorId,
                            calibrations = calibrations,
                            highlightLeadRow = false,
                            isGroupStart = index == 0,
                            isGroupEnd = index == section.points.lastIndex,
                            dividerHorizontalInset = 0.dp,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .animateItem()
                                .clickable(enabled = onPointClick != null) {
                                    onPointClick?.invoke(item)
                                }
                        )
                    }
                }
            }
        }
    }

    if (showDateRangePicker) {
        val initialRange = clampStatsDateRangeToAvailable(activeRange, availableRange) ?: availableRange
        val availableStartDateMillis = availableRange?.startMillis?.let(::toPickerUtcDateMillis)
        val availableEndDateMillis = availableRange?.endMillis?.let(::toPickerUtcDateMillis)
        val dateRangePickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = initialRange?.startMillis?.let(::toPickerUtcDateMillis),
            initialSelectedEndDateMillis = initialRange?.endMillis?.let(::toPickerUtcDateMillis),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val earliest = availableStartDateMillis ?: 0L
                    val latest = availableEndDateMillis ?: toPickerUtcDateMillis(System.currentTimeMillis())
                    return utcTimeMillis in earliest..latest
                }
            }
        )
        val canSaveRange =
            dateRangePickerState.selectedStartDateMillis != null &&
                dateRangePickerState.selectedEndDateMillis != null

        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = dateRangePickerState.selectedStartDateMillis
                            ?.let { pickerUtcDateMillisToLocalStart(it) }
                            ?: return@TextButton
                        val end = dateRangePickerState.selectedEndDateMillis
                            ?.let { pickerUtcDateMillisToLocalEnd(it) }
                            ?: return@TextButton
                        customRangeStartMillis = start
                        customRangeEndMillis = end
                        selectedHistoryRange = null
                        viewportSnapshot = null
                        showDateRangePicker = false
                    },
                    enabled = canSaveRange
                ) {
                    Text(text = stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.height(448.dp),
                title = {},
                headline = {
                    StatsDateRangePickerHeadline(dateRangePickerState)
                },
                showModeToggle = true
            )
        }
    }

    if (showExportSheet) {
        HistoryExportSheet(
            onDismiss = { showExportSheet = false },
            sheetState = rememberModalBottomSheetState()
        )
    }
}

@Composable
private fun HistoryDateMarker(
    label: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

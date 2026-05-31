@file:OptIn(ExperimentalMaterial3Api::class)

package tk.glucodata.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tk.glucodata.R
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.journal.buildJournalChartMarkers
import tk.glucodata.ui.journal.buildActiveInsulinSummary
import tk.glucodata.ui.journal.journalTypeColor
import tk.glucodata.ui.journal.journalTypeSelectedContainerColor
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.GlucoseFormatter
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class HistoryDateSection(
    val date: LocalDate,
    val label: String,
    val items: List<TimelineRowItem>
)

private data class TimelineRowItem(
    val timestamp: Long,
    val point: GlucosePoint?,
    val journalEntries: List<JournalEntry>
)

private data class TimelineJournalGrouping(
    val entriesByPointTimestamp: Map<Long, List<JournalEntry>>,
    val journalOnlyEntriesByTimestamp: Map<Long, List<JournalEntry>>
)

enum class TimelineBrowseMode {
    HISTORY,
    JOURNAL
}

private sealed class HistoryFilterOption {
    object Readings : HistoryFilterOption()
    data class Journal(val type: JournalEntryType) : HistoryFilterOption()
}

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

private fun buildHistorySections(items: List<TimelineRowItem>): List<HistoryDateSection> {
    if (items.isEmpty()) return emptyList()
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    val sections = ArrayList<HistoryDateSection>()
    var currentDate: LocalDate? = null
    var currentItems = ArrayList<TimelineRowItem>()

    fun flushSection() {
        val date = currentDate ?: return
        if (currentItems.isEmpty()) return
        sections.add(
            HistoryDateSection(
                date = date,
                label = formatter.format(Date(currentItems.first().timestamp)),
                items = currentItems.toList()
            )
        )
    }

    for (item in items.sortedByDescending { it.timestamp }) {
        val itemDate = Instant.ofEpochMilli(item.timestamp).atZone(zone).toLocalDate()
        if (currentDate == null || itemDate != currentDate) {
            flushSection()
            currentDate = itemDate
            currentItems = ArrayList()
        }
        currentItems.add(item)
    }
    flushSection()
    return sections
}

private fun resolveAvailableTimelineRange(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>
): StatsDateRange? {
    val startMillis = listOfNotNull(
        points.firstOrNull()?.timestamp,
        entries.minOfOrNull { it.timestamp }
    ).minOrNull() ?: return null
    val endMillis = listOfNotNull(
        points.lastOrNull()?.timestamp,
        entries.maxOfOrNull { it.timestamp }
    ).maxOrNull() ?: return null
    return StatsDateRange(startMillis = startMillis, endMillis = endMillis)
}

fun groupJournalEntriesByReading(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>,
    maxDistanceMillis: Long = 20L * 60L * 1000L
): Map<Long, List<JournalEntry>> {
    if (points.isEmpty() || entries.isEmpty()) return emptyMap()
    val sortedPoints = points.sortedBy { it.timestamp }
    val grouped = linkedMapOf<Long, MutableList<JournalEntry>>()

    entries.forEach { entry ->
        val insertionIndex = sortedPoints.binarySearchBy(entry.timestamp) { it.timestamp }
            .let { if (it >= 0) it else (-it - 1) }
            .coerceIn(0, sortedPoints.lastIndex)

        var closestPoint: GlucosePoint? = null
        var closestDistance = Long.MAX_VALUE

        for (candidateIndex in max(0, insertionIndex - 1)..min(sortedPoints.lastIndex, insertionIndex + 1)) {
            val candidate = sortedPoints[candidateIndex]
            val distance = abs(candidate.timestamp - entry.timestamp)
            if (distance < closestDistance) {
                closestPoint = candidate
                closestDistance = distance
            }
        }

        val targetPoint = closestPoint ?: return@forEach
        if (closestDistance <= maxDistanceMillis) {
            grouped.getOrPut(targetPoint.timestamp) { mutableListOf() }.add(entry)
        }
    }

    return grouped.mapValues { (_, groupedEntries) ->
        groupedEntries.sortedByDescending { it.timestamp }
    }
}

private fun groupJournalEntriesForTimeline(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>,
    maxDistanceMillis: Long = 20L * 60L * 1000L
): TimelineJournalGrouping {
    if (entries.isEmpty()) {
        return TimelineJournalGrouping(
            entriesByPointTimestamp = emptyMap(),
            journalOnlyEntriesByTimestamp = emptyMap()
        )
    }
    if (points.isEmpty()) {
        return TimelineJournalGrouping(
            entriesByPointTimestamp = emptyMap(),
            journalOnlyEntriesByTimestamp = entries
                .groupBy { it.timestamp }
                .mapValues { (_, groupedEntries) -> groupedEntries.sortedByDescending { it.timestamp } }
        )
    }

    val sortedPoints = points.sortedBy { it.timestamp }
    val entriesByPoint = linkedMapOf<Long, MutableList<JournalEntry>>()
    val journalOnly = linkedMapOf<Long, MutableList<JournalEntry>>()

    entries.forEach { entry ->
        val insertionIndex = sortedPoints.binarySearchBy(entry.timestamp) { it.timestamp }
            .let { if (it >= 0) it else (-it - 1) }
            .coerceIn(0, sortedPoints.lastIndex)

        var closestPoint: GlucosePoint? = null
        var closestDistance = Long.MAX_VALUE

        for (candidateIndex in max(0, insertionIndex - 1)..min(sortedPoints.lastIndex, insertionIndex + 1)) {
            val candidate = sortedPoints[candidateIndex]
            val distance = abs(candidate.timestamp - entry.timestamp)
            if (distance < closestDistance) {
                closestPoint = candidate
                closestDistance = distance
            }
        }

        val targetPoint = closestPoint
        if (targetPoint != null && closestDistance <= maxDistanceMillis) {
            entriesByPoint.getOrPut(targetPoint.timestamp) { mutableListOf() }.add(entry)
        } else {
            journalOnly.getOrPut(entry.timestamp) { mutableListOf() }.add(entry)
        }
    }

    return TimelineJournalGrouping(
        entriesByPointTimestamp = entriesByPoint.mapValues { (_, groupedEntries) ->
            groupedEntries.sortedByDescending { it.timestamp }
        },
        journalOnlyEntriesByTimestamp = journalOnly.mapValues { (_, groupedEntries) ->
            groupedEntries.sortedByDescending { it.timestamp }
        }
    )
}

private fun buildTimelineRows(
    points: List<GlucosePoint>,
    entries: List<JournalEntry>,
    browseMode: TimelineBrowseMode
): List<TimelineRowItem> {
    val grouping = groupJournalEntriesForTimeline(points, entries)
    val pointRows = points.mapNotNull { point ->
        val rowEntries = grouping.entriesByPointTimestamp[point.timestamp].orEmpty()
        when (browseMode) {
            TimelineBrowseMode.HISTORY -> TimelineRowItem(
                timestamp = point.timestamp,
                point = point,
                journalEntries = rowEntries
            )

            TimelineBrowseMode.JOURNAL -> rowEntries.takeIf { it.isNotEmpty() }?.let {
                TimelineRowItem(
                    timestamp = point.timestamp,
                    point = point,
                    journalEntries = it
                )
            }
        }
    }
    val journalOnlyRows = grouping.journalOnlyEntriesByTimestamp.map { (timestamp, groupedEntries) ->
        TimelineRowItem(
            timestamp = timestamp,
            point = null,
            journalEntries = groupedEntries
        )
    }
    return (pointRows + journalOnlyRows).sortedByDescending { it.timestamp }
}

private fun JournalEntryType.historyFilterIcon(): ImageVector = when (this) {
    JournalEntryType.INSULIN -> Icons.Default.Vaccines
    JournalEntryType.CARBS -> Icons.Default.Restaurant
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.Default.Label
}

private fun HistoryFilterOption.historyFilterIcon(): ImageVector = when (this) {
    HistoryFilterOption.Readings -> Icons.Default.ShowChart
    is HistoryFilterOption.Journal -> type.historyFilterIcon()
}

@Composable
fun HistoryBrowseScreen(
    glucoseHistory: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    sensorId: String,
    graphLow: Float,
    graphHigh: Float,
    targetLow: Float,
    targetHigh: Float,
    graphSmoothingMinutes: Int,
    collapseSmoothedData: Boolean,
    previewWindowMode: Int,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity>,
    title: String,
    browseMode: TimelineBrowseMode = TimelineBrowseMode.HISTORY,
    journalEnabled: Boolean = false,
    journalEntries: List<JournalEntry> = emptyList(),
    journalInsulinPresets: List<JournalInsulinPreset> = emptyList(),
    journalFoods: List<JournalFood> = emptyList(),
    onBack: (() -> Unit)? = null,
    onPointClick: ((GlucosePoint) -> Unit)? = null,
    onDeleteReading: ((GlucosePoint) -> Unit)? = null,
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    onAddJournalEntry: ((Long, JournalEntryType?, Float?) -> Unit)? = null,
    showTransferActions: Boolean = true,
    onOpenJournalSettings: (() -> Unit)? = null,
    onOpenFoodLibrary: (() -> Unit)? = null,
    onOpenInsulinLibrary: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.sortedBy { it.timestamp } }
    val journalPresetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val journalFoodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    val availableRange = remember(sortedHistory, journalEntries) {
        resolveAvailableTimelineRange(sortedHistory, journalEntries)
    }

    var selectedHistoryRange by rememberSaveable(browseMode) {
        mutableStateOf<StatsTimeRange?>(
            if (browseMode == TimelineBrowseMode.JOURNAL) StatsTimeRange.DAY_7 else StatsTimeRange.DAY_30
        )
    }
    var customRangeStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var customRangeEndMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.H24) }
    var showDateRangePicker by rememberSaveable { mutableStateOf(false) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    var viewportSnapshot by remember { mutableStateOf<ChartViewportSnapshot?>(null) }
    val isTopLevelJournal = browseMode == TimelineBrowseMode.JOURNAL && journalEnabled && onBack == null
    var showReadingRows by rememberSaveable(browseMode) {
        mutableStateOf(browseMode != TimelineBrowseMode.JOURNAL)
    }
    var selectedJournalTypeFilters by rememberSaveable {
        mutableStateOf(JournalEntryType.entries.map { it.name })
    }
    val selectedJournalTypes = remember(selectedJournalTypeFilters) {
        selectedJournalTypeFilters.mapNotNull { name ->
            runCatching { JournalEntryType.valueOf(name) }.getOrNull()
        }
    }

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
    val activeJournalEntries = remember(journalEntries, activeRange) {
        activeRange?.let { range ->
            journalEntries.filter { entry -> entry.timestamp in range.startMillis..range.endMillis }
        } ?: journalEntries
    }
    val filteredJournalEntries = remember(activeJournalEntries, selectedJournalTypes, journalEnabled) {
        if (!journalEnabled) {
            activeJournalEntries
        } else {
            activeJournalEntries.filter { it.type in selectedJournalTypes }
        }
    }
    val filteredHistory = remember(activeHistory, showReadingRows, journalEnabled) {
        if (journalEnabled && !showReadingRows) {
            emptyList()
        } else {
            activeHistory
        }
    }
    val effectiveBrowseMode = if (journalEnabled && !showReadingRows) {
        TimelineBrowseMode.JOURNAL
    } else {
        browseMode
    }
    val viewportStart = viewportSnapshot?.startMillis ?: activeRange?.startMillis ?: availableRange?.startMillis
    val viewportEnd = viewportSnapshot?.endMillis ?: activeRange?.endMillis ?: availableRange?.endMillis
    val visibleTimelineRows = remember(filteredHistory, filteredJournalEntries, viewportStart, viewportEnd, effectiveBrowseMode) {
        val windowStart = viewportStart ?: Long.MIN_VALUE
        val windowEnd = viewportEnd ?: Long.MAX_VALUE
        val visibleHistory = filteredHistory.sliceByTimestampRange(windowStart, windowEnd)
        val visibleJournalEntries = filteredJournalEntries.filter { entry ->
            entry.timestamp in windowStart..windowEnd
        }
        buildTimelineRows(
            points = visibleHistory,
            entries = visibleJournalEntries,
            browseMode = effectiveBrowseMode
        )
    }
    val visibleSections = remember(visibleTimelineRows) { buildHistorySections(visibleTimelineRows) }
    val journalMarkers = remember(filteredJournalEntries, journalPresetsById, journalFoodsById, unit, activeHistory) {
        buildJournalChartMarkers(filteredJournalEntries, journalPresetsById, unit, activeHistory, journalFoodsById)
    }
    val journalEntriesById = remember(filteredJournalEntries) { filteredJournalEntries.associateBy { it.id } }

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
            if (!isTopLevelJournal) {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        onBack?.let { handleBack ->
                            IconButton(onClick = handleBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null
                                )
                            }
                        }
                    },
                    actions = {
                        if (showTransferActions) {
                            if (journalEnabled && onAddJournalEntry != null) {
                                IconButton(
                                    onClick = {
                                        onAddJournalEntry(
                                            viewportSnapshot?.selectedPoint?.timestamp
                                                ?: viewportEnd
                                                ?: System.currentTimeMillis(),
                                            selectedJournalTypes.singleOrNull(),
                                            viewportSnapshot?.selectedPoint?.value
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null
                                    )
                                }
                            }
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
                    }
                )
            }
        }
    ) { innerPadding ->
        if (!isTopLevelJournal && sortedHistory.isEmpty() && journalEntries.isEmpty()) {
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
                .padding(innerPadding)
                .then(if (isTopLevelJournal) Modifier.statusBarsPadding() else Modifier),
            contentPadding = PaddingValues(bottom = if (isTopLevelJournal) 100.dp else 24.dp)
        ) {
            if (isTopLevelJournal) {
                item(key = "journal-header") {
                    JournalHeaderBlock(
                        title = title,
                        onAddJournalEntry = if (onAddJournalEntry != null) {
                            {
                                onAddJournalEntry(
                                    viewportSnapshot?.selectedPoint?.timestamp
                                        ?: viewportEnd
                                        ?: System.currentTimeMillis(),
                                    selectedJournalTypes.singleOrNull(),
                                    viewportSnapshot?.selectedPoint?.value
                                )
                            }
                        } else {
                            null
                        },
                        onOpenFoodLibrary = onOpenFoodLibrary,
                        onOpenInsulinLibrary = onOpenInsulinLibrary,
                        onOpenJournalSettings = onOpenJournalSettings,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
                    )
                }
            }

            item(key = "history-range-selector") {
                Box(modifier = Modifier.padding(start = 16.dp, top = if (isTopLevelJournal) 4.dp else 16.dp, end = 16.dp)) {
                    StatsRangeSelectorControl(
                        selectedRange = selectedHistoryRange,
                        activeRange = activeRange,
                        isLoading = false,
                        hasData = visibleTimelineRows.isNotEmpty(),
                        readingCount = if (effectiveBrowseMode == TimelineBrowseMode.JOURNAL) {
                            visibleTimelineRows.size
                        } else {
                            filteredHistory.size
                        },
                        countLabelResId = if (effectiveBrowseMode == TimelineBrowseMode.JOURNAL) {
                            R.string.journal_visible_events
                        } else {
                            R.string.readings
                        },
                        onRangeSelected = { range ->
                            selectedHistoryRange = range
                            viewportSnapshot = null
                        },
                        onCustomRangeClick = { showDateRangePicker = true }
                    )
                }
            }

            if (isTopLevelJournal) {
                item(key = "journal-overview") {
                    JournalOverviewPanel(
                        entries = activeJournalEntries,
                        presetsById = journalPresetsById,
                        selectedTimestamp = viewportSnapshot?.selectedPoint?.timestamp
                            ?: viewportEnd
                            ?: System.currentTimeMillis(),
                        selectedDisplayGlucose = viewportSnapshot?.selectedPoint?.value,
                        unit = unit,
                        onAddJournalEntry = onAddJournalEntry,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
                    )
                }
            }

            if (activeHistory.isNotEmpty()) {
                item(key = "history-chart") {
                    Box(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
                        DashboardChartSection(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isTopLevelJournal) 340.dp else 420.dp),
                            glucoseHistory = activeHistory,
                            journalMarkers = journalMarkers,
                            graphSmoothingMinutes = graphSmoothingMinutes,
                            collapseSmoothedData = collapseSmoothedData,
                            previewWindowMode = previewWindowMode,
                            graphLow = graphLow,
                            graphHigh = graphHigh,
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
                            onJournalMarkerClick = { entryId ->
                                journalEntriesById[entryId]?.let { onJournalEntryClick?.invoke(it) }
                            },
                            onViewportSnapshotChanged = { viewportSnapshot = it }
                        )
                    }
                }
            }
            if (browseMode == TimelineBrowseMode.JOURNAL && journalEnabled && !isTopLevelJournal) {
                item(key = "journal-overview") {
                    JournalOverviewPanel(
                        entries = activeJournalEntries,
                        presetsById = journalPresetsById,
                        selectedTimestamp = viewportSnapshot?.selectedPoint?.timestamp
                            ?: viewportEnd
                            ?: System.currentTimeMillis(),
                        selectedDisplayGlucose = viewportSnapshot?.selectedPoint?.value,
                        unit = unit,
                        onAddJournalEntry = onAddJournalEntry,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
                    )
                }
            }
            if (journalEnabled) {
                item(key = "history-journal-filter") {
                    val filterOptions = remember {
                        listOf(HistoryFilterOption.Readings) +
                            JournalEntryType.entries.map { HistoryFilterOption.Journal(it) }
                    }
                    val selectedFilterOptions = remember(showReadingRows, selectedJournalTypes) {
                        buildList {
                            if (showReadingRows) add(HistoryFilterOption.Readings)
                            selectedJournalTypes.forEach { add(HistoryFilterOption.Journal(it)) }
                        }
                    }
                    val selectedFilterContentColor = MaterialTheme.colorScheme.onSurface
                    val selectedFilterContainerBase = MaterialTheme.colorScheme.surfaceContainerHigh
                    val readingsSelectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    val readingsSelectedIconColor = MaterialTheme.colorScheme.onSurface
                    val readingsUnselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ConnectedButtonGroup(
                        options = filterOptions,
                        selectedOptions = selectedFilterOptions,
                        multiSelect = true,
                        onOptionSelected = { filterOption ->
                            when (filterOption) {
                                HistoryFilterOption.Readings -> {
                                    showReadingRows = !showReadingRows
                                }
                                is HistoryFilterOption.Journal -> {
                                    val filterType = filterOption.type
                                    selectedJournalTypeFilters = if (filterType in selectedJournalTypes) {
                                        selectedJournalTypes.filterNot { it == filterType }.map { it.name }
                                    } else {
                                        (selectedJournalTypes + filterType).map { it.name }
                                    }
                                }
                            }
                            viewportSnapshot = null
                        },
                        label = { _ -> },
                        icon = { filterOption -> filterOption.historyFilterIcon() },
                        selectedContainerColorFor = { filterOption ->
                            when (filterOption) {
                                HistoryFilterOption.Readings -> readingsSelectedContainerColor
                                is HistoryFilterOption.Journal -> journalTypeSelectedContainerColor(
                                    filterOption.type,
                                    selectedFilterContainerBase
                                )
                            }
                        },
                        selectedContentColorFor = { selectedFilterContentColor },
                        iconTint = { filterOption, selected ->
                            when (filterOption) {
                                HistoryFilterOption.Readings -> if (selected) {
                                    readingsSelectedIconColor
                                } else {
                                    readingsUnselectedIconColor
                                }
                                is HistoryFilterOption.Journal -> journalTypeColor(filterOption.type)
                            }
                        },
                        modifier = Modifier
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                            .fillMaxWidth(),
                        itemHeight = 44.dp,
                        spacing = 3.dp,
                        unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = stringResource(
                                if (effectiveBrowseMode == TimelineBrowseMode.JOURNAL) {
                                    R.string.journal_empty
                                } else {
                                    R.string.no_data_available
                                }
                            ),
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
                        items = section.items,
                        key = { index, item ->
                            val journalKey = item.journalEntries.joinToString(separator = ",") { it.id.toString() }
                            "${item.timestamp}-${item.point?.timestamp ?: "journal"}-$journalKey-$index"
                        }
                    ) { index, item ->
                        val readingPoint = item.point
                        if (readingPoint != null) {
                            ReadingRow(
                                point = readingPoint,
                                unit = unit,
                                viewMode = viewMode,
                                index = index,
                                totalCount = section.items.size,
                                history = section.items.mapNotNull(TimelineRowItem::point),
                                sensorId = sensorId,
                                calibrations = calibrations,
                                highlightLeadRow = false,
                                journalEntries = item.journalEntries,
                                journalPresetsById = journalPresetsById,
                                journalChipExpanded = true,
                                onJournalEntryClick = onJournalEntryClick,
                                showLeadingAction = journalEnabled && onAddJournalEntry != null,
                                leadingActionEmphasis = 0.42f,
                                onLeadingActionClick = if (journalEnabled && onAddJournalEntry != null) {
                                    {
                                        onAddJournalEntry(
                                            readingPoint.timestamp,
                                            selectedJournalTypes.singleOrNull(),
                                            readingPoint.value
                                        )
                                    }
                                } else {
                                    null
                                },
                                isGroupStart = index == 0,
                                isGroupEnd = index == section.items.lastIndex,
                                dividerHorizontalInset = 0.dp,
                                onValueClick = { onPointClick?.invoke(readingPoint) },
                                onDeleteReading = onDeleteReading,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem()
                            )
                        } else {
                            JournalTimelineRow(
                                timestamp = item.timestamp,
                                unit = unit,
                                journalEntries = item.journalEntries,
                                journalPresetsById = journalPresetsById,
                                onJournalEntryClick = onJournalEntryClick,
                                onAddJournalEntry = if (journalEnabled && onAddJournalEntry != null) {
                                    {
                                        onAddJournalEntry(
                                            item.timestamp,
                                            selectedJournalTypes.singleOrNull(),
                                            null
                                        )
                                    }
                                } else {
                                    null
                                },
                                index = index,
                                totalCount = section.items.size,
                                dividerHorizontalInset = 0.dp,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateItem()
                            )
                        }
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

    if (showExportSheet && showTransferActions) {
        HistoryExportSheet(
            onDismiss = { showExportSheet = false },
            sheetState = rememberModalBottomSheetState()
        )
    }
}

@Composable
private fun JournalHeaderBlock(
    title: String,
    onAddJournalEntry: (() -> Unit)?,
    onOpenFoodLibrary: (() -> Unit)?,
    onOpenInsulinLibrary: (() -> Unit)?,
    onOpenJournalSettings: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            onAddJournalEntry?.let { add ->
                IconButton(
                    onClick = add,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.journal_quick_actions),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            onOpenFoodLibrary?.let { openFood ->
                IconButton(
                    onClick = openFood,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restaurant,
                        contentDescription = stringResource(R.string.journal_food_library),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            onOpenInsulinLibrary?.let { openInsulin ->
                IconButton(
                    onClick = openInsulin,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Vaccines,
                        contentDescription = stringResource(R.string.journal_insulin_library),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            onOpenJournalSettings?.let { openSettings ->
                IconButton(
                    onClick = openSettings,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.journal_manage_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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

@Composable
private fun JournalOverviewPanel(
    entries: List<JournalEntry>,
    presetsById: Map<Long, JournalInsulinPreset>,
    selectedTimestamp: Long,
    selectedDisplayGlucose: Float?,
    unit: String,
    onAddJournalEntry: ((Long, JournalEntryType?, Float?) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val nowMillis = remember(entries) { System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }
    val startOfDayMillis = remember(nowMillis, zone) {
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val todaysEntries = remember(entries, startOfDayMillis, nowMillis) {
        entries.filter { it.timestamp in startOfDayMillis..nowMillis }
    }
    val activeInsulin = remember(entries, presetsById, nowMillis) {
        buildActiveInsulinSummary(entries, presetsById, nowMillis)
    }
    val activeInsulinUnits = activeInsulin
        ?.let { it.totalUnits * (it.weightedActivityPercent / 100f) }
        ?.coerceAtLeast(0f)
        ?: 0f
    val carbsToday = todaysEntries
        .filter { it.type == JournalEntryType.CARBS }
        .sumOf { (it.amount ?: 0f).toDouble() }
        .toFloat()
    val insulinToday = todaysEntries
        .filter { it.type == JournalEntryType.INSULIN }
        .sumOf { (it.amount ?: 0f).toDouble() }
        .toFloat()
    val activityMinutesToday = todaysEntries
        .filter { it.type == JournalEntryType.ACTIVITY }
        .sumOf { (it.durationMinutes ?: 0).toInt() }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val selectedTimeLabel = remember(selectedTimestamp) {
        timeFormatter.format(Date(selectedTimestamp))
    }
    val selectedGlucoseLabel = remember(selectedDisplayGlucose, unit) {
        selectedDisplayGlucose
            ?.takeIf { it.isFinite() && it > 0.1f }
            ?.let { GlucoseFormatter.format(it, GlucoseFormatter.isMmol(unit)) }
    }
    val activeUntilFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val activeInsulinDetail = activeInsulin?.nextEndingAt?.let { endingAt ->
        stringResource(R.string.journal_active_insulin_until, activeUntilFormatter.format(Date(endingAt)))
    } ?: activeInsulin?.let {
        stringResource(R.string.journal_active_now_percent, it.weightedActivityPercent)
    } ?: stringResource(R.string.journal_no_active_insulin)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.journal_quick_actions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.journal_events_today, todaysEntries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        JournalSelectedTimeCard(
            timeLabel = selectedTimeLabel,
            glucoseLabel = selectedGlucoseLabel
        )
        if (onAddJournalEntry != null) {
            JournalQuickLogRow(
                selectedTimestamp = selectedTimestamp,
                selectedDisplayGlucose = selectedDisplayGlucose,
                onAddJournalEntry = onAddJournalEntry
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalMetricTile(
                title = stringResource(R.string.journal_active_insulin),
                value = "${formatJournalMetric(activeInsulinUnits)} U",
                detail = activeInsulinDetail,
                icon = Icons.Default.Vaccines,
                type = JournalEntryType.INSULIN,
                modifier = Modifier.weight(1f)
            )
            JournalMetricTile(
                title = stringResource(R.string.journal_metric_carbs_today),
                value = "${formatJournalMetric(carbsToday, wholeNumber = true)} g",
                detail = stringResource(R.string.journal_type_food),
                icon = Icons.Default.Restaurant,
                type = JournalEntryType.CARBS,
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalMetricTile(
                title = stringResource(R.string.journal_metric_insulin_today),
                value = "${formatJournalMetric(insulinToday)} U",
                detail = stringResource(R.string.journal_type_insulin),
                icon = Icons.Default.Vaccines,
                type = JournalEntryType.INSULIN,
                modifier = Modifier.weight(1f)
            )
            JournalMetricTile(
                title = stringResource(R.string.journal_metric_activity_today),
                value = stringResource(R.string.minutes_short_format, activityMinutesToday),
                detail = stringResource(R.string.journal_type_activity),
                icon = Icons.Default.DirectionsRun,
                type = JournalEntryType.ACTIVITY,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun JournalMetricTile(
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    type: JournalEntryType,
    modifier: Modifier = Modifier
) {
    val tint = journalTypeColor(type)
    Surface(
        modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(18.dp),
        color = journalTypeSelectedContainerColor(
            type = type,
            surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ).copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(12.dp),
                color = tint.copy(alpha = 0.18f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun JournalSelectedTimeCard(
    timeLabel: String,
    glucoseLabel: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.journal_selected_time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            glucoseLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun JournalQuickLogRow(
    selectedTimestamp: Long,
    selectedDisplayGlucose: Float?,
    onAddJournalEntry: (Long, JournalEntryType?, Float?) -> Unit
) {
    val actions = listOf(
        JournalEntryType.INSULIN,
        JournalEntryType.CARBS,
        JournalEntryType.FINGERSTICK,
        JournalEntryType.ACTIVITY,
        JournalEntryType.NOTE
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        actions.forEach { type ->
            val tint = journalTypeColor(type)
            val label = when (type) {
                JournalEntryType.INSULIN -> stringResource(R.string.journal_type_insulin)
                JournalEntryType.CARBS -> stringResource(R.string.journal_type_food)
                JournalEntryType.FINGERSTICK -> stringResource(R.string.journal_type_bg_short)
                JournalEntryType.ACTIVITY -> stringResource(R.string.journal_type_activity)
                JournalEntryType.NOTE -> stringResource(R.string.journal_type_note)
            }
            Surface(
                onClick = {
                    onAddJournalEntry(
                        selectedTimestamp,
                        type,
                        selectedDisplayGlucose.takeIf { type == JournalEntryType.FINGERSTICK }
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp),
                shape = RoundedCornerShape(16.dp),
                color = journalTypeSelectedContainerColor(
                    type = type,
                    surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                contentColor = tint
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = type.historyFilterIcon(),
                        contentDescription = label,
                        modifier = Modifier.size(18.dp),
                        tint = tint
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatJournalMetric(value: Float, wholeNumber: Boolean = false): String {
    val pattern = when {
        wholeNumber -> "%.0f"
        abs(value) >= 10f -> "%.0f"
        else -> "%.1f"
    }
    return String.format(Locale.getDefault(), pattern, value)
}

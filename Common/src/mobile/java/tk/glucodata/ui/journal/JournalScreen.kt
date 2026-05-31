@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.ChartViewportSnapshot
import tk.glucodata.ui.DashboardChartSection
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.JournalTimelineRow
import tk.glucodata.ui.TimeRange
import tk.glucodata.ui.stats.StatsDateRange
import tk.glucodata.ui.stats.StatsDateRangePickerHeadline
import tk.glucodata.ui.stats.StatsRangeSelectorControl
import tk.glucodata.ui.stats.StatsTimeRange
import tk.glucodata.ui.stats.clampStatsDateRangeToAvailable
import tk.glucodata.ui.stats.pickerUtcDateMillisToLocalEnd
import tk.glucodata.ui.stats.pickerUtcDateMillisToLocalStart
import tk.glucodata.ui.stats.toPickerUtcDateMillis
import tk.glucodata.ui.util.ConnectedButtonGroup
import tk.glucodata.ui.util.GlucoseFormatter
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private data class JournalLedgerItem(
    val timestamp: Long,
    val entries: List<JournalEntry>
)

private data class JournalDateSection(
    val date: LocalDate,
    val label: String,
    val items: List<JournalLedgerItem>
)

@Composable
fun JournalScreen(
    glucoseHistory: List<GlucosePoint>,
    unit: String,
    viewMode: Int,
    graphLow: Float,
    graphHigh: Float,
    targetLow: Float,
    targetHigh: Float,
    graphSmoothingMinutes: Int,
    collapseSmoothedData: Boolean,
    previewWindowMode: Int,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity>,
    journalEntries: List<JournalEntry>,
    journalInsulinPresets: List<JournalInsulinPreset>,
    journalFoods: List<JournalFood>,
    onPointClick: ((GlucosePoint) -> Unit)?,
    onJournalEntryClick: ((JournalEntry) -> Unit)?,
    onAddJournalEntry: (Long, JournalEntryType?, Float?) -> Unit,
    onOpenFoodLibrary: () -> Unit,
    onOpenInsulinLibrary: () -> Unit,
    onOpenJournalSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.sortedBy { it.timestamp } }
    val presetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val foodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    val availableRange = remember(sortedHistory, journalEntries) {
        resolveJournalAvailableRange(sortedHistory, journalEntries)
    }

    var selectedRange by rememberSaveable { mutableStateOf<StatsTimeRange?>(StatsTimeRange.DAY_7) }
    var customRangeStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var customRangeEndMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.H24) }
    var showDateRangePicker by rememberSaveable { mutableStateOf(false) }
    var viewportSnapshot by remember { mutableStateOf<ChartViewportSnapshot?>(null) }
    var selectedTypeFilters by rememberSaveable {
        mutableStateOf(JournalEntryType.entries.map { it.name })
    }

    val selectedTypes = remember(selectedTypeFilters) {
        selectedTypeFilters.mapNotNull { name ->
            runCatching { JournalEntryType.valueOf(name) }.getOrNull()
        }
    }
    val customRange = remember(customRangeStartMillis, customRangeEndMillis) {
        if (customRangeStartMillis != null && customRangeEndMillis != null) {
            StatsDateRange(customRangeStartMillis!!, customRangeEndMillis!!)
        } else {
            null
        }
    }
    val activeRange = remember(selectedRange, customRange, availableRange) {
        resolveJournalActiveRange(selectedRange, customRange, availableRange)
    }
    val activeHistory = remember(sortedHistory, activeRange) {
        activeRange?.let { sortedHistory.sliceByTimestampRange(it.startMillis, it.endMillis) } ?: sortedHistory
    }
    val activeEntries = remember(journalEntries, activeRange) {
        activeRange?.let { range ->
            journalEntries.filter { it.timestamp in range.startMillis..range.endMillis }
        } ?: journalEntries
    }
    val filteredEntries = remember(activeEntries, selectedTypes) {
        activeEntries.filter { it.type in selectedTypes }
    }
    val sections = remember(filteredEntries) { buildJournalSections(filteredEntries) }
    val markers = remember(filteredEntries, presetsById, foodsById, unit, activeHistory) {
        buildJournalChartMarkers(filteredEntries, presetsById, unit, activeHistory, foodsById)
    }
    val entriesById = remember(filteredEntries) { filteredEntries.associateBy { it.id } }
    val selectedTimestamp = viewportSnapshot?.selectedPoint?.timestamp
        ?: activeRange?.endMillis
        ?: availableRange?.endMillis
        ?: System.currentTimeMillis()
    val selectedDisplayGlucose = viewportSnapshot?.selectedPoint?.value

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
        ) {
            item(key = "journal-title") {
                JournalHeader(
                    onAdd = { onAddJournalEntry(selectedTimestamp, selectedTypes.singleOrNull(), selectedDisplayGlucose) },
                    onOpenFoodLibrary = onOpenFoodLibrary,
                    onOpenInsulinLibrary = onOpenInsulinLibrary,
                    onOpenJournalSettings = onOpenJournalSettings
                )
            }

            item(key = "journal-range") {
                Spacer(modifier = Modifier.height(4.dp))
                StatsRangeSelectorControl(
                    selectedRange = selectedRange,
                    activeRange = activeRange,
                    isLoading = false,
                    hasData = filteredEntries.isNotEmpty(),
                    readingCount = filteredEntries.size,
                    countLabelResId = R.string.journal_visible_events,
                    onRangeSelected = { range ->
                        selectedRange = range
                        viewportSnapshot = null
                    },
                    onCustomRangeClick = { showDateRangePicker = true }
                )
            }

            item(key = "journal-actions") {
                Spacer(modifier = Modifier.height(16.dp))
                JournalActionPanel(
                    entries = journalEntries,
                    presetsById = presetsById,
                    selectedTimestamp = selectedTimestamp,
                    selectedDisplayGlucose = selectedDisplayGlucose,
                    unit = unit,
                    onAddJournalEntry = onAddJournalEntry
                )
            }

            if (activeHistory.isNotEmpty()) {
                item(key = "journal-chart") {
                    Spacer(modifier = Modifier.height(12.dp))
                    DashboardChartSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        glucoseHistory = activeHistory,
                        journalMarkers = markers,
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
                            entriesById[entryId]?.let { onJournalEntryClick?.invoke(it) }
                        },
                        onViewportSnapshotChanged = { viewportSnapshot = it }
                    )
                }
            }

            item(key = "journal-filter") {
                Spacer(modifier = Modifier.height(12.dp))
                JournalTypeFilter(
                    selectedTypes = selectedTypes,
                    onToggle = { type ->
                        selectedTypeFilters = if (type in selectedTypes) {
                            selectedTypes.filterNot { it == type }.map { it.name }
                        } else {
                            (selectedTypes + type).map { it.name }
                        }
                        viewportSnapshot = null
                    }
                )
            }

            if (sections.isEmpty()) {
                item(key = "journal-empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.journal_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item(key = "journal-ledger-gap") {
                    Spacer(modifier = Modifier.height(12.dp))
                }
                sections.forEachIndexed { sectionIndex, section ->
                    item(key = "journal-date-${section.date.toEpochDay()}") {
                        Text(
                            text = section.label,
                            modifier = Modifier.padding(start = 16.dp, top = if (sectionIndex == 0) 0.dp else 12.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    itemsIndexed(
                        items = section.items,
                        key = { index, item ->
                            "${item.timestamp}-${item.entries.joinToString(",") { it.id.toString() }}-$index"
                        }
                    ) { index, item ->
                        JournalTimelineRow(
                            timestamp = item.timestamp,
                            unit = unit,
                            journalEntries = item.entries,
                            journalPresetsById = presetsById,
                            onJournalEntryClick = onJournalEntryClick,
                            onAddJournalEntry = {
                                onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), null)
                            },
                            index = index,
                            totalCount = section.items.size,
                            dividerHorizontalInset = 0.dp,
                            modifier = Modifier.fillMaxWidth()
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
        val canSaveRange = dateRangePickerState.selectedStartDateMillis != null &&
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
                        selectedRange = null
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
}

@Composable
private fun JournalHeader(
    onAdd: () -> Unit,
    onOpenFoodLibrary: () -> Unit,
    onOpenInsulinLibrary: () -> Unit,
    onOpenJournalSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.journal_title),
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.journal_quick_actions),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onOpenFoodLibrary, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Restaurant,
                    contentDescription = stringResource(R.string.journal_food_library),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenInsulinLibrary, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Vaccines,
                    contentDescription = stringResource(R.string.journal_insulin_library),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenJournalSettings, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.journal_manage_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun JournalActionPanel(
    entries: List<JournalEntry>,
    presetsById: Map<Long, JournalInsulinPreset>,
    selectedTimestamp: Long,
    selectedDisplayGlucose: Float?,
    unit: String,
    onAddJournalEntry: (Long, JournalEntryType?, Float?) -> Unit
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
    val selectedTimeLabel = remember(selectedTimestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(selectedTimestamp))
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

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.padding(start = 1.dp)) {
            Text(
                text = stringResource(R.string.journal_quick_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.journal_events_today, todaysEntries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        JournalSelectedTimeCard(selectedTimeLabel, selectedGlucoseLabel)
        JournalQuickActionRow(
            selectedTimestamp = selectedTimestamp,
            selectedDisplayGlucose = selectedDisplayGlucose,
            onAddJournalEntry = onAddJournalEntry
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f))
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
    }
}

@Composable
private fun JournalSelectedTimeCard(
    timeLabel: String,
    glucoseLabel: String?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold
                )
            }
            glucoseLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun JournalQuickActionRow(
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        actions.forEach { type ->
            val tint = journalTypeColor(type)
            val label = type.label()
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
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                color = journalTypeSelectedContainerColor(type, MaterialTheme.colorScheme.surfaceContainerHighest),
                contentColor = tint
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = type.icon(),
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
        color = journalTypeSelectedContainerColor(type, MaterialTheme.colorScheme.surfaceContainerHighest).copy(alpha = 0.72f)
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
private fun JournalTypeFilter(
    selectedTypes: List<JournalEntryType>,
    onToggle: (JournalEntryType) -> Unit
) {
    val selectedContainerBase = MaterialTheme.colorScheme.surfaceContainerHigh
    val selectedContentColor = MaterialTheme.colorScheme.onSurface
    val unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ConnectedButtonGroup(
        options = JournalEntryType.entries,
        selectedOptions = selectedTypes,
        multiSelect = true,
        onOptionSelected = onToggle,
        label = { },
        icon = { it.icon() },
        iconOnly = true,
        modifier = Modifier.fillMaxWidth(),
        itemHeight = 44.dp,
        spacing = 3.dp,
        selectedContainerColorFor = { type ->
            journalTypeSelectedContainerColor(type, selectedContainerBase)
        },
        selectedContentColorFor = { selectedContentColor },
        iconTint = { type, _ -> journalTypeColor(type) },
        unselectedContainerColor = unselectedContainerColor,
        unselectedContentColor = unselectedContentColor
    )
}

@Composable
private fun JournalEntryType.label(): String = when (this) {
    JournalEntryType.INSULIN -> stringResource(R.string.journal_type_insulin)
    JournalEntryType.CARBS -> stringResource(R.string.journal_type_food)
    JournalEntryType.FINGERSTICK -> stringResource(R.string.journal_type_bg_short)
    JournalEntryType.ACTIVITY -> stringResource(R.string.journal_type_activity)
    JournalEntryType.NOTE -> stringResource(R.string.journal_type_note)
}

private fun JournalEntryType.icon(): ImageVector = when (this) {
    JournalEntryType.INSULIN -> Icons.Default.Vaccines
    JournalEntryType.CARBS -> Icons.Default.Restaurant
    JournalEntryType.FINGERSTICK -> Icons.Default.Bloodtype
    JournalEntryType.ACTIVITY -> Icons.Default.DirectionsRun
    JournalEntryType.NOTE -> Icons.AutoMirrored.Filled.Label
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

private fun resolveJournalAvailableRange(
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
    return StatsDateRange(startMillis, endMillis)
}

private fun resolveJournalActiveRange(
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
                StatsDateRange(startMillis, endMillis),
                boundedAvailableRange
            )
        }
    }
}

private fun buildJournalSections(entries: List<JournalEntry>): List<JournalDateSection> {
    if (entries.isEmpty()) return emptyList()
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { it.timestamp }
        .map { (timestamp, groupedEntries) ->
            JournalLedgerItem(
                timestamp = timestamp,
                entries = groupedEntries.sortedByDescending { it.timestamp }
            )
        }
        .sortedByDescending { it.timestamp }
        .fold(mutableListOf<JournalDateSectionBuilder>()) { sections, item ->
            val date = Instant.ofEpochMilli(item.timestamp).atZone(zone).toLocalDate()
            val section = sections.lastOrNull()?.takeIf { it.date == date }
                ?: JournalDateSectionBuilder(
                    date = date,
                    label = formatter.format(Date(item.timestamp))
                ).also(sections::add)
            section.items.add(item)
            sections
        }
        .map { builder ->
            JournalDateSection(
                date = builder.date,
                label = builder.label,
                items = builder.items.toList()
            )
        }
}

private class JournalDateSectionBuilder(
    val date: LocalDate,
    val label: String,
    val items: MutableList<JournalLedgerItem> = mutableListOf()
)

private fun formatJournalMetric(value: Float, wholeNumber: Boolean = false): String {
    val pattern = when {
        wholeNumber -> "%.0f"
        abs(value) >= 10f -> "%.0f"
        else -> "%.1f"
    }
    return String.format(Locale.getDefault(), pattern, value)
}

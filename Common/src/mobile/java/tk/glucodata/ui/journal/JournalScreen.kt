@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import tk.glucodata.ui.ReadingRow
import tk.glucodata.ui.TimeRange
import tk.glucodata.ui.util.ConnectedButtonGroup
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private data class JournalLedgerItem(
    val timestamp: Long,
    val entries: List<JournalEntry>,
    val point: GlucosePoint?
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
    sensorId: String?,
    onPointClick: ((GlucosePoint) -> Unit)?,
    onJournalEntryClick: ((JournalEntry) -> Unit)?,
    onAddJournalEntry: (Long, JournalEntryType?, Float?, Float?) -> Unit,
    onOpenFoodLibrary: () -> Unit,
    onOpenInsulinLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    useStatusBarsPadding: Boolean = true,
    bottomContentPadding: Dp = 104.dp
) {
    val view = LocalView.current
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.sortedBy { it.timestamp } }
    val presetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val foodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.H3) }
    var viewportSnapshot by remember { mutableStateOf<ChartViewportSnapshot?>(null) }
    var selectedTypeFilters by rememberSaveable {
        mutableStateOf(JournalEntryType.entries.map { it.name })
    }
    var chartActionTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    var chartActionDisplayValue by remember { mutableStateOf<Float?>(null) }
    var chartActionAmountFraction by remember { mutableStateOf<Float?>(null) }
    var fabExpanded by rememberSaveable { mutableStateOf(false) }

    val selectedTypes = remember(selectedTypeFilters) {
        selectedTypeFilters.mapNotNull { name ->
            runCatching { JournalEntryType.valueOf(name) }.getOrNull()
        }
    }
    val filteredEntries = remember(journalEntries, selectedTypes) {
        journalEntries.filter { it.type in selectedTypes }
    }
    val sections = remember(filteredEntries, sortedHistory) { buildJournalSections(filteredEntries, sortedHistory) }
    val markers = remember(filteredEntries, presetsById, foodsById, unit, sortedHistory) {
        buildJournalChartMarkers(filteredEntries, presetsById, unit, sortedHistory, foodsById)
    }
    val entriesById = remember(filteredEntries) { filteredEntries.associateBy { it.id } }
    val selectedTimestamp = viewportSnapshot?.selectedPoint?.timestamp
        ?: sortedHistory.lastOrNull()?.timestamp
        ?: journalEntries.maxOfOrNull { it.timestamp }
        ?: System.currentTimeMillis()
    val selectedDisplayGlucose = viewportSnapshot?.selectedPoint?.value

    fun clearChartAction() {
        chartActionTimestamp = null
        chartActionDisplayValue = null
        chartActionAmountFraction = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (useStatusBarsPadding) Modifier.statusBarsPadding() else Modifier)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = if (showTitle) 16.dp else 8.dp,
                bottom = bottomContentPadding
            )
        ) {
            if (showTitle) {
                item(key = "journal-title") {
                    JournalHeader(
                        onOpenFoodLibrary = onOpenFoodLibrary,
                        onOpenInsulinLibrary = onOpenInsulinLibrary
                    )
                }
            }

            item(key = "journal-metrics") {
                JournalMetricsPanel(
                    entries = journalEntries,
                    presetsById = presetsById
                )
            }

            if (sortedHistory.isNotEmpty()) {
                item(key = "journal-chart") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (showTitle) 324.dp else 348.dp)
                    ) {
                        DashboardChartSection(
                            modifier = Modifier.matchParentSize(),
                            glucoseHistory = sortedHistory,
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
                            onPointClick = {
                                clearChartAction()
                                onPointClick?.invoke(it)
                            },
                            onCalibrationClick = null,
                            onTimelineTap = { suggestion ->
                                if (chartActionTimestamp != null && !suggestion.forceMenu) {
                                    clearChartAction()
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                } else {
                                    chartActionTimestamp = suggestion.timestamp
                                    chartActionDisplayValue = suggestion.suggestedDisplayGlucose
                                    chartActionAmountFraction = suggestion.normalizedYFraction
                                    view.performHapticFeedback(
                                        if (suggestion.forceMenu) HapticFeedbackConstants.LONG_PRESS
                                        else HapticFeedbackConstants.CLOCK_TICK
                                    )
                                }
                            },
                            journalActionTimestamp = chartActionTimestamp,
                            journalActionDisplayValue = chartActionDisplayValue,
                            onDismissJournalAction = { clearChartAction() },
                            onJournalMarkerClick = { entryId ->
                                entriesById[entryId]?.let { onJournalEntryClick?.invoke(it) }
                            },
                            onViewportSnapshotChanged = { viewportSnapshot = it }
                        )

                        chartActionTimestamp?.let { actionTimestamp ->
                            JournalFloatingActionMenu(
                                visible = true,
                                selectedTimestamp = actionTimestamp,
                                viewportSnapshot = viewportSnapshot,
                                menuTopOffset = 40.dp,
                                menuItemSpacing = 6.dp,
                                menuYOffset = (-36).dp,
                                modifier = Modifier.matchParentSize(),
                                onTypeSelected = { type ->
                                    onAddJournalEntry(
                                        actionTimestamp,
                                        type,
                                        chartActionDisplayValue,
                                        chartActionAmountFraction
                                    )
                                    clearChartAction()
                                    fabExpanded = false
                                }
                            )
                        }
                    }
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
                        clearChartAction()
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
                sections.forEachIndexed { sectionIndex, section ->
                    item(key = "journal-date-${section.date.toEpochDay()}") {
                        Text(
                            text = section.label,
                            modifier = Modifier.padding(
                                start = 16.dp,
                                top = if (sectionIndex == 0) 12.dp else 18.dp,
                                bottom = 8.dp
                            ),
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
                        val point = item.point
                        if (point != null) {
                            val sectionPoints = section.items.mapNotNull(JournalLedgerItem::point)
                            val pointIndex = sectionPoints.indexOfFirst { it.timestamp == point.timestamp }
                                .takeIf { it >= 0 }
                                ?: index
                            ReadingRow(
                                point = point,
                                unit = unit,
                                viewMode = viewMode,
                                index = pointIndex,
                                totalCount = section.items.size,
                                history = sectionPoints,
                                sensorId = sensorId,
                                calibrations = calibrations,
                                journalEntries = item.entries,
                                journalPresetsById = presetsById,
                                journalFoodsById = foodsById,
                                journalChipExpanded = true,
                                onJournalEntryClick = onJournalEntryClick,
                                highlightLeadRow = false,
                                showLeadingAction = false,
                                onLeadingActionClick = {
                                    onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), point.value, null)
                                },
                                isGroupStart = index == 0,
                                isGroupEnd = index == section.items.lastIndex,
                                dividerHorizontalInset = 0.dp,
                                onValueClick = {
                                    onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), point.value, null)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            JournalTimelineRow(
                                timestamp = item.timestamp,
                                unit = unit,
                                journalEntries = item.entries,
                                journalPresetsById = presetsById,
                                journalFoodsById = foodsById,
                                onJournalEntryClick = onJournalEntryClick,
                                onAddJournalEntry = {
                                    onAddJournalEntry(item.timestamp, selectedTypes.singleOrNull(), null, null)
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

        JournalExpandableFab(
            expanded = fabExpanded,
            onExpandedChange = {
                fabExpanded = it
                if (it) clearChartAction()
            },
            onTypeSelected = { type ->
                onAddJournalEntry(
                    selectedTimestamp,
                    type,
                    selectedDisplayGlucose.takeIf { type == JournalEntryType.FINGERSTICK },
                    null
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
        )
    }
}

@Composable
private fun JournalHeader(
    onOpenFoodLibrary: () -> Unit,
    onOpenInsulinLibrary: () -> Unit
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
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenFoodLibrary, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Restaurant,
                    contentDescription = stringResource(R.string.journal_food_library),
                    tint = journalTypeColor(JournalEntryType.CARBS)
                )
            }
            IconButton(onClick = onOpenInsulinLibrary, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Default.Vaccines,
                    contentDescription = stringResource(R.string.journal_insulin_library),
                    tint = journalTypeColor(JournalEntryType.INSULIN)
                )
            }
        }
    }
}

@Composable
private fun JournalMetricsPanel(
    entries: List<JournalEntry>,
    presetsById: Map<Long, JournalInsulinPreset>
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
    val activeUntilFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val activeInsulinDetail = activeInsulin?.nextEndingAt?.let { endingAt ->
        stringResource(R.string.journal_active_insulin_until, activeUntilFormatter.format(Date(endingAt)))
    } ?: activeInsulin?.let {
        stringResource(R.string.journal_active_now_percent, it.weightedActivityPercent)
    } ?: stringResource(R.string.journal_no_active_insulin)
    val foodToday = todaysEntries
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalMetricCard(
                title = stringResource(R.string.journal_active_insulin),
                value = "${formatJournalMetric(activeInsulinUnits)} U",
                detail = activeInsulinDetail,
                icon = Icons.Default.Vaccines,
                type = JournalEntryType.INSULIN,
                modifier = Modifier.weight(1f)
            )
            JournalMetricCard(
                title = stringResource(R.string.journal_type_food),
                value = "${formatJournalMetric(foodToday, wholeNumber = true)} g",
                detail = stringResource(
                    R.string.journal_events_today,
                    todaysEntries.count { it.type == JournalEntryType.CARBS }
                ),
                icon = Icons.Default.Restaurant,
                type = JournalEntryType.CARBS,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalMetricCard(
                title = stringResource(R.string.journal_metric_insulin_today),
                value = "${formatJournalMetric(insulinToday)} U",
                detail = stringResource(R.string.journal_type_insulin),
                icon = Icons.Default.Vaccines,
                type = JournalEntryType.INSULIN,
                modifier = Modifier.weight(1f)
            )
            JournalMetricCard(
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
private fun JournalMetricCard(
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    type: JournalEntryType,
    modifier: Modifier = Modifier
) {
    val tint = journalTypeColor(type)
    Surface(
        modifier = modifier.heightIn(min = 74.dp),
        shape = RoundedCornerShape(18.dp),
        color = journalTypeSelectedContainerColor(
            type,
            MaterialTheme.colorScheme.surfaceContainerHighest
        ).copy(alpha = 0.68f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
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
        icon = { it.journalActionIcon() },
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

private fun buildJournalSections(
    entries: List<JournalEntry>,
    points: List<GlucosePoint>
): List<JournalDateSection> {
    if (entries.isEmpty()) return emptyList()
    val formatter = SimpleDateFormat("MMM d", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { it.timestamp }
        .map { (timestamp, groupedEntries) ->
            JournalLedgerItem(
                timestamp = timestamp,
                entries = groupedEntries.sortedByDescending { it.timestamp },
                point = findClosestPoint(points, timestamp)
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

private fun findClosestPoint(
    points: List<GlucosePoint>,
    timestamp: Long,
    maxDistanceMillis: Long = 20L * 60L * 1000L
): GlucosePoint? {
    if (points.isEmpty()) return null
    val insertionIndex = points.binarySearchBy(timestamp) { it.timestamp }
        .let { if (it >= 0) it else (-it - 1) }
        .coerceIn(0, points.lastIndex)
    var closestPoint: GlucosePoint? = null
    var closestDistance = Long.MAX_VALUE
    for (candidateIndex in maxOf(0, insertionIndex - 1)..minOf(points.lastIndex, insertionIndex + 1)) {
        val candidate = points[candidateIndex]
        val distance = kotlin.math.abs(candidate.timestamp - timestamp)
        if (distance < closestDistance) {
            closestPoint = candidate
            closestDistance = distance
        }
    }
    return closestPoint.takeIf { closestDistance <= maxDistanceMillis }
}

private fun formatJournalMetric(value: Float, wholeNumber: Boolean = false): String {
    val pattern = when {
        wholeNumber -> "%.0f"
        kotlin.math.abs(value) >= 10f -> "%.0f"
        else -> "%.1f"
    }
    return String.format(Locale.getDefault(), pattern, value)
}

private class JournalDateSectionBuilder(
    val date: LocalDate,
    val label: String,
    val items: MutableList<JournalLedgerItem> = mutableListOf()
)

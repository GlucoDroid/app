@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package tk.glucodata.ui.journal

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onAddJournalEntry: (Long, JournalEntryType?, Float?, Float?) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    useStatusBarsPadding: Boolean = true,
    bottomContentPadding: Dp = 104.dp
) {
    val view = LocalView.current
    val sortedHistory = remember(glucoseHistory) { glucoseHistory.sortedBy { it.timestamp } }
    val presetsById = remember(journalInsulinPresets) { journalInsulinPresets.associateBy { it.id } }
    val foodsById = remember(journalFoods) { journalFoods.associateBy { it.id } }
    var selectedChartRange by rememberSaveable { mutableStateOf(TimeRange.D3) }
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
    val sections = remember(filteredEntries) { buildJournalSections(filteredEntries) }
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
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showTitle) {
                item(key = "journal-title") {
                    JournalTitle()
                }
            }

            if (sortedHistory.isNotEmpty()) {
                item(key = "journal-chart") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (showTitle) 430.dp else 400.dp)
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
                                top = if (sectionIndex == 0) 2.dp else 14.dp,
                                bottom = 2.dp
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
                        JournalTimelineRow(
                            timestamp = item.timestamp,
                            unit = unit,
                            journalEntries = item.entries,
                            journalPresetsById = presetsById,
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
private fun JournalTitle() {
    Text(
        text = stringResource(R.string.journal_title),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.displaySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
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

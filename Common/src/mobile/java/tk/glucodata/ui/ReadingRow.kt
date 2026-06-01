@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tk.glucodata.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.delay
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalFood
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.ui.journal.JournalInlineChip

@Composable
fun ReadingRow(
    point: GlucosePoint,
    unit: String,
    viewMode: Int = 0,
    index: Int = 0,
    totalCount: Int = 1,
    history: List<GlucosePoint> = emptyList(), // Advanced Trend: Need history
    sensorId: String? = null,
    calibrations: List<tk.glucodata.data.calibration.CalibrationEntity> = emptyList(),
    journalEntries: List<JournalEntry> = emptyList(),
    journalPresetsById: Map<Long, JournalInsulinPreset> = emptyMap(),
    journalFoodsById: Map<Long, JournalFood> = emptyMap(),
    journalChipExpanded: Boolean = false,
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    highlightLeadRow: Boolean = true,
    showLeadingAction: Boolean = false,
    leadingActionEmphasis: Float = 1f,
    onLeadingActionClick: (() -> Unit)? = null,
    onValueClick: (() -> Unit)? = null,
    onDeleteReading: ((GlucosePoint) -> Unit)? = null,
    isGroupStart: Boolean = index == 0,
    isGroupEnd: Boolean = index == totalCount - 1,
    dividerHorizontalInset: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    // --- DYNAMIC COLOR LOGIC ---
    // User Request:
    // 1. Fresh (0-60s): Active Color -> Stock Color.
    // 2. Normal (60-90s): Stock Color.
    // 3. Stale (>90s): Stock Color -> Darker Shade.

    val isActive = highlightLeadRow && index == 0
    val view = LocalView.current
    var showDeleteDialog by rememberSaveable(point.timestamp, point.sensorSerial) { mutableStateOf(false) }
    val openDeleteDialog = {
        if (onDeleteReading != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showDeleteDialog = true
        }
    }

    val activeColor = MaterialTheme.colorScheme.secondaryContainer
    val stockColor = MaterialTheme.colorScheme.surfaceContainerLow
    val staleColor = MaterialTheme.colorScheme.surfaceContainerHighest // Slightly darker/different

    val ageState = remember(point.timestamp) { mutableStateOf(System.currentTimeMillis() - point.timestamp) }
    // Timer to update age for the active item
    if (isActive) {
        LaunchedEffect(point.timestamp) {
            while (true) {
                ageState.value = System.currentTimeMillis() - point.timestamp
                delay(1000) // Update every second
            }
        }
    }

    val containerColor = if (isActive) {
        val age = ageState.value
        when {
            age < 60_000 -> {
                // Phase 1: Fade to Stock
                val progress = age / 60_000f
                Color(
                    ColorUtils.blendARGB(
                        activeColor.toArgb(),
                        stockColor.toArgb(),
                        progress.coerceIn(0f, 1f)
                    )
                )
            }
            age < 90_000 -> {
                // Phase 2: Stock (Stable)
                stockColor
            }
            else -> {
                 // Phase 3: Stale (Darken slowly)
                 // "slowly start getting different darker shade"
                 // Let's cap the darkening at 100% after another 60s (just to have a bound)
                 val staleProgress = ((age - 90_000) / 60_000f).coerceIn(0f, 1f)
                 Color(
                    ColorUtils.blendARGB(
                        stockColor.toArgb(),
                        staleColor.toArgb(),
                        staleProgress
                    )
                )
            }
        }
    } else {
        // History: Always Stock
        stockColor
    }

    // Shape:
    // Active (Hero): "4dp bottom radius" to imply continuity. Top matches parent (16dp).
    // History: Rectangle (relies on parent clipping for its 4dp bottom).
    val shape = when {
        isActive && isGroupStart && isGroupEnd -> RoundedCornerShape(16.dp)
        isActive -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
        isGroupStart && isGroupEnd -> RoundedCornerShape(16.dp)
        isGroupStart -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        isGroupEnd -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RectangleShape
    }

    // Divider Alpha Logic
    val baseDividerAlpha = 0.1f // User Request: "perhaps make it 0.1f"
    val dividerAlpha = if (isActive) {
        val age = ageState.value
        when {
            age < 60_000 -> {
                // Fresh: Color is Distinct -> Stock. Divider: Hidden -> Visible.
                val progress = age / 60_000f
                baseDividerAlpha * progress.coerceIn(0f, 1f)
            }
            age < 90_000 -> {
                // Normal: Color is Stock. Divider: Visible.
                baseDividerAlpha
            }
            else -> {
                // Stale: Color is Stock -> Distinct. Divider: Visible -> Hidden.
                val staleProgress = ((age - 90_000) / 60_000f).coerceIn(0f, 1f)
                baseDividerAlpha * (1f - staleProgress)
            }
        }
    } else {
        baseDividerAlpha
    }

    // --- ADVANCED TREND ENGINE ---
    // Calculate on the fly using the passed history subset
    val trendResult = remember(history, index) {
        val relevantHistory = if (history.isNotEmpty()) history.drop(index) else listOf(point)
        val nativeList = relevantHistory.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
        tk.glucodata.logic.TrendEngine.calculateTrend(nativeList, useRaw = (viewMode == 1 || viewMode == 3), isMmol = tk.glucodata.ui.util.GlucoseFormatter.isMmol(unit))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    onDeleteReading != null && onValueClick != null -> Modifier.combinedClickable(
                        onClick = onValueClick,
                        onLongClick = { openDeleteDialog() },
                        hapticFeedbackEnabled = false
                    )
                    onDeleteReading != null -> Modifier.pointerInput(point.timestamp, point.sensorSerial) {
                        detectTapGestures(onLongPress = { openDeleteDialog() })
                    }
                    onValueClick != null -> Modifier.clickable(onClick = onValueClick)
                    else -> Modifier
                }
            ),
        shape = shape,
        color = containerColor,
        // User Request: Kill shadows (0dp)
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val hasInlineJournalEntries = journalEntries.isNotEmpty() && onJournalEntryClick != null
            val useClassicLayout = !hasInlineJournalEntries
            val rowMinHeight = if (journalChipExpanded && hasInlineJournalEntries) 64.dp else 48.dp
            val valueMinWidth = if (showLeadingAction || hasInlineJournalEntries) 104.dp else 124.dp
            val leadingActionSlotWidth = 44.dp
            val trendSlotWidth = 36.dp
            val timeStyle = MaterialTheme.typography.bodySmall
            val timeColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val timeWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            val isRawModeRR = viewMode == 1 || viewMode == 3
            val calibrationSensorId = sensorId?.takeIf { it.isNotBlank() }
            val hasCalibrationRR = !tk.glucodata.data.calibration.CalibrationManager.shouldOverwriteSensorValues() &&
                tk.glucodata.data.calibration.CalibrationManager.hasActiveCalibration(
                    isRawModeRR,
                    calibrationSensorId
                )
            val calibratedValueRR = if (hasCalibrationRR) {
                val baseValue = if (isRawModeRR) point.rawValue else point.value
                if (baseValue.isFinite() && baseValue > 0.1f) {
                    tk.glucodata.data.calibration.CalibrationManager.getCalibratedValue(
                        baseValue,
                        point.timestamp,
                        isRawModeRR,
                        sensorIdOverride = calibrationSensorId
                    )
                } else {
                    null
                }
            } else null
            val dvs = getDisplayValues(point, viewMode, unit, calibratedValueRR)
            val primaryColor = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            val secondaryColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            val unitColor = secondaryColor.copy(alpha = 0.6f)
            val tertiaryColor = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            val valueStyle = MaterialTheme.typography.titleMedium

            @Composable
            fun ReadingValueContent(modifier: Modifier = Modifier) {
                Row(
                    modifier = modifier,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tk.glucodata.data.calibration.CalibrationManager.hasCalibrationAt(point.timestamp, isRawModeRR)) {
                        Icon(
                            imageVector = Icons.Filled.WaterDrop,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = buildGlucoseString(dvs, primaryColor, secondaryColor, unitColor, true, "", tertiaryColor),
                        style = valueStyle.copy(fontFeatureSettings = "tnum")
                    )

                    Box(
                        modifier = Modifier.width(trendSlotWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        tk.glucodata.ui.components.TrendIndicator(
                            trendResult = trendResult,
                            color = tertiaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            @Composable
            fun JournalAddAffordance(modifier: Modifier = Modifier) {
                if (showLeadingAction && onLeadingActionClick != null) {
                    Surface(
                        onClick = onLeadingActionClick,
                        modifier = modifier
                            .size(24.dp)
                            .alpha(leadingActionEmphasis.coerceIn(0f, 1f)),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                            alpha = 0.38f + (0.18f * leadingActionEmphasis.coerceIn(0f, 1f))
                        ),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.54f + (0.34f * leadingActionEmphasis.coerceIn(0f, 1f))
                                )
                            )
                        }
                    }
                }
            }

            if (useClassicLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = java.text.SimpleDateFormat(
                            "HH:mm",
                            java.util.Locale.getDefault()
                        ).format(java.util.Date(point.timestamp)),
                        style = timeStyle,
                        fontWeight = timeWeight,
                        color = timeColor
                    )

                    if (showLeadingAction) {
                        Box(
                            modifier = Modifier.width(leadingActionSlotWidth),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            JournalAddAffordance()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minWidth = valueMinWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        ReadingValueContent(
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = rowMinHeight)
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = java.text.SimpleDateFormat(
                                "HH:mm",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(point.timestamp)),
                            style = timeStyle,
                            fontWeight = timeWeight,
                            color = timeColor
                        )

                        if (showLeadingAction) {
                            Box(
                                modifier = Modifier.width(leadingActionSlotWidth),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                JournalAddAffordance()
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(if (showLeadingAction) 16.dp else 12.dp))

                    if (hasInlineJournalEntries) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = rowMinHeight)
                                .padding(
                                    top = if (journalChipExpanded) 8.dp else 0.dp,
                                    end = 12.dp,
                                    bottom = if (journalChipExpanded) 8.dp else 0.dp
                                ),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                journalEntries.forEach { entry ->
                                    JournalInlineChip(
                                        entry = entry,
                                        unit = unit,
                                        insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                        food = entry.foodId?.let(journalFoodsById::get),
                                        expanded = journalChipExpanded,
                                        onClick = { onJournalEntryClick?.invoke(entry) }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = rowMinHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            ReadingValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }

                    if (hasInlineJournalEntries) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = valueMinWidth, minHeight = rowMinHeight),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            ReadingValueContent(
                                modifier = Modifier.padding(start = 12.dp, end = 16.dp)
                            )
                        }
                    }
                }
            }

            if (index < totalCount - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = dividerHorizontalInset),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha),
                    thickness = 1.dp
                )
            }
        }
    }

    if (showDeleteDialog && onDeleteReading != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = stringResource(R.string.delete_reading_title)) },
            text = { Text(text = stringResource(R.string.delete_reading_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteReading(point)
                        showDeleteDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun JournalTimelineRow(
    timestamp: Long,
    unit: String,
    journalEntries: List<JournalEntry>,
    journalPresetsById: Map<Long, JournalInsulinPreset> = emptyMap(),
    journalFoodsById: Map<Long, JournalFood> = emptyMap(),
    onJournalEntryClick: ((JournalEntry) -> Unit)? = null,
    onAddJournalEntry: (() -> Unit)? = null,
    index: Int = 0,
    totalCount: Int = 1,
    dividerHorizontalInset: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val rowMinHeight = 58.dp
    val timeSlotWidth = 58.dp
    val valueSlotWidth = 112.dp
    val shape = when {
        totalCount <= 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        index == totalCount - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RectangleShape
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onAddJournalEntry != null) {
                    Modifier.clickable {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onAddJournalEntry()
                    }
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowMinHeight)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(timeSlotWidth)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = rowMinHeight)
                        .padding(top = 8.dp, end = 12.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        journalEntries.forEach { entry ->
                            JournalInlineChip(
                                entry = entry,
                                unit = unit,
                                insulinPreset = entry.insulinPresetId?.let(journalPresetsById::get),
                                food = entry.foodId?.let(journalFoodsById::get),
                                expanded = true,
                                onClick = { onJournalEntryClick?.invoke(entry) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(valueSlotWidth))
            }

            if (index < totalCount - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = dividerHorizontalInset),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
                    thickness = 1.dp
                )
            }
        }
    }
}

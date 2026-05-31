package tk.glucodata.ui.calibration

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import tk.glucodata.Natives
import tk.glucodata.R
import tk.glucodata.data.calibration.CalibrationEntity
import tk.glucodata.data.calibration.CalibrationManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private data class CalibrationChartPoint(
    val x: Float,
    val y: Float
)

private enum class CalibrationModelRowStatus {
    ACTIVE,
    DISABLED,
    INCOMPLETE
}

private data class CalibrationModelRow(
    val calibration: CalibrationEntity,
    val sourceValue: Float,
    val secondaryValue: Float,
    val referenceValue: Float,
    val predictedValue: Float?,
    val residualValue: Float?,
    val status: CalibrationModelRowStatus
)

private data class CalibrationModelUiState(
    val rows: List<CalibrationModelRow>,
    val activeRows: List<CalibrationModelRow>,
    val fitLine: List<CalibrationChartPoint>,
    val meanAbsResidual: Float?,
    val maxAbsResidual: Float?,
    val sourceSpan: Float?,
    val validPointCount: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationModelTableScreen(
    navController: NavController,
    isMmol: Boolean,
    viewMode: Int = 0,
    onEdit: (CalibrationEntity) -> Unit
) {
    val isRawMode = viewMode == 1 || viewMode == 3
    val modeTitle = if (isRawMode) stringResource(R.string.raw) else stringResource(R.string.auto)
    val currentSensor = Natives.lastsensorname() ?: ""
    val allCalibrations by CalibrationManager
        .getCalibrationsFlow()
        .collectAsState(initial = CalibrationManager.getCachedCalibrations())
    val isEnabledForRaw by CalibrationManager.isEnabledForRaw.collectAsState()
    val isEnabledForAuto by CalibrationManager.isEnabledForAuto.collectAsState()
    val algorithmForRaw by CalibrationManager.algorithmForRaw.collectAsState()
    val algorithmForAuto by CalibrationManager.algorithmForAuto.collectAsState()
    val applyToPast by CalibrationManager.applyToPast.collectAsState()
    val lockPastHistory by CalibrationManager.lockPastHistory.collectAsState()
    val keepDisabledHistory by CalibrationManager.keepDisabledHistory.collectAsState()
    val weightMode by CalibrationManager.weightMode.collectAsState()

    val isCalibrationEnabled = if (isRawMode) isEnabledForRaw else isEnabledForAuto
    val selectedAlgorithm = if (isRawMode) algorithmForRaw else algorithmForAuto
    val calibrations = remember(allCalibrations, isRawMode, currentSensor) {
        allCalibrations
            .asSequence()
            .filter { it.isRawMode == isRawMode }
            .filter { it.sensorId == currentSensor || it.sensorId.isEmpty() }
            .sortedByDescending { it.timestamp }
            .toList()
    }
    val modelState = remember(
        calibrations,
        isRawMode,
        isCalibrationEnabled,
        selectedAlgorithm,
        currentSensor,
        applyToPast,
        lockPastHistory,
        keepDisabledHistory,
        weightMode
    ) {
        buildCalibrationModelUiState(
            calibrations = calibrations,
            isRawMode = isRawMode,
            isCalibrationEnabled = isCalibrationEnabled,
            sensorId = currentSensor
        )
    }
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.calibration_model_table_title),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.calibration_model_table_subtitle,
                                modeTitle,
                                selectedAlgorithm.title
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CalibrationModelChart(
                    modelState = modelState,
                    isCalibrationEnabled = isCalibrationEnabled,
                    isMmol = isMmol,
                    sourceLabel = modeTitle,
                    dateFormatter = dateFormatter,
                    onEdit = onEdit
                )
            }

            item {
                CalibrationModelSummaryCard(
                    algorithm = selectedAlgorithm,
                    modelState = modelState,
                    isCalibrationEnabled = isCalibrationEnabled,
                    isMmol = isMmol
                )
            }
        }
    }
}

private fun buildCalibrationModelUiState(
    calibrations: List<CalibrationEntity>,
    isRawMode: Boolean,
    isCalibrationEnabled: Boolean,
    sensorId: String
): CalibrationModelUiState {
    val rows = calibrations.map { calibration ->
        val sourceValue = if (isRawMode) calibration.sensorValueRaw else calibration.sensorValue
        val secondaryValue = if (isRawMode) calibration.sensorValue else calibration.sensorValueRaw
        val isValid = sourceValue.isFinite() && sourceValue > 0f &&
            calibration.userValue.isFinite() && calibration.userValue > 0f
        val participates = isCalibrationEnabled && calibration.isEnabled && isValid
        val predicted = if (isCalibrationEnabled && isValid) {
            CalibrationManager.getCalibratedValue(
                value = sourceValue,
                timestamp = calibration.timestamp,
                isRawMode = isRawMode,
                emitDiagnostics = false,
                sensorIdOverride = sensorId
            )
        } else {
            null
        }
        val status = when {
            !isValid -> CalibrationModelRowStatus.INCOMPLETE
            !participates -> CalibrationModelRowStatus.DISABLED
            else -> CalibrationModelRowStatus.ACTIVE
        }

        CalibrationModelRow(
            calibration = calibration,
            sourceValue = sourceValue,
            secondaryValue = secondaryValue,
            referenceValue = calibration.userValue,
            predictedValue = predicted,
            residualValue = predicted?.let { it - calibration.userValue },
            status = status
        )
    }

    val activeRows = rows.filter { it.status == CalibrationModelRowStatus.ACTIVE }
    val activeSourceValues = activeRows.map { it.sourceValue }
    val sourceMin = activeSourceValues.minOrNull()
    val sourceMax = activeSourceValues.maxOrNull()
    val sourceSpan = if (sourceMin != null && sourceMax != null && activeRows.size >= 2) {
        sourceMax - sourceMin
    } else {
        null
    }

    val fitLine = if (
        isCalibrationEnabled &&
        activeRows.size >= 2 &&
        sourceMin != null &&
        sourceMax != null &&
        sourceMax - sourceMin > 0.01f
    ) {
        val fitTimestamp = activeRows.maxOf { it.calibration.timestamp }
        (0..40).map { index ->
            val x = sourceMin + (sourceMax - sourceMin) * (index / 40f)
            CalibrationChartPoint(
                x = x,
                y = CalibrationManager.getCalibratedValue(
                    value = x,
                    timestamp = fitTimestamp,
                    isRawMode = isRawMode,
                    emitDiagnostics = false,
                    sensorIdOverride = sensorId
                )
            )
        }
    } else {
        emptyList()
    }

    val absResiduals = activeRows.mapNotNull { it.residualValue?.let(::abs) }
    return CalibrationModelUiState(
        rows = rows,
        activeRows = activeRows,
        fitLine = fitLine,
        meanAbsResidual = absResiduals.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        maxAbsResidual = absResiduals.maxOrNull(),
        sourceSpan = sourceSpan,
        validPointCount = rows.count { it.status != CalibrationModelRowStatus.INCOMPLETE }
    )
}

@Composable
private fun CalibrationModelSummaryCard(
    algorithm: CalibrationManager.CalibrationAlgorithm,
    modelState: CalibrationModelUiState,
    isCalibrationEnabled: Boolean,
    isMmol: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (isCalibrationEnabled) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = if (isCalibrationEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.calibration_model_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = algorithm.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = when {
                !isCalibrationEnabled -> stringResource(R.string.calibration_model_disabled_desc)
                modelState.activeRows.size == 1 -> stringResource(R.string.calibration_model_single_point)
                modelState.activeRows.size < 2 -> stringResource(R.string.calibration_model_not_enough_points)
                else -> stringResource(R.string.calibration_model_fit_range)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_active_points),
                    value = modelState.activeRows.size.toString(),
                    modifier = Modifier.weight(1f)
                )
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_disabled_points),
                    value = (modelState.validPointCount - modelState.activeRows.size).coerceAtLeast(0).toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_mean_error),
                    value = modelState.meanAbsResidual?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_max_error),
                    value = modelState.maxAbsResidual?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalibrationMetricTile(
                    label = stringResource(R.string.calibration_model_source_span),
                    value = modelState.sourceSpan?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CalibrationMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CalibrationModelChart(
    modelState: CalibrationModelUiState,
    isCalibrationEnabled: Boolean,
    isMmol: Boolean,
    sourceLabel: String,
    dateFormatter: SimpleDateFormat,
    onEdit: (CalibrationEntity) -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.tertiary
    val disabledColor = MaterialTheme.colorScheme.outline
    val fitColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val axisPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.RIGHT
        }
    }
    val chartRows = modelState.rows.filter {
        it.sourceValue.isFinite() && it.sourceValue > 0f &&
            it.referenceValue.isFinite() && it.referenceValue > 0f
    }
    val chartIdentity = remember(chartRows) {
        chartRows.joinToString(separator = "|") { it.calibration.id.toString() }
    }
    var selectedCalibrationId by rememberSaveable(chartIdentity) {
        mutableIntStateOf(
            modelState.activeRows.maxByOrNull { it.calibration.timestamp }?.calibration?.id
                ?: chartRows.firstOrNull()?.calibration?.id
                ?: -1
        )
    }
    var zoom by rememberSaveable(chartIdentity) { mutableFloatStateOf(1f) }
    var panFraction by rememberSaveable(chartIdentity) { mutableFloatStateOf(0.5f) }
    val selectedRow = chartRows.firstOrNull { it.calibration.id == selectedCalibrationId }
    val xValues = chartRows.map { it.sourceValue } + modelState.fitLine.map { it.x }
    val yValues = chartRows.flatMap { row ->
        listOfNotNull(row.referenceValue, row.predictedValue)
    } + modelState.fitLine.map { it.y }
    val hasChartData = xValues.isNotEmpty() && yValues.isNotEmpty()
    val minSpan = if (isMmol) 0.6f else 12f
    val xMinRaw = xValues.minOrNull() ?: 0f
    val xMaxRaw = xValues.maxOrNull() ?: 1f
    val yMinRaw = yValues.minOrNull() ?: 0f
    val yMaxRaw = yValues.maxOrNull() ?: 1f
    val xPad = max((xMaxRaw - xMinRaw) * 0.12f, minSpan)
    val yPad = max((yMaxRaw - yMinRaw) * 0.12f, minSpan)
    val fullXMin = xMinRaw - xPad
    val fullXMax = xMaxRaw + xPad
    val yMin = yMinRaw - yPad
    val yMax = yMaxRaw + yPad
    val fullXSpan = (fullXMax - fullXMin).coerceAtLeast(minSpan)
    val zoomLevel = zoom.coerceIn(1f, 6f)
    val visibleXSpan = fullXSpan / zoomLevel
    val panSpan = (fullXSpan - visibleXSpan).coerceAtLeast(0f)
    val xMin = fullXMin + panSpan * panFraction.coerceIn(0f, 1f)
    val xMax = xMin + visibleXSpan

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.calibration_model_chart_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.calibration_model_chart_subtitle, sourceLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(surfaceColor),
            contentAlignment = Alignment.Center
        ) {
            if (!hasChartData) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.calibration_model_no_points),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.calibration_model_no_points_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(chartRows, xMin, xMax, yMin, yMax, zoomLevel) {
                            fun nearestRowForOffset(offset: Offset): CalibrationModelRow? {
                                val left = 48.dp.toPx()
                                val top = 18.dp.toPx()
                                val right = size.width - 14.dp.toPx()
                                val bottom = size.height - 38.dp.toPx()
                                val chartWidth = (right - left).coerceAtLeast(1f)
                                val chartHeight = (bottom - top).coerceAtLeast(1f)

                                fun mapX(value: Float): Float {
                                    return left + ((value - xMin) / (xMax - xMin).coerceAtLeast(0.01f)) * chartWidth
                                }

                                fun mapY(value: Float): Float {
                                    return bottom - ((value - yMin) / (yMax - yMin).coerceAtLeast(0.01f)) * chartHeight
                                }

                                return chartRows
                                    .mapNotNull { row ->
                                        val x = mapX(row.sourceValue)
                                        val y = mapY(row.referenceValue)
                                        if (
                                            x in (left - 32.dp.toPx())..(right + 32.dp.toPx()) &&
                                            y in (top - 32.dp.toPx())..(bottom + 32.dp.toPx())
                                        ) {
                                            row to hypot((offset.x - x).toDouble(), (offset.y - y).toDouble()).toFloat()
                                        } else {
                                            null
                                        }
                                    }
                                    .minByOrNull { it.second }
                                    ?.takeIf { it.second <= 56.dp.toPx() }
                                    ?.first
                            }

                            awaitEachGesture {
                                var keepTracking: Boolean
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    keepTracking = pressed.any { it.pressed }
                                    if (pressed.size >= 2) {
                                        val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                                        val chartWidth = (size.width - 62.dp.toPx()).coerceAtLeast(1f)
                                        panFraction = if (nextZoom <= 1.01f) {
                                            0.5f
                                        } else {
                                            val panDelta = -event.calculatePan().x / chartWidth / (nextZoom - 1f).coerceAtLeast(0.05f)
                                            (panFraction + panDelta).coerceIn(0f, 1f)
                                        }
                                        zoom = nextZoom
                                        pressed.forEach { it.consume() }
                                    } else if (pressed.size == 1) {
                                        nearestRowForOffset(pressed.first().position)?.let { row ->
                                            selectedCalibrationId = row.calibration.id
                                            pressed.first().consume()
                                        }
                                    }
                                } while (keepTracking)
                            }
                        }
                ) {
                    val left = 48.dp.toPx()
                    val top = 18.dp.toPx()
                    val right = size.width - 14.dp.toPx()
                    val bottom = size.height - 38.dp.toPx()
                    val chartWidth = (right - left).coerceAtLeast(1f)
                    val chartHeight = (bottom - top).coerceAtLeast(1f)

                    fun mapX(value: Float): Float {
                        return left + ((value - xMin) / (xMax - xMin).coerceAtLeast(0.01f)) * chartWidth
                    }

                    fun mapY(value: Float): Float {
                        return bottom - ((value - yMin) / (yMax - yMin).coerceAtLeast(0.01f)) * chartHeight
                    }

                    repeat(6) { index ->
                        val t = index / 5f
                        val y = top + chartHeight * t
                        drawLine(
                            color = gridColor,
                            start = Offset(left, y),
                            end = Offset(right, y),
                            strokeWidth = 1.dp.toPx()
                        )
                        val value = yMax - (yMax - yMin) * t
                        axisPaint.color = labelColor
                        axisPaint.textSize = 11.dp.toPx()
                        axisPaint.textAlign = Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(
                            formatCalibrationValue(value, isMmol),
                            left - 8.dp.toPx(),
                            y + 4.dp.toPx(),
                            axisPaint
                        )
                    }

                    repeat(5) { index ->
                        val t = index / 4f
                        val x = left + chartWidth * t
                        drawLine(
                            color = gridColor.copy(alpha = 0.65f),
                            start = Offset(x, top),
                            end = Offset(x, bottom),
                            strokeWidth = 1.dp.toPx()
                        )
                        val value = xMin + (xMax - xMin) * t
                        axisPaint.color = labelColor
                        axisPaint.textSize = 11.dp.toPx()
                        axisPaint.textAlign = Paint.Align.CENTER
                        drawContext.canvas.nativeCanvas.drawText(
                            formatCalibrationValue(value, isMmol),
                            x,
                            bottom + 24.dp.toPx(),
                            axisPaint
                        )
                    }

                    drawLine(
                        color = axisColor.copy(alpha = 0.70f),
                        start = Offset(left, top),
                        end = Offset(left, bottom),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawLine(
                        color = axisColor.copy(alpha = 0.70f),
                        start = Offset(left, bottom),
                        end = Offset(right, bottom),
                        strokeWidth = 1.2.dp.toPx()
                    )

                    clipRect(left = left, top = top, right = right, bottom = bottom) {
                        if (modelState.fitLine.size >= 2 && isCalibrationEnabled) {
                            val path = Path()
                            modelState.fitLine.forEachIndexed { index, point ->
                                val x = mapX(point.x)
                                val y = mapY(point.y)
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path = path,
                                color = fitColor,
                                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        modelState.activeRows.forEach { row ->
                            val predicted = row.predictedValue ?: return@forEach
                            drawLine(
                                color = fitColor.copy(alpha = 0.26f),
                                start = Offset(mapX(row.sourceValue), mapY(row.referenceValue)),
                                end = Offset(mapX(row.sourceValue), mapY(predicted)),
                                strokeWidth = 1.4.dp.toPx()
                            )
                        }

                        selectedRow?.let { row ->
                            val selectedX = mapX(row.sourceValue)
                            val selectedY = mapY(row.referenceValue)
                            drawLine(
                                color = crosshairColor.copy(alpha = 0.38f),
                                start = Offset(selectedX, top),
                                end = Offset(selectedX, bottom),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = crosshairColor.copy(alpha = 0.28f),
                                start = Offset(left, selectedY),
                                end = Offset(right, selectedY),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        chartRows.forEach { row ->
                            val color = if (row.status == CalibrationModelRowStatus.ACTIVE) activeColor else disabledColor
                            val radius = if (row.status == CalibrationModelRowStatus.ACTIVE) 6.5.dp.toPx() else 5.dp.toPx()
                            val center = Offset(mapX(row.sourceValue), mapY(row.referenceValue))
                            drawCircle(
                                color = color.copy(alpha = if (row.status == CalibrationModelRowStatus.ACTIVE) 1f else 0.58f),
                                radius = radius,
                                center = center
                            )
                            if (row.calibration.id == selectedCalibrationId) {
                                drawCircle(
                                    color = color.copy(alpha = 0.24f),
                                    radius = radius * 2.3f,
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                            if (row.status != CalibrationModelRowStatus.ACTIVE) {
                                drawCircle(
                                    color = surfaceColor,
                                    radius = radius * 0.48f,
                                    center = center
                                )
                            }
                        }
                    }
                }

                selectedRow?.let { row ->
                    SelectedCalibrationTooltip(
                        row = row,
                        isMmol = isMmol,
                        sourceLabel = sourceLabel,
                        dateFormatter = dateFormatter,
                        onEdit = onEdit,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalibrationLegendItem(
                color = activeColor,
                label = stringResource(R.string.calibration_model_row_active)
            )
            CalibrationLegendItem(
                color = disabledColor,
                label = stringResource(R.string.calibration_model_row_disabled)
            )
            CalibrationLegendItem(
                color = fitColor,
                label = stringResource(R.string.calibration_model_fit)
            )
        }
    }
}

@Composable
private fun CalibrationLegendItem(
    color: Color,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun SelectedCalibrationTooltip(
    row: CalibrationModelRow,
    isMmol: Boolean,
    sourceLabel: String,
    dateFormatter: SimpleDateFormat,
    modifier: Modifier = Modifier,
    onEdit: (CalibrationEntity) -> Unit
) {
    Surface(
        onClick = { onEdit(row.calibration) },
        modifier = modifier.width(244.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.calibration_model_selected_point),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = "$sourceLabel ${formatCalibrationValue(row.sourceValue, isMmol)} \u2192 ${formatCalibrationValue(row.referenceValue, isMmol)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${stringResource(R.string.calibration_model_table_model)} ${row.predictedValue?.let { formatCalibrationValue(it, isMmol) } ?: "-"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = "${stringResource(R.string.calibration_model_table_delta)} ${row.residualValue?.let { formatSignedCalibrationValue(it, isMmol) } ?: "-"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Text(
                text = dateFormatter.format(Date(row.calibration.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun formatCalibrationValue(value: Float, isMmol: Boolean): String {
    return if (!value.isFinite()) {
        "-"
    } else if (isMmol) {
        String.format(Locale.getDefault(), "%.1f", value)
    } else {
        String.format(Locale.getDefault(), "%.0f", value)
    }
}

private fun formatSignedCalibrationValue(value: Float, isMmol: Boolean): String {
    if (!value.isFinite()) return "-"
    val prefix = if (value > 0f) "+" else ""
    return prefix + formatCalibrationValue(value, isMmol)
}

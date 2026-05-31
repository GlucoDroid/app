package tk.glucodata.ui.calibration

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val unitLabel = if (isMmol) "mmol/L" else "mg/dL"
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
        lockPastHistory
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
                CalibrationModelSummaryCard(
                    algorithm = selectedAlgorithm,
                    modelState = modelState,
                    isCalibrationEnabled = isCalibrationEnabled,
                    isMmol = isMmol,
                    unitLabel = unitLabel
                )
            }

            item {
                CalibrationModelChart(
                    modelState = modelState,
                    isCalibrationEnabled = isCalibrationEnabled,
                    isMmol = isMmol,
                    unitLabel = unitLabel,
                    sourceLabel = modeTitle
                )
            }

            item {
                CalibrationDataTable(
                    rows = modelState.rows,
                    dateFormatter = dateFormatter,
                    isMmol = isMmol,
                    unitLabel = unitLabel,
                    onEdit = onEdit
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
    isMmol: Boolean,
    unitLabel: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCalibrationEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = algorithm.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
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
                }
            }

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
                        suffix = unitLabel,
                        modifier = Modifier.weight(1f)
                    )
                    CalibrationMetricTile(
                        label = stringResource(R.string.calibration_model_max_error),
                        value = modelState.maxAbsResidual?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                        suffix = unitLabel,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalibrationMetricTile(
                        label = stringResource(R.string.calibration_model_source_span),
                        value = modelState.sourceSpan?.let { formatCalibrationValue(it, isMmol) } ?: "-",
                        suffix = unitLabel,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    suffix: String? = null
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
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (suffix != null && value != "-") {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationModelChart(
    modelState: CalibrationModelUiState,
    isCalibrationEnabled: Boolean,
    isMmol: Boolean,
    unitLabel: String,
    sourceLabel: String
) {
    val activeColor = MaterialTheme.colorScheme.tertiary
    val disabledColor = MaterialTheme.colorScheme.outline
    val fitColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
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
    val xMin = xMinRaw - xPad
    val xMax = xMaxRaw + xPad
    val yMin = yMinRaw - yPad
    val yMax = yMaxRaw + yPad

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.calibration_model_chart_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.calibration_model_chart_subtitle, sourceLabel, unitLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                    Canvas(modifier = Modifier.fillMaxSize()) {
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

                        repeat(5) { index ->
                            val t = index / 4f
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
                            drawContext.canvas.nativeCanvas.drawText(
                                formatCalibrationValue(value, isMmol),
                                left - 8.dp.toPx(),
                                y + 4.dp.toPx(),
                                axisPaint
                            )
                        }

                        repeat(4) { index ->
                            val t = index / 3f
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
                                bottom + 22.dp.toPx(),
                                axisPaint
                            )
                            axisPaint.textAlign = Paint.Align.RIGHT
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
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }

                        modelState.activeRows.forEach { row ->
                            val predicted = row.predictedValue ?: return@forEach
                            drawLine(
                                color = fitColor.copy(alpha = 0.28f),
                                start = Offset(mapX(row.sourceValue), mapY(row.referenceValue)),
                                end = Offset(mapX(row.sourceValue), mapY(predicted)),
                                strokeWidth = 1.4.dp.toPx()
                            )
                        }

                        chartRows.forEach { row ->
                            val color = if (row.status == CalibrationModelRowStatus.ACTIVE) activeColor else disabledColor
                            val radius = if (row.status == CalibrationModelRowStatus.ACTIVE) 5.5.dp.toPx() else 4.5.dp.toPx()
                            drawCircle(
                                color = color.copy(alpha = if (row.status == CalibrationModelRowStatus.ACTIVE) 1f else 0.58f),
                                radius = radius,
                                center = Offset(mapX(row.sourceValue), mapY(row.referenceValue))
                            )
                            if (row.status != CalibrationModelRowStatus.ACTIVE) {
                                drawCircle(
                                    color = surfaceColor,
                                    radius = radius * 0.48f,
                                    center = Offset(mapX(row.sourceValue), mapY(row.referenceValue))
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
private fun CalibrationDataTable(
    rows: List<CalibrationModelRow>,
    dateFormatter: SimpleDateFormat,
    isMmol: Boolean,
    unitLabel: String,
    onEdit: (CalibrationEntity) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.calibration_model_table_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.calibration_model_table_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 112.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.calibration_model_no_points_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }

            val horizontalScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .horizontalScroll(horizontalScroll)
                    .widthIn(min = 704.dp)
            ) {
                CalibrationTableHeader(unitLabel = unitLabel)
                rows.forEach { row ->
                    CalibrationTableRow(
                        row = row,
                        dateFormatter = dateFormatter,
                        isMmol = isMmol,
                        onEdit = onEdit
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
        }
    }
}

@Composable
private fun CalibrationTableHeader(unitLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalibrationTableCell(stringResource(R.string.calibration_model_table_time), 132.dp, true)
        CalibrationTableCell("${stringResource(R.string.calibration_model_table_source)} ($unitLabel)", 116.dp, true)
        CalibrationTableCell(stringResource(R.string.calibration_model_table_reference), 118.dp, true)
        CalibrationTableCell(stringResource(R.string.calibration_model_table_model), 104.dp, true)
        CalibrationTableCell(stringResource(R.string.calibration_model_table_delta), 84.dp, true)
        CalibrationTableCell(stringResource(R.string.calibration_model_table_status), 110.dp, true)
    }
}

@Composable
private fun CalibrationTableRow(
    row: CalibrationModelRow,
    dateFormatter: SimpleDateFormat,
    isMmol: Boolean,
    onEdit: (CalibrationEntity) -> Unit
) {
    val alpha = when (row.status) {
        CalibrationModelRowStatus.ACTIVE -> 1f
        CalibrationModelRowStatus.DISABLED -> 0.66f
        CalibrationModelRowStatus.INCOMPLETE -> 0.56f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit(row.calibration) }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalibrationTableCell(dateFormatter.format(Date(row.calibration.timestamp)), 132.dp, false, alpha)
        CalibrationTableCell(formatCalibrationValue(row.sourceValue, isMmol), 116.dp, false, alpha)
        CalibrationTableCell(formatCalibrationValue(row.referenceValue, isMmol), 118.dp, false, alpha)
        CalibrationTableCell(row.predictedValue?.let { formatCalibrationValue(it, isMmol) } ?: "-", 104.dp, false, alpha)
        CalibrationTableCell(row.residualValue?.let { formatSignedCalibrationValue(it, isMmol) } ?: "-", 84.dp, false, alpha)
        Box(modifier = Modifier.width(110.dp)) {
            CalibrationStatusPill(status = row.status)
        }
    }
}

@Composable
private fun CalibrationTableCell(
    text: String,
    width: Dp,
    isHeader: Boolean,
    alpha: Float = 1f
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        style = if (isHeader) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        color = if (isHeader) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        },
        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CalibrationStatusPill(status: CalibrationModelRowStatus) {
    val (label, icon, color) = when (status) {
        CalibrationModelRowStatus.ACTIVE -> Triple(
            stringResource(R.string.calibration_model_row_active),
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.tertiary
        )
        CalibrationModelRowStatus.DISABLED -> Triple(
            stringResource(R.string.calibration_model_row_disabled),
            Icons.Default.Close,
            MaterialTheme.colorScheme.outline
        )
        CalibrationModelRowStatus.INCOMPLETE -> Triple(
            stringResource(R.string.calibration_model_row_incomplete),
            Icons.Default.RadioButtonUnchecked,
            MaterialTheme.colorScheme.error
        )
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
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

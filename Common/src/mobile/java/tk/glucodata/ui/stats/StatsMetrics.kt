package tk.glucodata.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tk.glucodata.R
import tk.glucodata.ui.GlucosePoint
import java.util.Locale

/**
 * One metric, formatted for display. The same spec drives the statistics grid and the
 * pinned dashboard chips, so a number can only be formatted one way.
 */
internal data class MetricSpec(
    val metric: StatsMetric,
    val title: String,
    val value: String,
    val status: String,
    val meta: String,
    val tone: Color,
    val infoText: String? = null
)

@Composable
internal fun ScoreTile(
    title: String,
    value: String,
    status: String,
    meta: String,
    tone: Color,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    infoText: String? = null,
    forceStatusOwnRow: Boolean? = null
) {
    val expandable = !infoText.isNullOrBlank()
    val hasStatus = status.isNotBlank()
    val hasMeta = meta.isNotBlank()
    val tileShape = RoundedCornerShape(topStart = 20.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 20.dp)
    val tileColor = tone.copy(alpha = 0.09f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
    val titleStyle = MaterialTheme.typography.titleMedium.copy(lineHeight = 22.sp)
    val statusStyle = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp)
    val valueStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    Box(
        modifier = modifier
            .animateContentSize()
            .graphicsLayer {
                shape = tileShape
                clip = true
            }
            .background(
                color = tileColor,
                shape = tileShape
            )
            .then(
                if (expandable) Modifier.clickable(onClick = onToggleExpanded) else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (hasMeta) 6.dp else 4.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val textMeasurer = rememberTextMeasurer()
                val titleGapPx = with(density) { 12.dp.roundToPx() }
                val sharedWidthPx = with(density) { maxWidth.roundToPx() }
                val autoStatusNeedsOwnRow = remember(
                    forceStatusOwnRow,
                    value,
                    status,
                    textMeasurer,
                    density,
                    valueStyle,
                    statusStyle,
                    hasStatus,
                    sharedWidthPx
                ) {
                    if (forceStatusOwnRow != null || !hasStatus) {
                        false
                    } else {
                        val valueWidthPx = textMeasurer.measure(
                            text = AnnotatedString(value),
                            style = valueStyle,
                            maxLines = 1
                        ).size.width
                        val statusWidthPx = textMeasurer.measure(
                            text = AnnotatedString(status),
                            style = statusStyle,
                            maxLines = 1
                        ).size.width
                        statusWidthPx > (sharedWidthPx - valueWidthPx - titleGapPx).coerceAtLeast(0)
                    }
                }
                val statusNeedsOwnRow = forceStatusOwnRow ?: autoStatusNeedsOwnRow

                if (statusNeedsOwnRow) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    style = titleStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (expandable) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = value,
                                style = valueStyle,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 12.dp),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.End
                            )
                        }
                        Text(
                            text = status,
                            style = statusStyle,
                            color = tone,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (hasStatus) 4.dp else 0.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    style = titleStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (expandable) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            if (hasStatus) {
                                Text(
                                    text = status,
                                    style = statusStyle,
                                    color = tone,
                                    modifier = Modifier.padding(top = 2.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            text = value,
                            style = valueStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 12.dp),
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
            if (hasMeta) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "tnum",
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = expandable && expanded,
                enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(animationSpec = tween(180))
            ) {
                Text(
                    text = infoText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Measures whether a tile's status still fits beside its value, so both tiles in a row
 * can agree on one layout. Kept from the original tile; only its visibility changed.
 */
@Composable
internal fun rememberScoreTileNeedsOwnRow(
    contentWidth: Dp,
    value: String,
    status: String
): Boolean {
    if (status.isBlank()) return false
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val statusStyle = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp)
    val valueStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    return remember(contentWidth, value, status, density, textMeasurer, statusStyle, valueStyle) {
        val widthPx = with(density) { maxOf(contentWidth, 0.dp).roundToPx() }
        val titleGapPx = with(density) { 12.dp.roundToPx() }
        val valueWidthPx = textMeasurer.measure(
            text = AnnotatedString(value),
            style = valueStyle,
            maxLines = 1
        ).size.width
        val statusWidthPx = textMeasurer.measure(
            text = AnnotatedString(status),
            style = statusStyle,
            maxLines = 1
        ).size.width
        statusWidthPx > (widthPx - valueWidthPx - titleGapPx).coerceAtLeast(0)
    }
}

/**
 * Formats one metric from the current summary.
 *
 * Every glucose number goes through [formatMgDl], so a metric can never print mg/dL to
 * someone reading mmol/L — including the bounds quoted in the explanations, which come
 * from the user's own targets rather than fixed constants.
 */
@Composable
internal fun metricSpec(
    metric: StatsMetric,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit
): MetricSpec {
    val title = stringResource(metric.titleResId)
    val targetRange = "${formatMgDl(targets.lowMgDl, unit)}-${formatMgDl(targets.highMgDl, unit)}"

    val lowWord = stringResource(R.string.low_range)
    val highWord = stringResource(R.string.high_range)
    val inRangeWord = stringResource(R.string.in_range)
    val steadyWord = stringResource(R.string.gvi_good)
    val middlingWord = stringResource(R.string.gvi_moderate)
    val swingyWord = stringResource(R.string.gvi_poor)
    val typicalWord = stringResource(R.string.typical)
    val noneWord = stringResource(R.string.stats_metric_none)
    val rangeWord = stringResource(R.string.range)
    val targetWord = stringResource(R.string.gmi_target)
    val targetValue = stringResource(R.string.gmi_target_value)
    val tirWord = stringResource(R.string.tir)
    val stabilityWord = stringResource(R.string.stability)
    val trendWord = stringResource(R.string.stats_trend)

    fun bandTone(valueMgDl: Float): Color = when {
        valueMgDl < targets.lowMgDl || valueMgDl > targets.highMgDl -> TirVeryHighColor
        valueMgDl <= targets.lowMgDl + 8f || valueMgDl >= targets.highMgDl - 8f -> TirHighColor
        else -> TirInRangeColor
    }

    fun bandStatus(valueMgDl: Float): String = when {
        valueMgDl < targets.lowMgDl -> lowWord
        valueMgDl > targets.highMgDl -> highWord
        else -> inRangeWord
    }

    fun spec(
        value: String,
        status: String,
        meta: String = "",
        tone: Color,
        infoText: String? = null
    ) = MetricSpec(metric, title, value, status, meta, tone, infoText)

    return when (metric) {
        StatsMetric.TIME_IN_RANGE -> spec(
            value = String.format(Locale.getDefault(), "%.0f%%", summary.tir.inRangePercent),
            status = if (summary.tir.inRangePercent >= 70f) steadyWord else middlingWord,
            meta = "$rangeWord $targetRange",
            tone = tirHeatColor(summary.tir.inRangePercent)
        )

        StatsMetric.AVERAGE -> spec(
            value = formatMgDl(summary.avgMgDl, unit),
            status = bandStatus(summary.avgMgDl),
            meta = "$rangeWord $targetRange",
            tone = bandTone(summary.avgMgDl)
        )

        StatsMetric.GMI -> spec(
            value = String.format(Locale.getDefault(), "%.1f%%", summary.gmiPercent),
            status = if (summary.gmiPercent <= 7.0f) targetWord else highWord,
            meta = "$targetWord $targetValue",
            tone = when {
                summary.gmiPercent < 5.7f -> TirInRangeColor
                summary.gmiPercent < 6.5f -> TirHighColor
                else -> TirVeryHighColor
            }
        )

        StatsMetric.CV -> spec(
            value = String.format(Locale.getDefault(), "%.1f%%", summary.cvPercent),
            status = when {
                summary.cvPercent < 32f -> steadyWord
                summary.cvPercent < 40f -> middlingWord
                else -> swingyWord
            },
            tone = when {
                summary.cvPercent < 32f -> TirInRangeColor
                summary.cvPercent < 40f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.cv_description)
        )

        StatsMetric.TIGHT_RANGE -> {
            val (low, high) = StatsAnalytics.tightRangeBounds(targets)
            val bounds = "${formatMgDl(low, unit)}-${formatMgDl(high, unit)}"
            spec(
                value = String.format(Locale.getDefault(), "%.0f%%", summary.tightRangePercent),
                status = if (summary.tightRangePercent >= 50f) steadyWord else middlingWord,
                meta = bounds,
                tone = when {
                    summary.tightRangePercent >= 50f -> TirInRangeColor
                    summary.tightRangePercent >= 30f -> TirHighColor
                    else -> TirVeryHighColor
                },
                infoText = stringResource(R.string.stats_tight_range_description, bounds)
            )
        }

        StatsMetric.MEDIAN -> spec(
            value = formatMgDl(summary.medianMgDl, unit),
            status = bandStatus(summary.medianMgDl),
            meta = "$typicalWord · ${String.format(Locale.getDefault(), "%.0f%% %s", summary.tir.inRangePercent, tirWord)}",
            tone = bandTone(summary.medianMgDl)
        )

        StatsMetric.IQR -> spec(
            value = formatMgDl((summary.p75MgDl - summary.p25MgDl).coerceAtLeast(0f), unit),
            status = typicalWord,
            meta = "${formatMgDl(summary.p25MgDl, unit)}-${formatMgDl(summary.p75MgDl, unit)}",
            tone = when {
                summary.cvPercent < 32f -> TirInRangeColor
                summary.cvPercent < 40f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.iqr_description)
        )

        StatsMetric.STD_DEV -> spec(
            value = formatMgDl(summary.stdDevMgDl, unit),
            status = when {
                summary.stdDevMgDl < 18f -> steadyWord
                summary.stdDevMgDl < 27f -> middlingWord
                else -> swingyWord
            },
            tone = when {
                summary.stdDevMgDl < 18f -> TirInRangeColor
                summary.stdDevMgDl < 27f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.std_dev_description)
        )

        StatsMetric.LOW_EPISODES -> spec(
            value = summary.lowEpisodes.count.toString(),
            status = if (summary.lowEpisodes.count == 0) noneWord else lowWord,
            meta = episodeMeta(summary.lowEpisodes),
            tone = if (summary.lowEpisodes.count == 0) TirInRangeColor else TirVeryLowColor,
            infoText = stringResource(R.string.episodes_subtitle)
        )

        StatsMetric.HIGH_EPISODES -> spec(
            value = summary.highEpisodes.count.toString(),
            status = if (summary.highEpisodes.count == 0) noneWord else highWord,
            meta = episodeMeta(summary.highEpisodes),
            tone = if (summary.highEpisodes.count == 0) TirInRangeColor else TirVeryHighColor,
            infoText = stringResource(R.string.episodes_subtitle)
        )

        StatsMetric.COVERAGE -> spec(
            value = String.format(Locale.getDefault(), "%.0f%%", summary.coverage.percent),
            status = if (summary.coverage.percent >= 85f) steadyWord else middlingWord,
            meta = stringResource(R.string.stats_metric_readings, summary.coverage.readingCount),
            tone = when {
                summary.coverage.percent >= 85f -> TirInRangeColor
                summary.coverage.percent >= 70f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.stats_card_coverage_description)
        )

        StatsMetric.LBGI -> spec(
            value = String.format(Locale.getDefault(), "%.1f", summary.risk.lbgi),
            status = stringResource(
                when {
                    summary.risk.lbgi < 1.1f -> R.string.risk_minimal
                    summary.risk.lbgi < 2.5f -> R.string.risk_low
                    summary.risk.lbgi < 5f -> R.string.risk_moderate
                    else -> R.string.risk_high
                }
            ),
            tone = if (summary.risk.lbgi < 2.5f) TirInRangeColor else TirVeryLowColor,
            infoText = stringResource(R.string.lbgi_description)
        )

        StatsMetric.HBGI -> spec(
            value = String.format(Locale.getDefault(), "%.1f", summary.risk.hbgi),
            status = stringResource(
                when {
                    summary.risk.hbgi < 4.5f -> R.string.risk_low
                    summary.risk.hbgi < 9f -> R.string.risk_moderate
                    else -> R.string.risk_high
                }
            ),
            tone = if (summary.risk.hbgi < 4.5f) TirInRangeColor else TirVeryHighColor,
            infoText = stringResource(R.string.hbgi_description)
        )

        StatsMetric.GRI -> spec(
            value = String.format(Locale.getDefault(), "%.0f", summary.gri.value),
            status = stringResource(summary.gri.zone.labelResId),
            meta = "${stringResource(R.string.gri_from_lows, String.format(Locale.getDefault(), "%.0f", summary.gri.hypoComponent))} · ${stringResource(R.string.gri_from_highs, String.format(Locale.getDefault(), "%.0f", summary.gri.hyperComponent))}",
            tone = when (summary.gri.zone) {
                GriZone.A, GriZone.B -> TirInRangeColor
                GriZone.C -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.gri_description)
        )

        StatsMetric.GVI -> spec(
            value = String.format(Locale.getDefault(), "%.2f", summary.gvi.value),
            status = stringResource(summary.gvi.labelResId),
            meta = "$stabilityWord ${String.format(Locale.getDefault(), "%.0f%%", summary.gvi.stability)} · ROC ${String.format(Locale.getDefault(), "%.2f", summary.gvi.rateOfChange)}",
            tone = when {
                summary.gvi.value < 1.55f -> TirInRangeColor
                summary.gvi.value < 1.90f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.gvi_description)
        )

        StatsMetric.PSG -> spec(
            value = formatMgDl(summary.psg.baselineMgDl, unit),
            status = stringResource(summary.psg.labelResId),
            meta = "${String.format(Locale.getDefault(), "%.0f%%", summary.psg.confidence)} · $trendWord ${if (summary.psg.trend >= 0f) "+" else ""}${String.format(Locale.getDefault(), "%.0f%%", summary.psg.trend * 100f)}",
            tone = when (summary.psg.labelResId) {
                R.string.psg_stable -> TirInRangeColor
                R.string.psg_low -> TirLowColor
                R.string.psg_elevated -> TirVeryHighColor
                else -> TirHighColor
            },
            infoText = stringResource(R.string.psg_description)
        )
    }
}

@Composable
private fun episodeMeta(summary: EpisodeSummary): String {
    if (summary.count == 0) return ""
    return "${stringResource(R.string.episodes_typical)} ${durationText(summary.medianDurationMinutes)}"
}

/**
 * Packs metrics into rows.
 *
 * A metric marked wide takes a whole row; the rest pair up, and a metric left over at
 * the end widens to fill its row instead of leaving a hole beside it. That hole was
 * the gap — the tile design itself is unchanged.
 */
internal fun packMetricRows(
    metrics: List<StatsMetric>,
    wide: Set<StatsMetric>
): List<Pair<StatsMetric, StatsMetric?>> {
    val rows = ArrayList<Pair<StatsMetric, StatsMetric?>>()
    var index = 0
    while (index < metrics.size) {
        val first = metrics[index]
        val second = metrics.getOrNull(index + 1)
        if (first in wide || second == null || second in wide) {
            rows += first to null
            index += 1
        } else {
            rows += first to second
            index += 2
        }
    }
    return rows
}

@Composable
internal fun MetricsGrid(
    metrics: List<StatsMetric>,
    wideMetrics: Set<StatsMetric>,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier,
    rowModifier: (StatsMetric) -> Modifier = { Modifier }
) {
    var expanded by remember { mutableStateOf(emptySet<StatsMetric>()) }
    val rows = remember(metrics, wideMetrics) { packMetricRows(metrics, wideMetrics) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { (left, right) ->
            MetricRow(
                left = metricSpec(left, summary, targets, unit),
                right = right?.let { metricSpec(it, summary, targets, unit) },
                expanded = expanded,
                onToggleExpanded = { metric ->
                    expanded = if (metric in expanded) expanded - metric else expanded + metric
                },
                modifier = rowModifier(left)
            )
        }
    }
}

/**
 * Two tiles share a row and match heights — except while one of them is open, when the
 * neighbour keeps its own height and the row simply ends short. Stretching it would
 * make an unrelated tile look like it had expanded too.
 */
@Composable
private fun MetricRow(
    left: MetricSpec,
    right: MetricSpec?,
    expanded: Set<StatsMetric>,
    onToggleExpanded: (StatsMetric) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp
) {
    val anyExpanded = left.metric in expanded || (right != null && right.metric in expanded)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tileContentWidth = if (right == null) {
            maxWidth - 28.dp
        } else {
            ((maxWidth - spacing) / 2f) - 28.dp
        }
        val useOwnStatusRow = rememberScoreTileNeedsOwnRow(tileContentWidth, left.value, left.status) ||
            (right != null && rememberScoreTileNeedsOwnRow(tileContentWidth, right.value, right.status))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (anyExpanded) Modifier else Modifier.height(IntrinsicSize.Min)),
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            ScoreTile(
                title = left.title,
                value = left.value,
                status = left.status,
                meta = left.meta,
                tone = left.tone,
                expanded = left.metric in expanded,
                onToggleExpanded = { onToggleExpanded(left.metric) },
                infoText = left.infoText,
                forceStatusOwnRow = useOwnStatusRow,
                modifier = Modifier
                    .weight(1f)
                    .then(if (anyExpanded) Modifier else Modifier.fillMaxHeight())
            )
            if (right != null) {
                ScoreTile(
                    title = right.title,
                    value = right.value,
                    status = right.status,
                    meta = right.meta,
                    tone = right.tone,
                    expanded = right.metric in expanded,
                    onToggleExpanded = { onToggleExpanded(right.metric) },
                    infoText = right.infoText,
                    forceStatusOwnRow = useOwnStatusRow,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (anyExpanded) Modifier else Modifier.fillMaxHeight())
                )
            }
        }
    }
}

/** Compact form used by the dashboard strip, where vertical space is precious. */
@Composable
internal fun PinnedMetricChip(
    spec: MetricSpec,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(statsCardShape(16.dp, 10.dp))
            .background(spec.tone.copy(alpha = 0.11f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = spec.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = spec.value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            ),
            color = spec.tone,
            maxLines = 1
        )
    }
}

/** Windows the dashboard strip can summarise, cycled by tapping the leading pill. */
private enum class PinnedWindow(val labelResId: Int, val hours: Int) {
    H6(R.string.stats_window_6h, 6),
    H12(R.string.stats_window_12h, 12),
    H24(R.string.stats_window_24h, 24),
    D7(R.string.stats_window_7d, 24 * 7)
}

/** True when the user has pinned anything, so the dashboard can skip the row entirely. */
@Composable
fun hasPinnedStats(): Boolean {
    val context = LocalContext.current
    LaunchedEffect(context) { StatsLayoutStore.ensureLoaded(context) }
    val layout by StatsLayoutStore.state.collectAsState()
    return layout.dashboardMetrics.isNotEmpty()
}

/**
 * Metrics pinned from Statistics → Arrange, over a window the user can change in place.
 *
 * The period lives in the first slot as a tappable pill rather than on a caption line
 * above the row: a lone label was both an extra line of height and one more thing to
 * read. Values are computed from the history the Dashboard already holds, so this costs
 * one linear pass and no second subscription.
 */
@Composable
fun PinnedStatsStrip(
    history: List<GlucosePoint>,
    targetLowMgDl: Float,
    targetHighMgDl: Float,
    veryLowMgDl: Float,
    veryHighMgDl: Float,
    isMmol: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(context) { StatsLayoutStore.ensureLoaded(context) }
    val layout by StatsLayoutStore.state.collectAsState()
    val pinned = layout.dashboardMetrics
    if (pinned.isEmpty() || history.isEmpty()) return

    var window by rememberSaveable { mutableStateOf(PinnedWindow.H24) }
    val targets = remember(targetLowMgDl, targetHighMgDl, veryLowMgDl, veryHighMgDl) {
        StatsTargets(
            lowMgDl = targetLowMgDl,
            highMgDl = targetHighMgDl,
            veryLowMgDl = veryLowMgDl,
            veryHighMgDl = veryHighMgDl
        )
    }
    val summary = remember(history, targets, window, isMmol) {
        val end = history.last().timestamp
        val start = end - window.hours.toLong() * 60L * 60L * 1000L
        // The dashboard keeps history in display units; every analytic here is defined
        // in mg/dL, so convert back before measuring anything.
        val scale = if (isMmol) tk.glucodata.ui.util.GlucoseFormatter.MGDL_PER_MMOL else 1f
        val values = history.asSequence()
            .filter { it.timestamp >= start }
            .map { point -> point.copy(value = point.value * scale) }
            .toList()
        StatsAnalytics.dashboardSummary(
            history = values,
            targets = targets,
            range = StatsDateRange(startMillis = start, endMillis = end)
        )
    }
    if (summary.readingCount == 0) return
    val unit = if (isMmol) GlucoseUnit.MMOL else GlucoseUnit.MGDL

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PinnedWindowPill(
            label = stringResource(window.labelResId),
            onClick = {
                val entries = PinnedWindow.entries
                window = entries[(entries.indexOf(window) + 1) % entries.size]
            },
            modifier = Modifier.weight(1f)
        )
        pinned.forEach { metric ->
            PinnedMetricChip(
                spec = metricSpec(metric, summary, targets, unit),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PinnedWindowPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(statsCardShape(16.dp, 10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = stringResource(R.string.stats_window_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

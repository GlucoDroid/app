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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tk.glucodata.R
import tk.glucodata.ui.GlucosePoint
import java.util.Locale

/**
 * One metric, already formatted for display. Everything the tile needs and nothing
 * about how it is laid out, so the same spec drives the stats grid and the pinned
 * dashboard chips.
 */
internal data class MetricSpec(
    val metric: StatsMetric,
    val title: String,
    val value: String,
    val statusLine: String,
    val tone: Color,
    val infoText: String? = null
)

/**
 * Formats one metric from the current summary.
 *
 * Everything derived from a glucose value goes through [formatMgDl], so a metric can
 * never print mg/dL to someone reading in mmol/L — including the bounds quoted in the
 * explanations, which are computed from the user's own targets rather than hardcoded.
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

    fun bandTone(valueMgDl: Float): Color = when {
        valueMgDl < targets.lowMgDl || valueMgDl > targets.highMgDl -> TirVeryHighColor
        valueMgDl <= targets.lowMgDl + 8f || valueMgDl >= targets.highMgDl - 8f -> TirHighColor
        else -> TirInRangeColor
    }

    val lowWord = stringResource(R.string.low_range)
    val highWord = stringResource(R.string.high_range)
    val inRangeWord = stringResource(R.string.in_range)

    fun bandStatus(valueMgDl: Float): String = when {
        valueMgDl < targets.lowMgDl -> lowWord
        valueMgDl > targets.highMgDl -> highWord
        else -> inRangeWord
    }

    val steadyWord = stringResource(R.string.gvi_good)
    val middlingWord = stringResource(R.string.gvi_moderate)
    val swingyWord = stringResource(R.string.gvi_poor)
    val noneWord = stringResource(R.string.stats_metric_none)

    return when (metric) {
        StatsMetric.TIME_IN_RANGE -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.0f%%", summary.tir.inRangePercent),
            statusLine = targetRange,
            tone = tirHeatColor(summary.tir.inRangePercent)
        )

        StatsMetric.AVERAGE -> MetricSpec(
            metric = metric,
            title = title,
            value = formatMgDl(summary.avgMgDl, unit),
            statusLine = "${bandStatus(summary.avgMgDl)} · $targetRange",
            tone = bandTone(summary.avgMgDl)
        )

        StatsMetric.GMI -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.1f%%", summary.gmiPercent),
            statusLine = "${stringResource(R.string.gmi_target)} ${stringResource(R.string.gmi_target_value)}",
            tone = when {
                summary.gmiPercent < 5.7f -> TirInRangeColor
                summary.gmiPercent < 6.5f -> TirHighColor
                else -> TirVeryHighColor
            }
        )

        StatsMetric.CV -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.1f%%", summary.cvPercent),
            statusLine = when {
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
            MetricSpec(
                metric = metric,
                title = title,
                value = String.format(Locale.getDefault(), "%.0f%%", summary.tightRangePercent),
                statusLine = bounds,
                tone = when {
                    summary.tightRangePercent >= 50f -> TirInRangeColor
                    summary.tightRangePercent >= 30f -> TirHighColor
                    else -> TirVeryHighColor
                },
                infoText = stringResource(R.string.stats_tight_range_description, bounds)
            )
        }

        StatsMetric.MEDIAN -> MetricSpec(
            metric = metric,
            title = title,
            value = formatMgDl(summary.medianMgDl, unit),
            statusLine = "${stringResource(R.string.typical)} · ${bandStatus(summary.medianMgDl)}",
            tone = bandTone(summary.medianMgDl)
        )

        StatsMetric.IQR -> MetricSpec(
            metric = metric,
            title = title,
            value = formatMgDl((summary.p75MgDl - summary.p25MgDl).coerceAtLeast(0f), unit),
            statusLine = "${formatMgDl(summary.p25MgDl, unit)}-${formatMgDl(summary.p75MgDl, unit)}",
            tone = TirInRangeColor,
            infoText = stringResource(R.string.iqr_description)
        )

        StatsMetric.STD_DEV -> MetricSpec(
            metric = metric,
            title = title,
            value = formatMgDl(summary.stdDevMgDl, unit),
            statusLine = when {
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

        StatsMetric.LOW_EPISODES -> MetricSpec(
            metric = metric,
            title = title,
            value = summary.lowEpisodes.count.toString(),
            statusLine = episodeStatus(summary.lowEpisodes, noneWord),
            tone = if (summary.lowEpisodes.count == 0) TirInRangeColor else TirVeryLowColor,
            infoText = stringResource(R.string.episodes_subtitle)
        )

        StatsMetric.HIGH_EPISODES -> MetricSpec(
            metric = metric,
            title = title,
            value = summary.highEpisodes.count.toString(),
            statusLine = episodeStatus(summary.highEpisodes, noneWord),
            tone = if (summary.highEpisodes.count == 0) TirInRangeColor else TirVeryHighColor,
            infoText = stringResource(R.string.episodes_subtitle)
        )

        StatsMetric.COVERAGE -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.0f%%", summary.coverage.percent),
            statusLine = stringResource(R.string.stats_metric_readings, summary.coverage.readingCount),
            tone = when {
                summary.coverage.percent >= 85f -> TirInRangeColor
                summary.coverage.percent >= 70f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.stats_card_coverage_description)
        )

        StatsMetric.LBGI -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.1f", summary.risk.lbgi),
            statusLine = stringResource(
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

        StatsMetric.HBGI -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.1f", summary.risk.hbgi),
            statusLine = stringResource(
                when {
                    summary.risk.hbgi < 4.5f -> R.string.risk_low
                    summary.risk.hbgi < 9f -> R.string.risk_moderate
                    else -> R.string.risk_high
                }
            ),
            tone = if (summary.risk.hbgi < 4.5f) TirInRangeColor else TirVeryHighColor,
            infoText = stringResource(R.string.hbgi_description)
        )

        StatsMetric.GVI -> MetricSpec(
            metric = metric,
            title = title,
            value = String.format(Locale.getDefault(), "%.2f", summary.gvi.value),
            statusLine = stringResource(summary.gvi.labelResId),
            tone = when {
                summary.gvi.value < 1.55f -> TirInRangeColor
                summary.gvi.value < 1.90f -> TirHighColor
                else -> TirVeryHighColor
            },
            infoText = stringResource(R.string.gvi_description)
        )

        StatsMetric.PSG -> MetricSpec(
            metric = metric,
            title = title,
            value = formatMgDl(summary.psg.baselineMgDl, unit),
            statusLine = stringResource(summary.psg.labelResId),
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
private fun episodeStatus(summary: EpisodeSummary, noneWord: String): String {
    val typicalWord = stringResource(R.string.episodes_typical)
    if (summary.count == 0) return noneWord
    return "$typicalWord ${durationText(summary.medianDurationMinutes)}"
}

/**
 * Two columns, both stretched to the taller tile so rows never end ragged, and a
 * full-width tile for an odd last metric instead of a hole beside it.
 */
@Composable
internal fun MetricsGrid(
    metrics: List<StatsMetric>,
    summary: StatsSummary,
    targets: StatsTargets,
    unit: GlucoseUnit,
    modifier: Modifier = Modifier
) {
    val specs = metrics.map { metricSpec(it, summary, targets, unit) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        var index = 0
        while (index < specs.size) {
            val left = specs[index]
            val right = specs.getOrNull(index + 1)
            if (right == null) {
                MetricTile(spec = left, modifier = Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricTile(
                        spec = left,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    MetricTile(
                        spec = right,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
            index += 2
        }
    }
}

/**
 * Fixed three-line structure — label, value, status — so every tile is the same shape
 * whatever the numbers are. The previous version measured its own text to decide
 * between two layouts, which is what produced the ragged rows.
 */
@Composable
internal fun MetricTile(
    spec: MetricSpec,
    modifier: Modifier = Modifier
) {
    var expanded by remember(spec.metric) { mutableStateOf(false) }
    val expandable = !spec.infoText.isNullOrBlank()
    val shape = statsCardShape(20.dp, 12.dp)
    Column(
        modifier = modifier
            .animateContentSize()
            .clip(shape)
            .background(spec.tone.copy(alpha = 0.09f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh))
            .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (expandable) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Text(
            text = spec.value,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (spec.statusLine.isNotBlank()) {
            Text(
                text = spec.statusLine,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = spec.tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(
            visible = expandable && expanded,
            enter = fadeIn(tween(170)) + expandVertically(tween(220)),
            exit = fadeOut(tween(130)) + shrinkVertically(tween(180))
        ) {
            Text(
                text = spec.infoText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
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

/**
 * Metrics the user pinned in Arrange, shown on the dashboard over the last 24 hours.
 *
 * Renders nothing until something is pinned, so the dashboard is unchanged for anyone
 * who never opens the arrange screen.
 */
@Composable
fun PinnedStatsStrip(
    history: List<GlucosePoint>,
    targetLowMgDl: Float,
    targetHighMgDl: Float,
    veryLowMgDl: Float,
    veryHighMgDl: Float,
    isMmol: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(context) { StatsLayoutStore.ensureLoaded(context) }
    val layout by StatsLayoutStore.state.collectAsState()
    val pinned = layout.dashboardMetrics
    if (pinned.isEmpty() || history.isEmpty()) return

    val targets = remember(targetLowMgDl, targetHighMgDl, veryLowMgDl, veryHighMgDl) {
        StatsTargets(
            lowMgDl = targetLowMgDl,
            highMgDl = targetHighMgDl,
            veryLowMgDl = veryLowMgDl,
            veryHighMgDl = veryHighMgDl
        )
    }
    val summary = remember(history, targets) {
        val end = history.last().timestamp
        val start = end - 24L * 60L * 60L * 1000L
        val window = history.filter { it.timestamp >= start }
        StatsAnalytics.dashboardSummary(
            history = window,
            targets = targets,
            range = StatsDateRange(startMillis = start, endMillis = end)
        )
    }
    if (summary.readingCount == 0) return
    val unit = if (isMmol) GlucoseUnit.MMOL else GlucoseUnit.MGDL

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.stats_pinned_last_24h),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            pinned.forEach { metric ->
                PinnedMetricChip(
                    spec = metricSpec(metric, summary, targets, unit),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

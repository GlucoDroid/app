package tk.glucodata.ui.stats

import androidx.annotation.StringRes
import tk.glucodata.R
import tk.glucodata.ui.GlucosePoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class StatsTimeRange(val labelResId: Int, val days: Int) {
    DAY_1(R.string.range_1d, 1),
    DAY_7(R.string.range_7d, 7),
    DAY_14(R.string.range_14d, 14),
    DAY_30(R.string.range_30d, 30),
    DAY_90(R.string.range_90d, 90),
    DAY_ALL(R.string.range_all, 0)
}

enum class GlucoseUnit {
    MGDL,
    MMOL
}

data class StatsTargets(
    val lowMgDl: Float = 70f,
    val highMgDl: Float = 180f,
    val veryLowMgDl: Float = 54f,
    val veryHighMgDl: Float = 250f
)

data class TimeInRangeBreakdown(
    val veryLowPercent: Float = 0f,
    val lowPercent: Float = 0f,
    val inRangePercent: Float = 0f,
    val highPercent: Float = 0f,
    val veryHighPercent: Float = 0f
) {
    val belowRangePercent: Float
        get() = veryLowPercent + lowPercent

    val aboveRangePercent: Float
        get() = highPercent + veryHighPercent
}

data class AgpHourBin(
    val hour: Int,
    val p10MgDl: Float? = null,
    val p25MgDl: Float? = null,
    val medianMgDl: Float? = null,
    val p75MgDl: Float? = null,
    val p90MgDl: Float? = null,
    val sampleCount: Int = 0
)

data class DailyStats(
    val date: LocalDate,
    val averageMgDl: Float,
    val inRangePercent: Float,
    val readingCount: Int
)

enum class InsightSeverity {
    POSITIVE,
    ATTENTION,
    CAUTION
}

data class StatsInsight(
    val title: String,
    val message: String,
    val severity: InsightSeverity
)

data class TemperaturePoint(
    val timestamp: Long,
    val temperatureCelsius: Float
)

data class StatsDateRange(
    val startMillis: Long,
    val endMillis: Long
) {
    val daySpan: Int
        get() {
            val zone = ZoneId.systemDefault()
            val startDate = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
            return (ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0L) + 1L).toInt()
        }
}

data class GviScore(
    val value: Float = 1f,
    @param:StringRes val labelResId: Int = R.string.gvi_excellent,
    val stability: Float = 100f,
    val rateOfChange: Float = 0f
)

data class PsgScore(
    val baselineMgDl: Float = 0f,
    @param:StringRes val labelResId: Int = R.string.psg_stable,
    val trend: Float = 0f,
    val confidence: Float = 0f
)

enum class GriZone(@param:StringRes val labelResId: Int) {
    A(R.string.gri_zone_a),
    B(R.string.gri_zone_b),
    C(R.string.gri_zone_c),
    D(R.string.gri_zone_d),
    E(R.string.gri_zone_e);

    companion object {
        fun of(value: Float): GriZone = when {
            value < 20f -> A
            value < 40f -> B
            value < 60f -> C
            value < 80f -> D
            else -> E
        }
    }
}

data class GlycemiaRiskIndex(
    val value: Float = 0f,
    val zone: GriZone = GriZone.A,
    val hypoComponent: Float = 0f,
    val hyperComponent: Float = 0f,
    val veryLowPercent: Float = 0f,
    val lowPercent: Float = 0f,
    val highPercent: Float = 0f,
    val veryHighPercent: Float = 0f
)

data class RiskIndices(
    val lbgi: Float = 0f,
    val hbgi: Float = 0f
)

data class SensorCoverage(
    val percent: Float = 0f,
    val cadenceMillis: Long = 5L * 60_000L,
    val readingCount: Int = 0,
    val daysWithData: Int = 0,
    val windowDays: Float = 0f
)

enum class EpisodeKind {
    LOW,
    HIGH
}

data class GlucoseEpisode(
    val kind: EpisodeKind,
    val startMillis: Long,
    val endMillis: Long,
    val durationMinutes: Int,
    val extremeMgDl: Float,
    val severe: Boolean
)

data class EpisodeSummary(
    val kind: EpisodeKind,
    val count: Int = 0,
    val severeCount: Int = 0,
    val medianDurationMinutes: Int = 0,
    val longestDurationMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val perDay: Float = 0f,
    val busiestBlockStartHour: Int = 0,
    val busiestBlockCount: Int = 0
)

enum class DayPart(
    @param:StringRes val labelResId: Int,
    @param:StringRes val pluralLabelResId: Int,
    val startHour: Int
) {
    NIGHT(R.string.daypart_night, R.string.daypart_night_plural, 0),
    MORNING(R.string.daypart_morning, R.string.daypart_morning_plural, 6),
    AFTERNOON(R.string.daypart_afternoon, R.string.daypart_afternoon_plural, 12),
    EVENING(R.string.daypart_evening, R.string.daypart_evening_plural, 18)
}

data class DayPartStats(
    val part: DayPart,
    val averageMgDl: Float,
    val tir: TimeInRangeBreakdown,
    val readingCount: Int
)

data class WeekdayStats(
    val dayOfWeek: DayOfWeek,
    val averageMgDl: Float,
    val tir: TimeInRangeBreakdown,
    val readingCount: Int,
    val dayCount: Int
)

data class DayBreakdown(
    val date: LocalDate,
    val averageMgDl: Float,
    val minMgDl: Float,
    val maxMgDl: Float,
    val tir: TimeInRangeBreakdown,
    val readingCount: Int,
    val coveragePercent: Float
)

data class PeriodScalars(
    val readingCount: Int = 0,
    val averageMgDl: Float = 0f,
    val inRangePercent: Float = 0f,
    val cvPercent: Float = 0f,
    val griValue: Float = 0f
)

data class StatsComparison(
    val inRangeDelta: Float,
    val averageDelta: Float,
    val cvDelta: Float,
    val griDelta: Float,
    val previous: PeriodScalars
)

enum class FindingKind {
    SPARSE_COVERAGE,
    LOWS_CLUSTER,
    SEVERE_LOWS,
    LONG_HIGHS,
    DAYPART_HIGH,
    DAYPART_LOW,
    TREND_UP,
    TREND_DOWN,
    STEADY_STREAK,
    ROUGH_DAYS,
    VARIABILITY,
    ON_TARGET
}

data class StatsFinding(
    val kind: FindingKind,
    val severity: InsightSeverity,
    val primary: Float = 0f,
    val secondary: Float = 0f,
    val hour: Int = 0,
    val dayPart: DayPart? = null
)

data class FindingInput(
    val tir: TimeInRangeBreakdown,
    val averageMgDl: Float,
    val cvPercent: Float,
    val coverage: SensorCoverage,
    val lowEpisodes: EpisodeSummary,
    val highEpisodes: EpisodeSummary,
    val dayParts: List<DayPartStats>,
    val days: List<DayBreakdown>,
    val comparison: StatsComparison?
)

data class StatsSummary(
    val readingCount: Int = 0,
    val avgMgDl: Float = 0f,
    val p25MgDl: Float = 0f,
    val medianMgDl: Float = 0f,
    val p75MgDl: Float = 0f,
    val stdDevMgDl: Float = 0f,
    val cvPercent: Float = 0f,
    val gmiPercent: Float = 0f,
    val gvi: GviScore = GviScore(),
    val psg: PsgScore = PsgScore(),
    val minMgDl: Float = 0f,
    val maxMgDl: Float = 0f,
    val firstTimestamp: Long = 0L,
    val lastTimestamp: Long = 0L,
    val tir: TimeInRangeBreakdown = TimeInRangeBreakdown(),
    val tightRangePercent: Float = 0f,
    val gri: GlycemiaRiskIndex = GlycemiaRiskIndex(),
    val risk: RiskIndices = RiskIndices(),
    val coverage: SensorCoverage = SensorCoverage(),
    val episodes: List<GlucoseEpisode> = emptyList(),
    val lowEpisodes: EpisodeSummary = EpisodeSummary(EpisodeKind.LOW),
    val highEpisodes: EpisodeSummary = EpisodeSummary(EpisodeKind.HIGH),
    val dayParts: List<DayPartStats> = emptyList(),
    val weekdays: List<WeekdayStats> = emptyList(),
    val days: List<DayBreakdown> = emptyList(),
    val comparison: StatsComparison? = null,
    val agpByHour: List<AgpHourBin> = emptyList(),
    val dailyStats: List<DailyStats> = emptyList(),
    val insights: List<StatsInsight> = emptyList()
)

data class StatsUiState(
    val selectedRange: StatsTimeRange? = StatsTimeRange.DAY_14,
    val activeRange: StatsDateRange? = null,
    val availableRange: StatsDateRange? = null,
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val targets: StatsTargets = StatsTargets(),
    val isLoading: Boolean = true,
    val hasSensor: Boolean = true,
    val summary: StatsSummary = StatsSummary(),
    val temperaturePoints: List<TemperaturePoint> = emptyList(),
    val readings: List<GlucosePoint> = emptyList()
)

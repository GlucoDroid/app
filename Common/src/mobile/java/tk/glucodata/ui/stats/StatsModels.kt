package tk.glucodata.ui.stats

import androidx.annotation.StringRes
import tk.glucodata.R
import tk.glucodata.ui.GlucosePoint
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

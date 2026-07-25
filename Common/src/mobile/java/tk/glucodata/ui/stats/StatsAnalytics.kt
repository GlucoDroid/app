package tk.glucodata.ui.stats

import tk.glucodata.ui.GlucosePoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure glucose analytics — no Android, no Compose, no resources.
 *
 * Everything here is deterministic and unit-tested in
 * `Common/src/test/java/tk/glucodata/ui/stats/StatsAnalyticsTests.kt`. Screen code
 * only formats what these functions return; anything that needs a `Context` (strings,
 * colours) stays in the ViewModel or the Compose layer.
 *
 * Inputs are always mg/dL and always sorted ascending by timestamp.
 */
object StatsAnalytics {

    /**
     * GRI and LBGI/HBGI are defined on fixed clinical thresholds, not on the user's
     * personal target band. Keeping them fixed is what makes the numbers comparable
     * to a clinic report; the personal targets still drive TIR and everything else.
     */
    const val CLINICAL_VERY_LOW_MGDL = 54f
    const val CLINICAL_LOW_MGDL = 70f
    const val CLINICAL_HIGH_MGDL = 180f
    const val CLINICAL_VERY_HIGH_MGDL = 250f

    /** Time in tight range (2023 consensus): 70–140 mg/dL. */
    const val TIGHT_LOW_MGDL = 70f
    const val TIGHT_HIGH_MGDL = 140f

    /** An excursion has to last this long to count as an episode. */
    const val MIN_EPISODE_MINUTES = 15L

    /** Two excursions closer than this are the same episode with a blip in the middle. */
    private const val EPISODE_MERGE_MINUTES = 15L

    /** A reading gap wider than this ends the episode — we cannot claim what happened. */
    private const val MAX_EPISODE_GAP_MINUTES = 30L

    private const val MINUTE_MS = 60_000L
    private const val DEFAULT_CADENCE_MS = 5L * MINUTE_MS

    // ---------------------------------------------------------------- cadence

    /**
     * Median gap between readings, clamped to a sane sensor cadence. Used to turn a
     * reading count into minutes, so 1-minute sensors are not reported as 5× the
     * exposure of a 5-minute sensor.
     */
    fun estimateCadenceMillis(history: List<GlucosePoint>): Long {
        if (history.size < 3) return DEFAULT_CADENCE_MS
        val deltas = ArrayList<Long>(history.size - 1)
        for (index in 1..history.lastIndex) {
            val delta = history[index].timestamp - history[index - 1].timestamp
            if (delta in MINUTE_MS..(15L * MINUTE_MS)) deltas.add(delta)
        }
        if (deltas.isEmpty()) return DEFAULT_CADENCE_MS
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    // -------------------------------------------------------------- exposure

    /**
     * How much of the window the sensor actually covered. Percentages computed over
     * a half-empty window mean something different, so the screen says this out loud.
     */
    fun sensorCoverage(
        history: List<GlucosePoint>,
        range: StatsDateRange?,
        cadenceMillis: Long = estimateCadenceMillis(history)
    ): SensorCoverage {
        if (history.isEmpty()) return SensorCoverage()
        val start = range?.startMillis ?: history.first().timestamp
        val end = range?.endMillis ?: history.last().timestamp
        val windowMillis = (end - start).coerceAtLeast(cadenceMillis)
        val expected = (windowMillis.toDouble() / cadenceMillis.toDouble()).coerceAtLeast(1.0)
        val percent = ((history.size.toDouble() / expected) * 100.0).coerceIn(0.0, 100.0).toFloat()
        val zone = ZoneId.systemDefault()
        val days = history.mapTo(HashSet()) { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        return SensorCoverage(
            percent = percent,
            cadenceMillis = cadenceMillis,
            readingCount = history.size,
            daysWithData = days.size,
            windowDays = ((windowMillis / (24.0 * 60.0 * 60.0 * 1000.0)).toFloat()).coerceAtLeast(0f)
        )
    }

    // ------------------------------------------------------------------- GRI

    /**
     * Glycemia Risk Index (Klonoff et al., 2022): one 0–100 number that weights lows
     * roughly three times as heavily as highs, so it moves the way clinicians think.
     */
    fun glycemiaRiskIndex(values: List<Float>): GlycemiaRiskIndex {
        if (values.isEmpty()) return GlycemiaRiskIndex()
        val total = values.size.toFloat()
        val veryLow = values.count { it < CLINICAL_VERY_LOW_MGDL } / total * 100f
        val low = values.count { it >= CLINICAL_VERY_LOW_MGDL && it < CLINICAL_LOW_MGDL } / total * 100f
        val veryHigh = values.count { it > CLINICAL_VERY_HIGH_MGDL } / total * 100f
        val high = values.count { it > CLINICAL_HIGH_MGDL && it <= CLINICAL_VERY_HIGH_MGDL } / total * 100f

        val hypo = (3.0f * veryLow) + (2.4f * low)
        val hyper = (1.6f * veryHigh) + (0.8f * high)
        val gri = (hypo + hyper).coerceIn(0f, 100f)
        return GlycemiaRiskIndex(
            value = gri,
            zone = GriZone.of(gri),
            hypoComponent = hypo,
            hyperComponent = hyper,
            veryLowPercent = veryLow,
            lowPercent = low,
            highPercent = high,
            veryHighPercent = veryHigh
        )
    }

    /**
     * Kovatchev's symmetrised risk indices. LBGI answers "how bad are the lows",
     * HBGI "how bad are the highs". Risk grows non-linearly with distance from
     * range, so depth counts for far more than a plain count of excursions.
     */
    fun riskIndices(values: List<Float>): RiskIndices {
        if (values.isEmpty()) return RiskIndices()
        var lowSum = 0.0
        var highSum = 0.0
        var counted = 0
        values.forEach { value ->
            if (value <= 0f) return@forEach
            val f = 1.509 * ((ln(value.toDouble())).pow(1.084) - 5.381)
            val risk = 10.0 * f * f
            if (f < 0) lowSum += risk else highSum += risk
            counted++
        }
        if (counted == 0) return RiskIndices()
        return RiskIndices(
            lbgi = (lowSum / counted).toFloat(),
            hbgi = (highSum / counted).toFloat()
        )
    }

    /** Share of readings inside the 70–140 mg/dL tight band. */
    fun tightRangePercent(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val inside = values.count { it in TIGHT_LOW_MGDL..TIGHT_HIGH_MGDL }
        return inside.toFloat() / values.size.toFloat() * 100f
    }

    // -------------------------------------------------------------- episodes

    /**
     * Turns readings into discrete excursions. A 4% time-below made of one long night
     * is a different problem from twelve short dips, and only episodes show which it is.
     *
     * Rules: consecutive readings past the threshold form a run; runs separated by less
     * than [EPISODE_MERGE_MINUTES] merge; a reading gap over [MAX_EPISODE_GAP_MINUTES]
     * ends the run because the data cannot say what happened in between; runs shorter
     * than [MIN_EPISODE_MINUTES] are noise.
     */
    fun detectEpisodes(history: List<GlucosePoint>, targets: StatsTargets): List<GlucoseEpisode> {
        if (history.size < 2) return emptyList()
        val cadence = estimateCadenceMillis(history)
        val lows = scanEpisodes(history, cadence, EpisodeKind.LOW, targets.lowMgDl, targets.veryLowMgDl)
        val highs = scanEpisodes(history, cadence, EpisodeKind.HIGH, targets.highMgDl, targets.veryHighMgDl)
        return (lows + highs).sortedBy { it.startMillis }
    }

    private fun scanEpisodes(
        history: List<GlucosePoint>,
        cadenceMillis: Long,
        kind: EpisodeKind,
        threshold: Float,
        severeThreshold: Float
    ): List<GlucoseEpisode> {
        val maxGap = MAX_EPISODE_GAP_MINUTES * MINUTE_MS
        fun past(value: Float) = if (kind == EpisodeKind.LOW) value < threshold else value > threshold

        // Pass 1 — raw runs of readings past the threshold.
        val runs = ArrayList<MutableList<GlucosePoint>>()
        var current: MutableList<GlucosePoint>? = null
        var previousTimestamp = Long.MIN_VALUE
        history.forEach { point ->
            val gapBroken = previousTimestamp != Long.MIN_VALUE &&
                point.timestamp - previousTimestamp > maxGap
            if (gapBroken) {
                current?.let { runs.add(it) }
                current = null
            }
            if (past(point.value)) {
                val run = current ?: ArrayList<GlucosePoint>().also { current = it }
                run.add(point)
            } else {
                current?.let { runs.add(it) }
                current = null
            }
            previousTimestamp = point.timestamp
        }
        current?.let { runs.add(it) }
        if (runs.isEmpty()) return emptyList()

        // Pass 2 — merge runs interrupted by a brief return inside the band.
        val mergeWindow = EPISODE_MERGE_MINUTES * MINUTE_MS
        val merged = ArrayList<MutableList<GlucosePoint>>()
        runs.forEach { run ->
            val last = merged.lastOrNull()
            if (last != null && run.first().timestamp - last.last().timestamp <= mergeWindow) {
                last.addAll(run)
            } else {
                merged.add(run)
            }
        }

        // Pass 3 — keep the runs long enough to be an episode.
        return merged.mapNotNull { run ->
            val start = run.first().timestamp
            // The last reading still represents one cadence interval of exposure.
            val end = run.last().timestamp + cadenceMillis
            val durationMinutes = (end - start) / MINUTE_MS
            if (durationMinutes < MIN_EPISODE_MINUTES) return@mapNotNull null
            val extreme = if (kind == EpisodeKind.LOW) {
                run.minOf { it.value }
            } else {
                run.maxOf { it.value }
            }
            val severe = if (kind == EpisodeKind.LOW) extreme < severeThreshold else extreme > severeThreshold
            GlucoseEpisode(
                kind = kind,
                startMillis = start,
                endMillis = end,
                durationMinutes = durationMinutes.toInt(),
                extremeMgDl = extreme,
                severe = severe
            )
        }
    }

    /** Rolls episodes of one kind into the numbers the card shows. */
    fun summarizeEpisodes(
        episodes: List<GlucoseEpisode>,
        kind: EpisodeKind,
        windowDays: Float
    ): EpisodeSummary {
        val matching = episodes.filter { it.kind == kind }
        if (matching.isEmpty()) return EpisodeSummary(kind = kind)
        val durations = matching.map { it.durationMinutes }.sorted()
        val zone = ZoneId.systemDefault()
        val startHours = matching.map { Instant.ofEpochMilli(it.startMillis).atZone(zone).hour }
        // Six four-hour blocks; the busiest one is what the copy calls out.
        val blockCounts = IntArray(6)
        startHours.forEach { blockCounts[it / 4]++ }
        val busiestBlock = blockCounts.indices.maxByOrNull { blockCounts[it] } ?: 0
        return EpisodeSummary(
            kind = kind,
            count = matching.size,
            severeCount = matching.count { it.severe },
            medianDurationMinutes = durations[durations.size / 2],
            longestDurationMinutes = durations.last(),
            totalMinutes = durations.sum(),
            perDay = if (windowDays > 0f) matching.size / windowDays else 0f,
            busiestBlockStartHour = busiestBlock * 4,
            busiestBlockCount = blockCounts[busiestBlock]
        )
    }

    // -------------------------------------------------------- time-of-day cuts

    /** Four six-hour blocks. Enough resolution to act on, few enough to read at a glance. */
    fun dayPartStats(history: List<GlucosePoint>, targets: StatsTargets): List<DayPartStats> {
        if (history.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val buckets = Array(DayPart.entries.size) { ArrayList<Float>() }
        history.forEach { point ->
            val hour = Instant.ofEpochMilli(point.timestamp).atZone(zone).hour
            buckets[hour / 6].add(point.value)
        }
        return DayPart.entries.mapIndexed { index, part ->
            val values = buckets[index]
            DayPartStats(
                part = part,
                averageMgDl = if (values.isEmpty()) 0f else values.average().toFloat(),
                tir = timeInRange(values, targets),
                readingCount = values.size
            )
        }
    }

    /** Mon–Sun averages. Surfaces the weekend/weekday split without asking for it. */
    fun weekdayStats(history: List<GlucosePoint>, targets: StatsTargets): List<WeekdayStats> {
        if (history.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val buckets = Array(7) { ArrayList<Float>() }
        val days = Array(7) { HashSet<LocalDate>() }
        history.forEach { point ->
            val date = Instant.ofEpochMilli(point.timestamp).atZone(zone).toLocalDate()
            val index = date.dayOfWeek.value - 1
            buckets[index].add(point.value)
            days[index].add(date)
        }
        return (0..6).map { index ->
            val values = buckets[index]
            WeekdayStats(
                dayOfWeek = DayOfWeek.of(index + 1),
                averageMgDl = if (values.isEmpty()) 0f else values.average().toFloat(),
                tir = timeInRange(values, targets),
                readingCount = values.size,
                dayCount = days[index].size
            )
        }
    }

    /** One row per calendar day — the input for the day-by-day grid and its drill-down. */
    fun dayBreakdowns(history: List<GlucosePoint>, targets: StatsTargets): List<DayBreakdown> {
        if (history.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val cadence = estimateCadenceMillis(history)
        val expectedPerDay = (24.0 * 60.0 * 60.0 * 1000.0 / cadence).coerceAtLeast(1.0)
        return history
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .entries
            .sortedBy { it.key }
            .map { (date, points) ->
                val values = points.map { it.value }
                DayBreakdown(
                    date = date,
                    averageMgDl = values.average().toFloat(),
                    minMgDl = values.min(),
                    maxMgDl = values.max(),
                    tir = timeInRange(values, targets),
                    readingCount = values.size,
                    coveragePercent = ((values.size / expectedPerDay) * 100.0)
                        .coerceIn(0.0, 100.0)
                        .toFloat()
                )
            }
    }

    // ------------------------------------------------------------ comparison

    /** The scalars worth carrying across two periods. Cheap to compute, cheap to diff. */
    fun periodScalars(values: List<Float>, targets: StatsTargets): PeriodScalars {
        if (values.isEmpty()) return PeriodScalars()
        val average = values.average().toFloat()
        val variance = values.fold(0.0) { acc, value ->
            val diff = value - average
            acc + diff * diff
        } / values.size
        val stdDev = sqrt(variance).toFloat()
        return PeriodScalars(
            readingCount = values.size,
            averageMgDl = average,
            inRangePercent = timeInRange(values, targets).inRangePercent,
            cvPercent = if (average > 0f) stdDev / average * 100f else 0f,
            griValue = glycemiaRiskIndex(values).value
        )
    }

    fun compare(current: PeriodScalars, previous: PeriodScalars): StatsComparison? {
        if (current.readingCount == 0 || previous.readingCount == 0) return null
        return StatsComparison(
            inRangeDelta = current.inRangePercent - previous.inRangePercent,
            averageDelta = current.averageMgDl - previous.averageMgDl,
            cvDelta = current.cvPercent - previous.cvPercent,
            griDelta = current.griValue - previous.griValue,
            previous = previous
        )
    }

    // -------------------------------------------------------------- findings

    /**
     * Detects what is worth saying, with the numbers attached. Wording lives in
     * `strings.xml` — this decides *whether* a statement is true, never how it reads.
     * Ordered most-actionable first; the screen takes the top few.
     */
    fun findings(input: FindingInput): List<StatsFinding> {
        val findings = ArrayList<StatsFinding>()

        if (input.coverage.percent < 70f && input.coverage.readingCount > 0) {
            findings += StatsFinding(
                kind = FindingKind.SPARSE_COVERAGE,
                severity = InsightSeverity.ATTENTION,
                primary = input.coverage.percent
            )
        }

        val lows = input.lowEpisodes
        if (lows.count > 0 && lows.busiestBlockCount * 2 >= lows.count && lows.count >= 3) {
            findings += StatsFinding(
                kind = FindingKind.LOWS_CLUSTER,
                severity = InsightSeverity.CAUTION,
                primary = lows.busiestBlockCount.toFloat(),
                secondary = lows.count.toFloat(),
                hour = lows.busiestBlockStartHour
            )
        }

        if (lows.severeCount > 0) {
            findings += StatsFinding(
                kind = FindingKind.SEVERE_LOWS,
                severity = InsightSeverity.CAUTION,
                primary = lows.severeCount.toFloat(),
                secondary = lows.totalMinutes.toFloat()
            )
        }

        val highs = input.highEpisodes
        if (highs.longestDurationMinutes >= 180) {
            findings += StatsFinding(
                kind = FindingKind.LONG_HIGHS,
                severity = InsightSeverity.ATTENTION,
                primary = (highs.longestDurationMinutes / 60f),
                secondary = highs.count.toFloat()
            )
        }

        // The day part that is furthest from the rest of the day, in either direction.
        val parts = input.dayParts.filter { it.readingCount > 0 }
        if (parts.size >= 3) {
            val overall = input.averageMgDl
            val outlier = parts.maxByOrNull { kotlin.math.abs(it.averageMgDl - overall) }
            if (outlier != null && kotlin.math.abs(outlier.averageMgDl - overall) >= 15f) {
                findings += StatsFinding(
                    kind = if (outlier.averageMgDl > overall) FindingKind.DAYPART_HIGH else FindingKind.DAYPART_LOW,
                    severity = InsightSeverity.ATTENTION,
                    primary = outlier.averageMgDl,
                    secondary = kotlin.math.abs(outlier.averageMgDl - overall),
                    dayPart = outlier.part
                )
            }
        }

        input.comparison?.let { comparison ->
            if (kotlin.math.abs(comparison.inRangeDelta) >= 3f) {
                findings += StatsFinding(
                    kind = if (comparison.inRangeDelta > 0f) FindingKind.TREND_UP else FindingKind.TREND_DOWN,
                    severity = if (comparison.inRangeDelta > 0f) InsightSeverity.POSITIVE else InsightSeverity.ATTENTION,
                    primary = kotlin.math.abs(comparison.inRangeDelta),
                    secondary = comparison.previous.inRangePercent
                )
            }
        }

        val steadyDays = input.days.count { it.tir.inRangePercent >= 70f }
        if (input.days.size >= 5 && steadyDays == input.days.size) {
            findings += StatsFinding(
                kind = FindingKind.STEADY_STREAK,
                severity = InsightSeverity.POSITIVE,
                primary = input.days.size.toFloat()
            )
        } else if (input.days.size >= 7) {
            val roughDays = input.days.filter { it.tir.inRangePercent < 50f }
            if (roughDays.size >= 2) {
                findings += StatsFinding(
                    kind = FindingKind.ROUGH_DAYS,
                    severity = InsightSeverity.ATTENTION,
                    primary = roughDays.size.toFloat(),
                    secondary = input.days.size.toFloat()
                )
            }
        }

        if (input.cvPercent > 36f) {
            findings += StatsFinding(
                kind = FindingKind.VARIABILITY,
                severity = InsightSeverity.ATTENTION,
                primary = input.cvPercent
            )
        }

        if (findings.isEmpty() && input.tir.inRangePercent >= 70f && input.cvPercent <= 36f) {
            findings += StatsFinding(
                kind = FindingKind.ON_TARGET,
                severity = InsightSeverity.POSITIVE,
                primary = input.tir.inRangePercent,
                secondary = input.cvPercent
            )
        }

        return findings
    }

    // --------------------------------------------------------------- helpers

    fun timeInRange(values: List<Float>, targets: StatsTargets): TimeInRangeBreakdown {
        if (values.isEmpty()) return TimeInRangeBreakdown()
        val veryLow = targets.veryLowMgDl.coerceAtLeast(35f)
        val low = targets.lowMgDl.coerceAtLeast(veryLow + 1f)
        val high = targets.highMgDl.coerceAtLeast(low + 1f)
        val veryHigh = targets.veryHighMgDl.coerceAtLeast(high + 1f)
        val total = values.size.toFloat()
        return TimeInRangeBreakdown(
            veryLowPercent = values.count { it < veryLow } / total * 100f,
            lowPercent = values.count { it >= veryLow && it < low } / total * 100f,
            inRangePercent = values.count { it in low..high } / total * 100f,
            highPercent = values.count { it > high && it < veryHigh } / total * 100f,
            veryHighPercent = values.count { it >= veryHigh } / total * 100f
        )
    }
}

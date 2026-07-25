package tk.glucodata.ui.stats

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.R

/**
 * Which sections the statistics screen shows and in what order.
 *
 * Everyone reads this screen for something different — one person opens it for
 * overnight lows, the next for the A1c estimate — so the order is the user's, not
 * ours. The defaults below are only a starting arrangement.
 */
enum class StatsCard(@param:StringRes val titleResId: Int) {
    OVERVIEW(R.string.stats_card_overview),
    METRICS(R.string.stats_card_metrics),
    EPISODES(R.string.episodes_title),
    PATTERNS(R.string.stats_patterns),
    DAY_BY_DAY(R.string.stats_day_by_day),
    RISK_INDEX(R.string.gri_title),
    TEMPERATURE(R.string.stats_temperature_title),
    INSIGHTS(R.string.insights);

    companion object {
        /**
         * The risk index sits below the patterns rather than second from the top: it is
         * a summary of what the bands above already showed, not a headline.
         */
        val DEFAULT_ORDER = listOf(
            OVERVIEW,
            METRICS,
            EPISODES,
            PATTERNS,
            DAY_BY_DAY,
            RISK_INDEX,
            TEMPERATURE,
            INSIGHTS
        )
    }
}

/**
 * Individual numbers inside the metrics card. Order and visibility are the user's,
 * and a few can be pinned to the dashboard.
 */
enum class StatsMetric(
    @param:StringRes val titleResId: Int,
    val visibleByDefault: Boolean,
    val pinnable: Boolean
) {
    TIME_IN_RANGE(R.string.tir, false, true),
    AVERAGE(R.string.average_glucose, true, true),
    GMI(R.string.a1c_gmi_label, true, true),
    CV(R.string.cv, true, true),
    TIGHT_RANGE(R.string.stats_tight_range, true, true),
    MEDIAN(R.string.median, false, true),
    IQR(R.string.report_iqr_short, false, false),
    STD_DEV(R.string.std_dev_short, false, false),
    LOW_EPISODES(R.string.episodes_lows, false, true),
    HIGH_EPISODES(R.string.episodes_highs, false, true),
    COVERAGE(R.string.stats_card_coverage, false, true),
    LBGI(R.string.lbgi, false, false),
    HBGI(R.string.hbgi, false, false),
    GVI(R.string.gvi, false, false),
    PSG(R.string.psg, false, false);

    companion object {
        val DEFAULT_ORDER = entries.toList()
    }
}

data class StatsLayoutState(
    val cardOrder: List<StatsCard> = StatsCard.DEFAULT_ORDER,
    val hiddenCards: Set<StatsCard> = emptySet(),
    val metricOrder: List<StatsMetric> = StatsMetric.DEFAULT_ORDER,
    val hiddenMetrics: Set<StatsMetric> = StatsMetric.entries.filterNot { it.visibleByDefault }.toSet(),
    val dashboardMetrics: List<StatsMetric> = emptyList()
) {
    val visibleCards: List<StatsCard> get() = cardOrder.filterNot { it in hiddenCards }
    val visibleMetrics: List<StatsMetric> get() = metricOrder.filterNot { it in hiddenMetrics }
}

/**
 * Persists the layout in the shared preference file the rest of the app already uses.
 * Small enough that a single in-memory [StateFlow] plus a synchronous write is the
 * whole story; both the statistics screen and the dashboard read the same instance.
 */
object StatsLayoutStore {

    private const val PREFS = "tk.glucodata_preferences"
    private const val KEY_CARD_ORDER = "stats_layout_card_order"
    private const val KEY_CARD_HIDDEN = "stats_layout_card_hidden"
    private const val KEY_METRIC_ORDER = "stats_layout_metric_order"
    private const val KEY_METRIC_HIDDEN = "stats_layout_metric_hidden"
    private const val KEY_DASHBOARD = "stats_layout_dashboard_metrics"

    /** Cap on pinned dashboard metrics — the dashboard is not a second stats screen. */
    const val MAX_DASHBOARD_METRICS = 4

    private val _state = MutableStateFlow(StatsLayoutState())
    val state: StateFlow<StatsLayoutState> = _state.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    fun ensureLoaded(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = store
            _state.value = read(store)
        }
    }

    fun setCardOrder(order: List<StatsCard>) = update { current ->
        current.copy(cardOrder = order.distinct() + StatsCard.DEFAULT_ORDER.filterNot { it in order })
    }

    fun setCardHidden(card: StatsCard, hidden: Boolean) = update { current ->
        current.copy(
            hiddenCards = if (hidden) current.hiddenCards + card else current.hiddenCards - card
        )
    }

    fun setMetricOrder(order: List<StatsMetric>) = update { current ->
        current.copy(metricOrder = order.distinct() + StatsMetric.DEFAULT_ORDER.filterNot { it in order })
    }

    fun setMetricHidden(metric: StatsMetric, hidden: Boolean) = update { current ->
        current.copy(
            hiddenMetrics = if (hidden) current.hiddenMetrics + metric else current.hiddenMetrics - metric
        )
    }

    /** Returns false when the pin was rejected because the dashboard is already full. */
    fun setPinnedToDashboard(metric: StatsMetric, pinned: Boolean): Boolean {
        if (!metric.pinnable) return false
        val current = _state.value
        if (pinned && current.dashboardMetrics.size >= MAX_DASHBOARD_METRICS &&
            metric !in current.dashboardMetrics
        ) {
            return false
        }
        update { state ->
            state.copy(
                dashboardMetrics = if (pinned) {
                    (state.dashboardMetrics + metric).distinct()
                } else {
                    state.dashboardMetrics - metric
                }
            )
        }
        return true
    }

    fun resetLayout() = update { StatsLayoutState() }

    private inline fun update(transform: (StatsLayoutState) -> StatsLayoutState) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.edit()
            ?.putString(KEY_CARD_ORDER, next.cardOrder.joinToString(",") { it.name })
            ?.putString(KEY_CARD_HIDDEN, next.hiddenCards.joinToString(",") { it.name })
            ?.putString(KEY_METRIC_ORDER, next.metricOrder.joinToString(",") { it.name })
            ?.putString(KEY_METRIC_HIDDEN, next.hiddenMetrics.joinToString(",") { it.name })
            ?.putString(KEY_DASHBOARD, next.dashboardMetrics.joinToString(",") { it.name })
            ?.apply()
    }

    private fun read(store: SharedPreferences): StatsLayoutState {
        val defaults = StatsLayoutState()
        return StatsLayoutState(
            cardOrder = readOrder(store, KEY_CARD_ORDER, StatsCard.DEFAULT_ORDER) { StatsCard.valueOf(it) },
            hiddenCards = readSet(store, KEY_CARD_HIDDEN, defaults.hiddenCards) { StatsCard.valueOf(it) },
            metricOrder = readOrder(store, KEY_METRIC_ORDER, StatsMetric.DEFAULT_ORDER) { StatsMetric.valueOf(it) },
            hiddenMetrics = readSet(store, KEY_METRIC_HIDDEN, defaults.hiddenMetrics) { StatsMetric.valueOf(it) },
            dashboardMetrics = readOrder(store, KEY_DASHBOARD, emptyList()) { StatsMetric.valueOf(it) }
                .filter { it.pinnable }
                .take(MAX_DASHBOARD_METRICS)
        )
    }

    /**
     * Stored order wins for everything it names; anything added to the enum since the
     * layout was saved is appended, so a new card appears instead of vanishing.
     */
    private fun <T : Enum<T>> readOrder(
        store: SharedPreferences,
        key: String,
        fallback: List<T>,
        parse: (String) -> T
    ): List<T> {
        val raw = store.getString(key, null) ?: return fallback
        val stored = raw.split(',').mapNotNull { name ->
            runCatching { parse(name.trim()) }.getOrNull()
        }
        if (stored.isEmpty()) return fallback
        return stored.distinct() + fallback.filterNot { it in stored }
    }

    private fun <T : Enum<T>> readSet(
        store: SharedPreferences,
        key: String,
        fallback: Set<T>,
        parse: (String) -> T
    ): Set<T> {
        val raw = store.getString(key, null) ?: return fallback
        return raw.split(',')
            .mapNotNull { name -> runCatching { parse(name.trim()) }.getOrNull() }
            .toSet()
    }
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val mutable = toMutableList()
    mutable.add(to, mutable.removeAt(from))
    return mutable
}

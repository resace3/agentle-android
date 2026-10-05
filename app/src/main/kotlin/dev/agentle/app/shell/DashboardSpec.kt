package dev.agentle.app.shell

import dev.agentle.ai.api.screen.CardPart
import dev.agentle.ai.api.screen.ColumnPart
import dev.agentle.ai.api.screen.ScreenCatalog
import dev.agentle.ai.api.screen.ScreenRules
import dev.agentle.ai.api.screen.ScreenSpec
import dev.agentle.ai.api.screen.TrendChartPart
import kotlinx.serialization.Serializable

/**
 * A metric a user dashboard can show; each one is computed on the device from stored events. The names are the metric
 * codes ChatGPT may pick (ChatReplySchema.METRICS) and are stored, so they are never renamed.
 */
@Serializable
enum class DashboardMetric(val label: String) {
    STEPS("Steps"),
    UNLOCKS("Unlocks"),
    SCREEN_TIME_MINUTES("Screen time (min)"),
    DISTANCE_METERS("Distance (m)"),
    ACTIVE_CALORIES("Active calories (kcal)"),
    EXERCISE_MINUTES("Exercise (min)"),
    SLEEP_MINUTES("Sleep (min)"),
    HEART_RATE_AVG("Heart rate (avg bpm)"),
    RESTING_HEART_RATE("Resting heart rate (bpm)"),
    NOTIFICATIONS("Notifications"),
}

/**
 * A dashboard the user asked ChatGPT to make. It is declarative data only; nothing the model returns is ever executed.
 * A dashboard made since ChatReplySchema v2 has a [screen] of Agentle's parts, drawn with Google's A2UI renderer;
 * [metrics] and [days] then say what the phone computes for it. Older dashboards and the starters have no screen and
 * are drawn from [layout], one card per metric. [validated] and [fromScreen] are the only ways a model answer becomes a
 * spec.
 */
@Serializable
data class DashboardSpec(
    val id: String,
    val title: String,
    val metrics: List<DashboardMetric>,
    val days: Int,
    val screen: ScreenSpec? = null,
) {
    /** The parts to draw: the saved screen, or one card with a bar chart per metric for a dashboard without one. */
    fun layout(): ScreenSpec = screen ?: ScreenSpec(
        title,
        listOf(ColumnPart(ScreenCatalog.ROOT_ID, metrics.indices.map { "card_${it + 1}" })) +
            metrics.flatMapIndexed { index, metric ->
                val n = index + 1
                listOf(CardPart("card_$n", "chart_$n"), TrendChartPart("chart_$n", metric.name, days, "bar"))
            },
    )

    companion object {
        const val MAX_DAYS = 90
        const val MAX_TITLE = 60

        /** A spec from untrusted input, or null when the title is blank, no metric is known or [days] is out of range. */
        fun validated(id: String, title: String, metricNames: List<String>, days: Int): DashboardSpec? {
            val metrics = metricNames.mapNotNull { name -> DashboardMetric.entries.firstOrNull { it.name == name } }.distinct()
            val cleanTitle = title.trim().take(MAX_TITLE)
            return if (cleanTitle.isEmpty() || metrics.isEmpty() || days !in 1..MAX_DAYS) {
                null
            } else {
                DashboardSpec(id, cleanTitle, metrics, days)
            }
        }

        /**
         * A spec for a screen ChatGPT designed, or null when the screen fails [ScreenRules.recheck] (it already passed
         * ChatReplySchema; the recheck also guards the app's own copy) or names a metric the app cannot compute.
         */
        fun fromScreen(id: String, screen: ScreenSpec): DashboardSpec? {
            val metrics = screen.metrics.map { name -> DashboardMetric.entries.firstOrNull { it.name == name } ?: return null }
            val title = screen.title.trim()
            val usable = title.isNotEmpty() && ScreenRules.recheck(screen).isEmpty()
            return if (usable) DashboardSpec(id, title, metrics, screen.days, screen) else null
        }
    }
}

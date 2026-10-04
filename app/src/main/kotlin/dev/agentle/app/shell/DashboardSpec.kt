package dev.agentle.app.shell

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
 * A dashboard the user asked ChatGPT to make. It is declarative data only (a title, metrics and a day range); nothing
 * the model returns is ever executed. [validated] is the only way a model answer becomes a spec.
 */
@Serializable
data class DashboardSpec(val id: String, val title: String, val metrics: List<DashboardMetric>, val days: Int) {
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
    }
}

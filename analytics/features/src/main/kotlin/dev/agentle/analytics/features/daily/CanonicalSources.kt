package dev.agentle.analytics.features.daily

import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType

/** Metrics that more than one source can supply; a metric is never summed across sources. */
public enum class MetricFamily(public val eventTypes: Set<EventType>) {
    STEPS(setOf(EventType.STEP_SAMPLE)),
    HEART_RATE(setOf(EventType.HEART_RATE)),
    RESTING_HEART_RATE(setOf(EventType.RESTING_HEART_RATE)),
    SLEEP(setOf(EventType.SLEEP_SESSION)),
    EXERCISE(setOf(EventType.EXERCISE_SESSION)),
}

/**
 * Source priority per metric (docs/research/05 §7.7, docs/ARCHITECTURE.md §7): the user's canonical source first, then
 * the next ones. Each entry is a connector id (`googlehealth`) or a full source id (`android.steps`); a source no entry
 * names is never used for that metric.
 *
 * How the priority is applied, so a metric is never summed across sources (database-sync-02):
 * - Interval and sample metrics (steps, heart rate, exercise sessions) are fused per minute ([MinuteFusion]): the
 *   highest-priority source that has a record in a minute owns that minute.
 * - Sleep is chosen per night: the main session comes from the highest-priority source that has a session for it.
 * - Civil-date values (a daily resting heart rate) are chosen per date the same way.
 *
 * The default prefers the wearable API, then Health Connect, then (steps only) the phone. Step-counter sensor readings
 * (`TYPE_STEP_COUNTER`) are never stored as step samples (docs/ARCHITECTURE.md §6.4), so they cannot be selected.
 */
public data class CanonicalSourcePolicy(val preferences: Map<MetricFamily, List<String>>) {
    /** Rank of [source] for [family]: index of the first matching entry, or the list size when none matches. */
    public fun rank(family: MetricFamily, source: DataSourceId): Int {
        val list = preferences[family].orEmpty()
        val index = list.indexOfFirst { it == source.value || it == source.connectorId }
        return if (index >= 0) index else list.size
    }

    /** Whether [source] may supply [family] at all. */
    public fun isEligible(family: MetricFamily, source: DataSourceId): Boolean = rank(family, source) < preferences[family].orEmpty().size

    /** The highest-priority eligible source among [candidates] (ties by id), or null. */
    public fun choose(family: MetricFamily, candidates: Collection<DataSourceId>): DataSourceId? =
        candidates.distinct().filter { isEligible(family, it) }.minWithOrNull(compareBy<DataSourceId>({ rank(family, it) }, { it.value }))

    public companion object {
        private val HEALTH = listOf("googlehealth", "healthconnect")

        public val DEFAULT: CanonicalSourcePolicy = CanonicalSourcePolicy(
            mapOf(
                MetricFamily.STEPS to HEALTH + "android",
                MetricFamily.HEART_RATE to HEALTH,
                MetricFamily.RESTING_HEART_RATE to HEALTH,
                MetricFamily.SLEEP to HEALTH,
                MetricFamily.EXERCISE to HEALTH,
            ),
        )
    }
}

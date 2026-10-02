package dev.agentle.core.model

import kotlinx.serialization.Serializable

/**
 * Families of sources: the unit of the "delete wearable data" and "delete Android-collected data" actions
 * (docs/ARCHITECTURE.md §5.5). Derived rows record the families of their inputs in a [Lineage].
 */
@Serializable
public enum class SourceFamily {
    /** The user's wearable: Google Health API and Health Connect. */
    WEARABLE,

    /** On-device Android collectors. */
    ANDROID,

    /** Manual input (mood/energy logs, notes). */
    USER,

    /** Records Agentle produces itself (JITAI deliveries, insights, generated media). */
    AGENTLE,

    /** A connector this version does not know; deleted with every family-scoped action that cannot rule it out. */
    OTHER,
    ;

    public companion object {
        public fun of(connectorId: String): SourceFamily = when (connectorId) {
            in ConnectorIds.WEARABLE -> WEARABLE
            ConnectorIds.ANDROID -> ANDROID
            ConnectorIds.USER -> USER
            ConnectorIds.AGENTLE -> AGENTLE
            else -> OTHER
        }

        public fun of(source: DataSourceId): SourceFamily = of(source.connectorId)
    }
}

/**
 * Deletion lineage of a derived row (red team privacy-ai-07): the source families and data categories its inputs came
 * from. Every derived row (daily summaries, features, insights, decision traces and snapshots, eval-log traces, outcome
 * metrics, generated media, pooled AI text, diagnostics) stores one, so a per-family deletion can find every row whose
 * inputs include the family. [NONE] means the row was computed from no personal input at all.
 */
@Serializable
public data class Lineage(val families: Set<SourceFamily> = emptySet(), val categories: Set<DataCategory> = emptySet()) {
    public val isEmpty: Boolean get() = families.isEmpty() && categories.isEmpty()

    public operator fun plus(other: Lineage): Lineage = Lineage(families + other.families, categories + other.categories)

    public fun includes(family: SourceFamily): Boolean = family in families

    public fun includes(category: DataCategory): Boolean = category in categories

    public companion object {
        public val NONE: Lineage = Lineage()

        public fun of(event: PersonalEvent): Lineage = Lineage(setOf(SourceFamily.of(event.source)), setOf(event.type.category))

        public fun of(events: Iterable<PersonalEvent>): Lineage =
            events.fold(NONE) { acc, event -> acc + of(event) }

        public fun of(vararg families: SourceFamily): Lineage = Lineage(families.toSet())
    }
}

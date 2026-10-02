package dev.agentle.core.model

import kotlinx.serialization.Serializable

/**
 * Where data physically came from, for per-family deletion ("delete wearable data") and AI consent gating:
 * the Google Health API, Health Connect, or the phone itself (on-device collectors, the user's own input and
 * Agentle's own records).
 */
@Serializable
public enum class SourceFamily { GH_API, HEALTH_CONNECT, ON_DEVICE }

/** The family of a source: by connector id; anything that is not a wearable connector was produced on the device. */
public val DataSourceId.family: SourceFamily
    get() = when (connectorId) {
        ConnectorIds.GOOGLE_HEALTH -> SourceFamily.GH_API
        ConnectorIds.HEALTH_CONNECT -> SourceFamily.HEALTH_CONNECT
        else -> SourceFamily.ON_DEVICE
    }

/**
 * Lineage of a derived output (a daily row, a rolling window, an insight, proposal evidence): the union of the data
 * categories and source families of everything it was computed from. AI consent gating and per-family deletion follow
 * it. A consumer that cannot tell the lineage of a value must treat it as [UNKNOWN], which counts as every category and
 * every family (architecture red team, privacy-ai-04/07).
 */
@Serializable
public data class Lineage(val categories: Set<DataCategory> = emptySet(), val sourceFamilies: Set<SourceFamily> = emptySet()) {
    /** Union of two lineages. */
    public operator fun plus(other: Lineage): Lineage =
        Lineage(categories = categories + other.categories, sourceFamilies = sourceFamilies + other.sourceFamilies)

    public val isEmpty: Boolean get() = categories.isEmpty() && sourceFamilies.isEmpty()

    public companion object {
        /** Derived from nothing personal (clock and calendar). */
        public val NONE: Lineage = Lineage()

        /** Lineage that was lost or never recorded: every category and every family. */
        public val UNKNOWN: Lineage = Lineage(DataCategory.entries.toSet(), SourceFamily.entries.toSet())

        /** Lineage of [sources] carrying data of [categories]. */
        public fun of(categories: Set<DataCategory>, sources: Collection<DataSourceId>): Lineage =
            Lineage(categories, sources.mapTo(mutableSetOf()) { it.family })

        /** Union of many lineages; [NONE] when empty. */
        public fun union(all: Iterable<Lineage>): Lineage = all.fold(NONE) { acc, l -> acc + l }
    }
}

package dev.agentle.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
public enum class InsightOrigin { LOCAL, AI }

/** Evidence strength shown to the user; never phrased causally. */
@Serializable
public enum class EvidenceStrength { WEAK, MODERATE, STRONG, INSUFFICIENT }

@Serializable
public enum class InsightState { ACTIVE, DISMISSED, ARCHIVED }

@Serializable
public data class SupportItem(val label: String, val value: String)

/** An insight card (spec §22): title, finding, supporting data, period, strength. */
@Serializable
public data class Insight(
    val id: String,
    val kind: String,
    val title: String,
    val finding: String,
    val supportingData: List<SupportItem> = emptyList(),
    val periodStart: Instant,
    val periodEnd: Instant,
    val strength: EvidenceStrength,
    val confidence: Double? = null,
    val origin: InsightOrigin,
    /**
     * Data categories the insight was computed from (lineage). Defaults to every category: unknown lineage is treated as
     * using all of them, like [sourceFamilies].
     */
    val categories: Set<DataCategory> = DataCategory.entries.toSet(),
    val createdAt: Instant,
    val state: InsightState = InsightState.ACTIVE,
    /**
     * Source families the insight was computed from (lineage, see [Lineage]). Defaults to every family: an insight
     * whose lineage was not recorded must be deleted with any family and gated as if it used all of them.
     */
    val sourceFamilies: Set<SourceFamily> = SourceFamily.entries.toSet(),
)

package dev.agentle.analytics.features

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * The feature values of one evaluation pass (R10 §5.2). Every [FeatureRef] the candidate rules reference is resolved
 * once and memoized, so two rules evaluated in the same pass see identical values. The snapshot is stored with each
 * decision so a decision can be explained and replayed.
 *
 * @property at the evaluation instant.
 * @property zoneId the IANA zone the pass used for local-time features (R10 §10.1).
 * @property values keyed by [FeatureRef.key].
 */
@Serializable
public data class FeatureSnapshot(
    val at: Instant,
    val zoneId: String,
    val values: Map<String, FeatureValue>,
    val catalogVersion: Int = RealtimeFeatureCatalog.VERSION,
) {
    /** The value of [ref], or null when the pass did not resolve it (a caller bug, never a data condition). */
    public operator fun get(ref: FeatureRef): FeatureValue? = values[ref.key]

    public companion object {
        /** Builds a snapshot from resolved pairs. */
        public fun of(at: Instant, zoneId: String, values: Map<FeatureRef, FeatureValue>): FeatureSnapshot =
            FeatureSnapshot(at = at, zoneId = zoneId, values = values.mapKeys { it.key.key })
    }
}

/** Resolves feature values for one pass. Implemented by the realtime feature engine; faked in engine tests. */
public interface FeatureResolver {
    /** Resolves every ref in [refs] at [at]. Never throws for data conditions: unknowns are [FeatureValue.Missing]. */
    public suspend fun resolve(refs: Set<FeatureRef>, at: Instant): FeatureSnapshot
}

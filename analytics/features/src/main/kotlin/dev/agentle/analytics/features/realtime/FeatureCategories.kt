package dev.agentle.analytics.features.realtime

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.model.DataCategory

/**
 * The data category behind every value a `resolve()` returns, through the catalog's
 * [category][dev.agentle.analytics.features.FeatureDefinition.category] (jitai-correctness-19), so deleting a category
 * can scrub stored snapshots. Clock features and unknown ids have none: their values carry no personal data.
 */
public object FeatureCategories {
    /** The category of [ref]'s feature. */
    public fun of(ref: FeatureRef): DataCategory? = RealtimeFeatureCatalog[ref.featureId]?.category

    /** The category of a snapshot key ([FeatureRef.key]: the feature id, then `{args}` when it has args). */
    public fun ofKey(key: String): DataCategory? = RealtimeFeatureCatalog[key.substringBefore('{')]?.category

    /** [snapshot] without the values of the features in [category]. */
    public fun scrub(snapshot: FeatureSnapshot, category: DataCategory): FeatureSnapshot =
        snapshot.copy(values = snapshot.values.filterKeys { ofKey(it) != category })
}

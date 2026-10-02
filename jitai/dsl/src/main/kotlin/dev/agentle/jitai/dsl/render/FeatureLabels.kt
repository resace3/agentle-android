package dev.agentle.jitai.dsl.render

import dev.agentle.analytics.features.FeatureGroup
import dev.agentle.analytics.features.Freshness
import dev.agentle.analytics.features.RealtimeFeatureCatalog

/** Data labels shared by the renderer (SUPPRESSION note, R10 §13.5) and review items (C05, W02, W07). */
internal object FeatureLabels {
    private val DEVICE = mapOf(
        "charging" to "battery data",
        "battery_pct" to "battery data",
        "device_interactive" to "screen data",
        "dnd_active" to "Do Not Disturb data",
        "in_call" to "call data",
        "headphones_connected" to "headphone data",
    )

    /** "step data", "sleep data", ...; null for features that are always known (time, intervention history). */
    fun dataLabel(featureId: String): String? {
        val definition = RealtimeFeatureCatalog[featureId] ?: return null
        return when (definition.group) {
            FeatureGroup.TIME, FeatureGroup.HISTORY -> null
            FeatureGroup.DEVICE -> DEVICE[featureId]
            FeatureGroup.USAGE -> "app usage data"
            FeatureGroup.NOTIFICATIONS -> "notification data"
            FeatureGroup.PLACE -> "location data"
            FeatureGroup.ACTIVITY -> if (featureId == "activity_state") "activity data" else "step data"
            FeatureGroup.SLEEP -> "sleep data"
            FeatureGroup.HEART -> "heart rate data"
        }
    }

    /** The data label, or the feature id when it has none. */
    fun labelOrId(featureId: String): String = dataLabel(featureId) ?: featureId

    /**
     * A remote health feature (steps, sleep, resting heart rate, R10 §11.3 W07): its data reaches the phone when the
     * tracker syncs. `activity_state` is on-device (activity recognition) and is not one.
     */
    fun isRemoteHealth(featureId: String): Boolean {
        val definition = RealtimeFeatureCatalog[featureId] ?: return false
        val remoteGroup = definition.group == FeatureGroup.ACTIVITY || definition.group == FeatureGroup.SLEEP ||
            definition.group == FeatureGroup.HEART
        return remoteGroup && definition.freshness !is Freshness.CollectorCoverage
    }

    /** `Steps data` -> sentence start. */
    fun capitalized(text: String): String = text.replaceFirstChar { it.uppercaseChar() }
}

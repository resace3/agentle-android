package dev.agentle.core.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every screen of the app, shared by all feature modules so one feature can open another without depending on it
 * (docs/ARCHITECTURE.md §16). Each feature registers entries for its own routes; `:app` owns the back stack and the
 * `NavDisplay`, and saves the back stack with [AppRoute]'s generated serializer (a sealed hierarchy, so no
 * polymorphic module is needed).
 *
 * Arguments are ids and filters only, never personal data: routes are saved in the activity's saved state.
 * Adding a route is an additive change; renaming or removing one breaks saved back stacks of installed apps.
 */
@Serializable
public sealed interface AppRoute : NavKey {
    // ---- :feature:onboarding
    @Serializable
    public data object Onboarding : AppRoute

    // ---- :feature:hub
    @Serializable
    public data object Dashboard : AppRoute

    @Serializable
    public data object DataSources : AppRoute

    /** The Permission Center; [capabilityId] scrolls to and highlights one capability (ids from `capabilities.json`). */
    @Serializable
    public data class PermissionCenter(val capabilityId: String? = null) : AppRoute

    /**
     * Normalized events. Filters are optional: [source] is a `DataSourceId` value, [eventType] an `EventType` name,
     * [date] an ISO local date (`2026-10-02`) in the user's zone.
     */
    @Serializable
    public data class Timeline(val source: String? = null, val eventType: String? = null, val date: String? = null) : AppRoute

    // ---- :feature:insights
    @Serializable
    public data object Insights : AppRoute

    @Serializable
    public data class InsightDetail(val insightId: String) : AppRoute

    @Serializable
    public data class Jitais(val tab: JitaiTab = JitaiTab.ACTIVE) : AppRoute

    @Serializable
    public data class JitaiDetail(val jitaiId: String) : AppRoute

    /** Create a JITAI ([editJitaiId] null) or edit one; [mode] picks the manual form or natural-language entry. */
    @Serializable
    public data class JitaiBuilder(val mode: JitaiBuilderMode = JitaiBuilderMode.MANUAL, val editJitaiId: String? = null) : AppRoute

    /** Review of an AI-proposed or natural-language-converted JITAI before the user activates it. */
    @Serializable
    public data class ProposalReview(val proposalId: String) : AppRoute

    /** One delivered (or suppressed) intervention, opened from its notification or from JITAI history. */
    @Serializable
    public data class InterventionDetail(val decisionKey: String) : AppRoute

    // ---- :feature:connections

    /** The wearable connection (Google Health API: the spec's "Fitbit" screen). */
    @Serializable
    public data object Wearable : AppRoute

    @Serializable
    public data object ChatGpt : AppRoute

    @Serializable
    public data object AiDataSharing : AppRoute

    // ---- :feature:settings
    @Serializable
    public data object Settings : AppRoute

    @Serializable
    public data object DataRetention : AppRoute

    @Serializable
    public data object DeleteData : AppRoute

    @Serializable
    public data object BackgroundBehavior : AppRoute

    @Serializable
    public data object NotificationSettings : AppRoute

    @Serializable
    public data object Privacy : AppRoute

    @Serializable
    public data object Diagnostics : AppRoute

    /** Debug tools; registered only in debug builds of the fake flavor (spec §56). */
    @Serializable
    public data object DebugPanel : AppRoute
}

/** Tabs of the JITAI list (spec §22). */
@Serializable
public enum class JitaiTab { ACTIVE, SUGGESTED, PAUSED, HISTORY }

/** How the JITAI builder starts (spec §18): a manual form or a natural-language description. */
@Serializable
public enum class JitaiBuilderMode { MANUAL, NATURAL_LANGUAGE }

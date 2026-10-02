package dev.agentle.feature.settings.port

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.ConnectorMetadata
import kotlinx.coroutines.flow.Flow

/** Collection-frequency profile (docs/research/02 s2.3); stored in DataStore, default [BALANCED]. */
public enum class CollectionProfile { LOW, BALANCED, HIGH }

/** The app's battery-optimization status (`PowerManager.isIgnoringBatteryOptimizations`). */
public enum class BatteryOptimization {
    /** The system default: Doze and App Standby apply. Agentle is designed for it. */
    OPTIMIZED,

    /** The user set the app to "Unrestricted" themselves; Agentle never asks for it. */
    UNRESTRICTED,

    /** Could not be read. */
    UNKNOWN,
}

/** The app standby bucket (`UsageStatsManager.getAppStandbyBucket`), which bounds how often background work runs. */
public enum class StandbyBucket { ACTIVE, WORKING_SET, FREQUENT, RARE, RESTRICTED }

/** What the background-behavior screen shows. */
public data class BackgroundBehaviorState(
    /** The profile the user chose. */
    val profile: CollectionProfile,
    /** The profile in force: `LOW` while the Battery Saver adaptation is active, else [profile]. */
    val effectiveProfile: CollectionProfile,
    /** `PowerManager.isPowerSaveMode()` now. */
    val batterySaverOn: Boolean,
    /** Battery Saver has been on for 30 minutes, so `LOW` is in force until it has been off for 30 minutes (R02 s2.4). */
    val batterySaverAdaptationActive: Boolean,
    val batteryOptimization: BatteryOptimization,
    /** `ActivityManager.isBackgroundRestricted()`: the user restricted background activity for Agentle. */
    val backgroundRestricted: Boolean,
    /** Null when unknown. */
    val standbyBucket: StandbyBucket?,
    /** One entry per data source with its collection switch and permission summary; null when unavailable. */
    val sources: List<ConnectorMetadata>?,
)

/**
 * Background behavior (`AppRoute.BackgroundBehavior`): the collection profile, Battery Saver behavior, battery
 * optimization and a summary of the per-source collection switches (which the Data Sources screen changes).
 */
public interface BackgroundBehaviorPort {
    /**
     * The current state; emits again when the profile, Battery Saver, the standby bucket or a source changes. With
     * nothing connected and no permissions, [BackgroundBehaviorState.sources] lists every source as off or denied
     * (never null for that reason). A running sync does not change it. Emits `Outcome.Failure` (`DatabaseError`,
     * `UnsupportedFeature`) when the profile cannot be read.
     */
    public val state: Flow<Outcome<BackgroundBehaviorState>>

    /**
     * Stores [profile] and applies it at once (`WorkScheduler.apply(profile)`: periodic work re-enqueued with
     * `UPDATE`). Failures: `AppError.DatabaseError`, `UnsupportedFeature`. Main-safe.
     */
    public suspend fun setProfile(profile: CollectionProfile): Outcome<Unit>
}

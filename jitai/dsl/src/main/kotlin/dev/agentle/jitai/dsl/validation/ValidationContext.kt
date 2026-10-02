package dev.agentle.jitai.dsl.validation

import dev.agentle.analytics.features.FeatureDefinition
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.LocalTimeWindow
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.AppLabelResolver
import kotlinx.datetime.LocalTime
import java.util.UUID

/**
 * Everything the validator reads besides the rule itself (R10 §11 "typed proposal + feature catalog + origin + device
 * state"). All members are ports or plain values, so tests pass fakes and the validator stays a pure function of its
 * inputs.
 *
 * @property clock the only time source (`createdAt`, expiry checks of existing rules).
 * @property apps launcher-visible installed apps for app-label resolution (R10 §13.4); null when unknown, which skips
 *   the "package installed" check and leaves every `appLabel` unresolved (C01).
 * @property existingJitais the user's stored rules: ids for `jitai` args and suppression targets (E013, E056),
 *   content hashes for W01 and effective SUPPRESSION rules for W06.
 * @property mediaLibrary bundled media ids for `local_media` (E068).
 * @property ids new rule ids for normalization (R10 §11.4 step 1).
 * @property logger receives E099 events (stage and codes only, never rule text).
 */
public class ValidationContext(
    public val clock: AgentleClock,
    public val apps: AppLabelResolver? = null,
    public val existingJitais: List<JitaiDefinition> = emptyList(),
    public val settings: ValidationSettings = ValidationSettings(),
    public val featureAccess: FeatureAccess = FeatureAccess.ALL_READY,
    public val mediaLibrary: MediaLibrary = MediaLibrary.EMPTY,
    public val ids: IdGenerator = IdGenerator.RANDOM_UUID,
    public val logger: Logger = Logger.NONE,
)

/**
 * User settings the review items depend on (R10 §9.2, §9.3).
 *
 * @property quietHours null when quiet hours are off.
 * @property use24HourClock the device clock format for the rendered sentence (R10 §13.5).
 */
public data class ValidationSettings(
    val quietHours: LocalTimeWindow? = DEFAULT_QUIET_HOURS,
    val globalMaxPerDay: Int = DEFAULT_GLOBAL_MAX_PER_DAY,
    val globalMaxPerWeek: Int = DEFAULT_GLOBAL_MAX_PER_WEEK,
    val use24HourClock: Boolean = false,
) {
    public companion object {
        /** R10 §9.2: 22:00-07:00, on. */
        public val DEFAULT_QUIET_HOURS: LocalTimeWindow = LocalTimeWindow(LocalTime(22, 0), LocalTime(7, 0))
        public const val DEFAULT_GLOBAL_MAX_PER_DAY: Int = 6
        public const val DEFAULT_GLOBAL_MAX_PER_WEEK: Int = 30
    }
}

/** Whether a feature can produce values on this device right now (R10 §11.3 W02, W03). */
public sealed interface FeatureStatus {
    public data object Ready : FeatureStatus

    /** The device cannot provide it (API level, missing Google Play services): warning W02. */
    public data class Unsupported(val reason: String) : FeatureStatus

    /** It needs access that is not granted, e.g. "usage access": warning W03. [access] is shown to the user. */
    public data class NeedsAccess(val access: String) : FeatureStatus
}

/** Port answering [FeatureStatus] per feature; the app implements it from the capability registry. */
public fun interface FeatureAccess {
    public fun statusOf(feature: FeatureDefinition): FeatureStatus

    public companion object {
        /** Every feature ready (the test fixture F0 of R10 §12: every permission and special access granted). */
        public val ALL_READY: FeatureAccess = FeatureAccess { FeatureStatus.Ready }

        /**
         * A simple implementation: features above [apiLevel] are unsupported; a feature whose every source is a key
         * of [missingAccess] needs the access named by the first such source.
         */
        public fun of(apiLevel: Int, missingAccess: Map<String, String> = emptyMap()): FeatureAccess = FeatureAccess { feature ->
            val minApi = feature.minApi
            val blocked = feature.sources.isNotEmpty() && feature.sources.all { it in missingAccess }
            when {
                minApi != null && apiLevel < minApi -> FeatureStatus.Unsupported("API level $minApi or higher")
                blocked -> FeatureStatus.NeedsAccess(missingAccess.getValue(feature.sources.sorted().first()))
                else -> FeatureStatus.Ready
            }
        }
    }
}

/** The app's bundled media catalog (R10 §3.3 `local_media`). */
public fun interface MediaLibrary {
    public fun contains(assetId: String): Boolean

    public companion object {
        public val EMPTY: MediaLibrary = MediaLibrary { false }

        public fun of(ids: Set<String>): MediaLibrary = MediaLibrary { it in ids }
    }
}

/** New lowercase UUID v4 rule ids (R10 §2.1). */
public fun interface IdGenerator {
    public fun newId(): String

    public companion object {
        public val RANDOM_UUID: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}

package dev.agentle.connectors.api

import dev.agentle.core.model.PlaceClass
import kotlinx.coroutines.flow.Flow

/** A user-defined place for the local place class of foreground location fixes. Never leaves the device. */
public data class KnownPlace(
    val placeClass: PlaceClass,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = DEFAULT_RADIUS_METERS,
) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid coordinates" }
        require(radiusMeters > 0.0) { "radiusMeters must be positive" }
    }

    public companion object {
        public const val DEFAULT_RADIUS_METERS: Double = 150.0
    }
}

/**
 * The user's collection choices that on-device collectors read (docs/ARCHITECTURE.md §5.2: settings that are not
 * records live in DataStore). ANDROID-DATA persists them; defaults are the privacy-preserving choices.
 */
public data class CollectionSettings(
    /** Connector ids the user turned off; a disabled connector collects nothing and its live sources stay unregistered. */
    val disabledConnectors: Set<String> = emptySet(),
    /** Packages whose notification titles and text may be stored (notification_content, opt-in per app). */
    val notificationContentPackages: Set<String> = emptySet(),
    /**
     * Whether content capture may include the default SMS app and the default dialer. Off by default
     * (docs/research/01 §3.34: Play forbids deriving SMS or call-log data through other means).
     */
    val notificationContentFromSmsAndDialer: Boolean = false,
    /** Places used to classify foreground fixes as HOME, WORK or GYM; coordinates never leave the device. */
    val knownPlaces: List<KnownPlace> = emptyList(),
    /** Whether foreground location may use precise fixes; approximate by default. */
    val preciseLocation: Boolean = false,
    /**
     * Opt-in connectors the user turned on (for example `android.call`, which must never request `READ_PHONE_STATE`
     * unless enabled, red team lifecycle-battery-19). Opt-in connectors are off until listed here.
     */
    val enabledOptInConnectors: Set<String> = emptySet(),
    /**
     * Whether calendar event titles may be stored. Off by default (red team privacy-ai-16); attendee identities,
     * descriptions and locations are never read.
     */
    val calendarTitles: Boolean = false,
)

/**
 * Purges notification titles and text already stored for a package (red team privacy-ai-16): called when a package
 * became the default SMS app or dialer. ANDROID-DATA implements it; it must be idempotent.
 */
public fun interface NotificationContentPurger {
    public suspend fun purgeContent(packageName: String)
}

/** Read and update [CollectionSettings]. */
public interface CollectionSettingsStore {
    public val settings: Flow<CollectionSettings>

    public suspend fun update(transform: (CollectionSettings) -> CollectionSettings)
}

/**
 * Persistent flags the Permission Center needs (docs/research/01 §5.2, §5.3 A and E). ANDROID-DATA implements it in
 * DataStore (`:core:datastore`, "permission requested" flags).
 */
public interface PermissionRequestStore {
    /** Runtime permissions that were requested at least once (set when a request returned a non-empty result). */
    public suspend fun requestedPermissions(): Set<String>

    public suspend fun markRequested(permissions: Collection<String>)

    /**
     * Special accesses (`usage_access`, `notification_listener`, ...) whose Settings screen the user opened and came
     * back from. Together with a sideload install source this signals ECM "restricted settings" (§5.3 E).
     */
    public suspend fun settingsVisited(): Set<String>

    public suspend fun markSettingsVisited(specialAccess: String)

    /**
     * Runtime permissions whose last UI-derived verdict was DENIED_PERMANENTLY (red team testing-build-15). Only a pass
     * with a resumed Activity can read `shouldShowRequestPermissionRationale`; background passes read this set and never
     * change DENIED vs DENIED_PERMANENTLY themselves.
     */
    public suspend fun permanentlyDenied(): Set<String>

    /**
     * Stores the verdicts of a UI pass: `true` for DENIED_PERMANENTLY, `false` for DENIED (or granted again). Permissions
     * not in [verdicts] keep their stored verdict.
     */
    public suspend fun recordUiVerdicts(verdicts: Map<String, Boolean>)
}

package dev.agentle.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Permission Center groups (spec §6). */
@Serializable
public enum class CapabilityCategory(public val label: String) {
    @SerialName("Activity")
    ACTIVITY("Activity"),

    @SerialName("Location")
    LOCATION("Location"),

    @SerialName("Notifications")
    NOTIFICATIONS("Notifications"),

    @SerialName("Apps")
    APPS("Apps"),

    @SerialName("Bluetooth")
    BLUETOOTH("Bluetooth"),

    @SerialName("Media")
    MEDIA("Media"),

    @SerialName("Calendar")
    CALENDAR("Calendar"),

    @SerialName("Health")
    HEALTH("Health"),

    @SerialName("Communication")
    COMMUNICATION("Communication"),

    @SerialName("Device State")
    DEVICE_STATE("Device State"),

    @SerialName("Sensors")
    SENSORS("Sensors"),
}

/** Implementation decision for a capability in this build (docs/research/capabilities.json "plannedStatus"). */
@Serializable
public enum class PlannedStatus { IMPLEMENT, IMPLEMENT_DEBUG_ONLY, DEFER, DOCUMENT_UNAVAILABLE }

@Serializable
public enum class BackgroundSupport { YES, NONE, FOREGROUND_ONLY, WITH_FOREGROUND_SERVICE, UNKNOWN }

@Serializable
public enum class CollectionMode { CONTINUOUS, EVENT, PERIODIC, HISTORICAL }

/** Machine-readable availability summary, as in the spec's `DataCapability(status = ...)` example. */
@Serializable
public enum class CapabilityAvailability {
    AVAILABLE,
    AVAILABLE_WITH_RUNTIME_PERMISSION,
    AVAILABLE_WITH_SPECIAL_ACCESS,
    DEBUG_ONLY,
    DEFERRED,
    UNAVAILABLE,
}

/**
 * Static description of one data capability. Loaded from the registry resource generated from
 * docs/research/capabilities.json; ids are stable and referenced by connectors and the Permission Center.
 */
@Serializable
public data class DataCapability(
    val id: String,
    val name: String,
    val category: CapabilityCategory,
    val androidApis: List<String> = emptyList(),
    val runtimePermissions: List<String> = emptyList(),
    /** Special access granted on a Settings screen (e.g. usage_access, notification_listener), if any. */
    val specialAccess: String? = null,
    val minSdk: Int = 1,
    val backgroundSupport: BackgroundSupport = BackgroundSupport.UNKNOWN,
    val collectionModes: List<CollectionMode> = emptyList(),
    val playPolicy: String? = null,
    val plannedStatus: PlannedStatus,
    val eventTypes: List<String> = emptyList(),
    val notes: String? = null,
) {
    val requiresRuntimePermission: Boolean get() = runtimePermissions.isNotEmpty()
    val requiresSettingsGrant: Boolean
        get() = specialAccess != null && !specialAccess.equals("none", ignoreCase = true)
    val backgroundCapable: Boolean
        get() = backgroundSupport == BackgroundSupport.YES || backgroundSupport == BackgroundSupport.WITH_FOREGROUND_SERVICE

    val status: CapabilityAvailability
        get() = when (plannedStatus) {
            PlannedStatus.DOCUMENT_UNAVAILABLE -> CapabilityAvailability.UNAVAILABLE

            PlannedStatus.DEFER -> CapabilityAvailability.DEFERRED

            PlannedStatus.IMPLEMENT_DEBUG_ONLY -> CapabilityAvailability.DEBUG_ONLY

            PlannedStatus.IMPLEMENT -> when {
                requiresSettingsGrant -> CapabilityAvailability.AVAILABLE_WITH_SPECIAL_ACCESS
                requiresRuntimePermission -> CapabilityAvailability.AVAILABLE_WITH_RUNTIME_PERMISSION
                else -> CapabilityAvailability.AVAILABLE
            }
        }
}

/** Live permission state of a capability on this device (docs/research/01 §5.1). */
@Serializable
public enum class PermissionState {
    ALLOWED,
    DENIED,
    DENIED_PERMANENTLY,
    REQUIRES_SETTINGS,
    RESTRICTED_BY_ANDROID,
    UNAVAILABLE,
    PARTIALLY_ALLOWED,
    FOREGROUND_ONLY,
    BACKGROUND_ALLOWED,
    UNSUPPORTED_ON_DEVICE,
    ;

    /** Whether collection can run at all in this state (possibly reduced). */
    public val canCollect: Boolean
        get() = this == ALLOWED || this == PARTIALLY_ALLOWED || this == FOREGROUND_ONLY || this == BACKGROUND_ALLOWED

    public companion object {
        /** Resolution precedence: lower index wins when several conditions apply (docs/research/01 §5.1). */
        public val PRECEDENCE: List<PermissionState> = listOf(
            UNSUPPORTED_ON_DEVICE,
            RESTRICTED_BY_ANDROID,
            DENIED_PERMANENTLY,
            DENIED,
            REQUIRES_SETTINGS,
            UNAVAILABLE,
            PARTIALLY_ALLOWED,
            FOREGROUND_ONLY,
            ALLOWED,
            BACKGROUND_ALLOWED,
        )
    }
}

/** Why a capability cannot fully collect right now. Several can apply at once. */
@Serializable
public enum class Blocker {
    LOCATION_SERVICES_OFF,
    BLUETOOTH_OFF,
    USER_LOCKED,
    HC_UPDATE_REQUIRED,
    HC_NOT_INSTALLED,
    PLAY_SERVICES_MISSING,
    LISTENER_DISCONNECTED,
    ECM_RESTRICTED_SETTINGS,
    HARD_RESTRICTED_NOT_ALLOWLISTED,
    USER_RESTRICTION,
    MANAGED_PROFILE,
    BACKGROUND_RESTRICTED_BY_USER,
    HIBERNATION_ENABLED,
    STANDBY_BUCKET_RESTRICTED,
    OTP_REDACTION,
    NOT_IN_THIS_BUILD,
    NOTIFICATIONS_DISABLED,
    CHANNEL_BLOCKED,
    APPROXIMATE_ONLY,
    NO_HARDWARE,
    SDK_TOO_OLD,
    COLLECTION_DISABLED_BY_USER,
}

/** Resolved live status of one capability. */
@Serializable
public data class CapabilityStatus(
    val capabilityId: String,
    val state: PermissionState,
    val blockers: List<Blocker> = emptyList(),
    val evaluatedAt: Instant,
    /** Optional sanitized explanation for diagnostics. */
    val detail: String? = null,
)

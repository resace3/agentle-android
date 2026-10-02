package dev.agentle.connectors.android.permissions

import android.app.Activity
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.UnusedAppRestrictions
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.DataCapability
import dev.agentle.core.model.PermissionState
import kotlin.time.Instant

/**
 * The context-free signals of one capability (red team testing-build-15): every condition a resolver found without an
 * Activity, so workers can compute them. A runtime permission that was requested and is still denied is kept as a
 * pending denial; only [resolve] with a [DenialRefinement] turns it into DENIED or DENIED_PERMANENTLY.
 *
 * Precedence is docs/research/01 §5.1: UNSUPPORTED_ON_DEVICE, then RESTRICTED_BY_ANDROID, then the grant problems
 * (DENIED_PERMANENTLY / DENIED / REQUIRES_SETTINGS: the first missing mandatory grant wins), then UNAVAILABLE,
 * PARTIALLY_ALLOWED, FOREGROUND_ONLY, and finally the [base] state (ALLOWED or BACKGROUND_ALLOWED).
 */
public class StateBuilder(private val base: PermissionState = PermissionState.ALLOWED) {
    private class Condition(val state: PermissionState, val pendingPermission: String?)

    private val conditions = mutableListOf<Condition>()
    private val blockerSet = LinkedHashSet<Blocker>()

    /** Adds a condition; ties within a precedence tier keep the first one added. */
    public fun add(state: PermissionState, blocker: Blocker? = null): StateBuilder = apply {
        conditions += Condition(state, null)
        if (blocker != null) blockerSet += blocker
    }

    /**
     * Adds a runtime permission that is not held. Never requested is always DENIED; requested is a pending denial whose
     * DENIED vs DENIED_PERMANENTLY only a [DenialRefinement] decides.
     */
    public fun addRuntimeDenial(permission: String, requested: Boolean): StateBuilder = apply {
        conditions += Condition(PermissionState.DENIED, if (requested) permission else null)
    }

    /** Adds an informational blocker that does not change the state. */
    public fun note(blocker: Blocker): StateBuilder = apply { blockerSet += blocker }

    /** Requested runtime permissions still denied, in the order found: the input of a [DenialRefinement]. */
    public val pendingDenials: List<String> get() = conditions.mapNotNull { it.pendingPermission }.distinct()

    /** The state with every pending denial read as DENIED (what a pass knows before any refinement). */
    public val state: PermissionState get() = resolveState(DenialRefinement.NONE)

    public val blockers: List<Blocker> get() = blockerSet.toList()

    public fun resolveState(refinement: DenialRefinement): PermissionState = conditions
        .map { condition ->
            val permission = condition.pendingPermission
            if (permission != null && refinement.isPermanentlyDenied(permission)) PermissionState.DENIED_PERMANENTLY else condition.state
        }
        .minByOrNull(::tier) ?: base

    public fun resolve(capabilityId: String, at: Instant, refinement: DenialRefinement): CapabilityStatus =
        CapabilityStatus(capabilityId, resolveState(refinement), blockers, at)

    public fun build(capabilityId: String, at: Instant): CapabilityStatus = resolve(capabilityId, at, DenialRefinement.NONE)

    public companion object {
        /** The §5.1 precedence tier of [state]: lower wins. */
        public fun tier(state: PermissionState): Int = when (state) {
            PermissionState.UNSUPPORTED_ON_DEVICE -> 0
            PermissionState.RESTRICTED_BY_ANDROID -> 1
            PermissionState.DENIED_PERMANENTLY, PermissionState.DENIED, PermissionState.REQUIRES_SETTINGS -> 2
            PermissionState.UNAVAILABLE -> 3
            PermissionState.PARTIALLY_ALLOWED -> 4
            PermissionState.FOREGROUND_ONLY -> 5
            PermissionState.ALLOWED, PermissionState.BACKGROUND_ALLOWED -> 6
        }
    }
}

/**
 * The UI-only refinement of a requested, still denied runtime permission (red team testing-build-15):
 * `shouldShowRequestPermissionRationale` needs an Activity, so only a UI pass derives DENIED vs DENIED_PERMANENTLY.
 * Background passes use [LastUiVerdicts] and never change the last UI-derived value.
 */
public fun interface DenialRefinement {
    public fun isPermanentlyDenied(permission: String): Boolean

    public companion object {
        /** Reads every pending denial as DENIED. */
        public val NONE: DenialRefinement = DenialRefinement { false }
    }
}

/** A UI pass: the rationale is suppressed after a request, so the dialog will not show again (§5.3 A). */
public class RationaleRefinement(private val activity: Activity, private val platform: PlatformState) : DenialRefinement {
    override fun isPermanentlyDenied(permission: String): Boolean = !platform.shouldShowRationale(activity, permission)
}

/** A background pass: the verdicts the last UI pass stored, never re-derived here. */
public class LastUiVerdicts(private val permanentlyDenied: Set<String>) : DenialRefinement {
    override fun isPermanentlyDenied(permission: String): Boolean = permission in permanentlyDenied
}

/**
 * Everything one Permission Center pass reads once and shares between resolvers: platform reads, the user's settings,
 * the "requested once" and "returned from Settings" flags, the notification listener connection and the Health Connect
 * and Play services probes. It holds no Activity: resolvers only produce context-free signals.
 */
public class ResolverContext(
    public val platform: PlatformState,
    public val settings: CollectionSettings,
    public val requestedPermissions: Set<String>,
    public val settingsVisited: Set<String>,
    public val listenerConnected: Boolean,
    public val healthConnect: HealthConnectProbe,
    public val playServices: PlayServicesProbe,
) {
    private var unusedMemo: UnusedAppRestrictions? = null

    /** `PackageManagerCompat.getUnusedAppRestrictionsStatus`, read at most once per pass (it may bind a service). */
    public suspend fun unusedAppRestrictions(): UnusedAppRestrictions =
        unusedMemo ?: platform.unusedAppRestrictions().also { unusedMemo = it }
}

/** One resolver per mechanism of docs/research/01 §5.3. It returns context-free signals ([StateBuilder]). */
public fun interface CapabilityStateResolver {
    public suspend fun resolve(capability: DataCapability, context: ResolverContext): StateBuilder
}

/** Health Connect reads the resolvers need (§5.3 D). */
public interface HealthConnectProbe {
    /** `HealthConnectClient.getSdkStatus` (1 unavailable, 2 provider update required, 3 available). */
    public fun sdkStatus(): Int

    /** Granted Health Connect permissions; empty when Health Connect is unavailable or unreadable. */
    public suspend fun grantedPermissions(): Set<String>

    /** `HealthConnectFeatures.getFeatureStatus(feature) == FEATURE_STATUS_AVAILABLE`. */
    public fun featureAvailable(feature: Int): Boolean
}

/** What a Play services feature needs. */
public enum class PlayServicesRequirement {
    /** Any working Google Play services (Activity Recognition, fused location). */
    ANY,

    /** At least `LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE` (Recording API). */
    RECORDING_API,
}

/** Google Play services availability. */
public fun interface PlayServicesProbe {
    public fun isAvailable(requirement: PlayServicesRequirement): Boolean
}

package dev.agentle.feature.connections.wearable

import android.app.PendingIntent
import android.content.Intent
import dev.agentle.connectors.api.SyncResult
import dev.agentle.core.common.AppError
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.feature.connections.port.WearableAccountProblem
import dev.agentle.feature.connections.port.WearableAuthorizationResult
import dev.agentle.feature.connections.port.WearableConnectionState
import dev.agentle.feature.connections.port.WearableDataAccess
import dev.agentle.feature.connections.port.WearableDisconnectReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What the Wearable screen's main card shows. */
internal enum class WearablePhase {
    LOADING,
    LOAD_FAILED,
    NOT_AVAILABLE,
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    NEEDS_REAUTH,
    ACCOUNT_PROBLEM,
    CONNECTION_ERROR,
    PAUSED,
}

/** A user action the screen is waiting for. */
internal enum class WearableBusy { CONNECTING, AWAITING_CONSENT, SYNCING, DISCONNECTING }

/** The last sync as the screen shows it. */
internal data class WearableSyncUi(val state: SyncStatus, val lastSuccess: Instant?, val lastAttempt: Instant?, val errorCode: String?)

/** The disconnect dialog: whether the user chose to delete the synced data. */
internal data class WearableDisconnectDialog(val deleteData: Boolean = false)

/** A message about the outcome of something the user did, shown inline until dismissed. */
internal sealed interface WearableNotice {
    data class Connected(val missingCount: Int, val accountChanged: Boolean) : WearableNotice

    data object ConsentDenied : WearableNotice

    data object ConsentCancelled : WearableNotice

    data object NothingShared : WearableNotice

    data object ConsentScreenFailed : WearableNotice

    data class Unavailable(val blocker: Blocker) : WearableNotice

    data class AuthorizationFailed(val error: AppError) : WearableNotice

    data class SyncFinished(val status: SyncResult.Status, val committed: Int, val error: AppError?) : WearableNotice

    data class Disconnected(val report: WearableDisconnectReport) : WearableNotice

    data class DisconnectFailed(val error: AppError) : WearableNotice
}

/** User actions of the Wearable screen. */
internal sealed interface WearableAction {
    data object Connect : WearableAction

    data object Reconnect : WearableAction

    data object GrantMore : WearableAction

    data class ConsentResult(val resultCode: Int, val data: Intent?) : WearableAction

    data object ConsentLaunchFailed : WearableAction

    data object SyncNow : WearableAction

    data object RequestDisconnect : WearableAction

    data class SetDeleteData(val delete: Boolean) : WearableAction

    data object ConfirmDisconnect : WearableAction

    data object DismissDisconnect : WearableAction

    data object DismissNotice : WearableAction

    data object Retry : WearableAction
}

/** One-off effects the screen performs. */
internal sealed interface WearableEffect {
    /** Launch Google's consent screen with `StartIntentSenderForResult`. */
    class LaunchConsent(val pendingIntent: PendingIntent) : WearableEffect {
        override fun toString(): String = "LaunchConsent"
    }
}

/**
 * The Wearable screen state. [bound] is true while a Google account is connected (whatever its health), so data access,
 * sync and "Disconnect" apply. Personal fields ([accountLabel]) are left out of [toString].
 */
internal data class WearableUiState(
    val phase: WearablePhase = WearablePhase.LOADING,
    val bound: Boolean = false,
    val unavailableReason: Blocker? = null,
    val accountLabel: String? = null,
    val accountProblem: WearableAccountProblem? = null,
    val dataAccess: ImmutableList<WearableDataAccess> = persistentListOf(),
    val sync: WearableSyncUi? = null,
    val rateLimitedUntil: Instant? = null,
    val offline: Boolean = false,
    val busy: WearableBusy? = null,
    val notice: WearableNotice? = null,
    val disconnectDialog: WearableDisconnectDialog? = null,
    val zone: TimeZone = TimeZone.UTC,
) {
    val sharedCount: Int get() = dataAccess.count { it.permission == PermissionState.ALLOWED }

    /** Some data types are shared and some are not (the "partial" state). */
    val partial: Boolean get() = sharedCount > 0 && sharedCount < dataAccess.size

    val syncRunning: Boolean get() = busy == WearableBusy.SYNCING || sync?.state == SyncStatus.RUNNING

    /** "Sync now" works only while connected, online, not rate limited and with nothing else running. */
    val canSync: Boolean
        get() = phase == WearablePhase.CONNECTED && busy == null && !syncRunning && !offline && rateLimitedUntil == null

    /** A Google account is bound and nothing else runs, so "Disconnect" applies. */
    val canDisconnect: Boolean get() = bound && busy == null

    override fun toString(): String = "WearableUiState(phase=$phase, bound=$bound, unavailable=$unavailableReason, " +
        "account=${if (accountLabel == null) "none" else "<redacted>"}, problem=$accountProblem, " +
        "shared=$sharedCount/${dataAccess.size}, sync=$sync, rateLimitedUntil=$rateLimitedUntil, offline=$offline, " +
        "busy=$busy, notice=$notice, dialog=$disconnectDialog)"
}

/** The port's state as the ViewModel last saw it. */
internal sealed interface WearableRemote {
    data object Loading : WearableRemote

    data object Failed : WearableRemote

    data class Ready(val state: WearableConnectionState) : WearableRemote
}

/** What only the ViewModel knows: running actions, the notice, the dialog and a disconnect not yet reflected by the port. */
internal data class WearableLocal(
    val busy: WearableBusy? = null,
    val notice: WearableNotice? = null,
    val disconnectDialog: WearableDisconnectDialog? = null,
    val resultProblem: WearableAccountProblem? = null,
    val disconnected: Boolean = false,
)

/** The pure reducer: port state + local state + zone -> screen state. */
internal fun reduceWearable(remote: WearableRemote, local: WearableLocal, zone: TimeZone): WearableUiState {
    val state = (remote as? WearableRemote.Ready)?.state
    val base = WearableUiState(
        busy = local.busy,
        notice = local.notice,
        disconnectDialog = local.disconnectDialog,
        zone = zone,
    )
    if (state == null) {
        return base.copy(phase = if (remote is WearableRemote.Failed) WearablePhase.LOAD_FAILED else WearablePhase.LOADING)
    }
    val metadata = state.metadata
    val unavailableReason = unavailableReasonOf(state)
    // A disconnect that returned wins over a port state that still says connected (the screen shows it at once).
    val bound = unavailableReason == null && !local.disconnected && metadata.connection in BOUND_CONNECTIONS
    // The port's problem concerns the bound account; the last attempt's problem shows even before anything is bound.
    val problem = state.accountProblem?.takeIf { bound } ?: local.resultProblem
    val phase = when {
        unavailableReason != null -> WearablePhase.NOT_AVAILABLE
        local.busy == WearableBusy.CONNECTING || local.busy == WearableBusy.AWAITING_CONSENT -> WearablePhase.CONNECTING
        local.disconnected -> WearablePhase.NOT_CONNECTED
        metadata.connection == ConnectionStatus.CONNECTING -> WearablePhase.CONNECTING
        problem != null -> WearablePhase.ACCOUNT_PROBLEM
        metadata.connection == ConnectionStatus.NEEDS_REAUTH -> WearablePhase.NEEDS_REAUTH
        metadata.connection == ConnectionStatus.CONNECTED -> WearablePhase.CONNECTED
        metadata.connection == ConnectionStatus.ERROR -> WearablePhase.CONNECTION_ERROR
        metadata.connection == ConnectionStatus.DISABLED -> WearablePhase.PAUSED
        else -> WearablePhase.NOT_CONNECTED
    }
    return base.copy(
        phase = phase,
        bound = bound,
        unavailableReason = unavailableReason,
        accountLabel = state.accountLabel.takeIf { bound },
        accountProblem = problem.takeIf { phase == WearablePhase.ACCOUNT_PROBLEM },
        dataAccess = if (bound) state.dataAccess.toImmutableList() else persistentListOf(),
        sync = if (bound) {
            WearableSyncUi(
                state = metadata.syncState,
                lastSuccess = metadata.lastSuccessfulCollection,
                lastAttempt = metadata.lastAttemptedCollection,
                errorCode = metadata.lastError?.code,
            )
        } else {
            null
        },
        rateLimitedUntil = state.rateLimitedUntil.takeIf { bound },
        offline = !state.networkAvailable,
    )
}

/** Connection states in which a Google account is bound to the connector. */
private val BOUND_CONNECTIONS: Set<ConnectionStatus> =
    setOf(ConnectionStatus.CONNECTED, ConnectionStatus.NEEDS_REAUTH, ConnectionStatus.ERROR)

private fun unavailableReasonOf(state: WearableConnectionState): Blocker? = when {
    Blocker.NOT_IN_THIS_BUILD in state.blockers -> Blocker.NOT_IN_THIS_BUILD
    Blocker.PLAY_SERVICES_MISSING in state.blockers -> Blocker.PLAY_SERVICES_MISSING
    state.metadata.connection == ConnectionStatus.UNAVAILABLE -> Blocker.NOT_IN_THIS_BUILD
    else -> null
}

/** The notice for an authorization outcome; null when the outcome needs no message (a consent screen follows). */
internal fun noticeFor(result: WearableAuthorizationResult): WearableNotice? = when (result) {
    is WearableAuthorizationResult.Connected -> WearableNotice.Connected(result.missingDataTypes.size, result.accountChanged)
    is WearableAuthorizationResult.NeedsResolution -> null
    WearableAuthorizationResult.Denied -> WearableNotice.ConsentDenied
    WearableAuthorizationResult.Cancelled -> WearableNotice.ConsentCancelled
    WearableAuthorizationResult.NothingShared -> WearableNotice.NothingShared
    is WearableAuthorizationResult.AccountProblem -> null
    is WearableAuthorizationResult.Unavailable -> WearableNotice.Unavailable(result.blocker)
    is WearableAuthorizationResult.Failed -> WearableNotice.AuthorizationFailed(result.error)
}

package dev.agentle.feature.connections.port

import android.app.PendingIntent
import android.content.Intent
import dev.agentle.connectors.api.SyncResult
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PermissionState
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * What the Wearable screen (`AppRoute.Wearable`, the spec's "Fitbit" screen, Journeys 3 and 8) needs from the wearable
 * connection. The connection is the Google Health API (docs/ARCHITECTURE.md §7) and nothing else: no code path may reach
 * the legacy Fitbit Web API.
 *
 * Authorization follows Play services `AuthorizationClient` (red team oauth-security-06): a call yields a token, a
 * consent screen that only a visible screen may launch ([WearableAuthorizationResult.NeedsResolution]), a denial or a
 * failure, and the granted scopes can be partial. Background syncs never start UI; they record
 * [ConnectionStatus.NEEDS_REAUTH] instead, which this screen offers to fix with "Reconnect".
 *
 * Every suspend function is main-safe (the implementation moves work off the main thread) and never throws except
 * `CancellationException`; failures are values.
 */
public interface WearableConnectionPort {
    /**
     * The connection as the screen shows it. Emits the current value on collection, then every change.
     *
     * - Nothing connected yet: [ConnectionStatus.NOT_CONNECTED], no data access, no account label.
     * - The real Google Health API is behind a feature flag that stays off until a live test passes (oauth-security-05):
     *   while it is off, [ConnectionStatus.UNAVAILABLE] with [Blocker.NOT_IN_THIS_BUILD]. Play services missing,
     *   disabled or too old: [ConnectionStatus.UNAVAILABLE] with [Blocker.PLAY_SERVICES_MISSING].
     * - Connected with some scopes declined: [ConnectorMetadata.permissionSummary] is
     *   [PermissionState.PARTIALLY_ALLOWED] and [WearableConnectionState.dataAccess] lists the declined types as
     *   [PermissionState.DENIED].
     * - A sync is running: [ConnectorMetadata.syncState] is `RUNNING`; afterwards `SUCCEEDED`, `PARTIAL` or `FAILED`
     *   with [ConnectorMetadata.lastError] holding the `AppError` code.
     * - A background sync needed consent: [ConnectionStatus.NEEDS_REAUTH].
     * - Google's side needs the user (account not linked, profile not ready, legacy Fitbit login, another account):
     *   [WearableConnectionState.accountProblem], usually with [ConnectionStatus.ERROR] or `NEEDS_REAUTH`.
     * - Rate limited: [WearableConnectionState.rateLimitedUntil] until syncing may resume; offline:
     *   [WearableConnectionState.networkAvailable] false.
     *
     * The flow must not fail; if the stored state cannot be read it emits `ERROR` with a `database_error` last error.
     */
    public val state: Flow<WearableConnectionState>

    /**
     * Asks Google for access, interactively (the screen is visible). [purpose] says why: a first connection, a
     * reconnection after [ConnectionStatus.NEEDS_REAUTH], or asking again for the declined data types.
     *
     * Returns [WearableAuthorizationResult.NeedsResolution] when Google must show its consent or account screen: the
     * screen launches its `PendingIntent` with `StartIntentSenderForResult` and hands the outcome to
     * [completeAuthorization]. On success the account is checked (the Google Health identity) before it is stored as
     * connected. Never stores a refresh token or a client secret.
     */
    public suspend fun authorize(purpose: WearableAuthorizationPurpose): WearableAuthorizationResult

    /**
     * The outcome of the consent screen launched for [WearableAuthorizationResult.NeedsResolution]: the activity result
     * code and data exactly as received (the implementation reads them with `getAuthorizationResultFromIntent`). A
     * closed screen is [WearableAuthorizationResult.Cancelled]; a result that needs yet another screen is
     * [WearableAuthorizationResult.NeedsResolution] again. An implementation whose connector re-runs the authorization
     * after the screen (the Google Health connector's `connect`) may do exactly that when [resultCode] is
     * `Activity.RESULT_OK`.
     */
    public suspend fun completeAuthorization(resultCode: Int, data: Intent?): WearableAuthorizationResult

    /**
     * Runs a sync of every granted data type now ("Sync now"). The run belongs to the application (it continues if the
     * caller is cancelled); the call returns when it ends. [state] shows it as running meanwhile.
     *
     * Not connected (also right after [disconnect]): `SKIPPED_NOT_CONNECTED`, and nothing is fetched. Rate limited:
     * `FAILED` with `AppError.RateLimited` and no request sent. Offline: `FAILED` with `AppError.NetworkUnavailable`.
     * No granted data type: `SKIPPED_NO_PERMISSION`. A different Google Health account: `ACCOUNT_CHANGED`.
     */
    public suspend fun syncNow(): SyncResult

    /**
     * Disconnects: cancels the running sync and every scheduled one, revokes Google's access (`revokeAccess`), forgets
     * the connected account and, when [deleteSyncedData] is true, deletes the events synced from Google Health
     * (connector `googlehealth`; Health Connect data stays). From the moment this returns no sync of this connection
     * runs until the user connects again (Journey 8).
     *
     * A revocation that could not be confirmed (offline, Play services error) still disconnects locally:
     * [WearableDisconnectReport.accessRevoked] is false and the screen tells the user how to remove the access in their
     * Google Account. Failure (`database_error`) means nothing changed.
     */
    public suspend fun disconnect(deleteSyncedData: Boolean): Outcome<WearableDisconnectReport>
}

/**
 * The wearable connection as the screen shows it: the connector's own metadata plus what the screen needs beyond it.
 *
 * @property metadata the Google Health connector's metadata (connection, sync state, last sync, last error code).
 * @property blockers why the connection cannot be used on this device or build: [Blocker.NOT_IN_THIS_BUILD] (feature
 *   flag off) or [Blocker.PLAY_SERVICES_MISSING]; empty otherwise.
 * @property accountLabel the connected Google account as Google reports it (an email address), when known. Personal:
 *   shown on screen only, never logged ([toString] leaves it out).
 * @property dataAccess one entry per wearable data type (steps, sleep, heart rate, ...) with whether the user shared it;
 *   empty when not connected.
 * @property accountProblem something the user must fix on Google's side before syncing can work.
 * @property rateLimitedUntil while Google rate-limits the connection, when syncing may resume; null otherwise.
 * @property networkAvailable false while the device is offline.
 */
public data class WearableConnectionState(
    val metadata: ConnectorMetadata,
    val blockers: List<Blocker> = emptyList(),
    val accountLabel: String? = null,
    val dataAccess: List<WearableDataAccess> = emptyList(),
    val accountProblem: WearableAccountProblem? = null,
    val rateLimitedUntil: Instant? = null,
    val networkAvailable: Boolean = true,
) {
    override fun toString(): String =
        "WearableConnectionState(connection=${metadata.connection}, sync=${metadata.syncState}, blockers=$blockers, " +
            "account=${if (accountLabel == null) "none" else "<redacted>"}, dataAccess=${dataAccess.size}, " +
            "problem=$accountProblem, rateLimitedUntil=$rateLimitedUntil, online=$networkAvailable)"
}

/**
 * Whether one wearable data type is shared. [type] is the event type the data becomes (`STEP_SAMPLE`, `SLEEP_SESSION`,
 * `HEART_RATE`, `WEIGHT`, `WEARABLE_DEVICE`, ...; daily totals are listed under their metric) and [permission] is
 * [PermissionState.ALLOWED] or [PermissionState.DENIED].
 */
public data class WearableDataAccess(val type: EventType, val permission: PermissionState)

/** Something the user must fix on Google's side (docs/research/05 §2.5, §7.6). */
public sealed interface WearableAccountProblem {
    /** 400 `ACCOUNT_NOT_LINKED`: the Google account has no Google Health data yet. [actionUrl] is Google's own link. */
    public data class NotLinked(val actionUrl: String? = null) : WearableAccountProblem

    /** 412: the Google Health profile is not set up yet (the user finishes it in the Google Health app). */
    public data object ProfileNotReady : WearableAccountProblem

    /** 403 UberMint/GaiaMint: an old Fitbit login that has not moved to a Google Account yet. */
    public data object LegacyFitbitAccount : WearableAccountProblem

    /** Google now returns another Google Health account than the connected one; syncing stopped until reconnecting. */
    public data object AccountChanged : WearableAccountProblem
}

/** Why the screen asks for access. */
public enum class WearableAuthorizationPurpose {
    /** "Connect Google Health". */
    CONNECT,

    /** "Reconnect" after [ConnectionStatus.NEEDS_REAUTH] or an account change. */
    RECONNECT,

    /** "Share more data": ask again for the data types the user declined. */
    GRANT_MORE,
}

/** How an authorization attempt ended. */
public sealed interface WearableAuthorizationResult {
    /**
     * Connected (or reconnected, or more access granted). [missingDataTypes] are still not shared (partial consent);
     * [accountChanged] is true when another Google Health account replaced the previous one (its data stays until the
     * user deletes it).
     */
    public data class Connected(val missingDataTypes: Set<EventType> = emptySet(), val accountChanged: Boolean = false) :
        WearableAuthorizationResult

    /** Google must show a screen; only a visible screen launches [pendingIntent] (`StartIntentSenderForResult`). */
    public class NeedsResolution(public val pendingIntent: PendingIntent) : WearableAuthorizationResult {
        override fun toString(): String = "NeedsResolution"
    }

    /** The user declined on Google's consent screen. Nothing changed. */
    public data object Denied : WearableAuthorizationResult

    /** The user closed Google's screen without deciding. Nothing changed. */
    public data object Cancelled : WearableAuthorizationResult

    /** The user shared none of the requested data types (docs/research/05 §2.2 "Missing Permissions"). */
    public data object NothingShared : WearableAuthorizationResult

    /** Access was granted but Google's side needs the user first (see [WearableAccountProblem]). */
    public data class AccountProblem(val problem: WearableAccountProblem) : WearableAuthorizationResult

    /** The connection cannot be used here: [Blocker.NOT_IN_THIS_BUILD] or [Blocker.PLAY_SERVICES_MISSING]. */
    public data class Unavailable(val blocker: Blocker) : WearableAuthorizationResult

    /**
     * Anything else, as an `AppError`: `network_unavailable`, `remote_server_error`, `rate_limited`, `database_error`,
     * `unexpected` (for example a misconfigured OAuth client, Play services `DEVELOPER_ERROR`).
     */
    public data class Failed(val error: AppError) : WearableAuthorizationResult
}

/** What [WearableConnectionPort.disconnect] did. */
public data class WearableDisconnectReport(val dataDeleted: Boolean, val accessRevoked: Boolean)

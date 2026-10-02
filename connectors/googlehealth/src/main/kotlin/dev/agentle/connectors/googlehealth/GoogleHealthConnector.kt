package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.api.Connector
import dev.agentle.connectors.api.EventSink
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.connectors.googlehealth.GhJson.string
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.ErrorInfo
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.core.network.HttpClientFactory
import dev.agentle.core.network.NetworkJson
import dev.agentle.core.network.RateLimit
import dev.agentle.core.network.SlidingWindowRateLimiter
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Why the connected Google account cannot be synced until the user acts (docs/research/05 §7.6). */
public sealed interface GoogleHealthAccountProblem {
    /** 400 `ACCOUNT_NOT_LINKED`: the Google account has no Google Health data yet; [signupUrl] is Google's own link. */
    public data class AccountNotLinked(val signupUrl: String?) : GoogleHealthAccountProblem

    /** 412: the Google Health profile is not set up yet. */
    public data object ProfileNotReady : GoogleHealthAccountProblem

    /** A legacy Fitbit account that has not moved to a Google account (403 UberMint/GaiaMint). */
    public data object LegacyFitbitAccount : GoogleHealthAccountProblem

    /** Authorization now yields another Google Health user than the one the stored data belongs to; reconnect to switch. */
    public data object AccountChanged : GoogleHealthAccountProblem
}

/** Result of [GoogleHealthConnector.connect]. */
public sealed interface GoogleHealthConnectResult {
    /**
     * Connected. [grantedStreams] are the streams whose scope the user granted (the consent screen is granular);
     * [accountChanged] is true when this connection replaced another Google Health account (fresh cursors).
     */
    public data class Connected(val grantedStreams: Set<String>, val accountChanged: Boolean) : GoogleHealthConnectResult

    /** The user must act: the UI launches [handle] (on Android a `PendingIntent`) and then calls connect again. */
    public class NeedsResolution(public val handle: Any?) : GoogleHealthConnectResult {
        override fun toString(): String = "NeedsResolution"
    }

    /** Not connected: a sanitized [error], and the account [problem] when the user has to act on Google's side. */
    public data class Failed(val error: AppError, val problem: GoogleHealthAccountProblem? = null) : GoogleHealthConnectResult
}

/**
 * The Google Health API v4 connector (docs/research/05 §5, §7; docs/ARCHITECTURE.md §6.1). Reads only
 * `health.googleapis.com` (never the legacy Fitbit Web API) with tokens from [GoogleHealthAuthorizer], and writes
 * through [EventSink] with account-bound cursors and diff windows.
 *
 * Every run: the account binding (stored as the cursor of the pseudo-stream `account`) must exist; a connector-level
 * backoff after a rate limit is honored without a request; a silent token is requested (a background run never starts
 * UI: a pending user action is persisted as NEEDS_REAUTH and returned as a non-retryable error); the identity is read
 * and compared with the bound account (a different account stops with [SyncResult.Status.ACCOUNT_CHANGED]); the
 * account time zone comes from the settings; then each permitted stream runs under a per-(account, stream) lock.
 *
 * The production API is used only when [GoogleHealthConfig.liveApiEnabled] is set; a loopback base URL (fake flavor,
 * tests) is always allowed.
 *
 * Methods per data type (docs/research/05 §5.3): steps, distance, active energy and floors through `:reconcile` (one
 * deduplicated stream across devices, so a walk recorded by a watch and a phone counts once), heart rate through
 * 60-second `:rollUp` windows, daily totals through `:dailyRollUp`, and sleep, exercise, resting heart rate, weight and
 * body fat through `list` (with provenance). Reconciled points carry no `dataSource`, so the API-side skip of points
 * imported from Health Connect (§5.9, §7.7) cannot apply to them, and no row is ever dropped because another source is
 * connected: when Health Connect is also read directly, double counting is resolved downstream by the per-minute
 * source fusion, which keeps one source per minute.
 */
public class GoogleHealthConnector(
    private val config: GoogleHealthConfig,
    private val authorizer: GoogleHealthAuthorizer,
    sink: EventSink,
    private val clock: AgentleClock,
    private val logger: Logger = Logger.NONE,
    sleep: suspend (Duration) -> Unit = { delay(it) },
    random: Random = Random.Default,
    enabled: Boolean = true,
) : Connector {
    override val id: String = ConnectorIds.GOOGLE_HEALTH
    override val name: String = NAME
    private val catalog: List<GhStream> = GhStreams.catalog(config).filter { it.id in config.streams }
    override val supportedEventTypes: Set<EventType> = catalog.map { it.eventType }.toSet()

    /** Google Health is an account connection, not an Android capability: no registry entry applies. */
    override val capabilityIds: List<String> = emptyList()
    override val streamIds: Set<String> = catalog.map { it.id }.toSet()

    private val sink = GuardedSink(sink)
    private val engine = GhSyncEngine(this.sink, clock, config)
    private val api: GhApi
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var enabled: Boolean = enabled

    private val state = MutableStateFlow(
        ConnectorMetadata(
            connectorId = id,
            name = NAME,
            enabled = enabled,
            connection = when {
                !enabled -> ConnectionStatus.DISABLED
                !config.apiEnabled -> ConnectionStatus.UNAVAILABLE
                else -> ConnectionStatus.NOT_CONNECTED
            },
            permissionSummary = PermissionState.DENIED,
            supportedEventTypes = supportedEventTypes,
        ),
    )
    private val problem = MutableStateFlow<GoogleHealthAccountProblem?>(null)

    override val metadata: StateFlow<ConnectorMetadata> = state.asStateFlow()

    /** The account problem the user has to resolve, if any (persisted with the account binding). */
    public val accountProblem: StateFlow<GoogleHealthAccountProblem?> = problem.asStateFlow()

    /** HTTP requests sent so far (diagnostics and tests). */
    public val requestCount: Int get() = api.requests

    init {
        val client = HttpClientFactory.create(config.httpClientConfig(), logger) { clock.elapsed().inWholeMilliseconds }
        val service = NetworkJson.retrofit(config.httpUrl, client).create(GhService::class.java)
        val limiter = SlidingWindowRateLimiter(
            listOf(RateLimit(PER_SECOND, 1.seconds), RateLimit(PER_MINUTE, 1.minutes)),
            { clock.elapsed() },
            sleep,
        )
        api = GhApi(service, authorizer, clock, limiter, sleep, random, logger)
    }

    // ---------------------------------------------------------------- Connector

    override suspend fun sync(trigger: SyncTrigger): SyncResult = guarded(clock.now()) { startedAt -> run(catalog, trigger, startedAt) }

    /**
     * Syncs [stream] now ("sync now" for one stream, round-1 correction 4). A tracker or scale stream also refreshes the
     * paired devices first, so its coverage stays bounded by the device's last upload.
     */
    override suspend fun syncStream(stream: String, trigger: SyncTrigger): SyncResult {
        val startedAt = clock.now()
        val target = catalog.firstOrNull { it.id == stream }
            ?: return SyncResult(
                id,
                SyncResult.Status.FAILED,
                startedAt,
                startedAt,
                error = AppError.UnsupportedFeature("$id.stream", stream),
            )
        val devices = catalog.firstOrNull { it.kind == GhKind.DEVICES }
        val streams = if (target.device != GhDeviceClass.NONE && devices != null) listOf(devices, target) else listOf(target)
        return guarded(startedAt) { run(streams, trigger, it) }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) {
            state.update { it.copy(enabled = true) }
            refreshState()
        } else {
            state.update { it.copy(enabled = false, connection = ConnectionStatus.DISABLED, syncState = SyncStatus.IDLE) }
        }
    }

    // ---------------------------------------------------------------- connection

    /**
     * Connects interactively: asks for a token (the UI resolves a [GoogleHealthConnectResult.NeedsResolution] and calls
     * again), reads the identity and binds the connector to that account. Binding another account starts it with fresh
     * cursors; the data of the previous account stays until the user deletes it.
     */
    public suspend fun connect(): GoogleHealthConnectResult = try {
        connectChecked()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SinkFailure) {
        GoogleHealthConnectResult.Failed(AppError.DatabaseError(e.className))
    } catch (e: RuntimeException) {
        logger.e(GhApi.COMPONENT, "connect crashed", AppError.Unexpected(), mapOf("exception" to e::class.simpleName))
        GoogleHealthConnectResult.Failed(AppError.Unexpected(e::class.simpleName))
    }

    @Suppress("ReturnCount")
    private suspend fun connectChecked(): GoogleHealthConnectResult {
        if (!config.apiEnabled) return GoogleHealthConnectResult.Failed(AppError.UnsupportedFeature(LIVE_API_FEATURE))
        val token = when (val auth = api.authorize(interactive = true)) {
            is GoogleAuthorization.Token -> auth
            is GoogleAuthorization.NeedsResolution -> return GoogleHealthConnectResult.NeedsResolution(auth.handle)
            GoogleAuthorization.Denied -> return GoogleHealthConnectResult.Failed(AppError.AuthenticationRequired(PROVIDER, "denied"))
            is GoogleAuthorization.Failure -> return failedConnect(GhFailure.AuthorizerFailed(auth.statusCode))
        }
        val permissions = permissionsFor(token.grantedScopes)
        if (permissions.values.none { it == PermissionState.ALLOWED }) {
            return GoogleHealthConnectResult.Failed(AppError.PermissionDenied(SCOPES_CAPABILITY, "no_scope"))
        }
        val healthUserId = when (val identity = identify()) {
            is Identified.Ok -> identity.healthUserId
            is Identified.Failed -> return failedConnect(identity.failure)
        }
        val accountId = accountIdOf(healthUserId)
        val binding = readBinding()
        val previous = binding.accountId
        binding.write(accountId, null, GhBackoff())
        problem.value = null
        state.update {
            it.copy(
                connection = if (enabled) ConnectionStatus.CONNECTED else ConnectionStatus.DISABLED,
                permissionSummary = summaryOf(permissions),
                streamPermissions = permissions,
                lastError = null,
                coverageThrough = if (previous == accountId) it.coverageThrough else emptyMap(),
            )
        }
        val changed = previous != null && previous != accountId
        logger.i(
            GhApi.COMPONENT,
            "connected",
            mapOf(
                "streams" to permissions.count { it.value == PermissionState.ALLOWED },
                "accountChanged" to changed,
            ),
        )
        return GoogleHealthConnectResult.Connected(permissions.filterValues { it == PermissionState.ALLOWED }.keys, changed)
    }

    private fun failedConnect(failure: GhFailure): GoogleHealthConnectResult.Failed =
        GoogleHealthConnectResult.Failed(failure.toAppError(), problemOf(failure))

    /**
     * Revokes the grant and unbinds the account. Stored data and cursors stay (deleting data is a separate user
     * action); a later connection to the same account resumes, another account starts fresh. A failed revocation is
     * returned, but the connector is unbound either way.
     */
    public suspend fun disconnect(): Outcome<Unit> {
        // Runs in flight see the new generation and stop writing and publishing (no sync after a disconnect).
        generation.incrementAndGet()
        api.clearToken()
        val revoked = try {
            authorizer.revoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Outcome.Failure(AppError.Unexpected(e::class.simpleName))
        }
        try {
            readBinding().write(null, null, GhBackoff())
        } catch (e: SinkFailure) {
            return Outcome.Failure(AppError.DatabaseError(e.className))
        }
        problem.value = null
        state.update {
            it.copy(
                connection = if (enabled) ConnectionStatus.NOT_CONNECTED else ConnectionStatus.DISABLED,
                permissionSummary = PermissionState.DENIED,
                streamPermissions = emptyMap(),
                coverageThrough = emptyMap(),
                syncState = SyncStatus.IDLE,
                lastError = null,
            )
        }
        logger.i(GhApi.COMPONENT, "disconnected", mapOf("revoked" to (revoked is Outcome.Success)))
        return revoked
    }

    /**
     * Rebuilds [metadata] and [accountProblem] from storage and the locally granted scopes (no network): call it at
     * app start. Coverage per stream is `min(cursor position, device last upload)` as last committed.
     */
    public suspend fun refreshState() {
        val binding = try {
            readBinding()
        } catch (e: SinkFailure) {
            logger.w(GhApi.COMPONENT, "state unreadable", AppError.DatabaseError(e.className))
            return
        }
        val account = binding.accountId
        val coverage = HashMap<String, Instant>()
        var lastSuccess: Instant? = null
        if (account != null) {
            storedCursors(account).forEach { (stream, cursor) ->
                val position = GhStreamState.decode(cursor.lastSuccessCursor)
                position.through?.let { through -> coverage[stream.id] = position.deviceLastSync?.let { minOf(through, it) } ?: through }
                if (cursor.lastErrorCode == null) cursor.syncFinishedAt?.let { at -> lastSuccess = maxOf(lastSuccess ?: at, at) }
            }
        }
        val permissions = if (account == null) emptyMap() else permissionsFor(safeGrantedScopes())
        problem.value = problemOf(binding.problemCode)
        state.update {
            it.copy(
                enabled = enabled,
                connection = when {
                    !enabled -> ConnectionStatus.DISABLED
                    !config.apiEnabled -> ConnectionStatus.UNAVAILABLE
                    account == null -> ConnectionStatus.NOT_CONNECTED
                    else -> connectionFor(binding.problemCode)
                },
                permissionSummary = summaryOf(permissions),
                streamPermissions = permissions,
                coverageThrough = coverage,
                lastSuccessfulCollection = lastSuccess ?: it.lastSuccessfulCollection,
            )
        }
    }

    /** The stored cursors of [account]'s data streams; an unreadable cursor counts as absent. */
    private suspend fun storedCursors(account: String): List<Pair<GhStream, SyncCursor>> =
        catalog.filter { it.kind != GhKind.DEVICES }.mapNotNull { stream ->
            val cursor = try {
                sink.cursor(id, stream.id)
            } catch (e: SinkFailure) {
                logger.w(GhApi.COMPONENT, "cursor unreadable", AppError.DatabaseError(e.className), mapOf("stream" to stream.id))
                null
            }
            cursor?.takeIf { it.accountId == account }?.let { stream to it }
        }

    private suspend fun safeGrantedScopes(): Set<String> = try {
        authorizer.grantedScopes()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        logger.w(GhApi.COMPONENT, "granted scopes unreadable", AppError.Unexpected(e::class.simpleName))
        emptySet()
    }

    // ---------------------------------------------------------------- runs

    private suspend fun guarded(startedAt: Instant, block: suspend (Instant) -> SyncResult): SyncResult = try {
        block(startedAt)
    } catch (e: CancellationException) {
        state.update { it.copy(syncState = SyncStatus.IDLE) }
        throw e
    } catch (e: SinkFailure) {
        crashed(startedAt, AppError.DatabaseError(e.className))
    } catch (e: RuntimeException) {
        crashed(startedAt, AppError.Unexpected(e::class.simpleName))
    }

    private fun crashed(startedAt: Instant, error: AppError): SyncResult {
        val now = clock.now()
        logger.e(GhApi.COMPONENT, "sync failed", error)
        state.update { it.copy(syncState = SyncStatus.FAILED, lastError = info(error, now)) }
        return SyncResult(id, SyncResult.Status.FAILED, startedAt, now, error = error)
    }

    @Suppress("ReturnCount")
    private suspend fun run(streams: List<GhStream>, trigger: SyncTrigger, startedAt: Instant): SyncResult {
        val epoch = generation.get()
        if (!enabled) return SyncResult(id, SyncResult.Status.SKIPPED_DISABLED, startedAt, clock.now())
        if (!config.apiEnabled) return liveApiDisabled(startedAt)
        val binding = readBinding()
        val bound = binding.accountId ?: return notConnected(startedAt)
        val now = GhPlanner.truncate(clock.now())
        binding.backoff.nextAllowedAt?.takeIf { now < it }?.let { return backingOff(startedAt, it - now) }
        state.update { it.copy(syncState = SyncStatus.RUNNING, lastAttemptedCollection = startedAt) }
        val tally = RunTally()
        val token = when (val auth = api.authorize(interactive = false)) {
            is GoogleAuthorization.Token -> auth

            is GoogleAuthorization.NeedsResolution, GoogleAuthorization.Denied -> return finish(
                tally.stop(GhFailure.NeedsReauth),
                binding,
                startedAt,
            )

            is GoogleAuthorization.Failure -> return finish(tally.stop(GhFailure.AuthorizerFailed(auth.statusCode)), binding, startedAt)
        }
        val permissions = permissionsFor(token.grantedScopes)
        tally.permissions.putAll(permissions)
        val allowed = streams.filter { permissions[it.id] == PermissionState.ALLOWED }
        if (allowed.isEmpty()) return noPermission(startedAt, permissions)
        val healthUserId = when (val identity = identify()) {
            is Identified.Ok -> identity.healthUserId
            is Identified.Failed -> return finish(tally.stop(identity.failure), binding, startedAt)
        }
        if (accountIdOf(healthUserId) != bound) return accountChanged(binding, startedAt)
        tally.identified = true
        val zone = when (val settings = accountZone(token.grantedScopes)) {
            is Zoned.Ok -> settings.zone
            is Zoned.Failed -> return finish(tally.stop(settings.failure), binding, startedAt)
        }
        val mapper = GhMapper(bound, zone, now)
        val run = GhRun(api, GhFetcher(api, config, mapper, zone), mapper, bound, zone, trigger, now) { generation.get() == epoch }
        syncStreams(allowed, run, tally)
        if (generation.get() != epoch) return disconnectedDuringRun(startedAt)
        return finish(tally, binding, startedAt)
    }

    private suspend fun syncStreams(streams: List<GhStream>, run: GhRun, tally: RunTally) {
        for (stream in streams) {
            if (!enabled || tally.halted || !run.alive()) break
            val outcome = lockFor(run.accountId, stream.id).withLock { engine.sync(stream, run, logger) }
            tally.fetched += outcome.fetched
            tally.committed += outcome.committed
            tally.skipped += outcome.skipped
            when (outcome) {
                is GhStreamOutcome.Done -> {
                    tally.succeeded++
                    outcome.coverage?.let { tally.coverage[stream.id] = it }
                }

                is GhStreamOutcome.Superseded -> tally.succeeded++

                is GhStreamOutcome.Failed -> {
                    tally.failures += stream.id to outcome.failure
                    outcome.coverage?.let { tally.coverage[stream.id] = it }
                    if (outcome.failure is GhFailure.ScopeMissing) tally.permissions[stream.id] = PermissionState.DENIED
                    tally.halted = outcome.failure.stopsSource || outcome.failure.offline
                }
            }
        }
    }

    /** Status, error, binding (problem and backoff) and metadata of a finished run. */
    private suspend fun finish(tally: RunTally, binding: Binding, startedAt: Instant): SyncResult {
        val finishedAt = clock.now()
        val stop = tally.failures.firstOrNull { it.second.stopsSource }?.second
        val problemCode = problemCodeAfter(stop, tally, binding)
        val backoff = when {
            stop is GhFailure.RateLimited -> binding.backoff.after(stop.retryAfter, GhPlanner.truncate(finishedAt))
            stop == null && tally.identified -> GhBackoff()
            else -> binding.backoff
        }
        binding.write(binding.accountId, problemCode, backoff)
        problem.value = stop?.let { problemOf(it) } ?: problemOf(problemCode)
        val status = when {
            tally.failures.isEmpty() -> SyncResult.Status.SUCCESS
            tally.succeeded > 0 -> SyncResult.Status.PARTIAL
            else -> SyncResult.Status.FAILED
        }
        val error = errorOf(tally, stop, backoff, finishedAt)
        val connection = when {
            !enabled -> ConnectionStatus.DISABLED
            stop is GhFailure.AuthorizerFailed -> authConnection(stop.statusCode, problemCode)
            else -> connectionFor(problemCode)
        }
        publishRun(tally, status, connection, error, finishedAt)
        logger.i(
            GhApi.COMPONENT,
            "sync finished",
            mapOf(
                "status" to status.name,
                "fetched" to tally.fetched,
                "committed" to tally.committed,
                "skipped" to tally.skipped,
                "failedStreams" to tally.failures.size,
                "error" to error?.code,
            ),
        )
        return SyncResult(id, status, startedAt, finishedAt, tally.fetched, tally.committed, tally.skipped, error)
    }

    /** The persisted problem after a run: set by an account-level stop, cleared once the identity matched. */
    private fun problemCodeAfter(stop: GhFailure?, tally: RunTally, binding: Binding): String? = when (stop) {
        GhFailure.NeedsReauth -> NEEDS_REAUTH
        is GhFailure.AccountNotLinked -> GhFailure.ACCOUNT_NOT_LINKED
        GhFailure.ProfileNotReady -> GhFailure.PROFILE_NOT_READY
        GhFailure.LegacyFitbitAccount -> GhFailure.LEGACY_FITBIT_ACCOUNT
        else -> if (tally.identified) null else binding.problemCode
    }

    private fun publishRun(tally: RunTally, status: SyncResult.Status, connection: ConnectionStatus, error: AppError?, at: Instant) {
        state.update { current ->
            val coverage = HashMap(current.coverageThrough)
            tally.coverage.forEach { (stream, through) -> coverage[stream] = coverage[stream]?.let { maxOf(it, through) } ?: through }
            val permissions = tally.permissions.ifEmpty { current.streamPermissions }
            current.copy(
                connection = connection,
                permissionSummary = if (tally.permissions.isEmpty()) current.permissionSummary else summaryOf(permissions),
                streamPermissions = permissions,
                syncState = when (status) {
                    SyncResult.Status.SUCCESS -> SyncStatus.SUCCEEDED
                    SyncResult.Status.PARTIAL -> SyncStatus.PARTIAL
                    else -> SyncStatus.FAILED
                },
                lastSuccessfulCollection = if (status == SyncResult.Status.FAILED) current.lastSuccessfulCollection else at,
                lastError = error?.let { info(it, at) },
                coverageThrough = coverage,
            )
        }
    }

    /** The stopping failure; else the first retryable one (so the worker retries); else the first one. */
    private fun errorOf(tally: RunTally, stop: GhFailure?, backoff: GhBackoff, now: Instant): AppError? {
        if (stop is GhFailure.RateLimited) return AppError.RateLimited(backoff.nextAllowedAt?.let { it - now })
        val failures = tally.failures.map { (stream, failure) -> failure.toAppError(stream) }
        return stop?.toAppError() ?: failures.firstOrNull { it.retryable } ?: failures.firstOrNull()
    }

    private fun liveApiDisabled(startedAt: Instant): SyncResult {
        val now = clock.now()
        val error = AppError.UnsupportedFeature(LIVE_API_FEATURE)
        state.update { it.copy(connection = ConnectionStatus.UNAVAILABLE, lastError = info(error, now)) }
        return SyncResult(id, SyncResult.Status.SKIPPED_DISABLED, startedAt, now, error = error)
    }

    /** The user disconnected while this run was in flight: nothing is published and the binding is left alone. */
    private fun disconnectedDuringRun(startedAt: Instant): SyncResult {
        logger.i(GhApi.COMPONENT, "run stopped by disconnect")
        return SyncResult(id, SyncResult.Status.SKIPPED_NOT_CONNECTED, startedAt, clock.now())
    }

    private fun notConnected(startedAt: Instant): SyncResult {
        state.update { it.copy(connection = ConnectionStatus.NOT_CONNECTED, syncState = SyncStatus.IDLE) }
        return SyncResult(id, SyncResult.Status.SKIPPED_NOT_CONNECTED, startedAt, clock.now())
    }

    private fun backingOff(startedAt: Instant, wait: Duration): SyncResult {
        logger.i(GhApi.COMPONENT, "rate-limit backoff in force", mapOf("waitSeconds" to wait.inWholeSeconds))
        return SyncResult(id, SyncResult.Status.FAILED, startedAt, clock.now(), error = AppError.RateLimited(wait, "backoff"))
    }

    private fun noPermission(startedAt: Instant, permissions: Map<String, PermissionState>): SyncResult {
        val now = clock.now()
        val error = AppError.PermissionDenied(SCOPES_CAPABILITY, "no_scope")
        state.update {
            it.copy(
                permissionSummary = summaryOf(permissions),
                streamPermissions = permissions,
                syncState = SyncStatus.IDLE,
                lastError = info(error, now),
            )
        }
        return SyncResult(id, SyncResult.Status.SKIPPED_NO_PERMISSION, startedAt, now, error = error)
    }

    private suspend fun accountChanged(binding: Binding, startedAt: Instant): SyncResult {
        val now = clock.now()
        binding.write(binding.accountId, GhFailure.ACCOUNT_CHANGED, binding.backoff)
        problem.value = GoogleHealthAccountProblem.AccountChanged
        val error = AppError.NotEligible(GhFailure.ACCOUNT_CHANGED)
        state.update { it.copy(connection = ConnectionStatus.NEEDS_REAUTH, syncState = SyncStatus.FAILED, lastError = info(error, now)) }
        logger.w(GhApi.COMPONENT, "account changed; sync stopped until the user reconnects", error)
        return SyncResult(id, SyncResult.Status.ACCOUNT_CHANGED, startedAt, now, error = error)
    }

    // ---------------------------------------------------------------- account and zone

    private sealed interface Identified {
        data class Ok(val healthUserId: String) : Identified

        data class Failed(val failure: GhFailure) : Identified
    }

    /** `users/me/identity`: the Google Health user id the token belongs to (never stored raw; only its hash). */
    private suspend fun identify(): Identified =
        when (val result = api.execute(GhRequest.Identity) { !it.string(HEALTH_USER_ID).isNullOrBlank() }) {
            is GhResult.Ok -> Identified.Ok(requireNotNull(result.json.string(HEALTH_USER_ID)))
            is GhResult.Failed -> Identified.Failed(result.failure)
        }

    private sealed interface Zoned {
        data class Ok(val zone: TimeZone) : Zoned

        data class Failed(val failure: GhFailure) : Zoned
    }

    /**
     * The account's time zone from `users/me/settings` (`timeZone`, else the fixed `utcOffset`), used for civil days.
     * Without the settings scope, or when the settings cannot be read, the clock's zone is used (never the JVM default).
     */
    private suspend fun accountZone(granted: Set<String>): Zoned {
        if (GoogleHealthScopes.SETTINGS !in granted) return Zoned.Ok(clock.zone())
        return when (val result = api.execute(GhRequest.Settings)) {
            is GhResult.Ok -> Zoned.Ok(zoneOf(result.json))
            is GhResult.Failed -> if (result.failure.stopsSource) Zoned.Failed(result.failure) else Zoned.Ok(clock.zone())
        }
    }

    private fun zoneOf(settings: JsonObject): TimeZone {
        settings.string("timeZone")?.let { id ->
            try {
                return TimeZone.of(id)
            } catch (e: IllegalTimeZoneException) {
                logger.d(GhApi.COMPONENT, "unknown account time zone", mapOf("exception" to e::class.simpleName))
            }
        }
        GhJson.offsetSeconds(settings.string("utcOffset"))?.let { return UtcOffset(seconds = it).asTimeZone() }
        return clock.zone()
    }

    // ---------------------------------------------------------------- binding

    /** The account binding: account id hash, persisted problem code and rate-limit backoff. */
    private inner class Binding(private var cursor: SyncCursor?) {
        val accountId: String? get() = cursor?.accountId
        val problemCode: String? get() = cursor?.lastErrorCode
        val backoff: GhBackoff get() = GhBackoff.decode(cursor?.lastAttemptCursor)

        /** Stores a changed binding (compare-and-set; a concurrent writer wins, which only delays a backoff update). */
        suspend fun write(accountId: String?, problemCode: String?, backoff: GhBackoff) {
            if (accountId == this.accountId && problemCode == this.problemCode && backoff == this.backoff) return
            val next = SyncCursor(
                connectorId = id,
                stream = ACCOUNT_STREAM,
                lastAttemptCursor = backoff.takeIf { it != GhBackoff() }?.encode(),
                syncFinishedAt = clock.now(),
                lastErrorCode = problemCode,
                accountId = accountId,
                generation = cursor?.generation ?: 0L,
            )
            val result = sink.commit(emptyList(), next)
            if (!result.rejected) cursor = next.copy(generation = next.generation + 1)
        }
    }

    private suspend fun readBinding(): Binding = Binding(sink.cursor(id, ACCOUNT_STREAM))

    private fun lockFor(accountId: String, stream: String): Mutex = locks.computeIfAbsent("$accountId|$stream") { Mutex() }

    // ---------------------------------------------------------------- helpers

    private class RunTally {
        var fetched = 0
        var committed = 0
        var skipped = 0
        var succeeded = 0
        var identified = false
        var halted = false
        val failures = ArrayList<Pair<String?, GhFailure>>()
        val coverage = HashMap<String, Instant>()
        val permissions = LinkedHashMap<String, PermissionState>()

        fun stop(failure: GhFailure): RunTally = apply { failures += null to failure }
    }

    private fun permissionsFor(granted: Set<String>): Map<String, PermissionState> =
        catalog.associate { it.id to if (it.scope in granted) PermissionState.ALLOWED else PermissionState.DENIED }

    private fun info(error: AppError, at: Instant): ErrorInfo = ErrorInfo(error.code, at, error.detail)

    /** The connection state for a stored problem code. */
    private fun connectionFor(problemCode: String?): ConnectionStatus = when (problemCode) {
        null -> ConnectionStatus.CONNECTED
        NEEDS_REAUTH, GhFailure.ACCOUNT_CHANGED -> ConnectionStatus.NEEDS_REAUTH
        else -> ConnectionStatus.ERROR
    }

    /** Play services missing, outdated or disabled: unavailable on this device; a misconfigured client: error. */
    private fun authConnection(statusCode: Int, problemCode: String?): ConnectionStatus = when (statusCode) {
        GoogleAuthorization.Failure.SERVICE_MISSING,
        GoogleAuthorization.Failure.SERVICE_VERSION_UPDATE_REQUIRED,
        GoogleAuthorization.Failure.SERVICE_DISABLED,
        -> ConnectionStatus.UNAVAILABLE

        GoogleAuthorization.Failure.DEVELOPER_ERROR -> ConnectionStatus.ERROR

        else -> connectionFor(problemCode)
    }

    /** Device offline or egress blocked: every further stream would fail the same way, so the run stops. */
    private val GhFailure.offline: Boolean
        get() = this is GhFailure.Transient && code in OFFLINE_CODES

    public companion object {
        public const val NAME: String = "Google Health"

        /** Feature id reported while the live API flag is off. */
        public const val LIVE_API_FEATURE: String = "googlehealth.live_api"

        /** Capability id used in permission errors about the Google Health scopes. */
        public const val SCOPES_CAPABILITY: String = "googlehealth.scopes"

        internal const val ACCOUNT_STREAM: String = "account"
        internal const val NEEDS_REAUTH: String = "needs_reauth"
        private const val PROVIDER: String = GhFailure.PROVIDER
        private const val HEALTH_USER_ID = "healthUserId"
        private const val ACCOUNT_HASH_CHARS = 16
        private const val PER_SECOND = 4
        private const val PER_MINUTE = 200
        private val OFFLINE_CODES = setOf("connect", "dns", "egress_blocked", "cleartext_blocked")

        /** First wait after a stopping rate limit; doubles per consecutive one up to [BACKOFF_MAX]. */
        internal val BACKOFF_BASE: Duration = 1.minutes
        internal val BACKOFF_MAX: Duration = 1.hours

        /** The account id: a hash of the Google Health user id (round-2 correction 3); the raw id is never stored. */
        internal fun accountIdOf(healthUserId: String): String =
            GhMapper.hash("${ConnectorIds.GOOGLE_HEALTH}|$healthUserId", ACCOUNT_HASH_CHARS)

        internal fun problemOf(failure: GhFailure): GoogleHealthAccountProblem? = when (failure) {
            is GhFailure.AccountNotLinked -> GoogleHealthAccountProblem.AccountNotLinked(failure.signupUrl)
            GhFailure.ProfileNotReady -> GoogleHealthAccountProblem.ProfileNotReady
            GhFailure.LegacyFitbitAccount -> GoogleHealthAccountProblem.LegacyFitbitAccount
            else -> null
        }

        internal fun problemOf(code: String?): GoogleHealthAccountProblem? = when (code) {
            GhFailure.ACCOUNT_NOT_LINKED -> GoogleHealthAccountProblem.AccountNotLinked(null)
            GhFailure.PROFILE_NOT_READY -> GoogleHealthAccountProblem.ProfileNotReady
            GhFailure.LEGACY_FITBIT_ACCOUNT -> GoogleHealthAccountProblem.LegacyFitbitAccount
            GhFailure.ACCOUNT_CHANGED -> GoogleHealthAccountProblem.AccountChanged
            else -> null
        }

        internal fun summaryOf(permissions: Map<String, PermissionState>): PermissionState = when {
            permissions.values.none { it == PermissionState.ALLOWED } -> PermissionState.DENIED
            permissions.values.all { it == PermissionState.ALLOWED } -> PermissionState.ALLOWED
            else -> PermissionState.PARTIALLY_ALLOWED
        }
    }
}

/** The next backoff after a stopping rate limit: at least `Retry-After`, else 1, 2, 4 ... minutes, at most an hour. */
internal fun GhBackoff.after(retryAfter: Duration?, now: Instant): GhBackoff {
    val count = failures + 1
    val exponential = GoogleHealthConnector.BACKOFF_BASE * (1 shl (count - 1).coerceAtMost(MAX_DOUBLINGS))
    val wait = maxOf(retryAfter ?: Duration.ZERO, minOf(exponential, GoogleHealthConnector.BACKOFF_MAX))
    return GhBackoff(count, now + wait)
}

private const val MAX_DOUBLINGS = 6

package dev.agentle.ai.chatgpt

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.SingleFlight
import dev.agentle.core.network.AccessTokenSource
import dev.agentle.core.oauth.OAuthFailure
import dev.agentle.core.oauth.OAuthResult
import dev.agentle.core.oauth.RawTokenResponse
import dev.agentle.core.oauth.Secret
import dev.agentle.core.oauth.TokenClient
import dev.agentle.core.oauth.TokenResponse
import dev.agentle.core.oauth.TokenResponseRules
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one owner of the SIWC credentials (docs/research/06 §2.11, §2.12, §8.1, §8.2).
 *
 * - [withAccessToken] is the only way to call the API: it refreshes at <= 60 s remaining, and it alone owns the 401
 *   rule: one forced refresh, one retry, then REAUTH_REQUIRED(CREDENTIAL_REJECTED) (red team oauth-security-08).
 * - A refresh is one non-cancellable unit in the app-scoped [scope] (red team oauth-security-02): POST, durable
 *   checkpoint of the raw 2xx body as `pendingRotation`, ID-token check when one came back, then promotion (tokens
 *   written, checkpoint cleared). Concurrent callers share it through [SingleFlight]; a cancelled caller only abandons
 *   its own wait. A stored checkpoint always wins over the old refresh token, at startup and before any refresh.
 * - Sign-in completion, refresh and disconnect apply their results under one [Mutex] by compare-and-set on the
 *   credential generation they started from; tokens are written into the registration of the client id that obtained
 *   them (red team oauth-security-07/08).
 * - Tokens are cleared only on terminal refresh codes, `invalid_client`, an account mismatch or an invalid ID token,
 *   never on network errors, 5xx, captive portals or outages.
 * - [disconnect] runs under the Mutex: cancel in-flight calls, revoke, clear, DISCONNECTED (red team privacy-ai-09).
 *
 * Nothing secret is logged: tokens, codes and claims stay inside [Secret] and the vault.
 */
public class SiwcSessionManager(
    private val config: SiwcConfig,
    private val store: CredentialStore,
    private val discovery: SiwcDiscovery,
    private val tokenClient: TokenClient,
    private val idTokens: IdTokenVerifier,
    private val clock: AgentleClock,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.NONE,
) : AccessTokenSource {
    private val mutex = Mutex()
    private val refreshes = SingleFlight<Outcome<Grant>>(scope)
    private val inFlight: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private val state = MutableStateFlow(SiwcSnapshot(SiwcStatus.DISCONNECTED))

    // Guarded by mutex (reads of `vault` without it only feed labels and cache keys).
    @Volatile private var vault = SiwcVault()
    private var loaded = false
    private var dirty = false

    /** Changes whenever the credential owner changes (sign-in, disconnect, cleared tokens); stale results are dropped. */
    private var epoch = 0L

    /** Counts disconnects, so an attempt that began before one can never write tokens afterwards. */
    private var disconnects = 0L

    /** What the UI and the AI layer observe. */
    public val snapshot: StateFlow<SiwcSnapshot> = state.asStateFlow()

    /** Loads the vault and promotes a checkpointed rotation left by a crash (R06 §2.11). Safe to call repeatedly. */
    public suspend fun start(): SiwcSnapshot {
        val pending = mutex.withLock {
            ensureLoaded()
            vault.registration?.pendingRotation != null
        }
        if (pending) grant(force = false, rejected = null)
        return snapshot.value
    }

    /**
     * Runs [block] with a valid access token. A 401 from the block triggers one forced refresh and one retry; every
     * other failure is published as the new state and returned. Disconnect cancels the block; the caller then gets
     * `AppError.Cancelled("disconnected")`, while its own cancellation propagates as usual.
     */
    public suspend fun <T> withAccessToken(block: suspend (accessToken: String) -> SiwcResult<T>): Outcome<T> = tracked {
        when (val first = grant(force = false, rejected = null)) {
            is Outcome.Failure -> first

            is Outcome.Success -> when (val result = block(first.value.token)) {
                is SiwcResult.Ok -> succeeded(first.value, result.value)

                is SiwcResult.Failed ->
                    if (result.failure.unauthorized) retryOnce(first.value, block) else failed(first.value, result.failure)
            }
        }
    }

    override suspend fun accessToken(forceRefresh: Boolean, rejected: String?): Outcome<String> =
        when (val granted = grant(forceRefresh, rejected)) {
            is Outcome.Failure -> granted
            is Outcome.Success -> Outcome.Success(granted.value.token)
        }

    /**
     * Revokes the refresh token (2 tries, 300 ms apart), clears the tokens in the vault and in memory and publishes
     * DISCONNECTED, all under the session Mutex and whatever happens to the caller. Keeps the issued client id, `sub` and
     * label unless [forgetRegistration] ("delete all personal data" calls this before wiping the vault). A refresh that
     * is in flight can never write tokens afterwards: its compare-and-set fails and it revokes what it received.
     */
    public suspend fun disconnect(forgetRegistration: Boolean = false): DisconnectOutcome = withContext(NonCancellable) {
        mutex.withLock {
            ensureLoaded()
            inFlight.forEach { it.cancel(CancellationException(DISCONNECTED)) }
            val registration = vault.registration
            val confirmed = registration == null || revokeAll(registration.clientId, refreshTokensOf(registration))
            epoch += 1
            disconnects += 1
            val cleared = commitClearing(
                vault.copy(
                    generation = vault.generation + 1,
                    registration = registration?.takeUnless { forgetRegistration }?.copy(tokens = null, pendingRotation = null),
                    pendingRegistration = vault.pendingRegistration?.takeUnless { forgetRegistration },
                    signInMarker = null,
                    status = SiwcStatus.DISCONNECTED,
                ),
            )
            logger.i(COMPONENT, "disconnected", fields = mapOf("revocation" to if (confirmed) "confirmed" else "unconfirmed"))
            when {
                !cleared -> DisconnectOutcome.LocalClearFailed
                confirmed -> DisconnectOutcome.Disconnected
                else -> DisconnectOutcome.RevocationUnconfirmed
            }
        }
    }

    /** The active registration's client id (cache key for the model catalog), or null. */
    public fun currentClientId(): String? = vault.registration?.takeIf { !it.unusable }?.clientId

    /** The transient CONNECTING overlay, owned by the sign-in coordinator; never persisted. */
    public fun setConnecting(connecting: Boolean) {
        state.update { it.copy(connecting = connecting) }
    }

    /** The model the provider uses, shown with CONNECTED. */
    public fun setModel(model: String?) {
        state.update { it.copy(model = model) }
    }

    // ---- Access tokens -------------------------------------------------------------------------------------------

    internal data class Grant(val token: String, val epoch: Long)

    private sealed interface Decision {
        data class Use(val token: String) : Decision

        data object Refresh : Decision

        data class Fail(val failure: SiwcFailure) : Decision
    }

    private data class Step(val decision: Decision, val generation: Long, val registration: SiwcRegistration?, val epoch: Long)

    private suspend fun <T> tracked(block: suspend () -> Outcome<T>): Outcome<T> = coroutineScope {
        val child = async { block() }
        inFlight += child
        try {
            child.await()
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Failure(AppError.Cancelled(DISCONNECTED))
        } finally {
            inFlight -= child
        }
    }

    private suspend fun <T> retryOnce(first: Grant, block: suspend (String) -> SiwcResult<T>): Outcome<T> =
        when (val second = grant(force = true, rejected = first.token)) {
            is Outcome.Failure -> second

            is Outcome.Success -> when (val result = block(second.value.token)) {
                is SiwcResult.Ok -> succeeded(second.value, result.value)

                is SiwcResult.Failed -> failed(
                    second.value,
                    if (result.failure.unauthorized) {
                        SiwcErrorMapper.credentialRejected(
                            result.failure.status?.requestId,
                        )
                    } else {
                        result.failure
                    },
                )
            }
        }

    private suspend fun <T> succeeded(grant: Grant, value: T): Outcome<T> {
        mutex.withLock {
            if (grant.epoch == epoch && vault.registration?.tokens != null) publishLocked(SiwcStatus.CONNECTED)
        }
        return Outcome.Success(value)
    }

    private suspend fun failed(grant: Grant, failure: SiwcFailure): Outcome<Nothing> {
        mutex.withLock { if (grant.epoch == epoch) publishLocked(failure.status) }
        logger.w(COMPONENT, "call failed", failure.error, mapOf("state" to failure.status?.state, "reason" to failure.status?.reason))
        return Outcome.Failure(failure.error)
    }

    private suspend fun grant(force: Boolean, rejected: String?): Outcome<Grant> {
        val step = mutex.withLock { stepLocked(force, rejected) }
        return when (val decision = step.decision) {
            is Decision.Use -> Outcome.Success(Grant(decision.token, step.epoch))
            is Decision.Fail -> failed(Grant("", step.epoch), decision.failure)
            Decision.Refresh -> refreshes.run { withContext(NonCancellable) { refreshUnit(force, rejected) } }
        }
    }

    private suspend fun stepLocked(force: Boolean, rejected: String?): Step {
        ensureLoaded()
        val decision = if (dirty && !persist()) Decision.Fail(SiwcErrorMapper.storage()) else decide(vault, force, rejected)
        return Step(decision, vault.generation, vault.registration, epoch)
    }

    private fun decide(current: SiwcVault, force: Boolean, rejected: String?): Decision {
        val registration = current.registration?.takeIf { !it.unusable }
        val tokens = registration?.tokens
        return when {
            registration == null -> Decision.Fail(SiwcErrorMapper.notConnected())
            registration.pendingRotation != null -> Decision.Refresh
            tokens == null -> Decision.Fail(SiwcErrorMapper.notConnected())
            !tokens.planUsageGranted -> Decision.Fail(SiwcErrorMapper.planUsageNotGranted())
            force && rejected != null && !tokens.accessToken.matches(rejected) -> Decision.Use(tokens.accessToken.value)
            else -> timed(tokens, force)
        }
    }

    /** R06 §2.11 "When": refresh at <= leeway remaining or when forced, honouring `earliest_refresh_at`. */
    private fun timed(tokens: StoredTokens, force: Boolean): Decision {
        val remaining = tokens.remaining(clock.elapsed())
        val earliest = tokens.earliestRefreshAtEpochMs
        val notReady = earliest != null && clock.now().toEpochMilliseconds() < earliest
        return when {
            !force && remaining > config.refreshLeeway -> Decision.Use(tokens.accessToken.value)

            notReady && !force && remaining.isPositive() -> Decision.Use(tokens.accessToken.value)

            // A 401 on a still-valid token before earliest_refresh_at fails this call only; the session stays connected.
            earliest != null && notReady && remaining.isPositive() ->
                Decision.Fail(SiwcErrorMapper.refreshNotReady(earliest, clock.now()).copy(status = null))

            earliest != null && notReady -> Decision.Fail(SiwcErrorMapper.refreshNotReady(earliest, clock.now()))

            tokens.refreshToken == null -> Decision.Fail(SiwcErrorMapper.notConnected())

            else -> Decision.Refresh
        }
    }

    // ---- The refresh unit (runs in `scope`, non-cancellable) ------------------------------------------------------

    private sealed interface StepResult {
        /** Credentials changed (promoted, or another actor won): decide again. */
        data object Again : StepResult

        data class Done(val outcome: Outcome<Grant>) : StepResult
    }

    private suspend fun refreshUnit(force: Boolean, rejected: String?): Outcome<Grant> {
        var forced = force
        var stale = rejected
        var steps = 0
        var outcome: Outcome<Grant>? = null
        while (outcome == null) {
            val step = mutex.withLock { stepLocked(forced, stale) }
            outcome = when (val decision = step.decision) {
                is Decision.Use -> Outcome.Success(Grant(decision.token, step.epoch))

                is Decision.Fail -> failed(Grant("", step.epoch), decision.failure)

                Decision.Refresh -> {
                    steps += 1
                    val registration = step.registration
                    val result = if (registration == null || steps > MAX_REFRESH_STEPS) {
                        StepResult.Done(Outcome.Failure(AppError.Unexpected("refresh_loop")))
                    } else {
                        refreshOnce(step.generation, registration)
                    }
                    forced = false
                    stale = null
                    (result as? StepResult.Done)?.outcome
                }
            }
        }
        return outcome
    }

    /**
     * One refresh step. A stored checkpoint always wins over the old refresh token: a valid one is promoted without a
     * request; one whose body fails validation but carries a refresh token makes that token the one to use.
     */
    private suspend fun refreshOnce(generation: Long, registration: SiwcRegistration): StepResult {
        val pending = registration.pendingRotation
        val parsed = pending?.let(::parseRotation)
        val refreshToken = when {
            pending == null -> registration.tokens?.refreshToken
            parsed != null -> return promote(generation, registration, pending, parsed)
            else -> refreshTokenIn(pending.body) ?: return dropCheckpoint(generation, registration)
        } ?: return StepResult.Done(Outcome.Failure(SiwcErrorMapper.notConnected().error))
        val endpoint = discovery.cachedOrDocumented().tokenEndpoint
        return when (val answer = tokenClient.refreshRaw(endpoint, registration.clientId, refreshToken, RESOURCE_PARAMETER)) {
            is OAuthResult.Failure -> refreshFailed(generation, answer.failure)
            is OAuthResult.Success -> checkpoint(generation, registration, answer.value)
        }
    }

    /** R06 §2.11: terminal codes clear the tokens (keeping client id, `sub`, label); everything else keeps them. */
    private suspend fun refreshFailed(generation: Long, failure: OAuthFailure): StepResult {
        val mapped = SiwcErrorMapper.refresh(failure, clock.now())
        val terminal = mapped.status?.takeIf { it.state == SiwcState.REAUTH_REQUIRED }
        mutex.withLock {
            val current = vault.registration
            if (vault.generation == generation && current != null) {
                if (terminal != null) {
                    val unusable = current.unusable || terminal.reason == SiwcReason.REGISTRATION_INVALID
                    epoch += 1
                    commitClearing(
                        vault.copy(
                            generation = generation + 1,
                            registration = current.copy(tokens = null, pendingRotation = null, unusable = unusable),
                            status = terminal,
                        ),
                    )
                } else {
                    publishLocked(mapped.status)
                }
            }
        }
        val cleared = terminal != null
        logger.w(COMPONENT, "refresh failed", mapped.error, mapOf("outcome" to TokenClient.describe(failure), "cleared" to cleared))
        return StepResult.Done(Outcome.Failure(mapped.error))
    }

    /**
     * Durably checkpoints the raw answer before anything else can fail: the old refresh token is spent now. A body that
     * is not JSON at all never came from the authorization server (captive portal): it is not checkpointed and the old
     * tokens stay (red team testing-build round 3).
     */
    private suspend fun checkpoint(generation: Long, registration: SiwcRegistration, raw: RawTokenResponse): StepResult {
        if (!raw.body.value.trimStart().startsWith("{")) {
            val portal = SiwcErrorMapper.captivePortal()
            mutex.withLock { if (vault.generation == generation) publishLocked(portal.status) }
            logger.w(COMPONENT, "refresh answered by something other than the authorization server", portal.error)
            return StepResult.Done(Outcome.Failure(portal.error))
        }
        val pending = PendingRotation(raw.body, clock.elapsed().inWholeMilliseconds, clock.now().toEpochMilliseconds())
        val next = mutex.withLock {
            val current = vault.registration
            if (vault.generation != generation || current?.clientId != registration.clientId) {
                null
            } else {
                // Even if this write fails, the checkpoint stays in memory (dirty) and nothing unpersisted is handed out.
                commit(vault.copy(generation = generation + 1, registration = current.copy(pendingRotation = pending)))
                generation + 1
            }
        }
        if (next == null) return orphaned(registration.clientId, raw.body)
        val response = parseRotation(pending)
        return when {
            response != null -> promote(next, registration.copy(pendingRotation = pending), pending, response)
            refreshTokenIn(pending.body) != null -> keepInvalidCheckpoint(next)
            else -> dropCheckpoint(next, registration)
        }
    }

    private fun parseRotation(pending: PendingRotation): TokenResponse? =
        (RawTokenResponse(pending.body, HTTP_OK).parse(TokenResponseRules.REFRESH) as? OAuthResult.Success)?.value

    /**
     * A 2xx body that fails validation but carries a refresh token: the server rotated and that token is the only
     * valid one, so the raw checkpoint stays (red team oauth-security-02) and the next refresh uses it.
     */
    private suspend fun keepInvalidCheckpoint(generation: Long): StepResult {
        val failure = SiwcErrorMapper.invalidResponse(null, "token_response")
        mutex.withLock { if (vault.generation == generation) publishLocked(failure.status) }
        logger.w(COMPONENT, "refresh answer invalid; checkpoint kept", failure.error)
        return StepResult.Done(Outcome.Failure(failure.error))
    }

    /** A checkpoint without a refresh token protects nothing: drop it, the old refresh token was not replaced. */
    private suspend fun dropCheckpoint(generation: Long, registration: SiwcRegistration): StepResult {
        val failure = SiwcErrorMapper.invalidResponse(null, "token_response")
        mutex.withLock {
            val current = vault.registration
            if (vault.generation == generation && current?.clientId == registration.clientId) {
                commitClearing(
                    vault.copy(
                        generation = generation + 1,
                        registration = current.copy(pendingRotation = null),
                        status = failure.status ?: vault.status,
                    ),
                )
            }
        }
        logger.w(COMPONENT, "refresh answer invalid; checkpoint dropped", failure.error)
        return StepResult.Done(Outcome.Failure(failure.error))
    }

    /** Verifies and promotes a parsed checkpoint; retryable failures keep it, so RT2 survives (R06 §8.1). */
    private suspend fun promote(
        generation: Long,
        registration: SiwcRegistration,
        pending: PendingRotation,
        response: TokenResponse,
    ): StepResult = when (val verdict = verdict(registration, response.idToken)) {
        is Verdict.Keep -> {
            mutex.withLock { if (vault.generation == generation) publishLocked(verdict.failure.status) }
            StepResult.Done(Outcome.Failure(verdict.failure.error))
        }

        is Verdict.Discard -> discard(generation, registration, response, verdict.failure)

        Verdict.Accept -> {
            val promoted = mutex.withLock {
                val current = vault.registration
                if (vault.generation != generation || current?.clientId != registration.clientId) {
                    false
                } else {
                    val tokens = StoredTokens.rotate(current.tokens, response, pending.receivedAtElapsedMs, pending.receivedAtEpochMs)
                    val status = if (tokens.planUsageGranted) SiwcStatus.CONNECTED else SiwcErrorMapper.planUsageNotGranted().status
                    commit(
                        vault.copy(
                            generation = generation + 1,
                            registration = current.copy(tokens = tokens, pendingRotation = null),
                            status = status ?: vault.status,
                        ),
                    )
                    true
                }
            }
            if (promoted) StepResult.Again else orphaned(registration.clientId, pending.body)
        }
    }

    private suspend fun discard(
        generation: Long,
        registration: SiwcRegistration,
        response: TokenResponse,
        failure: SiwcFailure,
    ): StepResult {
        mutex.withLock {
            val current = vault.registration
            if (vault.generation == generation && current?.clientId == registration.clientId) {
                epoch += 1
                commitClearing(
                    vault.copy(
                        generation = generation + 1,
                        registration = current.copy(tokens = null, pendingRotation = null),
                        status = failure.status ?: vault.status,
                    ),
                )
            }
        }
        response.refreshToken?.let { revokeLater(registration.clientId, it) }
        logger.w(COMPONENT, "refreshed identity rejected", failure.error, mapOf("reason" to failure.status?.reason))
        return StepResult.Done(Outcome.Failure(failure.error))
    }

    private sealed interface Verdict {
        data object Accept : Verdict

        /** Retryable (JWKS outage, clock): keep the checkpoint. */
        data class Keep(val failure: SiwcFailure) : Verdict

        /** The identity is wrong: discard the rotated tokens. */
        data class Discard(val failure: SiwcFailure) : Verdict
    }

    /** R06 §2.11: an ID token in a refresh answer is verified, and its `sub` must equal the stored one. */
    private suspend fun verdict(registration: SiwcRegistration, idToken: Secret?): Verdict {
        if (idToken == null) return Verdict.Accept
        return when (val check = idTokens.verify(idToken, registration.clientId, expectedNonce = null)) {
            is IdTokenCheck.Valid ->
                if (registration.sub == null || registration.sub == check.identity.sub) {
                    Verdict.Accept
                } else {
                    Verdict.Discard(SiwcErrorMapper.accountMismatch())
                }

            IdTokenCheck.Unavailable -> Verdict.Keep(SiwcErrorMapper.identityUnavailable())

            IdTokenCheck.ClockWrong -> Verdict.Keep(SiwcErrorMapper.deviceClockWrong())

            is IdTokenCheck.Invalid -> Verdict.Discard(SiwcErrorMapper.invalidIdToken())
        }
    }

    /**
     * A rotation whose compare-and-set lost. If the registration it belongs to no longer holds tokens (disconnect or a
     * cleared registration), its refresh token is revoked; otherwise a sign-in replaced it within the same
     * registration, where a revocation could end the new grant, so it is only dropped.
     */
    private suspend fun orphaned(clientId: String, body: Secret): StepResult {
        val revoke = mutex.withLock {
            val current = vault.registration
            current?.clientId != clientId || (current.tokens == null && current.pendingRotation == null)
        }
        if (revoke) refreshTokenIn(body)?.let { revokeLater(clientId, it) }
        logger.i(COMPONENT, "rotation superseded", fields = mapOf("revoked" to revoke))
        return StepResult.Again
    }

    // ---- Sign-in support (SiwcAuthorizer) -------------------------------------------------------------------------

    internal enum class Binding { REAUTH, PENDING, NEW }

    /** Which registration an attempt is bound to (R06 §2.2, §2.14), fixed when the attempt begins. */
    internal data class SignInPlan(
        val binding: Binding,
        val clientId: String?,
        val loginHint: String?,
        val addAccount: Boolean,
        val disconnects: Long,
    ) {
        val firstRegistration: Boolean get() = binding == Binding.NEW
    }

    internal sealed interface Completion {
        data class Applied(val planUsageGranted: Boolean, val accountLabel: String?) : Completion

        data object AccountMismatch : Completion

        data object ConnectionChanged : Completion

        data object StorageFailed : Completion
    }

    /** Never re-registers while a registration exists: the active one, or a pending one left by an earlier attempt. */
    internal suspend fun planSignIn(addAccount: Boolean): SignInPlan = mutex.withLock {
        ensureLoaded()
        val active = vault.registration?.takeIf { !it.unusable }
        val pending = vault.pendingRegistration
        when {
            !addAccount && active != null -> SignInPlan(Binding.REAUTH, active.clientId, active.loginHint, false, disconnects)
            pending != null -> SignInPlan(Binding.PENDING, pending.clientId, null, addAccount, disconnects)
            else -> SignInPlan(Binding.NEW, null, null, addAccount, disconnects)
        }
    }

    /** R06 §2.5 step 5: the issued client id is persisted as the pending registration before the code is redeemed. */
    internal suspend fun savePendingRegistration(plan: SignInPlan, clientId: String): Outcome<Unit> = mutex.withLock {
        ensureLoaded()
        val pending = vault.pendingRegistration
        when {
            plan.disconnects != disconnects -> Outcome.Failure(AppError.Cancelled(DISCONNECTED))

            pending != null && pending.clientId != clientId -> Outcome.Failure(AppError.Cancelled(CONNECTION_CHANGED))

            pending != null -> Outcome.Success(Unit)

            else -> {
                val persisted = commit(
                    vault.copy(generation = vault.generation + 1, pendingRegistration = SiwcRegistration(clientId = clientId)),
                )
                if (persisted) Outcome.Success(Unit) else Outcome.Failure(SiwcErrorMapper.storage().error)
            }
        }
    }

    /** `invalid_client` at the exchange (R06 §8.1): the attempt's registration is unusable. */
    internal suspend fun markRegistrationInvalid(plan: SignInPlan, clientId: String): Unit = mutex.withLock {
        ensureLoaded()
        val active = vault.registration
        if (plan.binding == Binding.REAUTH && active?.clientId == clientId) {
            epoch += 1
            commitClearing(
                vault.copy(
                    generation = vault.generation + 1,
                    registration = active.copy(tokens = null, pendingRotation = null, unusable = true),
                    status = SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.REGISTRATION_INVALID),
                ),
            )
        } else if (vault.pendingRegistration?.clientId == clientId) {
            commit(vault.copy(generation = vault.generation + 1, pendingRegistration = null))
        }
    }

    /**
     * Applies a verified sign-in to the registration bound to the attempt, and only to it (red team oauth-security-07).
     * A different `sub` than the saved account is refused unless the user asked for another account; then
     * [onAccountChanged] runs before the new account is stored (red team privacy-ai-17).
     */
    internal suspend fun completeSignIn(
        plan: SignInPlan,
        clientId: String,
        identity: VerifiedIdentity,
        response: TokenResponse,
        onAccountChanged: suspend () -> Unit,
    ): Completion {
        val receivedAtElapsed = clock.elapsed()
        val receivedAtEpochMs = clock.now().toEpochMilliseconds()
        var callbackRanAt: Long? = null
        while (true) {
            val decided = decideSignIn(plan, clientId, identity, response, receivedAtElapsed, receivedAtEpochMs, callbackRanAt)
            if (decided != null) return decided
            // A confirmed account change: run the listener outside the non-reentrant Mutex (it may call disconnect()),
            // then decide again; the generation recheck refuses the sign-in if anything changed meanwhile.
            val generation = mutex.withLock { vault.generation }
            onAccountChanged()
            callbackRanAt = generation
        }
    }

    /** Null when [onAccountChanged] must run first (outside the lock). */
    private suspend fun decideSignIn(
        plan: SignInPlan,
        clientId: String,
        identity: VerifiedIdentity,
        response: TokenResponse,
        receivedAtElapsed: kotlin.time.Duration,
        receivedAtEpochMs: Long,
        callbackRanAt: Long?,
    ): Completion? = mutex.withLock {
        ensureLoaded()
        val active = vault.registration
        val bound = when (plan.binding) {
            Binding.REAUTH -> active?.takeIf { it.clientId == clientId && !it.unusable }
            Binding.PENDING, Binding.NEW -> vault.pendingRegistration?.takeIf { it.clientId == clientId }
        }
        val previousSub = active?.sub
        val accountChanged = previousSub != null && previousSub != identity.sub
        when {
            plan.disconnects != disconnects || bound == null -> Completion.ConnectionChanged

            callbackRanAt != null && callbackRanAt != vault.generation -> Completion.ConnectionChanged

            accountChanged && (plan.binding == Binding.REAUTH || !plan.addAccount) -> Completion.AccountMismatch

            accountChanged && callbackRanAt == null -> null

            else -> {
                val tokens = StoredTokens.from(response, receivedAtElapsed, receivedAtEpochMs)
                val replaced = active?.takeIf { it.clientId != clientId }
                val registration = bound.copy(
                    sub = identity.sub,
                    accountLabel = identity.label,
                    loginHint = identity.email,
                    tokens = tokens,
                    pendingRotation = null,
                    unusable = false,
                )
                val status = if (tokens.planUsageGranted) SiwcStatus.CONNECTED else SiwcErrorMapper.planUsageNotGranted().status
                epoch += 1
                val persisted = commit(
                    vault.copy(
                        generation = vault.generation + 1,
                        registration = registration,
                        pendingRegistration = if (plan.binding == Binding.REAUTH) vault.pendingRegistration else null,
                        status = status ?: vault.status,
                    ),
                )
                replaced?.let { old -> refreshTokensOf(old).forEach { revokeLater(old.clientId, it) } }
                if (persisted) Completion.Applied(tokens.planUsageGranted, identity.label) else Completion.StorageFailed
            }
        }
    }

    internal suspend fun writeSignInMarker(marker: SignInMarker): Unit = mutex.withLock {
        ensureLoaded()
        commit(vault.copy(signInMarker = marker))
    }

    /** Reads and clears the attempt marker. */
    internal suspend fun takeSignInMarker(): SignInMarker? = mutex.withLock {
        ensureLoaded()
        vault.signInMarker?.also { commit(vault.copy(signInMarker = null)) }
    }

    /**
     * Tokens of a sign-in that is not applied. They are revoked when that cannot end the stored grant: another account,
     * a registration without stored tokens, or a disconnect since the attempt began. Otherwise (a re-auth of the saved
     * registration) they are only dropped, because revoking them could disconnect the saved tokens too.
     */
    internal suspend fun discardSignInTokens(plan: SignInPlan, clientId: String, refreshToken: Secret?, otherAccount: Boolean) {
        if (refreshToken == null) return
        val safe = mutex.withLock {
            otherAccount || plan.binding != Binding.REAUTH || plan.disconnects != disconnects || vault.registration?.tokens == null
        }
        if (safe) revokeLater(clientId, refreshToken)
    }

    /** Best-effort revocation in the app scope (tokens being discarded); never awaited, never retried. */
    internal fun revokeLater(clientId: String, refreshToken: Secret) {
        scope.launch {
            val endpoint = (discovery.endpoints() as? SiwcResult.Ok)?.value?.revocationEndpoint ?: return@launch
            val result = tokenClient.revoke(endpoint, clientId, refreshToken)
            logger.i(COMPONENT, "discarded token revoked", fields = mapOf("confirmed" to (result is OAuthResult.Success)))
        }
    }

    // ---- Vault plumbing (callers hold the mutex) -----------------------------------------------------------------

    private suspend fun ensureLoaded() {
        if (loaded) return
        vault = when (val read = store.read()) {
            is VaultRead.Present -> read.vault

            VaultRead.Absent -> SiwcVault()

            VaultRead.Unreadable -> {
                // Keystore key lost or blob corrupt (restore to a new device): wipe it and ask for a new sign-in.
                store.wipe()
                logger.w(COMPONENT, "credential store unreadable; wiped")
                SiwcVault(status = SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.LOCAL_CREDENTIALS_UNREADABLE)).also { dirty = true }
            }
        }
        loaded = true
        epoch += 1
        if (dirty) persist()
        emit()
    }

    /** Replaces the vault in memory, persists it and publishes the snapshot; false if the write failed (kept dirty). */
    private suspend fun commit(next: SiwcVault): Boolean {
        vault = next
        dirty = true
        val persisted = persist()
        emit()
        return persisted
    }

    /**
     * [commit] for a change that removes credentials (red team R2-1). If the write fails, the old blob would bring the
     * tokens back after a restart, so the store is wiped and the cleared vault written again; false if that fails too.
     */
    private suspend fun commitClearing(next: SiwcVault): Boolean {
        if (commit(next)) return true
        val wiped = store.wipe() is Outcome.Success
        val rewritten = wiped && persist()
        if (!rewritten) logger.e(COMPONENT, "credential clear failed", SiwcErrorMapper.storage().error)
        return rewritten
    }

    private suspend fun persist(): Boolean {
        val written = store.write(vault) is Outcome.Success
        if (written) dirty = false else logger.e(COMPONENT, "credential write failed", SiwcErrorMapper.storage().error)
        return written
    }

    private suspend fun publishLocked(status: SiwcStatus?) {
        if (status != null && status != vault.status) commit(vault.copy(status = status))
    }

    private fun emit() {
        val current = vault
        state.update { it.copy(status = current.status, accountLabel = current.registration?.accountLabel) }
    }

    private fun refreshTokensOf(registration: SiwcRegistration): List<Secret> = listOfNotNull(
        registration.pendingRotation?.let { refreshTokenIn(it.body) },
        registration.tokens?.refreshToken,
    ).distinctBy { it.value }

    /** Revokes every token (R06 §2.12: two tries, 300 ms apart, on network errors and 5xx); true only if all succeeded. */
    private suspend fun revokeAll(clientId: String, tokens: List<Secret>): Boolean {
        if (tokens.isEmpty()) return true
        val endpoint = (discovery.endpoints() as? SiwcResult.Ok)?.value?.revocationEndpoint ?: return false
        return tokens.map { revokeWithRetry(endpoint, clientId, it) }.all { it }
    }

    private suspend fun revokeWithRetry(endpoint: okhttp3.HttpUrl, clientId: String, token: Secret): Boolean {
        var attempt = 0
        var confirmed = false
        var retry = true
        while (!confirmed && retry && attempt < REVOKE_ATTEMPTS) {
            if (attempt > 0) delay(REVOKE_RETRY_DELAY)
            attempt += 1
            when (val result = tokenClient.revoke(endpoint, clientId, token)) {
                is OAuthResult.Success -> confirmed = true
                is OAuthResult.Failure -> retry = retryable(result.failure)
            }
        }
        return confirmed
    }

    private fun retryable(failure: OAuthFailure): Boolean = when (failure) {
        is OAuthFailure.Network -> true
        is OAuthFailure.HttpStatus -> failure.httpStatus >= SERVER_ERROR
        is OAuthFailure.ErrorResponse -> failure.httpStatus >= SERVER_ERROR
        else -> false
    }

    private fun refreshTokenIn(body: Secret): Secret? = try {
        ((Json.parseToJsonElement(body.value) as? JsonObject)?.get("refresh_token") as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() }
            ?.let { Secret(it.content) }
    } catch (_: SerializationException) {
        // The exception message embeds the input (tokens); it is dropped (red team privacy-ai-11).
        null
    }

    internal companion object {
        const val COMPONENT = "siwc.session"
        const val DISCONNECTED = "disconnected"
        const val CONNECTION_CHANGED = "connection_changed"
        const val HTTP_OK = 200
        const val SERVER_ERROR = 500
        const val REVOKE_ATTEMPTS = 2
        const val MAX_REFRESH_STEPS = 3
        val REVOKE_RETRY_DELAY = 300.milliseconds
        val RESOURCE_PARAMETER = listOf("resource" to SiwcConstants.RESOURCE)
    }
}

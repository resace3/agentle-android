package dev.agentle.ai.chatgpt

import dev.agentle.ai.chatgpt.SiwcSessionManager.Binding
import dev.agentle.ai.chatgpt.SiwcSessionManager.Completion
import dev.agentle.ai.chatgpt.SiwcSessionManager.SignInPlan
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.oauth.AuthorizationCodeFlow
import dev.agentle.core.oauth.AuthorizationRequest
import dev.agentle.core.oauth.AuthorizationResult
import dev.agentle.core.oauth.AuthorizationSession
import dev.agentle.core.oauth.BrowserLauncher
import dev.agentle.core.oauth.CallbackOutcome
import dev.agentle.core.oauth.CallbackParameters
import dev.agentle.core.oauth.CallbackRejection
import dev.agentle.core.oauth.CallbackValidator
import dev.agentle.core.oauth.LoopbackServerConfig
import dev.agentle.core.oauth.OAuthErrorCodes
import dev.agentle.core.oauth.OAuthFailure
import dev.agentle.core.oauth.OAuthRandom
import dev.agentle.core.oauth.OAuthResult
import dev.agentle.core.oauth.TokenClient
import dev.agentle.core.oauth.TokenResponse
import dev.agentle.core.oauth.TokenResponseRules
import dev.agentle.core.oauth.toAppError
import dev.agentle.core.time.AgentleClock

/** One live browser round trip, bound to a registration when it began. Closing it releases the loopback port. */
public class SiwcAttempt internal constructor(
    internal val session: AuthorizationSession,
    internal val plan: SignInPlan,
    internal val endpoints: SiwcEndpoints,
) : AutoCloseable {
    /** True when the attempt registers a new client (`dynamic_agent_client` + `agent_name_hint`). */
    public val firstRegistration: Boolean get() = plan.firstRegistration

    /** Opens (or re-opens) the authorization page; null when the browser took it. */
    public suspend fun open(): AuthorizationResult? = session.open()

    override fun close(): Unit = session.close()

    override fun toString(): String = "SiwcAttempt(firstRegistration=$firstRegistration)"
}

/**
 * The browser part of Sign in with ChatGPT and the code redemption (docs/research/06 §2.2-2.8, §8.4):
 * - [begin] loads discovery, binds the attempt to a registration (the saved client id, a pending registration, or a
 *   new one with `dynamic_agent_client` + `agent_name_hint`), starts the loopback listener and builds the URL with
 *   `ext_agent_host_id` always, `login_hint` on re-auth and `prompt=consent` only when the user re-enables plan usage.
 *   Never `id_token_hint` or `force_reconsent`.
 * - [complete] waits for the callback, persists the issued client id before redeeming the code, exchanges it with the
 *   RFC 8707 `resource`, verifies the ID token with the attempt's nonce, compares `sub`, checks the plan scopes and
 *   hands the result to [SiwcSessionManager] for a compare-and-set write.
 *
 * `SignInCoordinator` drives it; it is not meant to be called from UI code.
 */
public class SiwcAuthorizer(
    private val config: SiwcConfig,
    private val session: SiwcSessionManager,
    private val discovery: SiwcDiscovery,
    private val tokenClient: TokenClient,
    private val idTokens: IdTokenVerifier,
    private val installIds: InstallIdProvider,
    browser: BrowserLauncher,
    random: OAuthRandom,
    clock: AgentleClock,
    private val accountChanges: AccountChangeListener = AccountChangeListener.NONE,
    private val logger: Logger = Logger.NONE,
) {
    private val flow = AuthorizationCodeFlow(
        browser,
        clock,
        random,
        LoopbackServerConfig(
            callbackPath = SiwcConstants.CALLBACK_PATH,
            timeout = config.loopbackTimeout,
            pollInterval = config.loopbackPollInterval,
            expectedIssuer = config.issuer,
            appName = config.appName,
            returnLink = config.returnLink,
        ),
        logger,
    )

    internal sealed interface Began {
        data class Started(val attempt: SiwcAttempt) : Began

        data class Failed(val outcome: SignInOutcome) : Began
    }

    internal sealed interface Finished {
        data class Done(val outcome: SignInOutcome) : Finished

        /** The exchange failed with `invalid_grant`: restart once with the issued client id (R06 §2.2). */
        data object CodeRejected : Finished
    }

    internal suspend fun begin(request: SignInRequest): Began {
        val endpoints = when (val found = discovery.endpoints()) {
            is SiwcResult.Failed -> return Began.Failed(signInOutcomeOf(found.failure))
            is SiwcResult.Ok -> found.value
        }
        val plan = session.planSignIn(request.addAccount)
        val hostId = installIds.hostId()
        if (!HostIds.isValid(hostId)) return Began.Failed(SignInOutcome.Failed(SiwcReason.APP_BUG, AppError.Unexpected("invalid_host_id")))
        val started = flow.begin(validatorFor(plan)) { redirectUri, attempt ->
            AuthorizationRequest(
                authorizationEndpoint = endpoints.authorizationEndpoint,
                clientId = plan.clientId ?: SiwcConstants.BOOTSTRAP_CLIENT_ID,
                redirectUri = redirectUri,
                scopes = SiwcConstants.SCOPES,
                state = attempt.state,
                pkce = attempt.pkce,
                nonce = attempt.nonce,
                extraParameters = parametersFor(plan, request, hostId),
                allowLoopbackHttp = config.allowCleartextLoopback,
            )
        }
        logger.i(COMPONENT, "sign-in attempt", fields = mapOf("binding" to plan.binding, "started" to (started is Outcome.Success)))
        return when (started) {
            is Outcome.Failure -> Began.Failed(SignInOutcome.Failed(SiwcReason.NONE, started.error))
            is Outcome.Success -> Began.Started(SiwcAttempt(started.value, plan, endpoints))
        }
    }

    internal suspend fun complete(attempt: SiwcAttempt): Finished = when (val result = attempt.session.await()) {
        is AuthorizationResult.Authorized -> redeem(attempt, result)
        is AuthorizationResult.NotCompleted -> Finished.Done(notCompleted(result.outcome))
        AuthorizationResult.NoBrowser -> Finished.Done(SignInOutcome.NoBrowser)
        is AuthorizationResult.Failed -> Finished.Done(SignInOutcome.Failed(SiwcReason.NONE, result.error))
    }

    /** R06 §2.3. */
    private fun parametersFor(plan: SignInPlan, request: SignInRequest, hostId: String): List<Pair<String, String>> = listOfNotNull(
        ("agent_name_hint" to config.appName).takeIf { plan.binding == Binding.NEW },
        "ext_agent_host_id" to hostId,
        "resource" to SiwcConstants.RESOURCE,
        plan.loginHint?.let { "login_hint" to it },
        ("prompt" to "consent").takeIf { request.enablePlanUsage },
    )

    /** R06 §2.5 step 4. */
    private fun validatorFor(plan: SignInPlan): CallbackValidator = CallbackValidator { parameters ->
        val returned = parameters.all("client_id")
        when {
            plan.clientId != null -> CONNECTION_CHANGED.takeIf {
                returned.isNotEmpty() &&
                    (returned.size > 1 || returned[0] != plan.clientId)
            }

            returned.isEmpty() -> REGISTRATION_INCOMPLETE

            !isIssuedClientId(parameters) -> INVALID_CLIENT_ID

            else -> null
        }
    }

    private fun isIssuedClientId(parameters: CallbackParameters): Boolean {
        val clientId = parameters.single("client_id") ?: return false
        return SiwcConstants.ISSUED_CLIENT_ID.matches(clientId) && clientId != SiwcConstants.BOOTSTRAP_CLIENT_ID
    }

    private suspend fun redeem(attempt: SiwcAttempt, authorized: AuthorizationResult.Authorized): Finished {
        val plan = attempt.plan
        val clientId = plan.clientId ?: authorized.callback.parameters.single("client_id")
            ?: return Finished.Done(SignInOutcome.RegistrationIncomplete)
        if (plan.binding == Binding.NEW) {
            // Persisted before the code is redeemed, so a failed exchange never leads to a second registration.
            val saved = session.savePendingRegistration(plan, clientId)
            if (saved is Outcome.Failure) return Finished.Done(savedFailure(saved.error))
        }
        val exchanged = tokenClient.exchangeCode(
            tokenEndpoint = attempt.endpoints.tokenEndpoint,
            clientId = clientId,
            code = authorized.callback.code,
            codeVerifier = authorized.attempt.pkce.verifier,
            redirectUri = authorized.redirectUri,
            extraParameters = listOf("resource" to SiwcConstants.RESOURCE),
            rules = EXCHANGE_RULES,
        )
        return when (exchanged) {
            is OAuthResult.Failure -> exchangeFailed(plan, clientId, exchanged.failure)
            is OAuthResult.Success -> verifyAndStore(plan, clientId, exchanged.value, authorized)
        }
    }

    private suspend fun exchangeFailed(plan: SignInPlan, clientId: String, failure: OAuthFailure): Finished {
        logger.w(COMPONENT, "code exchange failed", fields = mapOf("outcome" to TokenClient.describe(failure)))
        val code = (failure as? OAuthFailure.ErrorResponse)?.error
        if (code == OAuthErrorCodes.INVALID_CLIENT) session.markRegistrationInvalid(plan, clientId)
        return when (code) {
            OAuthErrorCodes.INVALID_GRANT -> Finished.CodeRejected
            else -> Finished.Done(signInOutcomeOf(SiwcErrorMapper.refresh(failure)))
        }
    }

    private suspend fun verifyAndStore(
        plan: SignInPlan,
        clientId: String,
        response: TokenResponse,
        authorized: AuthorizationResult.Authorized,
    ): Finished {
        val idToken = response.idToken
            ?: return Finished.Done(signInOutcomeOf(SiwcErrorMapper.invalidResponse(null, "token_response")))
        val check = idTokens.verify(idToken, clientId, authorized.attempt.nonce)
        if (check !is IdTokenCheck.Valid) {
            // Nothing is stored for an identity that could not be verified (R06 §8.1 "Discard").
            session.discardSignInTokens(plan, clientId, response.refreshToken, otherAccount = false)
            return Finished.Done(identityOutcome(check))
        }
        val completion = session.completeSignIn(plan, clientId, check.identity, response, accountChanges::onAccountChanged)
        if (completion is Completion.AccountMismatch || completion is Completion.ConnectionChanged) {
            session.discardSignInTokens(plan, clientId, response.refreshToken, otherAccount = completion is Completion.AccountMismatch)
        }
        logger.i(COMPONENT, "sign-in completed", fields = mapOf("result" to completion::class.simpleName))
        return Finished.Done(
            when (completion) {
                is Completion.Applied ->
                    if (completion.planUsageGranted) SignInOutcome.Connected(completion.accountLabel) else SignInOutcome.PlanUsageNotGranted

                Completion.AccountMismatch -> SignInOutcome.AccountMismatch

                Completion.ConnectionChanged -> SignInOutcome.ConnectionChanged

                Completion.StorageFailed -> SignInOutcome.Failed(SiwcReason.STORAGE, SiwcErrorMapper.storage().error)
            },
        )
    }

    private fun identityOutcome(check: IdTokenCheck): SignInOutcome = when (check) {
        IdTokenCheck.Unavailable -> SignInOutcome.IdentityVerificationUnavailable
        IdTokenCheck.ClockWrong -> SignInOutcome.DeviceClockWrong
        else -> SignInOutcome.InvalidIdToken
    }

    private fun savedFailure(error: AppError): SignInOutcome = when {
        error is AppError.Cancelled -> SignInOutcome.ConnectionChanged
        else -> SignInOutcome.Failed(SiwcReason.STORAGE, error)
    }

    private fun notCompleted(outcome: CallbackOutcome): SignInOutcome = when (outcome) {
        is CallbackOutcome.Rejected -> when (outcome.rejection) {
            REGISTRATION_INCOMPLETE -> SignInOutcome.RegistrationIncomplete
            CONNECTION_CHANGED -> SignInOutcome.ConnectionChanged
            else -> SignInOutcome.Failed(SiwcReason.NONE, outcome.toAppError(SiwcErrorMapper.PROVIDER) ?: UNEXPECTED)
        }

        is CallbackOutcome.Denied ->
            if (outcome.error == "access_denied") {
                SignInOutcome.NotCompleted
            } else {
                SignInOutcome.Failed(SiwcReason.AUTH_SERVER, outcome.toAppError(SiwcErrorMapper.PROVIDER) ?: UNEXPECTED)
            }

        else -> SignInOutcome.NotCompleted
    }

    private companion object {
        const val COMPONENT = "siwc.authorizer"
        val REGISTRATION_INCOMPLETE = CallbackRejection("registration_incomplete")
        val CONNECTION_CHANGED = CallbackRejection("connection_changed")
        val INVALID_CLIENT_ID = CallbackRejection("invalid_client_id")
        val UNEXPECTED = AppError.Unexpected("sign_in")

        /** R06 §2.6: `scope` and `id_token` are required; a refresh token whenever `offline_access` was granted. */
        val EXCHANGE_RULES = TokenResponseRules(requireIdToken = true, requireScope = true)
    }
}

/** Maps a classified failure before or during sign-in to the outcome the UI shows. */
internal fun signInOutcomeOf(failure: SiwcFailure): SignInOutcome = when (failure.status?.reason) {
    SiwcReason.IDENTITY_VERIFICATION_UNAVAILABLE -> SignInOutcome.IdentityVerificationUnavailable
    SiwcReason.DEVICE_CLOCK_WRONG -> SignInOutcome.DeviceClockWrong
    SiwcReason.INVALID_ID_TOKEN -> SignInOutcome.InvalidIdToken
    else -> SignInOutcome.Failed(failure.status?.reason ?: SiwcReason.NONE, failure.error)
}

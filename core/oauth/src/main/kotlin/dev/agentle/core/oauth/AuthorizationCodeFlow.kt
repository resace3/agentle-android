package dev.agentle.core.oauth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.AgentleClock
import okhttp3.HttpUrl

/** Fresh per-attempt secrets (docs/research/06 §2.4): never reused, never logged. */
public data class AuthorizationAttempt(val state: Secret, val nonce: Secret, val pkce: PkcePair)

/** How one browser round trip ended. */
public sealed interface AuthorizationResult {
    /** The callback carried a code; redeem it with [redirectUri] and the [attempt]'s PKCE verifier. */
    public data class Authorized(val callback: CallbackOutcome.Authorized, val attempt: AuthorizationAttempt, val redirectUri: HttpUrl) :
        AuthorizationResult

    /** Denied, rejected, timed out or cancelled; nothing to redeem. */
    public data class NotCompleted(val outcome: CallbackOutcome) : AuthorizationResult

    /** The loopback listener could not start or the browser could not be opened. */
    public data class Failed(val error: AppError) : AuthorizationResult
}

/**
 * The browser part of the authorization-code flow for native apps (RFC 8252 §7.3): start the loopback listener,
 * build the authorization URL for its redirect URI, open the browser, wait for the callback, and close the listener
 * (always, also on cancellation). The code exchange is a separate step ([TokenClient.exchangeCode]) so a provider
 * can persist what the callback taught it first (Sign in with ChatGPT persists the issued client id).
 */
public class AuthorizationCodeFlow(
    private val browser: BrowserLauncher,
    private val clock: AgentleClock,
    private val random: OAuthRandom,
    private val loopbackConfig: LoopbackServerConfig = LoopbackServerConfig(),
    private val logger: Logger = Logger.NONE,
) {
    /**
     * Runs one attempt. [buildRequest] receives the listener's redirect URI and fresh attempt secrets and returns the
     * authorization request; [validator] applies provider rules to the callback before it settles.
     */
    public suspend fun authorize(
        validator: CallbackValidator? = null,
        buildRequest: (redirectUri: HttpUrl, attempt: AuthorizationAttempt) -> AuthorizationRequest,
    ): AuthorizationResult {
        val attempt = AuthorizationAttempt(random.state(), random.nonce(), random.pkce())
        val server = when (val started = LoopbackCallbackServer.start(attempt.state, clock, loopbackConfig, logger, validator)) {
            is Outcome.Failure -> return AuthorizationResult.Failed(started.error)
            is Outcome.Success -> started.value
        }
        return server.use {
            val request = buildRequest(server.redirectUri, attempt)
            require(request.redirectUri == server.redirectUri) { "the authorization request must use the listener's redirect URI" }
            require(request.state == attempt.state && request.pkce == attempt.pkce) {
                "the authorization request must use the attempt's secrets"
            }
            when (val launched = browser.launch(request.toUrl())) {
                is Outcome.Failure -> AuthorizationResult.Failed(launched.error)

                is Outcome.Success -> when (val outcome = server.await()) {
                    is CallbackOutcome.Authorized -> AuthorizationResult.Authorized(outcome, attempt, server.redirectUri)
                    else -> AuthorizationResult.NotCompleted(outcome)
                }
            }
        }
    }
}

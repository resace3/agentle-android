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

    /** No browser on the device can open the authorization page (red team oauth-security-16). Nothing was sent. */
    public data object NoBrowser : AuthorizationResult

    /** The loopback listener could not start or the browser could not be opened for another reason. */
    public data class Failed(val error: AppError) : AuthorizationResult
}

/**
 * One live authorization attempt: the loopback listener is bound and the authorization URL is built. A provider's
 * sign-in coordinator keeps it while the user is in the browser, re-opens the page on a repeated tap ([open]), and
 * ends it with [close] (cancel button, or a new attempt replacing it). `toString` shows no attempt secret.
 */
public class AuthorizationSession internal constructor(
    public val attempt: AuthorizationAttempt,
    public val redirectUri: HttpUrl,
    private val authorizationUrl: HttpUrl,
    private val server: LoopbackCallbackServer,
    private val browser: BrowserLauncher,
) : AutoCloseable {
    /** True once the callback arrived, the attempt timed out or the session was closed. */
    public val isSettled: Boolean get() = server.isSettled

    /**
     * Asks the browser to open (or re-open) the authorization page. Null when it did; [AuthorizationResult.NoBrowser]
     * or [AuthorizationResult.Failed] when it could not, in which case the caller should [close] the session.
     */
    public suspend fun open(): AuthorizationResult? = when (val launched = browser.launch(authorizationUrl)) {
        is Outcome.Success -> null

        is Outcome.Failure -> if (BrowserLauncher.isNoBrowser(launched.error)) {
            AuthorizationResult.NoBrowser
        } else {
            AuthorizationResult.Failed(launched.error)
        }
    }

    /**
     * Waits for the callback. [close] (or cancelling the caller, which also closes the listener) ends the wait with
     * [CallbackOutcome.Cancelled].
     */
    public suspend fun await(): AuthorizationResult = when (val outcome = server.await()) {
        is CallbackOutcome.Authorized -> AuthorizationResult.Authorized(outcome, attempt, redirectUri)
        else -> AuthorizationResult.NotCompleted(outcome)
    }

    /** Releases the port; idempotent. */
    override fun close(): Unit = server.close()

    override fun toString(): String = "AuthorizationSession(port=${redirectUri.port}, settled=$isSettled)"
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
     * Starts one attempt without opening the browser. [buildRequest] receives the listener's redirect URI and fresh
     * attempt secrets and returns the authorization request; [validator] applies provider rules to the callback
     * before it settles. The caller owns the returned session and must [AuthorizationSession.close] it.
     */
    public fun begin(
        validator: CallbackValidator? = null,
        buildRequest: (redirectUri: HttpUrl, attempt: AuthorizationAttempt) -> AuthorizationRequest,
    ): Outcome<AuthorizationSession> {
        val attempt = AuthorizationAttempt(random.state(), random.nonce(), random.pkce())
        val server = when (val started = LoopbackCallbackServer.start(attempt.state, clock, loopbackConfig, logger, validator)) {
            is Outcome.Failure -> return started
            is Outcome.Success -> started.value
        }
        var handedOver = false
        try {
            val request = buildRequest(server.redirectUri, attempt)
            require(request.redirectUri == server.redirectUri) { "the authorization request must use the listener's redirect URI" }
            require(request.state == attempt.state && request.pkce == attempt.pkce) {
                "the authorization request must use the attempt's secrets"
            }
            val session = AuthorizationSession(attempt, server.redirectUri, request.toUrl(), server, browser)
            handedOver = true
            return Outcome.Success(session)
        } finally {
            if (!handedOver) server.close()
        }
    }

    /** Runs one whole attempt: [begin], open the browser, wait for the callback, close the listener. */
    public suspend fun authorize(
        validator: CallbackValidator? = null,
        buildRequest: (redirectUri: HttpUrl, attempt: AuthorizationAttempt) -> AuthorizationRequest,
    ): AuthorizationResult = when (val begun = begin(validator, buildRequest)) {
        is Outcome.Failure -> AuthorizationResult.Failed(begun.error)
        is Outcome.Success -> begun.value.use { session -> session.open() ?: session.await() }
    }
}

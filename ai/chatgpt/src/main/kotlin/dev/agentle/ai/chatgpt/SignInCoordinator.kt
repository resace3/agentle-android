package dev.agentle.ai.chatgpt

import dev.agentle.ai.chatgpt.SiwcAuthorizer.Began
import dev.agentle.ai.chatgpt.SiwcAuthorizer.Finished
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.oauth.AuthorizationResult
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The app-wide owner of at most one sign-in attempt (red team oauth-security-04). It lives in the app scope (a Hilt
 * singleton), never in a ViewModel, so rotating the screen or leaving it does not lose a callback.
 *
 * - [signIn] is single-flight: a repeated tap re-opens the live attempt's page and awaits the same outcome.
 * - [cancelSignIn] closes the loopback listener; the waiting callers get [SignInOutcome.NotCompleted].
 * - CONNECTING is a UI flag derived from the live attempt and never persisted.
 * - Only a non-secret marker (`createdAt`, `firstRegistration`) is persisted while an attempt is live. After process
 *   death, [recoverInterruptedSignIn] reports [SignInOutcome.Interrupted] once and clears it.
 * - An `invalid_grant` at the exchange restarts the authorization once with the issued client id, never re-registering
 *   (R06 §2.2).
 */
public class SignInCoordinator(
    private val authorizer: SiwcAuthorizer,
    private val session: SiwcSessionManager,
    private val clock: AgentleClock,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.NONE,
) : ChatGptAuthClient {
    private val lock = Any()
    private var live: LiveAttempt? = null

    private class LiveAttempt {
        lateinit var result: Deferred<SignInOutcome>

        @Volatile var attempt: SiwcAttempt? = null

        @Volatile var cancelled: Boolean = false
    }

    override val snapshot: StateFlow<SiwcSnapshot> get() = session.snapshot

    override suspend fun signIn(request: SignInRequest): SignInOutcome {
        var joined = true
        val current = synchronized(lock) {
            live?.takeIf { it.result.isActive } ?: LiveAttempt().also { created ->
                created.result = scope.async(start = CoroutineStart.LAZY) { run(request, created) }
                live = created
                joined = false
            }
        }
        if (joined) {
            // A repeated tap: bring the page back instead of starting a second listener (R06 §3.3).
            val reopened = current.attempt?.open()
            logger.i(COMPONENT, "sign-in re-opened", fields = mapOf("browser" to (reopened == null)))
        } else {
            current.result.start()
        }
        return current.result.await()
    }

    override fun cancelSignIn() {
        val current = synchronized(lock) { live } ?: return
        current.cancelled = true
        current.attempt?.close()
    }

    override suspend fun recoverInterruptedSignIn(): SignInOutcome? {
        if (synchronized(lock) { live?.result?.isActive == true }) return null
        val marker = session.takeSignInMarker() ?: return null
        logger.w(COMPONENT, "sign-in was interrupted", fields = mapOf("first_registration" to marker.firstRegistration))
        return SignInOutcome.Interrupted(marker.firstRegistration)
    }

    override suspend fun disconnect(forgetRegistration: Boolean): DisconnectOutcome {
        cancelSignIn()
        return session.disconnect(forgetRegistration)
    }

    private suspend fun run(request: SignInRequest, live: LiveAttempt): SignInOutcome {
        session.setConnecting(true)
        try {
            var restarted = false
            var outcome: SignInOutcome? = null
            while (outcome == null) {
                outcome = when (val finished = attemptOnce(request, live)) {
                    is Finished.Done -> finished.outcome

                    Finished.CodeRejected -> if (restarted) {
                        SignInOutcome.Failed(SiwcReason.NONE, CODE_REJECTED)
                    } else {
                        restarted = true
                        null
                    }
                }
            }
            logger.i(COMPONENT, "sign-in finished", fields = mapOf("outcome" to outcome::class.simpleName))
            return outcome
        } finally {
            withContext(NonCancellable) { session.takeSignInMarker() }
            session.setConnecting(false)
        }
    }

    private suspend fun attemptOnce(request: SignInRequest, live: LiveAttempt): Finished {
        val attempt = when (val began = authorizer.begin(request)) {
            is Began.Failed -> return Finished.Done(began.outcome)
            is Began.Started -> began.attempt
        }
        return attempt.use {
            live.attempt = attempt
            if (live.cancelled) {
                Finished.Done(SignInOutcome.NotCompleted)
            } else {
                session.writeSignInMarker(SignInMarker(clock.now().toEpochMilliseconds(), attempt.firstRegistration))
                when (val opened = attempt.open()) {
                    null -> authorizer.complete(attempt)
                    AuthorizationResult.NoBrowser -> Finished.Done(SignInOutcome.NoBrowser)
                    is AuthorizationResult.Failed -> Finished.Done(SignInOutcome.Failed(SiwcReason.NONE, opened.error))
                    else -> Finished.Done(SignInOutcome.NotCompleted)
                }
            }
        }
    }

    private companion object {
        const val COMPONENT = "siwc.signin"
        val CODE_REJECTED = AppError.AuthenticationRequired(SiwcErrorMapper.PROVIDER, "authorization_code_rejected")
    }
}

package dev.agentle.feature.connections.port

import dev.agentle.ai.api.AiCapabilities
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * What the ChatGPT screen (`AppRoute.ChatGpt`, Journeys 4 and 9) needs from Sign in with ChatGPT (docs/ARCHITECTURE.md
 * §8, docs/research/06 §8). The sign-in attempt lives in the application (one at a time), never in the screen, so
 * leaving the screen or rotating it does not lose the browser's answer.
 *
 * Every suspend function is main-safe and never throws except `CancellationException`.
 */
public interface ChatGptConnectionPort {
    /**
     * The connection as the screen shows it; emits the current value on collection, then every change.
     *
     * - Never connected or disconnected: [AiProviderState.Disconnected], [ChatGptConnectionState.capabilities] null
     *   (unknown), [PlanUsageAvailability.Unknown].
     * - A sign-in attempt is waiting for the browser: [ChatGptConnectionState.signInInProgress] true (also after the
     *   screen was recreated).
     * - Plan usage is reported exactly as the provider last reported it; nothing is assumed (spec §12): connected
     *   without a confirmed plan request is [PlanUsageAvailability.Unknown].
     * - No sign-in in this build: [ChatGptConnectionState.available] false.
     *
     * After [disconnect] returns, the state is [AiProviderState.Disconnected].
     */
    public val state: Flow<ChatGptConnectionState>

    /**
     * Starts the browser sign-in ("Continue with ChatGPT") and suspends until it ends (at most the attempt's
     * 10-minute timeout). Single-flight: while an attempt is live, another call re-opens its page and returns the same
     * result. [request] adds `prompt=consent` for "Use your ChatGPT plan" or starts a separate account registration.
     */
    public suspend fun connect(request: ChatGptConnectRequest = ChatGptConnectRequest()): ChatGptConnectResult

    /** Gives up the live attempt (closes the local listener); its [connect] callers get [ChatGptConnectResult.Cancelled]. */
    public fun cancelConnect()

    /**
     * On the first call after a cold start: [ChatGptConnectResult.Interrupted] once if the app died while a sign-in
     * was waiting for the browser, otherwise null.
     */
    public suspend fun takeInterruptedConnect(): ChatGptConnectResult.Interrupted?

    /** Records that the user saw the first-connection notice "You're using your ChatGPT plan" (docs/research/06 §6). */
    public suspend fun acknowledgePlanNotice(): Outcome<Unit>

    /**
     * Disconnects: cancels in-flight AI requests, revokes the refresh token, clears the tokens on the device and shows
     * [AiProviderState.Disconnected]. The client registration and host id stay unless [forgetRegistration] is true.
     * AI actions elsewhere then fail with `authentication_required`, without network.
     */
    public suspend fun disconnect(forgetRegistration: Boolean): ChatGptDisconnectResult
}

/** Options of a sign-in. */
public data class ChatGptConnectRequest(
    /** The user tapped "Use your ChatGPT plan" after declining plan usage: ask for consent again. */
    val enablePlanUsage: Boolean = false,
    /** "Use a different account": a new registration that replaces the current account (and voids AI consent). */
    val addAccount: Boolean = false,
)

/**
 * The ChatGPT connection.
 *
 * @property available false when this build has no ChatGPT sign-in.
 * @property provider the provider state (connected account label, needs re-auth, not eligible, usage limited, ...).
 * @property capabilities what the connected account can do through this app, as the provider reports it; null while
 *   unknown (not connected, or not reported yet).
 * @property planUsage whether the ChatGPT plan can pay for requests, as last reported.
 * @property signInInProgress a sign-in is waiting for the browser.
 * @property lastRequest the most recent AI request (metadata only), if any.
 * @property planNoticeAcknowledged the user dismissed the first-connection notice.
 */
public data class ChatGptConnectionState(
    val available: Boolean,
    val provider: AiProviderState,
    val capabilities: AiCapabilities? = null,
    val planUsage: PlanUsageAvailability = PlanUsageAvailability.Unknown,
    val signInInProgress: Boolean = false,
    val lastRequest: AiRequestRecord? = null,
    val planNoticeAcknowledged: Boolean = false,
) {
    override fun toString(): String =
        "ChatGptConnectionState(available=$available, provider=${provider::class.simpleName}, planUsage=$planUsage, " +
            "signInInProgress=$signInInProgress, lastRequest=${lastRequest?.id}, notice=$planNoticeAcknowledged)"
}

/** Whether the user's ChatGPT plan can pay for AI requests from this app (docs/research/06 §5). */
public sealed interface PlanUsageAvailability {
    /** Not reported yet (for example connected, but no request has been made since). */
    public data object Unknown : PlanUsageAvailability

    /** A request was paid by the plan. */
    public data object Available : PlanUsageAvailability

    /** Signed in, but plan usage was declined on the consent screen. */
    public data object NotGranted : PlanUsageAvailability

    /** The plan cannot pay here; [reason] is the provider's code (for example `account_not_eligible`). */
    public data class NotEligible(val reason: String) : PlanUsageAvailability

    /** The plan's or the app's usage limit was reached; [until] only when the provider says (never inferred). */
    public data class LimitReached(val until: Instant? = null) : PlanUsageAvailability

    /** Usage could not be checked right now; retried later. */
    public data object TemporarilyUnavailable : PlanUsageAvailability
}

/** How a sign-in attempt ended (docs/research/06 §8.1 "Sign-in and token signals"). */
public sealed interface ChatGptConnectResult {
    /** Signed in with plan usage granted. [accountLabel] is personal ([toString] leaves it out). */
    public data class Connected(val accountLabel: String? = null) : ChatGptConnectResult {
        override fun toString(): String = "Connected"
    }

    /** Signed in, but plan usage was declined: offer "Use your ChatGPT plan". */
    public data object PlanUsageNotGranted : ChatGptConnectResult

    /** The user declined on OpenAI's consent page (`access_denied`). */
    public data object Denied : ChatGptConnectResult

    /**
     * The sign-in ended without a connection and the sign-in layer does not say why (declined, tab closed or timed out:
     * "Sign-in was not completed"). Nothing changed.
     */
    public data object NotCompleted : ChatGptConnectResult

    /** The user closed the browser tab or cancelled. */
    public data object Cancelled : ChatGptConnectResult

    /** The attempt expired before the browser answered. */
    public data object TimedOut : ChatGptConnectResult

    /** No browser on the device can open the sign-in page. */
    public data object NoBrowser : ChatGptConnectResult

    /**
     * The answer did not belong to this attempt and was refused: a wrong `state` (tampered), another client id, an
     * incomplete registration or an ID token that failed verification. Nothing was stored.
     */
    public data object AttemptRejected : ChatGptConnectResult

    /** A different ChatGPT account than the connected one: nothing was switched (red team privacy-ai-17). */
    public data object AccountMismatch : ChatGptConnectResult

    /** The ID token only fits a different device time: the user should fix the clock. */
    public data object DeviceClockWrong : ChatGptConnectResult

    /** The device is offline or the network failed. */
    public data object NetworkError : ChatGptConnectResult

    /** OpenAI's sign-in or identity service is unavailable; retry later. */
    public data object ServiceUnavailable : ChatGptConnectResult

    /**
     * The app died while a sign-in waited for the browser. For a [firstRegistration] ChatGPT may list an extra
     * "Agentle" under Login connections, which the user can remove.
     */
    public data class Interrupted(val firstRegistration: Boolean) : ChatGptConnectResult

    /** Anything else, as an `AppError`. */
    public data class Failed(val error: AppError) : ChatGptConnectResult
}

/** How a disconnect ended. Tokens on the device are cleared in both success cases (docs/research/06 §2.12). */
public sealed interface ChatGptDisconnectResult {
    public data object Disconnected : ChatGptDisconnectResult

    /** OpenAI did not confirm the revocation: the user should also disconnect Agentle in ChatGPT's settings. */
    public data object RevocationUnconfirmed : ChatGptDisconnectResult

    /** Nothing changed (for example `database_error`). */
    public data class Failed(val error: AppError) : ChatGptDisconnectResult
}

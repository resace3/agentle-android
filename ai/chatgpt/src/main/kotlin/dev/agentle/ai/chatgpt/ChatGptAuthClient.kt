package dev.agentle.ai.chatgpt

import dev.agentle.core.common.AppError
import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI asks for.
 *
 * @property enablePlanUsage the user tapped "Use your ChatGPT plan" after declining it: adds `prompt=consent` (R06 §2.3,
 *   only on this explicit action).
 * @property addAccount "Use a different account or workspace" (R06 §2.14): a new registration whose account replaces the
 *   current one, which also invalidates the AI consent grants. Without it, a different account is refused.
 */
public data class SignInRequest(val enablePlanUsage: Boolean = false, val addAccount: Boolean = false)

/** How a sign-in attempt ended (docs/research/06 §8.1 "Sign-in and token signals"). */
public sealed interface SignInOutcome {
    /** Exchange OK, ID token valid, both plan scopes granted. */
    public data class Connected(val accountLabel: String?) : SignInOutcome

    /** Signed in, but `chatgpt.tokens.use.direct` or `resource.invoke` was declined: NOT_ELIGIBLE(PLAN_USAGE_NOT_GRANTED). */
    public data object PlanUsageNotGranted : SignInOutcome

    /** Consent denied, tab closed, timed out or cancelled: "Sign-in was not completed". State unchanged. */
    public data object NotCompleted : SignInOutcome

    /** No browser can open the page (red team oauth-security-16). */
    public data object NoBrowser : SignInOutcome

    /** A new registration came back without an issued client id. */
    public data object RegistrationIncomplete : SignInOutcome

    /** The callback named another client id than the attempt's registration, or the connection changed meanwhile. */
    public data object ConnectionChanged : SignInOutcome

    /**
     * The verified account (`sub`) differs from the saved one (red team privacy-ai-17). Nothing was switched and the new
     * tokens were discarded; the UI asks "Choose the original account or add a separate connection" (`addAccount`).
     */
    public data object AccountMismatch : SignInOutcome

    public data object InvalidIdToken : SignInOutcome

    /** JWKS or discovery outage: retry later. */
    public data object IdentityVerificationUnavailable : SignInOutcome

    /** The ID token fits only at its own `iat`: ask the user to fix the device clock. */
    public data object DeviceClockWrong : SignInOutcome

    /**
     * The app died during an attempt (red team oauth-security-04, testing-build-08). For a [firstRegistration] the UI
     * explains that ChatGPT may list an extra "Agentle" under Login connections, which the user can remove.
     */
    public data class Interrupted(val firstRegistration: Boolean) : SignInOutcome

    /** Anything else; [reason] picks the message. */
    public data class Failed(val reason: SiwcReason, val error: AppError) : SignInOutcome
}

/** How a disconnect ended. Local tokens are cleared in both cases (R06 §2.12). */
public sealed interface DisconnectOutcome {
    public data object Disconnected : DisconnectOutcome

    /** Revocation failed or no revocation endpoint is known; the UI shows [MESSAGE] (red team privacy-ai-09). */
    public data object RevocationUnconfirmed : DisconnectOutcome {
        public const val MESSAGE: String =
            "Local credentials were removed, but remote disconnection could not be confirmed. Disconnect the app in ChatGPT Settings."
    }

    /** The cleared credentials could not be persisted, even after wiping the store (red team R2-1); tell the user to retry. */
    public data object LocalClearFailed : DisconnectOutcome
}

/** Called when a confirmed account change replaces the saved account (red team privacy-ai-17). */
public fun interface AccountChangeListener {
    /** Invalidate every AI consent grant. Must not call back into the SIWC components. */
    public suspend fun onAccountChanged()

    public companion object {
        public val NONE: AccountChangeListener = AccountChangeListener {}
    }
}

/** The sign-in port the UI uses; `SignInCoordinator` implements it, `FakeChatGptAuthClient` scripts it in unit tests. */
public interface ChatGptAuthClient {
    public val snapshot: StateFlow<SiwcSnapshot>

    /** Single-flight: while an attempt is live, a repeated call re-opens its page and awaits the same outcome. */
    public suspend fun signIn(request: SignInRequest = SignInRequest()): SignInOutcome

    /** Closes the live attempt's listener; its [signIn] callers get [SignInOutcome.NotCompleted]. */
    public fun cancelSignIn()

    /** On a cold start: [SignInOutcome.Interrupted] once if an attempt marker survived, otherwise null. */
    public suspend fun recoverInterruptedSignIn(): SignInOutcome?

    public suspend fun disconnect(forgetRegistration: Boolean = false): DisconnectOutcome
}

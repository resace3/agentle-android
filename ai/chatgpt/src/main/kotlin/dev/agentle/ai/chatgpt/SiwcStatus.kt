package dev.agentle.ai.chatgpt

import dev.agentle.ai.api.AiProviderState
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** The persisted connection states of docs/research/06 §8.1. `CONNECTING` is a UI flag, never persisted. */
public enum class SiwcState {
    CONNECTED,
    DISCONNECTED,
    NOT_ELIGIBLE,
    PLAN_USAGE_UNAVAILABLE,
    RATE_LIMITED,
    REAUTH_REQUIRED,
    SERVER_ERROR,
    NETWORK_UNAVAILABLE,
}

/** Why a state was entered (R06 §8.1 "reason"), plus the sign-in and local reasons it names. */
public enum class SiwcReason {
    NONE,

    // NOT_ELIGIBLE
    PLAN_USAGE_NOT_GRANTED,
    ACCOUNT_NOT_ELIGIBLE,
    GRANT_NOT_AUTHORIZED,
    CLIENT_NOT_ENABLED,
    POLICY_RESTRICTED,

    // REAUTH_REQUIRED
    REGISTRATION_INVALID,
    REFRESH_REJECTED,
    CREDENTIAL_REJECTED,
    LOCAL_CREDENTIALS_UNREADABLE,
    ACCOUNT_MISMATCH,
    INVALID_ID_TOKEN,

    // RATE_LIMITED
    PLAN_LIMIT,
    TOO_MANY_REQUESTS,

    // PLAN_USAGE_UNAVAILABLE
    USAGE_UNAVAILABLE,
    USER_UNAVAILABLE,
    ROUTING,

    // NETWORK_UNAVAILABLE: a 2xx that is not the server's JSON (captive portal) or a TLS failure (interception).
    CAPTIVE_PORTAL,
    TLS_FAILURE,

    // SERVER_ERROR
    IDENTITY_VERIFICATION_UNAVAILABLE,
    DEVICE_CLOCK_WRONG,
    DISCOVERY_FAILED,
    AUTH_SERVER,
    REFRESH_NOT_READY,
    APP_BUG,
    UNSUPPORTED_CAPABILITY,
    INVALID_REQUEST,
    MODEL_UNAVAILABLE,
    UPSTREAM,
    STREAM_INTERRUPTED,
    INCOMPLETE,
    INVALID_RESPONSE,
    STORAGE,

    // Sign-in attempt outcomes that leave the persisted state unchanged.
    REGISTRATION_INCOMPLETE,
    CONNECTION_CHANGED,
    SIGN_IN_NOT_COMPLETED,
    NO_BROWSER,
}

/**
 * The connection status the UI and the AI layer read. [requestId] is the server's `x-request-id` (diagnostics only),
 * [param] the offending request field for `UNSUPPORTED_CAPABILITY`, [retryAtEpochMs] when a retry may help.
 */
@Serializable
public data class SiwcStatus(
    val state: SiwcState,
    val reason: SiwcReason = SiwcReason.NONE,
    val requestId: String? = null,
    val param: String? = null,
    val retryAtEpochMs: Long? = null,
) {
    public companion object {
        public val DISCONNECTED: SiwcStatus = SiwcStatus(SiwcState.DISCONNECTED)
        public val CONNECTED: SiwcStatus = SiwcStatus(SiwcState.CONNECTED)

        public fun retryAt(instant: Instant?): Long? = instant?.toEpochMilliseconds()
    }
}

/** What the UI shows: the persisted status plus the transient `CONNECTING` overlay and non-secret labels. */
public data class SiwcSnapshot(
    val status: SiwcStatus,
    val connecting: Boolean = false,
    val accountLabel: String? = null,
    val model: String? = null,
) {
    /**
     * The coarse provider state of docs/ARCHITECTURE.md §8 (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `NEEDS_REAUTH`,
     * `NOT_ELIGIBLE(reason)`, `USAGE_LIMITED(until?)`, `UNAVAILABLE`, `ERROR` as `Unavailable("error:...")`).
     */
    public fun toProviderState(): AiProviderState = when {
        connecting -> AiProviderState.Connecting

        else -> when (status.state) {
            SiwcState.CONNECTED -> AiProviderState.Connected(accountLabel, model)

            SiwcState.DISCONNECTED -> AiProviderState.Disconnected

            SiwcState.REAUTH_REQUIRED -> AiProviderState.NeedsReauth

            SiwcState.NOT_ELIGIBLE -> AiProviderState.NotEligible(status.reason.name.lowercase())

            SiwcState.RATE_LIMITED -> AiProviderState.UsageLimited(status.retryAtEpochMs)

            SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcState.NETWORK_UNAVAILABLE ->
                AiProviderState.Unavailable(status.state.name.lowercase())

            SiwcState.SERVER_ERROR -> AiProviderState.Unavailable("error:${status.reason.name.lowercase()}")
        }
    }
}

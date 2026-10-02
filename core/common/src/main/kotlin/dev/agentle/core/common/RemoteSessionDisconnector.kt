package dev.agentle.core.common

/**
 * A remote account session that "delete all personal data" ends first (red team privacy-ai-09): the ChatGPT session
 * revokes its refresh token and clears its credentials under its session mutex; the Google session revokes the grant.
 * Implementations never throw for remote failures; they report [RemoteDisconnectOutcome.NOT_CONFIRMED].
 */
public interface RemoteSessionDisconnector {
    /** Stable id used in deletion reports, e.g. `chatgpt`, `googlehealth`. */
    public val sessionId: String

    public suspend fun disconnectForDeletion(): RemoteDisconnectOutcome
}

public enum class RemoteDisconnectOutcome {
    /** Remote grants were revoked and local credentials cleared. */
    DISCONNECTED,

    /** There was no session to end. */
    NOT_CONNECTED,

    /** Revocation could not be confirmed (offline, server error); local credentials are still cleared. */
    NOT_CONFIRMED,
}

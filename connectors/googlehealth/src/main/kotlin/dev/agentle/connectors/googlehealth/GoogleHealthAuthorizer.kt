package dev.agentle.connectors.googlehealth

import dev.agentle.core.common.Outcome

/**
 * Result of asking for a Google access token, modeled on Play services `AuthorizationClient.authorize()`
 * (docs/ARCHITECTURE.md §7): a token with the scopes the user actually granted, a pending user action that only a UI
 * may resolve, a denial, or a Play services status code.
 */
public sealed interface GoogleAuthorization {
    /** An access token and the scopes granted with it (the consent screen is granular, docs/research/05 §2.2). */
    public data class Token(val value: String, val grantedScopes: Set<String>) : GoogleAuthorization {
        override fun toString(): String = "Token(value=<redacted>, grantedScopes=$grantedScopes)"
    }

    /**
     * The user must act (consent, account choice). [handle] is opaque to JVM code: on Android it is the
     * `PendingIntent` of the authorization result, and only a UI layer launches it. Background code never does; it
     * reports NEEDS_REAUTH instead.
     */
    public class NeedsResolution(public val handle: Any? = null) : GoogleAuthorization {
        override fun toString(): String = "NeedsResolution"
    }

    /** The user declined consent. */
    public data object Denied : GoogleAuthorization

    /**
     * Play services failed with a status code. The constants are the `ConnectionResult` / `CommonStatusCodes` values as
     * remembered (UNVERIFIED: the class reference was not fetched).
     */
    public data class Failure(val statusCode: Int) : GoogleAuthorization {
        public companion object {
            /** Google Play services is not installed. */
            public const val SERVICE_MISSING: Int = 1

            /** The installed Google Play services is too old. */
            public const val SERVICE_VERSION_UPDATE_REQUIRED: Int = 2

            /** Google Play services is disabled. */
            public const val SERVICE_DISABLED: Int = 3
            public const val NETWORK_ERROR: Int = 7
            public const val INTERNAL_ERROR: Int = 8

            /** The OAuth client is misconfigured (package name or signing certificate not registered). */
            public const val DEVELOPER_ERROR: Int = 10
            public const val CANCELED: Int = 16
        }
    }
}

/**
 * Port to Google authorization for the Google Health connector. Production wraps Play services
 * `AuthorizationClient` (an Android adapter outside this module); the fake flavor binds a scripted fake. The port is
 * flow-agnostic: no authorization-code exchange, no refresh token and no client secret ever reach the app.
 */
public interface GoogleHealthAuthorizer {
    /** A token. With [interactive] false an implementation never starts UI; it returns [GoogleAuthorization.NeedsResolution]. */
    public suspend fun token(interactive: Boolean): GoogleAuthorization

    /** Drops [token] from the local token cache (Play services `clearToken`) after the server rejected it with 401. */
    public suspend fun invalidate(token: String)

    /** Scopes currently granted (full scope URLs); empty when not connected or unknown. */
    public suspend fun grantedScopes(): Set<String>

    /** Revokes every granted scope (`revokeAccess`). */
    public suspend fun revoke(): Outcome<Unit>
}

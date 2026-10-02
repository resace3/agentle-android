package dev.agentle.fakes.googlehealth

/**
 * The outcome of one authorization call, shaped like Play services `AuthorizationClient` results: a token with its
 * granted scopes, a pending user resolution (an opaque handle; the JVM never starts UI), a denial, or a failure with
 * a status code. The fake keeps its own copy of these shapes instead of depending on the client module.
 */
public sealed interface FakeAuthorization {
    public data class Token(val value: String, val grantedScopes: Set<String>) : FakeAuthorization {
        override fun toString(): String = "Token(value=<fake>, grantedScopes=$grantedScopes)"
    }

    public data object NeedsResolution : FakeAuthorization

    public data object Denied : FakeAuthorization

    public data class Failure(val statusCode: Int) : FakeAuthorization

    /**
     * Play services status codes the fake can return (`ConnectionResult` / `CommonStatusCodes` values as remembered,
     * UNVERIFIED against the class reference: nothing was fetched).
     */
    public companion object {
        /** Google Play services is not installed. */
        public const val SERVICE_MISSING: Int = 1

        /** The installed Google Play services is too old. */
        public const val SERVICE_VERSION_UPDATE_REQUIRED: Int = 2

        /** Google Play services is disabled on the device. */
        public const val SERVICE_DISABLED: Int = 3
        public const val NETWORK_ERROR: Int = 7
        public const val INTERNAL_ERROR: Int = 8

        /** A misconfigured OAuth client (package name or signing certificate not registered). */
        public const val DEVELOPER_ERROR: Int = 10
        public const val CANCELED: Int = 16
    }
}

/**
 * A scripted stand-in for the Google authorization client, for JVM tests and the app's fake flavor. It issues the
 * tokens of docs/research/05 §8.1 (`fake-valid`, `fake-expired`, `fake-revoked`, `fake-scope-*`, ...), which
 * [FakeGoogleHealthServer] understands. Granted scopes are full scope URLs, as `AuthorizationClient` reports them.
 *
 * Default behavior: [token] returns the current token. Scripts (testing-build-01):
 * - [grantPartial]: the user granted only some scopes;
 * - [expireOnce]: the current token is `fake-expired` until the client invalidates it, then a fresh one is issued;
 * - [needResolutionInBackground] (also [requireConsent]): silent calls need a user resolution until an interactive
 *   call succeeds, so a background worker gets [FakeAuthorization.NeedsResolution];
 * - [revokeAccessUpstream]: the user removed the app's access in their Google Account; the cached token is rejected
 *   (`fake-revoked`) and, once invalidated, silent calls need a resolution; [revoke] is the app's own disconnect;
 * - [failWithNetworkError]: the next calls fail with `NETWORK_ERROR`;
 * - [playServicesMissing]: Google Play services is missing (or outdated or disabled) until [playServicesAvailable];
 * - [enqueue]: any sequence of outcomes, for example a cancellation or a denial.
 */
public class FakeGoogleAuthorizer(initialToken: String = FakeTokens.VALID) {
    private val lock = Any()
    private var current: String = initialToken
    private var refreshed: String? = null
    private var revoked = false
    private var revokedUpstream = false
    private var unavailable: Int? = null
    private val script = ArrayDeque<FakeAuthorization>()
    private val log = ArrayList<String>()

    /** What an interactive call returns while consent is pending (null: the user approves and gets the token). */
    @Volatile public var interactiveOutcome: FakeAuthorization? = null

    /** The calls made so far, for example `token(interactive=false)` or `invalidate(fake-expired)`. */
    public val calls: List<String> get() = synchronized(lock) { log.toList() }

    /** How many times the client invalidated a token. */
    public val invalidations: Int get() = synchronized(lock) { log.count { it.startsWith("invalidate") } }

    @Suppress("ReturnCount")
    public fun token(interactive: Boolean): FakeAuthorization = synchronized(lock) {
        log += "token(interactive=$interactive)"
        unavailable?.let { return FakeAuthorization.Failure(it) }
        script.removeFirstOrNull()?.let { return it }
        if (revoked || current.isEmpty()) {
            if (!interactive) return FakeAuthorization.NeedsResolution
            interactiveOutcome?.let { return it }
            revoked = false
            if (current.isEmpty()) current = FakeTokens.VALID
        }
        return FakeAuthorization.Token(current, grantedScopes(current))
    }

    public fun invalidate(token: String) {
        synchronized(lock) {
            log += "invalidate($token)"
            if (token != current) return
            if (revokedUpstream) {
                revokedUpstream = false
                revoked = true
                current = FakeTokens.VALID
            } else {
                refreshed?.let {
                    current = it
                    refreshed = null
                }
            }
        }
    }

    public fun grantedScopes(): Set<String> = synchronized(lock) {
        log += "grantedScopes()"
        if (revoked || unavailable != null) emptySet() else grantedScopes(current)
    }

    /** Revokes the grant (the app's disconnect): later silent calls need a resolution. False without Play services. */
    public fun revoke(): Boolean = synchronized(lock) {
        log += "revoke()"
        if (unavailable != null) return false
        revoked = true
        true
    }

    // ------------------------------------------------------------------ scripting

    /** From now on, issue [token] (for example [FakeTokens.scoped] for a partial grant). */
    public fun issue(token: String) {
        synchronized(lock) {
            current = token
            refreshed = null
            revoked = false
            revokedUpstream = false
        }
    }

    /** The user granted only [scopes] (suffixes such as `sleep.readonly`, or full scope URLs). */
    public fun grantPartial(scopes: Collection<String>) {
        issue(FakeTokens.scoped(scopes))
    }

    /** The current token is expired: it is `fake-expired` until invalidated, then [next] is issued. */
    public fun expireOnce(next: String = FakeTokens.VALID) {
        synchronized(lock) {
            current = FakeTokens.EXPIRED
            refreshed = next
        }
    }

    /** The next [token] calls return [results], in order, before the default behavior resumes. */
    public fun enqueue(vararg results: FakeAuthorization) {
        synchronized(lock) { script.addAll(results) }
    }

    /** Silent calls need user interaction until an interactive call succeeds (no grant on this device yet). */
    public fun requireConsent() {
        synchronized(lock) { revoked = true }
    }

    /** A background (silent) call gets a pending resolution, as when consent must be shown again; see [requireConsent]. */
    public fun needResolutionInBackground() {
        requireConsent()
    }

    /**
     * The user removed the app's access in their Google Account: the cached token is `fake-revoked` (the server answers
     * 401), and after the client invalidates it silent calls need a resolution; an interactive consent issues a fresh
     * token.
     */
    public fun revokeAccessUpstream() {
        synchronized(lock) {
            current = FakeTokens.REVOKED
            refreshed = null
            revokedUpstream = true
        }
    }

    /** The next [times] calls fail with `NETWORK_ERROR` (status 7). */
    public fun failWithNetworkError(times: Int = 1) {
        synchronized(lock) { repeat(times) { script.addLast(FakeAuthorization.Failure(FakeAuthorization.NETWORK_ERROR)) } }
    }

    /** Google Play services is unavailable: every call fails with [statusCode] until [playServicesAvailable]. */
    public fun playServicesMissing(statusCode: Int = FakeAuthorization.SERVICE_MISSING) {
        synchronized(lock) { unavailable = statusCode }
    }

    public fun playServicesAvailable() {
        synchronized(lock) { unavailable = null }
    }

    private fun grantedScopes(token: String): Set<String> =
        FakeTokens.scopesOf(token)?.map { GhScopes.full(it) }?.toSet() ?: GhScopes.V1.map { GhScopes.full(it) }.toSet()
}

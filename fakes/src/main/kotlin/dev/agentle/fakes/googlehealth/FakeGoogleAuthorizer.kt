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

    public companion object {
        /** `CommonStatusCodes.DEVELOPER_ERROR` (a misconfigured OAuth client) and `CANCELED`. */
        public const val DEVELOPER_ERROR: Int = 10
        public const val CANCELED: Int = 16
        public const val NETWORK_ERROR: Int = 7
    }
}

/**
 * A scripted stand-in for the Google authorization client, for JVM tests and the app's fake flavor. It issues the
 * tokens of docs/research/05 §8.1 (`fake-valid`, `fake-expired`, `fake-revoked`, `fake-scope-*`, ...), which
 * [FakeGoogleHealthServer] understands, and can return a pending resolution, a status-code failure, a cancellation,
 * or a revoked grant. Granted scopes are full scope URLs, as `AuthorizationClient` reports them.
 *
 * Default behavior: [token] returns the current token. [expireOnce] makes the current token `fake-expired` until
 * the client invalidates it, after which the next call returns the refreshed token. After [revoke], silent calls
 * need a resolution until an interactive call succeeds.
 */
public class FakeGoogleAuthorizer(initialToken: String = FakeTokens.VALID) {
    private val lock = Any()
    private var current: String = initialToken
    private var refreshed: String? = null
    private var revoked = false
    private val script = ArrayDeque<FakeAuthorization>()
    private val log = ArrayList<String>()

    /** What an interactive call returns while consent is pending (null: the user approves and gets the token). */
    @Volatile public var interactiveOutcome: FakeAuthorization? = null

    /** The calls made so far, for example `token(interactive=false)` or `invalidate(fake-expired)`. */
    public val calls: List<String> get() = synchronized(lock) { log.toList() }

    /** How many times the client invalidated a token. */
    public val invalidations: Int get() = synchronized(lock) { log.count { it.startsWith("invalidate") } }

    public suspend fun token(interactive: Boolean): FakeAuthorization = synchronized(lock) {
        log += "token(interactive=$interactive)"
        script.removeFirstOrNull()?.let { return it }
        if (revoked || current.isEmpty()) {
            if (!interactive) return FakeAuthorization.NeedsResolution
            interactiveOutcome?.let { return it }
            revoked = false
            if (current.isEmpty()) current = FakeTokens.VALID
        }
        return FakeAuthorization.Token(current, grantedScopes(current))
    }

    public suspend fun invalidate(token: String) {
        synchronized(lock) {
            log += "invalidate($token)"
            if (token == current) {
                refreshed?.let {
                    current = it
                    refreshed = null
                }
            }
        }
    }

    public suspend fun grantedScopes(): Set<String> = synchronized(lock) {
        log += "grantedScopes()"
        if (revoked) emptySet() else grantedScopes(current)
    }

    /** Revokes the grant: later silent calls need a resolution. */
    public suspend fun revoke(): Boolean = synchronized(lock) {
        log += "revoke()"
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
        }
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

    private fun grantedScopes(token: String): Set<String> =
        FakeTokens.scopesOf(token)?.map { GhScopes.full(it) }?.toSet() ?: GhScopes.V1.map { GhScopes.full(it) }.toSet()
}

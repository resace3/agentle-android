package dev.agentle.core.oauth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.network.AccessTokenSource
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Durable storage of one [TokenSet]. [save] returns only after the set is persisted (atomic replace). */
public interface TokenSetStore {
    public suspend fun load(): TokenSet?

    public suspend fun save(tokens: TokenSet)

    public suspend fun clear()
}

/** A [TokenSetStore] in memory, for tests and the fake flavor. */
public class InMemoryTokenSetStore(initial: TokenSet? = null) : TokenSetStore {
    @Volatile private var tokens: TokenSet? = initial

    /** Every set ever saved, in order (tests check that a rotation was persisted before use). */
    public val saved: MutableList<TokenSet> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun load(): TokenSet? = tokens

    override suspend fun save(tokens: TokenSet) {
        this.tokens = tokens
        saved += tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

/**
 * An [AccessTokenSource] over a rotating refresh token, for providers without extra refresh rules:
 *
 * - refreshes when at most [refreshLeeway] of the access token's lifetime remains, or when forced after a 401;
 * - serializes refreshes with a [Mutex] and re-checks inside it, so N concurrent callers cause one refresh and a stale
 *   401 (the caller's token is older than the stored one) causes none;
 * - runs the refresh request and the persisting of the rotated set as one non-cancellable step, so a cancelled caller
 *   never loses the only valid refresh token, and persists before returning the new access token;
 * - clears the tokens only on a terminal refresh error ([OAuthErrorCodes.TERMINAL_REFRESH], `invalid_client`), never
 *   on network errors or 5xx.
 */
public class RotatingTokenSource(
    private val tokenClient: TokenClient,
    private val tokenEndpoint: HttpUrl,
    private val clientId: String,
    private val store: TokenSetStore,
    private val clock: AgentleClock,
    private val provider: String,
    private val refreshLeeway: Duration = 60.seconds,
    private val extraParameters: List<Pair<String, String>> = emptyList(),
    private val logger: Logger = Logger.NONE,
) : AccessTokenSource {
    private val mutex = Mutex()

    override suspend fun accessToken(forceRefresh: Boolean, rejected: String?): Outcome<String> = mutex.withLock {
        val current = store.load() ?: return@withLock Outcome.Failure(AppError.AuthenticationRequired(provider, "no_tokens"))
        val now = clock.now()
        val staleRejection = forceRefresh && rejected != null && !current.accessToken.matches(rejected)
        val needsRefresh = !staleRejection && (forceRefresh || current.expiresWithin(now, refreshLeeway))
        if (!needsRefresh) return@withLock Outcome.Success(current.accessToken.value)
        val refreshToken =
            current.refreshToken ?: return@withLock Outcome.Failure(AppError.AuthenticationRequired(provider, "no_refresh_token"))
        withContext(NonCancellable) { refreshAndPersist(current, refreshToken) }
    }

    private suspend fun refreshAndPersist(current: TokenSet, refreshToken: Secret): Outcome<String> =
        when (val result = tokenClient.refresh(tokenEndpoint, clientId, refreshToken, extraParameters)) {
            is OAuthResult.Success -> {
                val rotated = TokenSet.rotate(current, result.value, clock.now())
                store.save(rotated)
                Outcome.Success(rotated.accessToken.value)
            }

            is OAuthResult.Failure -> {
                val failure = result.failure
                val terminal = failure is OAuthFailure.ErrorResponse &&
                    (failure.error in OAuthErrorCodes.TERMINAL_REFRESH || failure.error == OAuthErrorCodes.INVALID_CLIENT)
                if (terminal) store.clear()
                logger.w(COMPONENT, "refresh failed", fields = mapOf("outcome" to TokenClient.describe(failure), "cleared" to terminal))
                Outcome.Failure(if (terminal) AppError.AuthenticationRequired(provider, "refresh_rejected") else failure.appError)
            }
        }

    private companion object {
        const val COMPONENT = "oauth.refresh"
    }
}

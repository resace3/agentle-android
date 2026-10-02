package dev.agentle.core.oauth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.SingleFlight
import dev.agentle.core.network.AccessTokenSource
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
 * - coalesces refreshes with a [SingleFlight] that runs in [scope] (an app-lifetime scope with a `SupervisorJob`),
 *   detached from every caller, and re-checks the stored set inside it, so N concurrent callers cause one refresh and a
 *   stale 401 (the caller's token is older than the stored one) causes none;
 * - runs the refresh request and the persisting of the rotated set as one non-cancellable unit, so a cancelled caller
 *   only abandons its own wait and never loses the only valid refresh token, and persists before returning the new
 *   access token (red team oauth-security-02, lifecycle-battery-13);
 * - clears the tokens only on a terminal refresh error ([OAuthErrorCodes.TERMINAL_REFRESH], `invalid_client`), never
 *   on network errors or 5xx.
 *
 * A 200 whose body fails validation loses the rotated token here; providers whose refresh tokens rotate and that
 * need a durable checkpoint of the raw answer use [TokenClient.refreshRaw] (Sign in with ChatGPT does).
 */
public class RotatingTokenSource(
    private val tokenClient: TokenClient,
    private val tokenEndpoint: HttpUrl,
    private val clientId: String,
    private val store: TokenSetStore,
    private val clock: AgentleClock,
    private val provider: String,
    scope: CoroutineScope,
    private val refreshLeeway: Duration = 60.seconds,
    private val extraParameters: List<Pair<String, String>> = emptyList(),
    private val logger: Logger = Logger.NONE,
) : AccessTokenSource {
    private val refreshes = SingleFlight<Outcome<String>>(scope)

    override suspend fun accessToken(forceRefresh: Boolean, rejected: String?): Outcome<String> =
        usable(store.load(), forceRefresh, rejected) ?: refreshes.run {
            withContext(NonCancellable) { refreshIfStillNeeded(forceRefresh, rejected) }
        }

    /** The answer without a refresh, or null if one is needed. */
    private fun usable(current: TokenSet?, forceRefresh: Boolean, rejected: String?): Outcome<String>? = when {
        current == null -> Outcome.Failure(AppError.AuthenticationRequired(provider, "no_tokens"))
        forceRefresh && rejected != null && !current.accessToken.matches(rejected) -> Outcome.Success(current.accessToken.value)
        !forceRefresh && !current.expiresWithin(clock.now(), refreshLeeway) -> Outcome.Success(current.accessToken.value)
        else -> null
    }

    private suspend fun refreshIfStillNeeded(forceRefresh: Boolean, rejected: String?): Outcome<String> {
        val current = store.load()
        val answer = usable(current, forceRefresh, rejected)
        val refreshToken = current?.refreshToken
        return when {
            answer != null -> answer
            refreshToken == null -> Outcome.Failure(AppError.AuthenticationRequired(provider, "no_refresh_token"))
            else -> refreshAndPersist(current, refreshToken)
        }
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

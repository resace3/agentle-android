package dev.agentle.core.network

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome

/**
 * Where a remote client gets its bearer token. Implementations (SIWC session manager, Google authorizer, fakes)
 * own refresh and must coalesce concurrent refreshes (`SingleFlight`): rotating refresh tokens are single-use.
 */
public interface AccessTokenSource {
    /**
     * A usable access token. [forceRefresh] is set after the server rejected [rejected] with 401; the source must
     * refresh unless the token it holds is already newer than [rejected] (another caller refreshed meanwhile).
     */
    public suspend fun accessToken(forceRefresh: Boolean = false, rejected: String? = null): Outcome<String>
}

/**
 * Calls [block] with a token; on [AppError.TokenExpired] (HTTP 401) refreshes once and calls it again. A second
 * 401 becomes [AppError.AuthenticationRequired]: the user has to sign in again, retrying cannot fix it.
 */
public suspend fun <T> AccessTokenSource.withAccessToken(provider: String, block: suspend (token: String) -> Outcome<T>): Outcome<T> {
    val first = when (val token = accessToken()) {
        is Outcome.Failure -> return token
        is Outcome.Success -> token.value
    }
    val result = block(first)
    if ((result as? Outcome.Failure)?.error !is AppError.TokenExpired) return result
    val second = when (val token = accessToken(forceRefresh = true, rejected = first)) {
        is Outcome.Failure -> return token
        is Outcome.Success -> token.value
    }
    val retried = block(second)
    return if ((retried as? Outcome.Failure)?.error is AppError.TokenExpired) {
        Outcome.Failure(AppError.AuthenticationRequired(provider, "401 after refresh"))
    } else {
        retried
    }
}

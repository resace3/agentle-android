package dev.agentle.core.oauth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlin.time.Duration

/**
 * Why a token-endpoint or revocation call failed. Richer than [AppError] so providers can apply their documented
 * rules (docs/research/06 §5.5: classify OAuth errors by the `error` string, never by the HTTP status), and every
 * variant still maps to an [AppError] through [appError]. No variant carries server-provided free text.
 */
public sealed interface OAuthFailure {
    public val appError: AppError

    /** The server answered with an OAuth error code (`{"error":"x"}` or `{"error":{"code":"x"}}`). */
    public data class ErrorResponse(val error: String, val httpStatus: Int, val shape: BodyShape, val requestId: String? = null) :
        OAuthFailure {
        override val appError: AppError get() = OAuthErrorCodes.toAppError(error, httpStatus)
    }

    /** A non-2xx status without a usable OAuth error code (HTML gateway page, empty 504, `{"detail":...}`). */
    public data class HttpStatus(
        val httpStatus: Int,
        val shape: BodyShape,
        val retryAfter: Duration? = null,
        val requestId: String? = null,
    ) : OAuthFailure {
        override val appError: AppError
            get() = when {
                httpStatus == TOO_MANY_REQUESTS -> AppError.RateLimited(retryAfter, "oauth_http_$httpStatus")
                else -> AppError.RemoteServerError(httpStatus, "oauth_http_$httpStatus")
            }
    }

    /** The request never got an HTTP answer: DNS, connect, TLS, timeout, connection reset. [kind] is a stable code. */
    public data class Network(val kind: String) : OAuthFailure {
        override val appError: AppError get() = AppError.NetworkUnavailable(kind)
    }

    /** A 2xx answer that violates the protocol (bad JSON, `token_type` not bearer, no access token, ...). */
    public data class InvalidResponse(val reason: String, val httpStatus: Int) : OAuthFailure {
        override val appError: AppError get() = AppError.ParsingError("oauth_invalid_response:$reason")
    }

    /** A 3xx answer. Token and revocation calls never follow redirects (docs/research/06 §8.3). */
    public data class Redirected(val httpStatus: Int) : OAuthFailure {
        override val appError: AppError get() = AppError.RemoteServerError(httpStatus, "oauth_redirect_refused")
    }

    /** The request was refused locally before it left the device (for example cleartext to a non-loopback host). */
    public data class Blocked(val reason: String) : OAuthFailure {
        override val appError: AppError get() = AppError.Unexpected("oauth_blocked:$reason")
    }
}

private const val TOO_MANY_REQUESTS = 429

/** The result of an OAuth endpoint call: a value, or an [OAuthFailure] that callers can map with [toOutcome]. */
public sealed interface OAuthResult<out T> {
    public data class Success<out T>(val value: T) : OAuthResult<T>

    public data class Failure(val failure: OAuthFailure) : OAuthResult<Nothing>
}

public fun <T> OAuthResult<T>.toOutcome(): Outcome<T> = when (this) {
    is OAuthResult.Success -> Outcome.Success(value)
    is OAuthResult.Failure -> Outcome.Failure(failure.appError)
}

/** Standard OAuth error codes (RFC 6749 §5.2, RFC 7009 §2.2.1) and the refresh codes Sign in with ChatGPT documents. */
public object OAuthErrorCodes {
    public const val INVALID_REQUEST: String = "invalid_request"
    public const val INVALID_CLIENT: String = "invalid_client"
    public const val INVALID_GRANT: String = "invalid_grant"
    public const val UNAUTHORIZED_CLIENT: String = "unauthorized_client"
    public const val UNSUPPORTED_GRANT_TYPE: String = "unsupported_grant_type"
    public const val INVALID_SCOPE: String = "invalid_scope"
    public const val INVALID_TARGET: String = "invalid_target"
    public const val TEMPORARILY_UNAVAILABLE: String = "temporarily_unavailable"
    public const val SERVER_ERROR: String = "server_error"
    public const val SLOW_DOWN: String = "slow_down"
    public const val UNSUPPORTED_TOKEN_TYPE: String = "unsupported_token_type"

    /**
     * Refresh-grant codes after which the refresh token is unusable and the user must authorize again
     * (docs/research/06 §2.11, [D-ER]): `invalid_grant` plus the provider codes OpenAI documents.
     */
    public val TERMINAL_REFRESH: Set<String> = setOf(
        INVALID_GRANT,
        "invalid_refresh_token",
        "token_expired",
        "refresh_token_expired",
        "refresh_token_invalidated",
        "refresh_token_reused",
    )

    /** Generic mapping; providers refine it (for example `SiwcErrorMapper`). */
    public fun toAppError(error: String, httpStatus: Int): AppError = when (error) {
        in TERMINAL_REFRESH, INVALID_CLIENT, UNAUTHORIZED_CLIENT, INVALID_SCOPE -> AppError.AuthenticationRequired(
            "oauth",
            "oauth_error:$error",
        )

        TEMPORARILY_UNAVAILABLE -> AppError.RemoteServerError(
            if (httpStatus >=
                SERVER_ERROR_MIN
            ) {
                httpStatus
            } else {
                UNAVAILABLE
            },
            "oauth_error:$error",
        )

        SERVER_ERROR -> AppError.RemoteServerError(if (httpStatus >= SERVER_ERROR_MIN) httpStatus else INTERNAL, "oauth_error:$error")

        SLOW_DOWN -> AppError.RateLimited(null, "oauth_error:$error")

        else -> AppError.Unexpected("oauth_error:$error")
    }

    private const val SERVER_ERROR_MIN = 500
    private const val INTERNAL = 500
    private const val UNAVAILABLE = 503
}

package dev.agentle.core.network

import dev.agentle.core.common.AppError
import dev.agentle.core.common.AppException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Generic HTTP failure mapping. Provider modules refine it with their own documented error codes
 * (`SiwcErrorMapper`, the Google Health error policy); this is the fallback and the transport-level mapping.
 */
public object HttpErrorMapper {
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429

    /**
     * Maps a non-2xx status. [provider] names the remote service (`googlehealth`, `chatgpt`) in errors that need it.
     * 401 is [AppError.TokenExpired] so callers refresh once (see [withAccessToken]); 403 is a remote permission
     * problem (missing scope, plan) that the provider mapper usually refines.
     */
    public fun fromStatus(
        status: Int,
        provider: String,
        retryAfterHeader: String? = null,
        now: Instant? = null,
        detail: String? = null,
    ): AppError = when (status) {
        UNAUTHORIZED -> AppError.TokenExpired(provider, detail)
        FORBIDDEN -> AppError.PermissionDenied("$provider.remote", detail)
        REQUEST_TIMEOUT -> AppError.NetworkUnavailable(detail ?: "http 408")
        TOO_MANY_REQUESTS -> AppError.RateLimited(RetryAfter.parse(retryAfterHeader, now), detail)
        else -> AppError.RemoteServerError(status, detail)
    }

    /** Maps a thrown failure. Cancellation is rethrown, never turned into an error. */
    public fun fromThrowable(t: Throwable, provider: String, now: Instant? = null): AppError = when (t) {
        is CancellationException -> throw t
        is AppException -> t.error
        is HttpException -> fromStatus(t.code(), provider, t.response()?.headers()?.get("Retry-After"), now)
        is UnknownHostException -> AppError.NetworkUnavailable("dns")
        is ConnectException -> AppError.NetworkUnavailable("connect")
        is InterruptedIOException -> AppError.NetworkUnavailable("timeout")
        is SSLException -> AppError.NetworkUnavailable("tls")
        is CleartextNotPermittedException -> AppError.Unexpected("cleartext blocked")
        is IOException -> AppError.NetworkUnavailable(t::class.simpleName)
        is SerializationException -> AppError.ParsingError(t::class.simpleName)
        else -> AppError.Unexpected(t::class.simpleName)
    }

    /** True for failures that a later attempt can fix without user action: 408, 429, 5xx (not 501), I/O. */
    public fun isTransient(error: AppError): Boolean = when (error) {
        is AppError.RateLimited, is AppError.NetworkUnavailable -> true
        is AppError.RemoteServerError -> error.status in 500..599 && error.status != 501
        else -> false
    }
}

/** `Retry-After` as delta-seconds or an HTTP-date (RFC 9110 §10.2.3). Unparseable or past values give null/zero. */
public object RetryAfter {
    public fun parse(header: String?, now: Instant?): Duration? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return if (it >= 0) it.seconds else null }
        if (now == null) return null
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - now.toEpochMilliseconds()).coerceAtLeast(0).milliseconds
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

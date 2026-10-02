package dev.agentle.core.common

import kotlin.time.Duration

/**
 * Structured application errors (docs/ARCHITECTURE.md §15). Every error has a stable [code] used in logs,
 * diagnostics, the database and tests; user-facing text is resolved from the code by the UI layer, never from
 * [detail] (which is sanitized developer context and is never shown to normal users).
 */
public sealed class AppError(public val code: String, public open val detail: String? = null) {
    /** True if retrying the same operation later can succeed without user action. */
    public open val retryable: Boolean = false

    public data class PermissionDenied(val capabilityId: String, override val detail: String? = null) :
        AppError("permission_denied", detail)

    public data class PermissionPermanentlyDenied(val capabilityId: String, override val detail: String? = null) :
        AppError("permission_permanently_denied", detail)

    public data class AuthenticationRequired(val provider: String, override val detail: String? = null) :
        AppError("authentication_required", detail)

    public data class TokenExpired(val provider: String, override val detail: String? = null) : AppError("token_expired", detail) {
        override val retryable: Boolean = true
    }

    public data class RateLimited(val retryAfter: Duration? = null, override val detail: String? = null) :
        AppError("rate_limited", detail) {
        override val retryable: Boolean = true
    }

    public data class NetworkUnavailable(override val detail: String? = null) : AppError("network_unavailable", detail) {
        override val retryable: Boolean = true
    }

    public data class RemoteServerError(val status: Int, override val detail: String? = null) :
        AppError("remote_server_error", detail) {
        override val retryable: Boolean = status >= 500
    }

    public data class ParsingError(override val detail: String? = null) : AppError("parsing_error", detail)

    public data class DatabaseError(override val detail: String? = null) : AppError("database_error", detail) {
        override val retryable: Boolean = true
    }

    public data class UnsupportedFeature(val feature: String, override val detail: String? = null) :
        AppError("unsupported_feature", detail)

    public data class ValidationError(val codes: List<String>, override val detail: String? = null) :
        AppError("validation_error", detail)

    /** A request tried to include a data category the user has not allowed. Always fail closed. */
    public data class ConsentViolation(val categories: Set<String>, override val detail: String? = null) :
        AppError("consent_violation", detail)

    /** The remote service refused for account/plan reasons (e.g. ChatGPT plan not eligible, usage limit). */
    public data class NotEligible(val reason: String, override val detail: String? = null) : AppError("not_eligible", detail)

    public data class Cancelled(override val detail: String? = null) : AppError("cancelled", detail)

    public data class Unexpected(override val detail: String? = null) : AppError("unexpected", detail)

    override fun toString(): String = "AppError($code${detail?.let { ": $it" } ?: ""})"
}

/** Thrown inside coroutines when a typed error must cross a boundary that only speaks exceptions. */
public class AppException(public val error: AppError, cause: Throwable? = null) : RuntimeException(error.toString(), cause)

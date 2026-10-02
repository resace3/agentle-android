package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.googlehealth.GhJson.array
import dev.agentle.connectors.googlehealth.GhJson.obj
import dev.agentle.connectors.googlehealth.GhJson.string
import dev.agentle.core.common.AppError
import dev.agentle.core.network.ResponseBodies
import dev.agentle.core.network.RetryAfter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Why a Google Health call failed, classified per docs/research/05 §7.6 by HTTP status, `ErrorInfo.reason` and
 * headers. Message text is never stored or logged; it is read only for the documented UberMint/GaiaMint case.
 */
internal sealed interface GhFailure {
    /** A second 401, or the authorizer needs a user resolution or was denied: stop the source. */
    data object NeedsReauth : GhFailure

    /** 403 for a missing scope (`ACCESS_TOKEN_SCOPE_INSUFFICIENT`, `MISSING_OAUTH_SCOPE`, `insufficient_scope`). */
    data class ScopeMissing(val reason: String) : GhFailure

    /** 400 `ACCOUNT_NOT_LINKED`: stop the source; [signupUrl] is Google's signup link when it is a Google https URL. */
    data class AccountNotLinked(val signupUrl: String?) : GhFailure

    /** 412: no Google Health profile yet. Stop the source. */
    data object ProfileNotReady : GhFailure

    /** 403 "Could not mint UberMint from GaiaMint": a legacy Fitbit account. Stop the source. */
    data object LegacyFitbitAccount : GhFailure

    /** 429 after the in-run retries, or with a `Retry-After` beyond the in-run cap. */
    data class RateLimited(val retryAfter: Duration?) : GhFailure

    /** 5xx, I/O, timeouts, malformed 2xx bodies and pagination faults; [code] is a stable token, never a message. */
    data class Transient(val code: String, val status: Int? = null) : GhFailure

    /** Other 400 and 403, 404 (HTML or JSON) and 501: never retried; the stream is skipped for this run. */
    data class Unsupported(val code: String) : GhFailure

    /** 400 with a filter reason: the caller switches to the fallback filter once. */
    data class FilterRejected(val reason: String) : GhFailure

    /** 400 on a request that carried a page token (R8d): the caller restarts the window once. */
    data object PageTokenRejected : GhFailure

    /** The authorizer failed with a Play services status code. */
    data class AuthorizerFailed(val statusCode: Int) : GhFailure

    /** Whether the failure stops the whole source for this run (and later runs until the user acts or the wait ends). */
    val stopsSource: Boolean
        get() = when (this) {
            NeedsReauth, ProfileNotReady, LegacyFitbitAccount, is AccountNotLinked, is AuthorizerFailed, is RateLimited -> true
            is ScopeMissing, is Transient, is Unsupported, is FilterRejected, PageTokenRejected -> false
        }

    companion object {
        const val PROVIDER: String = "googlehealth"
        const val ACCOUNT_NOT_LINKED: String = "account_not_linked"
        const val PROFILE_NOT_READY: String = "profile_not_ready"
        const val LEGACY_FITBIT_ACCOUNT: String = "legacy_fitbit_account"
        const val ACCOUNT_CHANGED: String = "account_changed"
        const val MALFORMED: String = "malformed_body"
    }
}

/** The error as an [AppError] with stable, sanitized details (codes and class names only). */
internal fun GhFailure.toAppError(stream: String? = null): AppError {
    val target = "${GhFailure.PROVIDER}.${stream ?: "remote"}"
    return when (this) {
        GhFailure.NeedsReauth -> AppError.AuthenticationRequired(GhFailure.PROVIDER)

        is GhFailure.ScopeMissing -> AppError.PermissionDenied(target, reason)

        is GhFailure.AccountNotLinked -> AppError.NotEligible(GhFailure.ACCOUNT_NOT_LINKED)

        GhFailure.ProfileNotReady -> AppError.NotEligible(GhFailure.PROFILE_NOT_READY)

        GhFailure.LegacyFitbitAccount -> AppError.NotEligible(GhFailure.LEGACY_FITBIT_ACCOUNT)

        is GhFailure.RateLimited -> AppError.RateLimited(retryAfter)

        is GhFailure.Transient -> if (status != null && status >= SERVER_ERROR) {
            AppError.RemoteServerError(status, code)
        } else {
            AppError.NetworkUnavailable(code)
        }

        is GhFailure.Unsupported -> AppError.UnsupportedFeature(target, code)

        is GhFailure.FilterRejected -> AppError.UnsupportedFeature(target, reason)

        GhFailure.PageTokenRejected -> AppError.NetworkUnavailable("page_token")

        is GhFailure.AuthorizerFailed -> authorizerError(statusCode)
    }
}

private fun authorizerError(statusCode: Int): AppError {
    val detail = "auth_status_$statusCode"
    return when (statusCode) {
        GoogleAuthorization.Failure.NETWORK_ERROR -> AppError.NetworkUnavailable(detail)

        GoogleAuthorization.Failure.SERVICE_MISSING,
        GoogleAuthorization.Failure.SERVICE_VERSION_UPDATE_REQUIRED,
        GoogleAuthorization.Failure.SERVICE_DISABLED,
        -> AppError.UnsupportedFeature("${GhFailure.PROVIDER}.play_services", detail)

        GoogleAuthorization.Failure.DEVELOPER_ERROR -> AppError.UnsupportedFeature("${GhFailure.PROVIDER}.oauth_client", detail)

        GoogleAuthorization.Failure.CANCELED -> AppError.Cancelled(detail)

        else -> AppError.Unexpected(detail)
    }
}

private const val SERVER_ERROR = 500

/** Classification of non-2xx responses (docs/research/05 §5.8, §7.6). */
internal object GhErrors {
    private val SCOPE_REASONS = setOf("ACCESS_TOKEN_SCOPE_INSUFFICIENT", "MISSING_OAUTH_SCOPE")
    private const val FILTER_PREFIX = "INVALID_DATA_POINT_FILTER"
    private const val TIME_RANGE = "INVALID_TIME_RANGE"
    private const val NOT_LINKED = "ACCOUNT_NOT_LINKED"
    private const val BAD_REQUEST = 400
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val TIMEOUT = 408
    private const val PRECONDITION = 412
    private const val TOO_MANY = 429
    private const val NOT_IMPLEMENTED = 501

    /** What an error body says, without its message. */
    data class Body(val reasons: List<String>, val redirectUri: String?, val legacyAccount: Boolean)

    /**
     * Classifies a non-2xx response. [hadPageToken] marks a page-token request (R8d). A 401 is classified as
     * [GhFailure.NeedsReauth]; the caller first invalidates the token and retries once.
     */
    @Suppress("CyclomaticComplexMethod")
    fun classify(
        status: Int,
        contentType: String?,
        body: String?,
        retryAfter: String?,
        wwwAuthenticate: String?,
        hadPageToken: Boolean,
        now: Instant,
    ): GhFailure {
        val parsed = if (ResponseBodies.isJsonContentType(contentType)) parseBody(body) else null
        val reasons = parsed?.reasons.orEmpty()
        return when {
            status == UNAUTHORIZED -> GhFailure.NeedsReauth
            status == TOO_MANY -> GhFailure.RateLimited(RetryAfter.parse(retryAfter, now))
            status == TIMEOUT -> GhFailure.Transient("http_$status", status)
            status >= SERVER_ERROR && status != NOT_IMPLEMENTED -> GhFailure.Transient("http_$status", status)
            status == PRECONDITION -> GhFailure.ProfileNotReady
            status == FORBIDDEN -> forbidden(reasons, wwwAuthenticate, parsed?.legacyAccount == true)
            status == BAD_REQUEST && NOT_LINKED in reasons -> GhFailure.AccountNotLinked(safeSignupUrl(parsed?.redirectUri))
            status == BAD_REQUEST && reasons.any(::isFilterReason) -> GhFailure.FilterRejected(reasons.first(::isFilterReason))
            status == BAD_REQUEST && hadPageToken -> GhFailure.PageTokenRejected
            status in REDIRECTS -> GhFailure.Transient("http_$status", status)
            else -> GhFailure.Unsupported(if (parsed == null) "http_${status}_non_json" else "http_$status")
        }
    }

    private fun forbidden(reasons: List<String>, wwwAuthenticate: String?, legacy: Boolean): GhFailure = when {
        reasons.any { it in SCOPE_REASONS } -> GhFailure.ScopeMissing(reasons.first { it in SCOPE_REASONS })
        wwwAuthenticate?.contains("insufficient_scope") == true -> GhFailure.ScopeMissing("insufficient_scope")
        legacy -> GhFailure.LegacyFitbitAccount
        else -> GhFailure.Unsupported("http_403")
    }

    fun isFilterReason(reason: String): Boolean = reason.startsWith(FILTER_PREFIX) || reason == TIME_RANGE

    /** Reasons from `details[].reason` and `details[].metadata.detailedReasons`, the redirect URI and the legacy marker. */
    fun parseBody(body: String?): Body? {
        val error = body?.let(GhJson::parseObject)?.obj("error") ?: return null
        val reasons = ArrayList<String>()
        var redirect: String? = null
        error.array("details")?.forEach { detail ->
            val d = detail as? JsonObject ?: return@forEach
            d.string("reason")?.let(reasons::add)
            val metadata = d.obj("metadata")
            when (val detailed = metadata?.get("detailedReasons")) {
                is JsonPrimitive -> detailed.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(reasons::add)
                is JsonArray -> detailed.forEach { (it as? JsonPrimitive)?.content?.let(reasons::add) }
                else -> Unit
            }
            redirect = redirect ?: metadata?.string("redirect_uri")
        }
        // docs/research/05 §7.6: the legacy-account 403 is recognized by its message (the only message-based rule).
        val message = error.string("message").orEmpty()
        val legacy = message.contains("UberMint") || message.contains("GaiaMint")
        return Body(reasons, redirect, legacy)
    }

    /** Google's signup link, only when it is an https URL on a Google host; anything else is dropped. */
    fun safeSignupUrl(uri: String?): String? {
        val url = uri?.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase()
        return if (url.isHttps && (host == "google.com" || host.endsWith(".google.com"))) url.toString() else null
    }

    private val REDIRECTS = 300..399
}

package dev.agentle.ai.chatgpt

import dev.agentle.core.common.AppError
import dev.agentle.core.oauth.BodyShape
import dev.agentle.core.oauth.ErrorBody
import dev.agentle.core.oauth.OAuthErrorCodes
import dev.agentle.core.oauth.OAuthFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** The result of one SIWC exchange: a value, or a classified [SiwcFailure] (with its status and 401 flag). */
public sealed interface SiwcResult<out T> {
    public data class Ok<out T>(val value: T) : SiwcResult<T>

    public data class Failed(val failure: SiwcFailure) : SiwcResult<Nothing>
}

/**
 * One classified SIWC failure: the [status] to publish (null keeps the current one) and the [error] callers get.
 * [unauthorized] marks an HTTP 401, which only `SiwcSessionManager` handles (one refresh, one retry).
 */
public data class SiwcFailure(
    val status: SiwcStatus?,
    val error: AppError,
    val unauthorized: Boolean = false,
    val retryAfter: Duration? = null,
)

/**
 * The pure mapping of docs/research/06 §5.5 and §8.1: (HTTP status, error body, stream event, phase) to a state and
 * reason. OAuth bodies `{"error":"x"}`, API bodies `{"error":{"code":"x"}}` and `{"detail":"..."}` are all read by
 * [ErrorBody]; the legacy `v2` codes map to their successors. Server messages are never read, so nothing a server
 * echoes can reach a log, a stored status or an [AppError].
 */
public object SiwcErrorMapper {
    public const val PROVIDER: String = "chatgpt"

    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val TOO_MANY_REQUESTS = 429
    private const val SERVER_ERROR_MIN = 500
    private const val SERVICE_UNAVAILABLE = 503
    private const val BAD_REQUEST = 400

    /** Legacy DevKit codes and their successors (R06 §5.5 "Legacy codes"). */
    public val LEGACY_CODES: Map<String, String> = mapOf(
        "subscription_sharing_v2_user_not_eligible" to "subscription_sharing_user_not_eligible",
        "subscription_sharing_v2_route_not_supported" to "subscription_sharing_route_not_supported",
        "subscription_sharing_v2_invalid_user" to "subscription_sharing_invalid_user",
        "subscription_sharing_v2_user_unavailable" to "subscription_sharing_user_unavailable",
        "subscription_sharing_v2_client_not_enabled" to "subscription_sharing_client_not_enabled",
    )

    public fun normalize(code: String?): String? = code?.let { LEGACY_CODES[it] ?: it }

    /** An API answer before the stream opened (or `GET /v1/models`). */
    public fun api(
        httpStatus: Int,
        body: ErrorBody,
        requestId: String? = null,
        retryAfter: Duration? = null,
        now: Instant? = null,
    ): SiwcFailure {
        val code = normalize(body.code)
        return when {
            httpStatus == UNAUTHORIZED || code == "subscription_sharing_invalid_user" ->
                SiwcFailure(null, AppError.TokenExpired(PROVIDER, "http_401"), unauthorized = true)

            else -> byCode(code, body.param, requestId, retryAfter, now, httpStatus)
                ?: byStatus(httpStatus, body.shape, requestId, retryAfter, now)
        }
    }

    /** `response.failed` or an `error` event inside a stream (R06 §4.3). */
    public fun streamEvent(code: String?, requestId: String? = null): SiwcFailure =
        byCode(normalize(code), null, requestId, null, null, BAD_REQUEST)
            ?: failure(SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM, requestId, AppError.RemoteServerError(SERVER_ERROR_MIN, "upstream"))

    /** The stream ended without `response.completed` (R06 §8.1: retry once, never deliver partial text). */
    public fun streamInterrupted(requestId: String? = null): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.STREAM_INTERRUPTED, requestId, AppError.NetworkUnavailable("stream_interrupted"))

    /** `response.incomplete` (R06 §8.1: retry with less context, not as is). */
    public fun incomplete(requestId: String? = null): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.INCOMPLETE, requestId, AppError.RemoteServerError(OK_STATUS, "response_incomplete"))

    /** A 2xx whose body or framing is not what R06 §4 documents. */
    public fun invalidResponse(requestId: String? = null, what: String = "response"): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.INVALID_RESPONSE, requestId, AppError.ParsingError("invalid_$what"))

    /**
     * DNS, connect, TLS or timeout before headers (R06 §8.1). A TLS failure is usually an intercepting proxy or a
     * captive portal on https; the credentials are kept either way (red team testing-build round 3).
     */
    public fun transport(kind: String): SiwcFailure {
        val reason = if (kind == TLS) SiwcReason.TLS_FAILURE else SiwcReason.NONE
        return failure(SiwcState.NETWORK_UNAVAILABLE, reason, null, AppError.NetworkUnavailable(kind))
    }

    /** A 2xx whose body is not JSON at all (captive portal login page): the network is not usable yet. */
    public fun captivePortal(): SiwcFailure =
        failure(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.CAPTIVE_PORTAL, null, AppError.NetworkUnavailable("captive_portal"))

    /** Refused on the device (cleartext or egress allow-list): a configuration bug, never the user's network. */
    public fun blocked(reason: String): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.APP_BUG, null, AppError.Unexpected("blocked:$reason"))

    /** No usable access token: never connected, disconnected, or the registration needs a new sign-in. */
    public fun notConnected(): SiwcFailure = SiwcFailure(null, AppError.AuthenticationRequired(PROVIDER, "not_connected"))

    /** R06 §2.8: the plan scopes were not granted, so inference is blocked locally. */
    public fun planUsageNotGranted(): SiwcFailure = failure(
        SiwcState.NOT_ELIGIBLE,
        SiwcReason.PLAN_USAGE_NOT_GRANTED,
        null,
        AppError.NotEligible("plan_usage_not_granted"),
    )

    /** R06 §2.11: the token expired but `earliest_refresh_at` is still in the future. */
    public fun refreshNotReady(earliestEpochMs: Long, now: Instant): SiwcFailure {
        val wait = (earliestEpochMs - now.toEpochMilliseconds()).coerceAtLeast(0).milliseconds
        return SiwcFailure(
            SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.REFRESH_NOT_READY, retryAtEpochMs = earliestEpochMs),
            AppError.RateLimited(wait, "refresh_not_ready"),
            retryAfter = wait,
        )
    }

    /** The retry after a 401 was rejected again (R06 §8.1): keep the tokens until the user reconnects. */
    public fun credentialRejected(requestId: String? = null): SiwcFailure = failure(
        SiwcState.REAUTH_REQUIRED,
        SiwcReason.CREDENTIAL_REJECTED,
        requestId,
        AppError.AuthenticationRequired(PROVIDER, "credential_rejected"),
    )

    /** JWKS or discovery outage (R06 §2.7): retryable, never an invalid identity. */
    public fun identityUnavailable(): SiwcFailure = failure(
        SiwcState.SERVER_ERROR,
        SiwcReason.IDENTITY_VERIFICATION_UNAVAILABLE,
        null,
        AppError.RemoteServerError(SERVICE_UNAVAILABLE, "identity_verification_unavailable"),
    )

    /** The ID token is valid except for its time claims, which fit at its own `iat`: the device clock is wrong. */
    public fun deviceClockWrong(): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.DEVICE_CLOCK_WRONG, null, AppError.Unexpected("device_clock_wrong"))

    public fun invalidIdToken(): SiwcFailure = failure(
        SiwcState.REAUTH_REQUIRED,
        SiwcReason.INVALID_ID_TOKEN,
        null,
        AppError.AuthenticationRequired(PROVIDER, "invalid_id_token"),
    )

    /** A refresh returned an ID token for another `sub` (R06 §2.11). */
    public fun accountMismatch(): SiwcFailure = failure(
        SiwcState.REAUTH_REQUIRED,
        SiwcReason.ACCOUNT_MISMATCH,
        null,
        AppError.AuthenticationRequired(PROVIDER, "account_mismatch"),
    )

    /** Discovery answered but fails R06 §2.1 validation: fail closed. */
    public fun discoveryFailed(problem: String): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.DISCOVERY_FAILED, null, AppError.ParsingError("discovery_$problem"))

    /** Discovery or JWKS could not be fetched. */
    public fun documentUnavailable(failure: OAuthFailure): SiwcFailure = when (failure) {
        is OAuthFailure.Network -> transport(failure.kind)
        is OAuthFailure.Blocked -> blocked(failure.reason)
        else -> identityUnavailable()
    }

    /** The credential store could not persist a change; nothing unpersisted is used. */
    public fun storage(): SiwcFailure =
        failure(SiwcState.SERVER_ERROR, SiwcReason.STORAGE, null, AppError.DatabaseError("credential_write_failed"))

    /**
     * A token-endpoint failure on refresh or code exchange (R06 §2.11, §8.1): terminal codes and `invalid_client`
     * require a new sign-in; network errors, 5xx and captive portals keep the credentials. (An exchange handles
     * `invalid_grant` itself before calling this: it restarts the authorization once.)
     */
    public fun refresh(failure: OAuthFailure, now: Instant? = null): SiwcFailure = when (failure) {
        is OAuthFailure.ErrorResponse -> when (failure.error) {
            in OAuthErrorCodes.TERMINAL_REFRESH -> failure(
                SiwcState.REAUTH_REQUIRED,
                SiwcReason.REFRESH_REJECTED,
                failure.requestId,
                AppError.AuthenticationRequired(PROVIDER, "refresh_rejected"),
            )

            OAuthErrorCodes.INVALID_CLIENT -> failure(
                SiwcState.REAUTH_REQUIRED,
                SiwcReason.REGISTRATION_INVALID,
                failure.requestId,
                AppError.AuthenticationRequired(PROVIDER, "registration_invalid"),
            )

            OAuthErrorCodes.SLOW_DOWN -> rateLimited(null, failure.requestId, now)

            else -> failure(SiwcState.SERVER_ERROR, SiwcReason.AUTH_SERVER, failure.requestId, failure.appError)
        }

        is OAuthFailure.HttpStatus -> if (failure.httpStatus == TOO_MANY_REQUESTS) {
            rateLimited(failure.retryAfter, failure.requestId, now)
        } else {
            failure(SiwcState.SERVER_ERROR, SiwcReason.AUTH_SERVER, failure.requestId, failure.appError)
        }

        is OAuthFailure.Network -> transport(failure.kind)

        is OAuthFailure.InvalidResponse ->
            if (failure.reason == NOT_JSON) captivePortal() else invalidResponse(null, "token_response")

        is OAuthFailure.Redirected -> failure(SiwcState.SERVER_ERROR, SiwcReason.AUTH_SERVER, null, failure.appError)

        is OAuthFailure.Blocked -> blocked(failure.reason)
    }

    private fun byCode(
        code: String?,
        param: String?,
        requestId: String?,
        retryAfter: Duration?,
        now: Instant?,
        httpStatus: Int,
    ): SiwcFailure? = when (code) {
        "subscription_sharing_user_not_eligible" ->
            failure(SiwcState.NOT_ELIGIBLE, SiwcReason.ACCOUNT_NOT_ELIGIBLE, requestId, AppError.NotEligible("account_not_eligible"))

        "subscription_sharing_usage_limit_exceeded" ->
            // No automatic retry and no reset time inferred from the code (R06 §5.5).
            failure(SiwcState.RATE_LIMITED, SiwcReason.PLAN_LIMIT, requestId, AppError.NotEligible("usage_limit_reached"))

        "subscription_sharing_usage_unavailable" -> failure(
            SiwcState.PLAN_USAGE_UNAVAILABLE,
            SiwcReason.USAGE_UNAVAILABLE,
            requestId,
            AppError.RemoteServerError(SERVICE_UNAVAILABLE, "usage_unavailable"),
        )

        "subscription_sharing_user_unavailable" -> failure(
            SiwcState.PLAN_USAGE_UNAVAILABLE,
            SiwcReason.USER_UNAVAILABLE,
            requestId,
            AppError.RemoteServerError(SERVICE_UNAVAILABLE, "user_unavailable"),
        )

        "subscription_sharing_unsupported_capability" -> SiwcFailure(
            SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.UNSUPPORTED_CAPABILITY, requestId, param),
            AppError.UnsupportedFeature(param ?: "unknown", "unsupported_capability"),
        )

        "subscription_sharing_route_not_supported" ->
            failure(SiwcState.SERVER_ERROR, SiwcReason.APP_BUG, requestId, AppError.Unexpected("route_not_supported"))

        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            failure(SiwcState.NOT_ELIGIBLE, SiwcReason.GRANT_NOT_AUTHORIZED, requestId, AppError.NotEligible("grant_not_authorized"))

        "subscription_sharing_client_not_enabled" ->
            failure(SiwcState.NOT_ELIGIBLE, SiwcReason.CLIENT_NOT_ENABLED, requestId, AppError.NotEligible("client_not_enabled"))

        "model_not_found" -> failure(
            SiwcState.SERVER_ERROR,
            SiwcReason.MODEL_UNAVAILABLE,
            requestId,
            AppError.RemoteServerError(httpStatus, "model_unavailable"),
        )

        "rate_limit_exceeded" -> rateLimited(retryAfter, requestId, now)

        "server_error" ->
            failure(SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM, requestId, AppError.RemoteServerError(SERVER_ERROR_MIN, "upstream"))

        else -> null
    }

    private fun byStatus(httpStatus: Int, shape: BodyShape, requestId: String?, retryAfter: Duration?, now: Instant?): SiwcFailure = when {
        httpStatus == FORBIDDEN ->
            failure(SiwcState.NOT_ELIGIBLE, SiwcReason.POLICY_RESTRICTED, requestId, AppError.NotEligible("policy_restricted"))

        httpStatus == TOO_MANY_REQUESTS -> rateLimited(retryAfter, requestId, now)

        httpStatus == SERVICE_UNAVAILABLE && shape == BodyShape.DETAIL -> failure(
            SiwcState.PLAN_USAGE_UNAVAILABLE,
            SiwcReason.ROUTING,
            requestId,
            AppError.RemoteServerError(SERVICE_UNAVAILABLE, "routing_unavailable"),
        )

        httpStatus >= SERVER_ERROR_MIN ->
            failure(SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM, requestId, AppError.RemoteServerError(httpStatus, "upstream"))

        else ->
            failure(
                SiwcState.SERVER_ERROR,
                SiwcReason.INVALID_REQUEST,
                requestId,
                AppError.RemoteServerError(httpStatus, "invalid_request"),
            )
    }

    private fun rateLimited(retryAfter: Duration?, requestId: String?, now: Instant?): SiwcFailure {
        val retryAt = retryAfter?.let { now?.plus(it) }?.toEpochMilliseconds()
        return SiwcFailure(
            SiwcStatus(SiwcState.RATE_LIMITED, SiwcReason.TOO_MANY_REQUESTS, requestId, retryAtEpochMs = retryAt),
            AppError.RateLimited(retryAfter, "too_many_requests"),
            retryAfter = retryAfter,
        )
    }

    private fun failure(state: SiwcState, reason: SiwcReason, requestId: String?, error: AppError): SiwcFailure =
        SiwcFailure(SiwcStatus(state, reason, requestId), error)

    private const val OK_STATUS = 200
    private const val TLS = "tls"

    /** The core parser's reason for a body that is not a JSON object. */
    private const val NOT_JSON = "not_json_object"
}

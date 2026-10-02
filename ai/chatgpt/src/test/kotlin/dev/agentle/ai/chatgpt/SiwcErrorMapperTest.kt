package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.oauth.BodyShape
import dev.agentle.core.oauth.ErrorBody
import dev.agentle.core.oauth.OAuthFailure
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The pure mapping of docs/research/06 §5.5 (every code, including the legacy v2 ones) and §8.1. */
class SiwcErrorMapperTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    @ParameterizedTest(name = "R06 5.5 {0} (HTTP {1}) -> {2}/{3}")
    @MethodSource("apiCodes")
    fun `every documented API error code maps to its state and reason`(code: String, status: Int, state: SiwcState, reason: SiwcReason) {
        val failure = SiwcErrorMapper.api(status, ErrorBody(BodyShape.ERROR_OBJECT, code, null, null), "req_1", null, now)

        assertThat(failure.status?.state).isEqualTo(state)
        assertThat(failure.status?.reason).isEqualTo(reason)
        assertThat(failure.status?.requestId).isEqualTo("req_1")
        assertThat(failure.unauthorized).isFalse()
    }

    @ParameterizedTest(name = "legacy {0} -> {1}")
    @MethodSource("legacyCodes")
    fun `legacy v2 codes map like their successors`(legacy: String, successor: String) {
        assertThat(SiwcErrorMapper.normalize(legacy)).isEqualTo(successor)
        val viaLegacy = SiwcErrorMapper.api(403, ErrorBody(BodyShape.ERROR_OBJECT, legacy, null, null))
        val viaSuccessor = SiwcErrorMapper.api(403, ErrorBody(BodyShape.ERROR_OBJECT, successor, null, null))
        assertThat(viaLegacy).isEqualTo(viaSuccessor)
    }

    @Test
    fun `a 401 and the invalid-user code are the session manager's to handle`() {
        val plain = SiwcErrorMapper.api(401, ErrorBody(BodyShape.DETAIL, null, null, null))
        val invalidUser = SiwcErrorMapper.api(400, ErrorBody(BodyShape.ERROR_OBJECT, "subscription_sharing_v2_invalid_user", null, null))

        assertThat(plain.unauthorized).isTrue()
        assertThat(plain.status).isNull()
        assertThat(invalidUser.unauthorized).isTrue()
    }

    @ParameterizedTest(name = "HTTP {0} {1} -> {2}/{3}")
    @MethodSource("statusOnly")
    fun `answers without a known code map by status and body shape`(status: Int, shape: BodyShape, state: SiwcState, reason: SiwcReason) {
        val failure = SiwcErrorMapper.api(status, ErrorBody(shape, null, null, null))

        assertThat(failure.status?.state).isEqualTo(state)
        assertThat(failure.status?.reason).isEqualTo(reason)
    }

    @Test
    fun `rate limits carry Retry-After as a retry instant`() {
        val failure = SiwcErrorMapper.api(429, ErrorBody(BodyShape.ERROR_OBJECT, "rate_limit_exceeded", null, null), null, 30.seconds, now)

        assertThat(failure.status?.retryAtEpochMs).isEqualTo((now + 30.seconds).toEpochMilliseconds())
        assertThat(failure.error).isEqualTo(AppError.RateLimited(30.seconds, "too_many_requests"))
        assertThat(failure.retryAfter).isEqualTo(30.seconds)
    }

    @Test
    fun `the plan usage limit never infers a reset time`() {
        val failure = SiwcErrorMapper.api(
            429,
            ErrorBody(BodyShape.ERROR_OBJECT, "subscription_sharing_usage_limit_exceeded", null, null),
            null,
            30.seconds,
            now,
        )

        assertThat(failure.status?.retryAtEpochMs).isNull()
        assertThat(failure.error.retryable).isFalse()
    }

    @Test
    fun `an unsupported capability names the offending field`() {
        val failure = SiwcErrorMapper.api(
            400,
            ErrorBody(BodyShape.ERROR_OBJECT, "subscription_sharing_unsupported_capability", "text.format", null),
        )

        assertThat(failure.status?.param).isEqualTo("text.format")
        assertThat(failure.error).isEqualTo(AppError.UnsupportedFeature("text.format", "unsupported_capability"))
    }

    @ParameterizedTest(name = "refresh {0}")
    @MethodSource("refreshFailures")
    fun `token endpoint failures keep or clear credentials as R06 8_1 says`(failure: OAuthFailure, state: SiwcState, reason: SiwcReason) {
        val mapped = SiwcErrorMapper.refresh(failure, now)

        assertThat(mapped.status?.state).isEqualTo(state)
        assertThat(mapped.status?.reason).isEqualTo(reason)
    }

    @Test
    fun `transport kinds keep the credentials and name TLS failures`() {
        assertThat(SiwcErrorMapper.transport("tls").status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.TLS_FAILURE))
        assertThat(SiwcErrorMapper.transport("dns").status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE))
        assertThat(SiwcErrorMapper.blocked("egress").status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.APP_BUG))
        assertThat(
            SiwcErrorMapper.documentUnavailable(OAuthFailure.Network("connect")).error,
        ).isEqualTo(AppError.NetworkUnavailable("connect"))
        assertThat(
            SiwcErrorMapper.documentUnavailable(OAuthFailure.Blocked("cleartext")).error,
        ).isEqualTo(AppError.Unexpected("blocked:cleartext"))
        assertThat(SiwcErrorMapper.documentUnavailable(OAuthFailure.HttpStatus(503, BodyShape.EMPTY, null, null)).status?.reason)
            .isEqualTo(SiwcReason.IDENTITY_VERIFICATION_UNAVAILABLE)
    }

    @Test
    fun `stream and local failures have their own reasons`() {
        assertThat(SiwcErrorMapper.streamEvent(null).status?.reason).isEqualTo(SiwcReason.UPSTREAM)
        assertThat(SiwcErrorMapper.streamInterrupted().error).isEqualTo(AppError.NetworkUnavailable("stream_interrupted"))
        assertThat(SiwcErrorMapper.refreshNotReady((now + 90.seconds).toEpochMilliseconds(), now).error)
            .isEqualTo(AppError.RateLimited(90.seconds, "refresh_not_ready"))
        assertThat(SiwcErrorMapper.refreshNotReady(0, now).retryAfter).isEqualTo(0.seconds)
        assertThat(SiwcErrorMapper.discoveryFailed("issuer_mismatch").error).isEqualTo(AppError.ParsingError("discovery_issuer_mismatch"))
        assertThat(SiwcErrorMapper.deviceClockWrong().status?.reason).isEqualTo(SiwcReason.DEVICE_CLOCK_WRONG)
        assertThat(SiwcErrorMapper.notConnected().status).isNull()
    }

    @Test
    fun `every persisted state maps to the coarse provider state of ARCHITECTURE 8`() {
        val states = SiwcState.entries.map { SiwcSnapshot(SiwcStatus(it, SiwcReason.NONE)).toProviderState()::class.simpleName }

        assertThat(states).containsExactly(
            "Connected",
            "Disconnected",
            "NotEligible",
            "Unavailable",
            "UsageLimited",
            "NeedsReauth",
            "Unavailable",
            "Unavailable",
        ).inOrder()
        assertThat(SiwcSnapshot(SiwcStatus.CONNECTED, connecting = true).toProviderState()::class.simpleName).isEqualTo("Connecting")
    }

    companion object {
        @JvmStatic
        fun apiCodes(): List<Arguments> = listOf(
            Arguments.of("subscription_sharing_user_not_eligible", 403, SiwcState.NOT_ELIGIBLE, SiwcReason.ACCOUNT_NOT_ELIGIBLE),
            Arguments.of("subscription_sharing_usage_limit_exceeded", 429, SiwcState.RATE_LIMITED, SiwcReason.PLAN_LIMIT),
            Arguments.of("subscription_sharing_usage_unavailable", 503, SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.USAGE_UNAVAILABLE),
            Arguments.of("subscription_sharing_user_unavailable", 503, SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.USER_UNAVAILABLE),
            Arguments.of("subscription_sharing_unsupported_capability", 400, SiwcState.SERVER_ERROR, SiwcReason.UNSUPPORTED_CAPABILITY),
            Arguments.of("subscription_sharing_route_not_supported", 403, SiwcState.SERVER_ERROR, SiwcReason.APP_BUG),
            Arguments.of("chatpass_v2_scope_not_authorized", 403, SiwcState.NOT_ELIGIBLE, SiwcReason.GRANT_NOT_AUTHORIZED),
            Arguments.of("chatpass_v2_invalid_authorization_context", 403, SiwcState.NOT_ELIGIBLE, SiwcReason.GRANT_NOT_AUTHORIZED),
            Arguments.of("subscription_sharing_client_not_enabled", 403, SiwcState.NOT_ELIGIBLE, SiwcReason.CLIENT_NOT_ENABLED),
            Arguments.of("model_not_found", 404, SiwcState.SERVER_ERROR, SiwcReason.MODEL_UNAVAILABLE),
            Arguments.of("rate_limit_exceeded", 429, SiwcState.RATE_LIMITED, SiwcReason.TOO_MANY_REQUESTS),
            Arguments.of("server_error", 500, SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM),
            Arguments.of("unknown_code", 400, SiwcState.SERVER_ERROR, SiwcReason.INVALID_REQUEST),
        )

        @JvmStatic
        fun legacyCodes(): List<Arguments> = SiwcErrorMapper.LEGACY_CODES.map { (legacy, successor) -> Arguments.of(legacy, successor) }

        @JvmStatic
        fun statusOnly(): List<Arguments> = listOf(
            Arguments.of(403, BodyShape.DETAIL, SiwcState.NOT_ELIGIBLE, SiwcReason.POLICY_RESTRICTED),
            Arguments.of(429, BodyShape.EMPTY, SiwcState.RATE_LIMITED, SiwcReason.TOO_MANY_REQUESTS),
            Arguments.of(503, BodyShape.DETAIL, SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.ROUTING),
            Arguments.of(503, BodyShape.HTML, SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM),
            Arguments.of(502, BodyShape.HTML, SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM),
            Arguments.of(400, BodyShape.UNPARSEABLE, SiwcState.SERVER_ERROR, SiwcReason.INVALID_REQUEST),
        )

        @JvmStatic
        fun refreshFailures(): List<Arguments> = listOf(
            Arguments.of(
                OAuthFailure.ErrorResponse("invalid_grant", 400, BodyShape.OAUTH_STRING),
                SiwcState.REAUTH_REQUIRED,
                SiwcReason.REFRESH_REJECTED,
            ),
            Arguments.of(
                OAuthFailure.ErrorResponse("invalid_client", 401, BodyShape.OAUTH_STRING),
                SiwcState.REAUTH_REQUIRED,
                SiwcReason.REGISTRATION_INVALID,
            ),
            Arguments.of(
                OAuthFailure.ErrorResponse("slow_down", 400, BodyShape.OAUTH_STRING),
                SiwcState.RATE_LIMITED,
                SiwcReason.TOO_MANY_REQUESTS,
            ),
            Arguments.of(
                OAuthFailure.ErrorResponse("temporarily_unavailable", 503, BodyShape.OAUTH_STRING),
                SiwcState.SERVER_ERROR,
                SiwcReason.AUTH_SERVER,
            ),
            Arguments.of(
                OAuthFailure.HttpStatus(429, BodyShape.EMPTY, 5.seconds, null),
                SiwcState.RATE_LIMITED,
                SiwcReason.TOO_MANY_REQUESTS,
            ),
            Arguments.of(OAuthFailure.HttpStatus(502, BodyShape.HTML, null, null), SiwcState.SERVER_ERROR, SiwcReason.AUTH_SERVER),
            Arguments.of(OAuthFailure.Network("timeout"), SiwcState.NETWORK_UNAVAILABLE, SiwcReason.NONE),
            Arguments.of(OAuthFailure.InvalidResponse("not_json_object", 200), SiwcState.NETWORK_UNAVAILABLE, SiwcReason.CAPTIVE_PORTAL),
            Arguments.of(OAuthFailure.InvalidResponse("missing_access_token", 200), SiwcState.SERVER_ERROR, SiwcReason.INVALID_RESPONSE),
            Arguments.of(OAuthFailure.Redirected(302), SiwcState.SERVER_ERROR, SiwcReason.AUTH_SERVER),
            Arguments.of(OAuthFailure.Blocked("egress"), SiwcState.SERVER_ERROR, SiwcReason.APP_BUG),
        )
    }
}

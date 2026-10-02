package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.fakes.googlehealth.FakeResponse
import dev.agentle.fakes.googlehealth.GoogleHealthFixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GhErrorsTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private fun classify(response: FakeResponse, hadPageToken: Boolean = false): GhFailure = GhErrors.classify(
        status = response.code,
        contentType = response.contentType,
        body = response.body,
        retryAfter = response.headers["Retry-After"],
        wwwAuthenticate = response.headers["www-authenticate"],
        hadPageToken = hadPageToken,
        now = now,
    )

    private fun classify(id: String, hadPageToken: Boolean = false, retryAfter: String? = null): GhFailure =
        classify(GoogleHealthFixtures.error(id, retryAfter = retryAfter), hadPageToken)

    @Test
    fun `R05 8 5 every error fixture is classified`() {
        val expected = mapOf(
            "E400-INVALID-ARGUMENT" to GhFailure.Unsupported("http_400"),
            // The top-level reason comes first; the detailed reason only refines it.
            "E400-FILTER-A" to GhFailure.FilterRejected("INVALID_DATA_POINT_FILTER"),
            "E400-FILTER-B" to GhFailure.FilterRejected("INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER"),
            "E400-ACCOUNT-NOT-LINKED" to GhFailure.AccountNotLinked("https://fitbit.google.com/auth/signup"),
            "E400-PAGE-TOKEN" to GhFailure.Unsupported("http_400"),
            "E400-BAD-JSON" to GhFailure.Unsupported("http_400"),
            "E401-MISSING" to GhFailure.NeedsReauth,
            "E401-INVALID" to GhFailure.NeedsReauth,
            "E401-APIKEY" to GhFailure.NeedsReauth,
            "E403-SCOPE-A" to GhFailure.ScopeMissing("ACCESS_TOKEN_SCOPE_INSUFFICIENT"),
            "E403-LEGACY-A" to GhFailure.LegacyFitbitAccount,
            "E403-LEGACY-B" to GhFailure.Unsupported("http_403"),
            "E404-HTML" to GhFailure.Unsupported("http_404_non_json"),
            "E404-JSON" to GhFailure.Unsupported("http_404"),
            "E412" to GhFailure.ProfileNotReady,
            "E429" to GhFailure.RateLimited(null),
            "E500" to GhFailure.Transient("http_500", 500),
            "E502" to GhFailure.Transient("http_502", 502),
            "E503" to GhFailure.Transient("http_503", 503),
            "E504" to GhFailure.Transient("http_504", 504),
        )
        val actual = GoogleHealthFixtures.ERROR_IDS.filter { it != "E403-SCOPE-B" }.associateWith { classify(it) }
        assertThat(actual).containsExactlyEntriesIn(expected)
        assertThat(classify("E403-SCOPE-B")).isInstanceOf(GhFailure.ScopeMissing::class.java)
    }

    @Test
    fun `R05 8 6 R8d a 400 on a page-token request restarts the window`() {
        assertThat(classify("E400-PAGE-TOKEN", hadPageToken = true)).isEqualTo(GhFailure.PageTokenRejected)
        // A filter reason wins over the page token.
        assertThat(classify("E400-FILTER-B", hadPageToken = true)).isInstanceOf(GhFailure.FilterRejected::class.java)
    }

    @ParameterizedTest(name = "Retry-After {0}")
    @CsvSource("7, 7", "'Thu, 01 Oct 2026 12:00:30 GMT', 30", "3600, 3600")
    fun `R05 8 7 S28 S30 S31 Retry-After as seconds or an HTTP date`(header: String, seconds: Long) {
        assertThat(classify("E429", retryAfter = header)).isEqualTo(GhFailure.RateLimited(seconds.seconds))
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = [301, 302, 307, 308])
    fun `round-2 8 redirects are never followed and count as transient`(status: Int) {
        val failure = GhErrors.classify(status, null, null, null, null, hadPageToken = false, now = now)
        assertThat(failure).isEqualTo(GhFailure.Transient("http_$status", status))
    }

    @Test
    fun `other statuses - 408, 501 and unknown 4xx`() {
        assertThat(GhErrors.classify(408, null, null, null, null, false, now)).isEqualTo(GhFailure.Transient("http_408", 408))
        assertThat(GhErrors.classify(501, null, null, null, null, false, now)).isEqualTo(GhFailure.Unsupported("http_501_non_json"))
        val teapot = """{"error": {"code": 418, "status": "UNKNOWN"}}"""
        assertThat(GhErrors.classify(418, "application/json", teapot, null, null, false, now)).isEqualTo(GhFailure.Unsupported("http_418"))
        // A JSON body without an error object is not an API error body.
        assertThat(GhErrors.classify(418, "application/json", "{}", null, null, false, now))
            .isEqualTo(GhFailure.Unsupported("http_418_non_json"))
        assertThat(GhErrors.classify(403, "application/json", "{}", null, "Bearer error=\"insufficient_scope\"", false, now))
            .isEqualTo(GhFailure.ScopeMissing("insufficient_scope"))
    }

    @Test
    fun `error bodies - detailed reasons as a string or an array, and a malformed body`() {
        val array = """{"error": {"details": [{"metadata": {"detailedReasons": ["INVALID_TIME_RANGE"]}}]}}"""
        assertThat(GhErrors.parseBody(array)?.reasons).containsExactly("INVALID_TIME_RANGE")
        val text = """{"error": {"details": [{"reason": "X", "metadata": {"detailedReasons": "A, B"}}]}}"""
        assertThat(GhErrors.parseBody(text)?.reasons).containsExactly("X", "A", "B").inOrder()
        assertThat(GhErrors.parseBody("not json")).isNull()
        assertThat(GhErrors.parseBody(null)).isNull()
        assertThat(GhErrors.isFilterReason("INVALID_TIME_RANGE")).isTrue()
        assertThat(GhErrors.isFilterReason("INVALID_ARGUMENT")).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "https://fitbit.google.com/auth/signup, https://fitbit.google.com/auth/signup",
        "https://google.com/x, https://google.com/x",
        "http://fitbit.google.com/auth/signup, ",
        "https://google.com.evil.example/x, ",
        "https://evil.example/google.com, ",
        "javascript:alert(1), ",
    )
    fun `S02 only an https Google signup link is kept`(input: String, expected: String?) {
        assertThat(GhErrors.safeSignupUrl(input)).isEqualTo(expected)
    }

    @Test
    fun `failures map to sanitized app errors`() {
        assertThat(GhFailure.NeedsReauth.toAppError()).isEqualTo(AppError.AuthenticationRequired("googlehealth"))
        assertThat(GhFailure.NeedsReauth.toAppError().retryable).isFalse()
        assertThat(GhFailure.ScopeMissing("R").toAppError("steps")).isEqualTo(AppError.PermissionDenied("googlehealth.steps", "R"))
        assertThat(GhFailure.AccountNotLinked(null).toAppError()).isEqualTo(AppError.NotEligible("account_not_linked"))
        assertThat(GhFailure.ProfileNotReady.toAppError()).isEqualTo(AppError.NotEligible("profile_not_ready"))
        assertThat(GhFailure.LegacyFitbitAccount.toAppError()).isEqualTo(AppError.NotEligible("legacy_fitbit_account"))
        assertThat(GhFailure.RateLimited(7.seconds).toAppError().retryable).isTrue()
        assertThat(GhFailure.Transient("http_503", 503).toAppError()).isEqualTo(AppError.RemoteServerError(503, "http_503"))
        assertThat(GhFailure.Transient("timeout").toAppError()).isEqualTo(AppError.NetworkUnavailable("timeout"))
        assertThat(
            GhFailure.Unsupported("http_404").toAppError("sleep"),
        ).isEqualTo(AppError.UnsupportedFeature("googlehealth.sleep", "http_404"))
        assertThat(GhFailure.FilterRejected("F").toAppError()).isEqualTo(AppError.UnsupportedFeature("googlehealth.remote", "F"))
        assertThat(GhFailure.PageTokenRejected.toAppError()).isEqualTo(AppError.NetworkUnavailable("page_token"))
    }

    @ParameterizedTest(name = "status {0}")
    @CsvSource(
        "1, unsupported_feature, false",
        "2, unsupported_feature, false",
        "3, unsupported_feature, false",
        "7, network_unavailable, true",
        "8, unexpected, false",
        "10, unsupported_feature, false",
        "16, cancelled, false",
    )
    fun `testing-build-01 Play services status codes map to app errors`(status: Int, code: String, retryable: Boolean) {
        val error = GhFailure.AuthorizerFailed(status).toAppError()
        assertThat(error.code).isEqualTo(code)
        assertThat(error.retryable).isEqualTo(retryable)
        assertThat(error.detail).isEqualTo("auth_status_$status")
    }

    @Test
    fun `which failures stop the source`() {
        val stopping = listOf(
            GhFailure.NeedsReauth,
            GhFailure.ProfileNotReady,
            GhFailure.LegacyFitbitAccount,
            GhFailure.AccountNotLinked(null),
            GhFailure.AuthorizerFailed(1),
            GhFailure.RateLimited(null),
        )
        val local = listOf(
            GhFailure.ScopeMissing("x"),
            GhFailure.Transient("x"),
            GhFailure.Unsupported("x"),
            GhFailure.FilterRejected("x"),
            GhFailure.PageTokenRejected,
        )
        assertThat(stopping.all { it.stopsSource }).isTrue()
        assertThat(local.none { it.stopsSource }).isTrue()
    }
}

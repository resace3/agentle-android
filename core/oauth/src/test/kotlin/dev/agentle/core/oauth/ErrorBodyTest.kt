package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ErrorBodyTest {
    @Test
    fun `every documented body shape is recognized (R06 5_5)`() {
        assertThat(ErrorBody.parse("""{"error":"invalid_grant","error_description":"x"}""")).isEqualTo(
            ErrorBody(BodyShape.OAUTH_STRING, "invalid_grant", null, null),
        )
        assertThat(
            ErrorBody.parse(
                """{"error":{"code":"subscription_sharing_unsupported_capability","param":"temperature","type":"invalid_request_error"}}""",
            ),
        ).isEqualTo(
            ErrorBody(BodyShape.ERROR_OBJECT, "subscription_sharing_unsupported_capability", "temperature", "invalid_request_error"),
        )
        assertThat(ErrorBody.parse("""{"error":{"message":"m","type":"server_error","param":null,"code":null}}""")).isEqualTo(
            ErrorBody(BodyShape.ERROR_OBJECT, null, null, "server_error"),
        )
        assertThat(ErrorBody.parse("""{"detail":"Unauthorized"}""").shape).isEqualTo(BodyShape.DETAIL)
        assertThat(ErrorBody.parse("""{"other":1}""").shape).isEqualTo(BodyShape.OTHER_JSON)
        assertThat(ErrorBody.parse("""[1]""").shape).isEqualTo(BodyShape.OTHER_JSON)
        assertThat(ErrorBody.parse("<html><body>502</body></html>").shape).isEqualTo(BodyShape.HTML)
        assertThat(ErrorBody.parse("Bad gateway", "text/html; charset=utf-8").shape).isEqualTo(BodyShape.HTML)
        assertThat(ErrorBody.parse("""{"error":"inv""").shape).isEqualTo(BodyShape.UNPARSEABLE)
        assertThat(ErrorBody.parse("  ")).isEqualTo(ErrorBody.EMPTY)
        assertThat(ErrorBody.parse(null)).isEqualTo(ErrorBody.EMPTY)
    }

    @Test
    fun `free text and odd codes never survive parsing`() {
        val parsed = ErrorBody.parse("""{"error":"has spaces <b>","error_description":"secret echo rt_1"}""")
        assertThat(parsed.code).isNull()
        assertThat(parsed.toString()).doesNotContain("rt_1")
        assertThat(ErrorBody.parse("""{"error":{"code":"x","param":"bad param!"}}""").param).isNull()
        assertThat(ErrorBody.parse("""{"error":7}""").code).isNull()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "invalid_grant, 400, authentication_required",
        "invalid_refresh_token, 400, authentication_required",
        "refresh_token_reused, 400, authentication_required",
        "invalid_client, 401, authentication_required",
        "unauthorized_client, 400, authentication_required",
        "invalid_scope, 400, authentication_required",
        "temporarily_unavailable, 503, remote_server_error",
        "temporarily_unavailable, 400, remote_server_error",
        "server_error, 500, remote_server_error",
        "server_error, 400, remote_server_error",
        "slow_down, 400, rate_limited",
        "invalid_request, 400, unexpected",
        "invalid_target, 400, unexpected",
    )
    fun `OAuth error codes map by code, not by status`(code: String, status: Int, appCode: String) {
        val error = OAuthFailure.ErrorResponse(code, status, BodyShape.OAUTH_STRING).appError
        assertThat(error.code).isEqualTo(appCode)
        if (error is AppError.RemoteServerError) assertThat(error.status).isAtLeast(500)
    }

    @Test
    fun `transport failures become stable kinds without messages`() {
        assertThat(transportFailureKind(UnknownHostException("auth.example"))).isEqualTo("dns")
        assertThat(transportFailureKind(ConnectException("refused"))).isEqualTo("connect")
        assertThat(transportFailureKind(InterruptedIOException("timeout"))).isEqualTo("timeout")
        assertThat(transportFailureKind(SSLException("handshake"))).isEqualTo("tls")
        assertThat(transportFailureKind(IOException("at_1 leaked?"))).isEqualTo("io:IOException")
        assertThat(OAuthFailure.Redirected(307).appError).isEqualTo(AppError.RemoteServerError(307, "oauth_redirect_refused"))
        assertThat(TokenClient.describe(OAuthFailure.Blocked("cleartext"))).isEqualTo("blocked:cleartext")
    }
}

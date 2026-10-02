package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.network.HttpClientConfig
import dev.agentle.core.network.HttpClientFactory
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TokenClientTest {
    private val server = MockWebServer()
    private val clock = TestAgentleClock()
    private val sink = CapturingSink()
    private val base = HttpClientFactory.create(HttpClientConfig("Agentle/test", allowCleartextLoopback = true))
    private val client = TokenClient(base, clock, sink.logger())
    private val redirect = "http://127.0.0.1:45678/auth/callback".toHttpUrl()

    @BeforeEach
    fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @AfterEach
    fun stop() = server.close()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private val okBody = """{"access_token":"at_1","refresh_token":"rt_1","id_token":"idt","token_type":"Bearer",""" +
        """"expires_in":3600,"scope":"openid offline_access","earliest_refresh_at":1790000000}"""

    private suspend fun exchange() = client.exchangeCode(
        server.url("/oauth/token"),
        "oaiapp_x",
        Secret("code-1"),
        Secret("v".repeat(43)),
        redirect,
        listOf("resource" to "https://api.example.test/v1"),
        TokenResponseRules(requireIdToken = true, requireScope = true),
    )

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
            java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

    @Test
    fun `code exchange posts exactly the documented form fields`() = runTest {
        server.enqueue(json(200, okBody))
        val result = exchange() as OAuthResult.Success
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.headers["Content-Type"]).startsWith("application/x-www-form-urlencoded")
        assertThat(recorded.headers["Accept"]).isEqualTo("application/json")
        assertThat(form(recorded.body!!.utf8())).containsExactly(
            "grant_type", "authorization_code",
            "client_id", "oaiapp_x",
            "code", "code-1",
            "code_verifier", "v".repeat(43),
            "redirect_uri", "http://127.0.0.1:45678/auth/callback",
            "resource", "https://api.example.test/v1",
        )
        with(result.value) {
            assertThat(accessToken.value).isEqualTo("at_1")
            assertThat(refreshToken!!.value).isEqualTo("rt_1")
            assertThat(idToken!!.value).isEqualTo("idt")
            assertThat(expiresIn).isEqualTo(3600.seconds)
            assertThat(grantedScopes).containsExactly("openid", "offline_access")
            assertThat(earliestRefreshAt).isEqualTo(Instant.fromEpochSeconds(1_790_000_000))
        }
    }

    @Test
    fun `refresh sends no scope and appends provider parameters`() = runTest {
        server.enqueue(json(200, """{"access_token":"at_2","refresh_token":"rt_2","token_type":"bearer","expires_in":3600}"""))
        val result = client.refresh(server.url("/oauth/token"), "oaiapp_x", Secret("rt_1"), listOf("resource" to "r"))
        assertThat(result).isInstanceOf(OAuthResult.Success::class.java)
        assertThat(form(server.takeRequest().body!!.utf8())).containsExactly(
            "grant_type",
            "refresh_token",
            "client_id",
            "oaiapp_x",
            "refresh_token",
            "rt_1",
            "resource",
            "r",
        )
    }

    @Test
    fun `an OAuth string error is returned with its code and status`() = runTest {
        server.enqueue(json(400, """{"error":"invalid_grant","error_description":"code_verifier does not match code_challenge"}"""))
        val failure = (exchange() as OAuthResult.Failure).failure as OAuthFailure.ErrorResponse
        assertThat(failure.error).isEqualTo("invalid_grant")
        assertThat(failure.httpStatus).isEqualTo(400)
        assertThat(failure.shape).isEqualTo(BodyShape.OAUTH_STRING)
        assertThat(failure.toString()).doesNotContain("code_verifier does not match")
        assertThat(failure.appError).isInstanceOf(AppError.AuthenticationRequired::class.java)
    }

    @Test
    fun `an API style error object is returned with its code and the request id`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(400).setHeader("x-request-id", "req_123")
                .body("""{"error":{"code":"refresh_token_reused","message":"..."}}""").build(),
        )
        val failure = (client.refresh(server.url("/t"), "c", Secret("rt")) as OAuthResult.Failure).failure as OAuthFailure.ErrorResponse
        assertThat(failure.error).isEqualTo("refresh_token_reused")
        assertThat(failure.shape).isEqualTo(BodyShape.ERROR_OBJECT)
        assertThat(failure.requestId).isEqualTo("req_123")
    }

    @Test
    fun `an HTML 502 is an http failure with its shape`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).setHeader("Content-Type", "text/html").body("<html>bad gateway</html>").build())
        val failure = (exchange() as OAuthResult.Failure).failure
        assertThat(failure).isEqualTo(OAuthFailure.HttpStatus(502, BodyShape.HTML))
        assertThat(failure.appError).isEqualTo(AppError.RemoteServerError(502, "oauth_http_502"))
        assertThat(failure.appError.retryable).isTrue()
    }

    @Test
    fun `a 429 without a code carries Retry-After`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "20").build())
        val failure = (exchange() as OAuthResult.Failure).failure as OAuthFailure.HttpStatus
        assertThat(failure.retryAfter).isEqualTo(20.seconds)
        assertThat(failure.appError).isEqualTo(AppError.RateLimited(20.seconds, "oauth_http_429"))
    }

    @Test
    fun `redirects are never followed`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", server.url("/elsewhere").toString()).build())
        val failure = (exchange() as OAuthResult.Failure).failure
        assertThat(failure).isEqualTo(OAuthFailure.Redirected(302))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a socket closed before the answer is a network failure and the POST is not replayed`() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(json(200, okBody))
        val failure = (client.refresh(server.url("/t"), "c", Secret("rt_1")) as OAuthResult.Failure).failure
        assertThat(failure).isInstanceOf(OAuthFailure.Network::class.java)
        assertThat(failure.appError).isInstanceOf(AppError.NetworkUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a connection refused is a network failure`() = runTest {
        val port = server.port
        server.close()
        val failure = (client.refresh("http://127.0.0.1:$port/t".toHttpUrl(), "c", Secret("rt")) as OAuthResult.Failure).failure
        assertThat(failure).isEqualTo(OAuthFailure.Network("connect"))
    }

    @Test
    fun `cleartext is refused locally when the base client is TLS only`() = runTest {
        val tlsOnly = TokenClient(HttpClientFactory.create(HttpClientConfig("Agentle/test")), clock)
        val failure = (tlsOnly.refresh(server.url("/t"), "c", Secret("rt")) as OAuthResult.Failure).failure
        assertThat(failure).isEqualTo(OAuthFailure.Blocked("cleartext"))
        assertThat(failure.appError).isInstanceOf(AppError.Unexpected::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a malformed or oversized 200 is an invalid response`() = runTest {
        server.enqueue(json(200, """{"access_token":"at_1","token_type":"Bea"""))
        assertThat((exchange() as OAuthResult.Failure).failure).isEqualTo(OAuthFailure.InvalidResponse("not_json_object", 200))
        server.enqueue(json(200, "{\"pad\":\"" + "x".repeat(300_000) + "\"}"))
        assertThat((exchange() as OAuthResult.Failure).failure).isEqualTo(OAuthFailure.InvalidResponse("body_too_large", 200))
        assertThat(OAuthFailure.InvalidResponse("x", 200).appError).isInstanceOf(AppError.ParsingError::class.java)
    }

    @Test
    fun `revocation succeeds on an empty 200 and posts token, hint and client`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertThat(client.revoke(server.url("/revoke"), "oaiapp_x", Secret("rt_9"))).isEqualTo(OAuthResult.Success(Unit))
        assertThat(form(server.takeRequest().body!!.utf8())).containsExactly(
            "token",
            "rt_9",
            "token_type_hint",
            "refresh_token",
            "client_id",
            "oaiapp_x",
        )
    }

    @Test
    fun `revocation failures are returned, not thrown`() = runTest {
        server.enqueue(json(503, """{"error":"temporarily_unavailable"}"""))
        server.enqueue(json(400, """{"error":"unsupported_token_type"}"""))
        val first = (client.revoke(server.url("/revoke"), "c", Secret("rt")) as OAuthResult.Failure).failure
        assertThat(first.appError).isEqualTo(AppError.RemoteServerError(503, "oauth_error:temporarily_unavailable"))
        val second = (client.revoke(server.url("/revoke"), "c", Secret("rt")) as OAuthResult.Failure).failure
        assertThat((second as OAuthFailure.ErrorResponse).error).isEqualTo("unsupported_token_type")
        server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "/x").build())
        assertThat(
            (client.revoke(server.url("/revoke"), "c", Secret("rt")) as OAuthResult.Failure).failure,
        ).isEqualTo(OAuthFailure.Redirected(301))
    }

    @Test
    fun `results convert to outcomes with app errors`() = runTest {
        assertThat(OAuthResult.Success(1).toOutcome()).isEqualTo(Outcome.Success(1))
        assertThat(
            OAuthResult.Failure(OAuthFailure.Network("dns")).toOutcome(),
        ).isEqualTo(Outcome.Failure(AppError.NetworkUnavailable("dns")))
    }

    @Test
    fun `no token, code or verifier reaches the logs`() = runTest {
        server.enqueue(json(200, okBody))
        exchange()
        server.enqueue(json(400, """{"error":"invalid_grant"}"""))
        client.refresh(server.url("/oauth/token"), "oaiapp_x", Secret("rt_1"))
        val logs = sink.text()
        assertThat(logs).contains("token request finished")
        listOf("at_1", "rt_1", "idt", "code-1", "v".repeat(43)).forEach { assertThat(logs).doesNotContain(it) }
    }
}

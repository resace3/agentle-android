package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** docs/research/06-openai-sign-in-with-chatgpt.md §9.5: the app's own listener, driven by a plain HTTP client. */
class LoopbackCallbackServerTest {
    private val clock = TestAgentleClock()
    private val sink = CapturingSink()
    private val state = Secret("S-state-value-0123456789abcdefghijklmnopqr")
    private val config = LoopbackServerConfig(pollInterval = 10.milliseconds, connectionReadTimeout = 300.milliseconds)
    private val servers = mutableListOf<LoopbackCallbackServer>()

    @AfterEach
    fun closeAll() = servers.forEach(LoopbackCallbackServer::close)

    private fun start(config: LoopbackServerConfig = this.config, validator: CallbackValidator? = null): LoopbackCallbackServer {
        val started = LoopbackCallbackServer.start(state, clock, config, sink.logger(), validator)
        return (started as Outcome.Success).value.also { servers += it }
    }

    private fun LoopbackCallbackServer.callback(query: String, host: String? = "127.0.0.1:$port") =
        RawHttp.get(port, "/auth/callback?$query", host)!!

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

    @Test
    fun `binds the IPv4 loopback address on an ephemeral port`() {
        val server = start()
        assertThat(server.bindAddress.hostAddress).isEqualTo("127.0.0.1")
        assertThat(server.bindAddress.isLoopbackAddress).isTrue()
        assertThat(server.port).isGreaterThan(0)
        assertThat(server.redirectUri.toString()).isEqualTo("http://127.0.0.1:${server.port}/auth/callback")
    }

    @Test
    fun `a valid callback gets the no-store return page and settles with the code (R06 9_5 row 1)`() {
        val server = start(config.copy(returnLink = "intent://siwc-done#Intent;scheme=agentle;package=dev.agentle.app;end"))
        val reply = server.callback("code=c-secret-1&state=${state.value}&client_id=oaiapp_fakeA1&scope=openid+email")
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.header("Content-Type")).isEqualTo("text/html; charset=utf-8")
        assertThat(reply.header("Cache-Control")).isEqualTo("no-store")
        assertThat(reply.header("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(reply.header("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(reply.header("Connection")).isEqualTo("close")
        assertThat(reply.header("Content-Security-Policy")).startsWith("default-src 'none'")
        assertThat(reply.body).contains("Return to Agentle")
        assertThat(reply.body).contains("href=\"intent://siwc-done#Intent;scheme=agentle;package=dev.agentle.app;end\"")
        assertThat(reply.body).doesNotContain("c-secret-1")
        assertThat(reply.body).doesNotContain(state.value)
        val outcome = blocking { server.await() } as CallbackOutcome.Authorized
        assertThat(outcome.code.value).isEqualTo("c-secret-1")
        assertThat(outcome.parameters.single("client_id")).isEqualTo("oaiapp_fakeA1")
        assertThat(outcome.parameters.single("scope")).isEqualTo("openid email")
        assertThat(outcome.parameters.names).containsExactly("client_id", "scope")
        assertThat(outcome.parameters.toString()).doesNotContain("oaiapp_fakeA1")
    }

    @Test
    fun `the page script is pinned by its hash and strips the code from the address bar`() {
        val server = start()
        val reply = server.callback("code=c1&state=${state.value}")
        val csp = reply.header("Content-Security-Policy")!!
        assertThat(csp).contains("script-src '${CallbackPages.hash(CallbackPages.SCRIPT)}'")
        assertThat(csp).contains("style-src '${CallbackPages.hash(CallbackPages.STYLE)}'")
        assertThat(csp).contains("frame-ancestors 'none'")
        assertThat(reply.body).contains("<script>history.replaceState(null,\"\",location.pathname);</script>")
        // Independently computed: sha256 of the script text, standard base64.
        assertThat(CallbackPages.hash("alert(1)")).isEqualTo("sha256-bhHHL3z2vDgxUt0W3dWQOrprscmda2Y5pLsLg4GF+pI=")
    }

    @Test
    fun `access_denied gets the not completed page and settles with the error (R06 9_5 row 2)`() {
        val server = start()
        val reply = server.callback("error=access_denied&error_description=%3Cb%3Ehi%3C%2Fb%3E&state=${state.value}")
        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.body).contains("Sign-in was not completed")
        assertThat(reply.body).doesNotContain("<b>hi</b>")
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Denied("access_denied"))
        assertThat(CallbackOutcome.Denied("access_denied").toAppError("chatgpt")).isEqualTo(AppError.Cancelled("authorization_denied"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["state=TAMPERED", "state=S-state-value-0123456789abcdefghijklmnopq", "", "state=", "STATE=x"])
    fun `wrong or missing state gets 400 and the attempt keeps waiting (R06 9_5 row 3)`(stateParam: String) {
        val server = start()
        val reply = server.callback("code=c1&$stateParam")
        assertThat(reply.status).isEqualTo(400)
        assertThat(server.isSettled).isFalse()
        assertThat(server.callback("code=c2&state=${state.value}").status).isEqualTo(200)
        assertThat((blocking { server.await() } as CallbackOutcome.Authorized).code.value).isEqualTo("c2")
    }

    @Test
    fun `two state parameters get 400 even if one is right`() {
        val server = start()
        assertThat(server.callback("code=c1&state=${state.value}&state=${state.value}").status).isEqualTo(400)
        assertThat(server.callback("code=c1&state=x&state=${state.value}").status).isEqualTo(400)
        assertThat(server.isSettled).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "/auth/callback/", "/auth/Callback", "/auth/%63allback", "/auth", "/", "/favicon.ico", "//auth/callback", "/auth/callback;x",
        ],
    )
    fun `any other path gets 404 and does not settle (R06 9_5 row 4)`(path: String) {
        val server = start()
        assertThat(RawHttp.get(server.port, "$path?code=c1&state=${state.value}")!!.status).isEqualTo(404)
        assertThat(server.isSettled).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["POST", "HEAD", "PUT", "DELETE", "OPTIONS", "get"])
    fun `any other method gets 404 and does not settle`(method: String) {
        val server = start()
        val reply = RawHttp.send(
            server.port,
            "$method /auth/callback?code=c1&state=${state.value} HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n",
        )
        assertThat(reply?.status ?: 404).isAnyOf(404, 400)
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `a wrong, missing or duplicate Host gets 404 and does not settle (DNS rebinding defense)`() {
        val server = start()
        val p = server.port
        listOf("localhost:$p", "127.0.0.1", "127.0.0.1:${p + 1}", "attacker.example:$p", "127.0.0.1:$p.evil", "[::1]:$p").forEach { host ->
            assertThat(server.callback("code=c1&state=${state.value}", host).status).isEqualTo(404)
        }
        assertThat(server.callback("code=c1&state=${state.value}", host = null).status).isEqualTo(404)
        val duplicate = RawHttp.get(p, "/auth/callback?code=c1&state=${state.value}", extraHeaders = "Host: 127.0.0.1:$p\r\n")!!
        assertThat(duplicate.status).isEqualTo(404)
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `a replayed callback after settling gets 404 and the first outcome stands`() {
        val server = start()
        assertThat(server.callback("code=first&state=${state.value}").status).isEqualTo(200)
        assertThat(server.callback("code=second&state=${state.value}").status).isEqualTo(404)
        assertThat(server.callback("error=access_denied&state=${state.value}").status).isEqualTo(404)
        assertThat((blocking { server.await() } as CallbackOutcome.Authorized).code.value).isEqualTo("first")
    }

    @Test
    fun `concurrent valid callbacks settle exactly once`() {
        val server = start()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val replies = (1..8).map { i ->
                pool.submit(Callable { server.callback("code=c$i&state=${state.value}").status })
            }.map { it.get() }
            assertThat(replies.count { it == 200 }).isEqualTo(1)
            assertThat(replies.count { it == 404 }).isEqualTo(7)
        } finally {
            pool.shutdownNow()
        }
        assertThat(blocking { server.await() }).isInstanceOf(CallbackOutcome.Authorized::class.java)
    }

    @Test
    fun `no callback within 10 minutes on the monotonic clock times out and releases the port (R06 9_4 tab closed)`() {
        val server = start(config.copy(timeout = 10.minutes))
        clock.advanceBy(9.minutes)
        assertThat(server.isSettled).isFalse()
        clock.advanceBy(1.minutes)
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.TimedOut)
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, server.port).close() }
        assertThat(CallbackOutcome.TimedOut.toAppError("x")).isEqualTo(AppError.Cancelled("authorization_timeout"))
    }

    @Test
    fun `a wall clock change does not end the attempt`() {
        val server = start(config.copy(timeout = 10.minutes))
        clock.setWallClock(clock.now() + 1.minutes * 60)
        assertThat(server.callback("code=c1&state=${state.value}").status).isEqualTo(200)
    }

    @Test
    fun `cancelling the waiting caller closes the listener`() {
        val server = start()
        runBlocking {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { server.await() }
            waiting.cancel()
            runCatching { waiting.await() }
        }
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Cancelled)
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, server.port).close() }
        assertThat(CallbackOutcome.Cancelled.toAppError("x")).isInstanceOf(AppError.Cancelled::class.java)
    }

    @Test
    fun `an idle connection is closed after the read timeout while other connections are served (R06 9_5 row 5)`() {
        val server = start()
        Socket(RawHttp.LOOPBACK, server.port).use { idle ->
            idle.soTimeout = 5_000
            assertThat(server.callback("code=c1&state=${state.value}").status).isEqualTo(200)
            assertThat(idle.getInputStream().read()).isEqualTo(-1)
        }
    }

    @Test
    fun `connections above the cap are dropped without an answer`() {
        val server = start(config.copy(maxConcurrentConnections = 1, connectionReadTimeout = 5.seconds))
        Socket(RawHttp.LOOPBACK, server.port).use { holder ->
            holder.getOutputStream().write("GET /".toByteArray())
            val dropped = runCatching { RawHttp.get(server.port, "/auth/callback?code=c1&state=${state.value}") }.getOrNull()
            assertThat(dropped).isNull()
        }
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `an oversized request line gets 431 and does not settle`() {
        val server = start()
        val reply = server.callback("code=c1&state=${state.value}&pad=${"x".repeat(9_000)}")
        assertThat(reply.status).isEqualTo(431)
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `oversized headers get 431`() {
        val server = start()
        val reply = RawHttp.get(
            server.port,
            "/auth/callback?code=c1&state=${state.value}",
            extraHeaders = "Cookie: ${"y".repeat(17_000)}\r\n",
        )
        assertThat(reply!!.status).isEqualTo(431)
        assertThat(server.isSettled).isFalse()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "GET /auth/callback HTTP/2.0\r\nHost: {HOST}\r\n\r\n",
            "GET  /auth/callback HTTP/1.1\r\nHost: {HOST}\r\n\r\n",
            "GET /auth/callback HTTP/1.1\r\nHost : {HOST}\r\n\r\n",
            "GET /auth/callback HTTP/1.1\r\n folded\r\n\r\n",
            "GET /auth/callback HTTP/1.1\r\nno-colon\r\n\r\n",
            "\r\nGET /auth/callback HTTP/1.1\r\n\r\n",
        ],
    )
    fun `malformed requests get 400 and do not settle`(raw: String) {
        val server = start()
        val reply = RawHttp.send(server.port, raw.replace("{HOST}", "127.0.0.1:${server.port}"))
        assertThat(reply!!.status).isEqualTo(400)
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `absolute-form targets and fragments are not the callback`() {
        val server = start()
        assertThat(
            RawHttp.get(server.port, "http://127.0.0.1:${server.port}/auth/callback?code=c1&state=${state.value}")!!.status,
        ).isEqualTo(404)
        assertThat(RawHttp.get(server.port, "/auth/callback?code=c1&state=${state.value}#frag")!!.status).isEqualTo(404)
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `bad percent-encoding gets 400 and does not settle`() {
        val server = start()
        assertThat(server.callback("code=%zz&state=${state.value}").status).isEqualTo(400)
        assertThat(server.isSettled).isFalse()
    }

    @Test
    fun `LF-only line endings and HTTP 1_0 are tolerated`() {
        val server = start()
        val reply = RawHttp.send(
            server.port,
            "GET /auth/callback?code=c1&state=${state.value} HTTP/1.0\nHost: 127.0.0.1:${server.port}\n\n",
        )
        assertThat(reply!!.status).isEqualTo(200)
    }

    @Test
    fun `the right state without a code settles as rejected`() {
        val server = start()
        val reply = server.callback("state=${state.value}&code=")
        assertThat(reply.status).isEqualTo(400)
        assertThat(reply.body).contains("Sign-in was not completed")
        val outcome = blocking { server.await() }
        assertThat(outcome).isEqualTo(CallbackOutcome.Rejected(CallbackRejection.MISSING_CODE))
        assertThat(outcome.toAppError("chatgpt")).isEqualTo(AppError.AuthenticationRequired("chatgpt", "callback_rejected:missing_code"))
    }

    @Test
    fun `two codes settle as rejected`() {
        val server = start()
        server.callback("state=${state.value}&code=a&code=b")
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Rejected(CallbackRejection.MISSING_CODE))
    }

    @Test
    fun `an iss parameter must equal the expected issuer (RFC 9207)`() {
        val server = start(config.copy(expectedIssuer = "https://auth.example.test"))
        server.callback("code=c1&state=${state.value}&iss=https%3A%2F%2Fevil.example")
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Rejected(CallbackRejection.ISSUER_MISMATCH))
        val second = start(config.copy(expectedIssuer = "https://auth.example.test"))
        second.callback("code=c1&state=${state.value}&iss=https%3A%2F%2Fauth.example.test")
        assertThat(blocking { second.await() }).isInstanceOf(CallbackOutcome.Authorized::class.java)
    }

    @Test
    fun `the provider validator can reject a callback with its own code`() {
        val server = start(validator = { params ->
            if (params.single("client_id") ==
                null
            ) {
                CallbackRejection("registration_incomplete")
            } else {
                null
            }
        })
        assertThat(server.callback("code=c1&state=${state.value}").status).isEqualTo(400)
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Rejected(CallbackRejection("registration_incomplete")))
    }

    @Test
    fun `odd error values are reduced to a safe code`() {
        val server = start()
        server.callback("error=%3Cscript%3E&state=${state.value}")
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Denied("invalid_error"))
    }

    @Test
    fun `server side authorization errors map to retryable or unexpected app errors`() {
        assertThat(CallbackOutcome.Denied("temporarily_unavailable").toAppError("p")!!.retryable).isTrue()
        assertThat(CallbackOutcome.Denied("server_error").toAppError("p")).isInstanceOf(AppError.RemoteServerError::class.java)
        assertThat(CallbackOutcome.Denied("invalid_scope").toAppError("p")).isInstanceOf(AppError.Unexpected::class.java)
        assertThat(CallbackOutcome.Authorized(Secret("c"), CallbackParameters(emptyMap())).toAppError("p")).isNull()
    }

    @Test
    fun `closing before a callback settles as cancelled and later requests are refused`() {
        val server = start()
        server.close()
        server.close()
        assertThat(blocking { server.await() }).isEqualTo(CallbackOutcome.Cancelled)
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, server.port).close() }
    }

    @Test
    fun `nothing from the request is reflected and no code or state reaches the logs`() {
        val server = start(config.copy(appName = "Agentle <b>"))
        val reply = server.callback("code=c-secret-xyz&state=${state.value}&client_id=%3Cimg%3E")
        RawHttp.get(server.port, "/nope?code=c-secret-xyz&state=${state.value}")
        assertThat(reply.body).doesNotContain("<img>")
        assertThat(reply.body).contains("Agentle &lt;b&gt;")
        blocking { server.await() }
        val logs = sink.text()
        assertThat(logs).contains("authorization attempt settled")
        assertThat(logs).doesNotContain("c-secret-xyz")
        assertThat(logs).doesNotContain(state.value)
    }

    @Test
    fun `config rejects unusable values`() {
        assertThrows<IllegalArgumentException> { LoopbackServerConfig(callbackPath = "auth/callback") }
        assertThrows<IllegalArgumentException> { LoopbackServerConfig(callbackPath = "/auth?x") }
        assertThrows<IllegalArgumentException> { LoopbackServerConfig(timeout = 0.seconds) }
        assertThrows<IllegalArgumentException> { LoopbackServerConfig(maxRequestLineBytes = 100) }
        assertThrows<IllegalArgumentException> { LoopbackServerConfig(maxConcurrentConnections = 0) }
    }
}

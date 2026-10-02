package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import java.net.Socket
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AuthorizationCodeFlowTest {
    private val clock = TestAgentleClock()
    private val random = OAuthRandom(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })
    private val loopback = LoopbackServerConfig(pollInterval = 10.milliseconds)
    private var lastPort = -1

    /** Plays the authorization server and the browser: sends the redirect the server would send. */
    private fun browser(answer: (state: String) -> String) = BrowserLauncher { url ->
        val redirect = url.queryParameter("redirect_uri")!!.toHttpUrl()
        lastPort = redirect.port
        withContext(Dispatchers.IO) { RawHttp.get(redirect.port, "${redirect.encodedPath}?${answer(url.queryParameter("state")!!)}") }
        Outcome.Success(Unit)
    }

    private fun request(redirectUri: HttpUrl, attempt: AuthorizationAttempt) = AuthorizationRequest(
        authorizationEndpoint = "https://auth.example.test/authorize".toHttpUrl(),
        clientId = "client-1",
        redirectUri = redirectUri,
        scopes = listOf("openid"),
        state = attempt.state,
        pkce = attempt.pkce,
        nonce = attempt.nonce,
    )

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

    @Test
    fun `a full round trip returns the code with the attempt secrets and closes the listener`() {
        val flow = AuthorizationCodeFlow(browser { state -> "code=c-1&state=$state" }, clock, random, loopback)
        val result = blocking { flow.authorize(buildRequest = ::request) } as AuthorizationResult.Authorized
        assertThat(result.callback.code.value).isEqualTo("c-1")
        assertThat(result.redirectUri.port).isEqualTo(lastPort)
        assertThat(result.attempt.pkce.challenge).isEqualTo(Pkce.challengeS256(result.attempt.pkce.verifier))
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, lastPort).close() }
    }

    @Test
    fun `a denial is reported as not completed`() {
        val flow = AuthorizationCodeFlow(browser { state -> "error=access_denied&state=$state" }, clock, random, loopback)
        assertThat(
            blocking {
                flow.authorize(buildRequest = ::request)
            },
        ).isEqualTo(AuthorizationResult.NotCompleted(CallbackOutcome.Denied("access_denied")))
    }

    @Test
    fun `a browser that cannot open fails the attempt and releases the port`() {
        var port = -1
        val flow = AuthorizationCodeFlow(
            { url ->
                port = url.queryParameter("redirect_uri")!!.toHttpUrl().port
                Outcome.Failure(AppError.UnsupportedFeature("browser"))
            },
            clock,
            random,
            loopback,
        )
        assertThat(
            blocking {
                flow.authorize(buildRequest = ::request)
            },
        ).isEqualTo(AuthorizationResult.Failed(AppError.UnsupportedFeature("browser")))
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, port).close() }
    }

    @Test
    fun `no installed browser is a distinct outcome and releases the port (oauth-security-16)`() {
        var port = -1
        val flow = AuthorizationCodeFlow(
            { url ->
                port = url.queryParameter("redirect_uri")!!.toHttpUrl().port
                Outcome.Failure(BrowserLauncher.NO_BROWSER_ERROR)
            },
            clock,
            random,
            loopback,
        )
        assertThat(blocking { flow.authorize(buildRequest = ::request) }).isEqualTo(AuthorizationResult.NoBrowser)
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, port).close() }
        assertThat(BrowserLauncher.isNoBrowser(AppError.UnsupportedFeature("browser"))).isFalse()
    }

    @Test
    fun `a session re-opens the same page on a repeated tap and close cancels the wait`() {
        val launcher = RecordingBrowserLauncher()
        val session = (AuthorizationCodeFlow(launcher, clock, random, loopback).begin(buildRequest = ::request) as Outcome.Success).value
        blocking {
            assertThat(session.open()).isNull()
            assertThat(session.open()).isNull()
        }
        assertThat(launcher.launched).hasSize(2)
        assertThat(launcher.launched.toSet()).hasSize(1)
        assertThat(session.toString()).doesNotContain(session.attempt.state.value)
        val result = blocking {
            coroutineScope {
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.await() }
                session.close()
                waiting.await()
            }
        }
        assertThat(result).isEqualTo(AuthorizationResult.NotCompleted(CallbackOutcome.Cancelled))
        assertThat(session.isSettled).isTrue()
        assertThrows<ConnectException> { Socket(RawHttp.LOOPBACK, session.redirectUri.port).close() }
    }

    @Test
    fun `a session still accepts the callback after a re-open`() {
        val launcher = RecordingBrowserLauncher()
        val session = (AuthorizationCodeFlow(launcher, clock, random, loopback).begin(buildRequest = ::request) as Outcome.Success).value
        session.use {
            blocking { session.open() }
            val url = launcher.launched.single()
            RawHttp.get(session.redirectUri.port, "/auth/callback?code=c-9&state=${url.queryParameter("state")}")
            val result = blocking { session.await() } as AuthorizationResult.Authorized
            assertThat(result.callback.code).isEqualTo(Secret("c-9"))
        }
    }

    @Test
    fun `the validator runs before the attempt settles`() {
        val flow = AuthorizationCodeFlow(browser { state -> "code=c&state=$state" }, clock, random, loopback)
        val result = blocking { flow.authorize(validator = { CallbackRejection("nope") }, buildRequest = ::request) }
        assertThat(result).isEqualTo(AuthorizationResult.NotCompleted(CallbackOutcome.Rejected(CallbackRejection("nope"))))
    }

    @Test
    fun `a request that ignores the listener or the attempt secrets is a programming error`() {
        val flow = AuthorizationCodeFlow(RecordingBrowserLauncher(), clock, random, loopback)
        assertThrows<IllegalArgumentException> {
            blocking { flow.authorize { _, attempt -> request("http://127.0.0.1:1/auth/callback".toHttpUrl(), attempt) } }
        }
        assertThrows<IllegalArgumentException> {
            blocking { flow.authorize { uri, attempt -> request(uri, attempt).copy(state = Secret("other")) } }
        }
    }

    @Test
    fun `the recording launcher keeps every URL`() = runBlocking {
        val launcher = RecordingBrowserLauncher()
        launcher.launch("https://a.example/x".toHttpUrl())
        assertThat(launcher.launched.map { it.host }).containsExactly("a.example")
    }
}

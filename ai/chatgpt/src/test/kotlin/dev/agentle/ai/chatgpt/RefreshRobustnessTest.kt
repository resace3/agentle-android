package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Refresh rules of docs/research/06 §2.11 and §8.1, and red team oauth-security-02/08 and testing-build round 3. */
class RefreshRobustnessTest : SiwcFakeTest() {
    private suspend fun SiwcGraph.token(): Outcome<String> = session.withAccessToken { SiwcResult.Ok(it) }

    private fun storedRefreshToken(): String? = vault().registration?.tokens?.refreshToken?.value

    @Test
    fun `fifty concurrent callers with an expired token cause exactly one refresh (R08 T-TOK-01)`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(1.hours)

        val tokens = List(50) { async { siwc.token() } }.awaitAll()

        assertThat(tokens.map { it.value() }.toSet()).containsExactly("at_2")
        assertThat(server.refreshCount()).isEqualTo(1)
        assertThat(storedRefreshToken()).isEqualTo("rt_2")
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `a token with more than a minute left is used as is, with a minute or less it is refreshed first`() = runTest {
        val siwc = graph()
        siwc.connect()

        clock.advanceBy(3530.seconds)
        assertThat(siwc.token().value()).isEqualTo("at_1")
        clock.advanceBy(20.seconds)
        assertThat(siwc.token().value()).isEqualTo("at_2")

        assertThat(server.refreshCount()).isEqualTo(1)
    }

    @Test
    fun `a caller cancelled after the server rotated does not lose the new refresh token`() = runTest {
        val gated = GatedStore(store)
        val siwc = graph(credentialStore = gated)
        siwc.connect()
        clock.advanceBy(1.hours)
        gated.holdWhen { it.registration?.pendingRotation != null }

        val caller = async { siwc.token() }
        gated.reached.await()
        caller.cancelAndJoin()
        gated.release()

        eventually("the rotation to be promoted") { storedRefreshToken() == "rt_2" }
        assertThat(vault().registration!!.pendingRotation).isNull()
        assertThat(siwc.token().value()).isEqualTo("at_2")
        clock.advanceBy(1.hours)
        assertThat(siwc.token().value()).isEqualTo("at_3")
        assertThat(server.refreshCount()).isEqualTo(2)
    }

    @Test
    fun `a JWKS outage after the rotation keeps the checkpoint and the next call promotes it without a new request`() = runTest {
        graph().connect()
        scenario(ChatGptScenario.REFRESH_WITH_ID_TOKEN)
        clock.advanceBy(1.hours)
        val restarted = graph()
        server.failNext(FakeRoute.JWKS, 503, ChatGptFixtures.DETAIL_UNAVAILABLE)

        assertThat(restarted.token().error()).isEqualTo(AppError.RemoteServerError(503, "identity_verification_unavailable"))
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.IDENTITY_VERIFICATION_UNAVAILABLE))
        assertThat(vault().registration!!.pendingRotation).isNotNull()
        assertThat(server.liveRefreshTokens()).containsExactly("rt_2")

        assertThat(restarted.token().value()).isEqualTo("at_2")

        assertThat(server.refreshCount()).isEqualTo(1)
        assertThat(storedRefreshToken()).isEqualTo("rt_2")
        assertThat(vault().registration!!.pendingRotation).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
    }

    @Test
    fun `a 2xx refresh answer that fails validation keeps its refresh token as the only one to use`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(1.hours)
        server.failNext(FakeRoute.TOKEN_REFRESH, 200, """{"refresh_token":"rt_rescued","token_type":"Bearer","expires_in":"soon"}""")

        assertThat(siwc.token().error()).isEqualTo(AppError.ParsingError("invalid_token_response"))
        assertThat(vault().registration!!.pendingRotation).isNotNull()
        assertThat(storedRefreshToken()).isEqualTo("rt_1")

        // The next refresh sends the rescued token, not the old one: the fake never issued it and answers invalid_grant.
        assertThat(siwc.token().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "refresh_rejected"))
        assertThat(server.liveRefreshTokens()).containsExactly("rt_1")
    }

    @Test
    fun `a 2xx refresh answer without a refresh token is dropped and the old refresh token stays usable`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(1.hours)
        server.failNext(FakeRoute.TOKEN_REFRESH, 200, """{"token_type":"Bearer"}""")

        assertThat(siwc.token().error()).isEqualTo(AppError.ParsingError("invalid_token_response"))
        assertThat(vault().registration!!.pendingRotation).isNull()

        assertThat(siwc.token().value()).isEqualTo("at_2")
        assertThat(server.refreshCount()).isEqualTo(2)
    }

    @Test
    fun `a crash between the refresh answer and the token write is recovered from the checkpoint on restart`() = runTest {
        val crashing = CrashingStore(store) { it.registration?.pendingRotation != null }
        val before = graph(credentialStore = crashing)
        before.connect()
        clock.advanceBy(1.hours)

        assertThat(before.token().error()).isEqualTo(AppError.DatabaseError("credential_write_failed"))
        assertThat(crashing.crashed).isTrue()
        assertThat(vault().registration!!.pendingRotation).isNotNull()
        assertThat(storedRefreshToken()).isEqualTo("rt_1")

        val after = graph()
        after.session.start()

        assertThat(storedRefreshToken()).isEqualTo("rt_2")
        assertThat(vault().registration!!.pendingRotation).isNull()
        assertThat(after.token().value()).isEqualTo("at_2")
        assertThat(server.refreshCount()).isEqualTo(1)
    }

    @Test
    fun `rotated tokens are handed out only once they are persisted`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(1.hours)
        store.failWrites = true

        assertThat(siwc.token().error()).isEqualTo(AppError.DatabaseError("credential_write_failed"))
        assertThat(storedRefreshToken()).isEqualTo("rt_1")

        store.failWrites = false
        assertThat(siwc.token().value()).isEqualTo("at_2")
        assertThat(storedRefreshToken()).isEqualTo("rt_2")
        assertThat(server.refreshCount()).isEqualTo(1)
    }

    @ParameterizedTest(name = "R06 9.4 refresh failure (terminal): {0} as {1}")
    @MethodSource("terminalCodes")
    fun `a terminal refresh code clears the tokens but keeps client id, sub and label`(code: String, shape: String) = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(1.hours)
        val body = if (shape == "oauth") ChatGptFixtures.oauthError(code) else ChatGptFixtures.objectError(code)
        server.failNext(FakeRoute.TOKEN_REFRESH, 400, body)

        assertThat(siwc.token().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "refresh_rejected"))

        val registration = vault().registration!!
        assertThat(registration.tokens).isNull()
        assertThat(registration.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(registration.sub).isEqualTo(ChatGptFixtures.SUB)
        assertThat(registration.accountLabel).isEqualTo(ChatGptFixtures.EMAIL)
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.REFRESH_REJECTED))
        assertThat(siwc.session.snapshot.value.toProviderState()).isEqualTo(AiProviderState.NeedsReauth)
    }

    @ParameterizedTest(name = "R06 9.4 refresh failure (transient): {0}")
    @EnumSource(names = ["REFRESH_TRANSIENT", "REFRESH_BAD_GATEWAY", "REFRESH_SOCKET_CLOSE"])
    fun `a transient refresh failure keeps the tokens and the next call refreshes`(row: ChatGptScenario) = runTest {
        val siwc = graph()
        siwc.connect()
        val saved = vault().registration!!.tokens
        scenario(row)
        clock.advanceBy(1.hours)

        val failure = siwc.token().error()

        assertThat(failure.retryable).isTrue()
        assertThat(vault().registration!!.tokens).isEqualTo(saved)
        assertThat(vault().status.state).isAnyOf(SiwcState.SERVER_ERROR, SiwcState.NETWORK_UNAVAILABLE)
        assertThat(siwc.token().value()).isEqualTo("at_2")
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
    }

    @Test
    fun `invalid_client on refresh marks the registration unusable and the next sign-in registers anew`() = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(ChatGptScenario.REFRESH_INVALID_CLIENT)
        clock.advanceBy(1.hours)

        assertThat(siwc.token().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "registration_invalid"))
        assertThat(vault().registration!!.unusable).isTrue()
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.REGISTRATION_INVALID))
        assertThat(siwc.session.currentClientId()).isNull()

        scenario(ChatGptScenario.HAPPY)
        siwc.connect()
        assertThat(browser.launched.last().queryParameter("client_id")).isEqualTo("dynamic_agent_client")
        assertThat(vault().registration!!.unusable).isFalse()
    }

    @Test
    fun `earliest_refresh_at is honoured with a retryable REFRESH_NOT_READY`() = runTest {
        scenario(ChatGptScenario.REFRESH_NOT_READY)
        val signedInAt = clock.now()
        val siwc = graph()
        siwc.connect()

        clock.advanceBy(3590.seconds)
        assertThat(siwc.token().value()).isEqualTo("at_1")
        clock.advanceBy(20.seconds)
        val notReady = siwc.token().error()

        assertThat(notReady).isEqualTo(AppError.RateLimited(3590.seconds, "refresh_not_ready"))
        assertThat(notReady.retryable).isTrue()
        assertThat(vault().status).isEqualTo(
            SiwcStatus(
                SiwcState.SERVER_ERROR,
                SiwcReason.REFRESH_NOT_READY,
                retryAtEpochMs = (signedInAt + 7200.seconds).toEpochMilliseconds(),
            ),
        )
        assertThat(server.refreshCount()).isEqualTo(0)

        clock.advanceBy(1.hours)
        assertThat(siwc.token().value()).isEqualTo("at_2")
    }

    @Test
    fun `a 401 before earliest_refresh_at does not force a refresh (R06 8_1)`() = runTest {
        val siwc = graph()
        siwc.connect()

        val result = siwc.session.withAccessToken<Unit> {
            SiwcResult.Failed(SiwcFailure(null, AppError.TokenExpired("chatgpt"), unauthorized = true))
        }

        assertThat(result.error()).isInstanceOf(AppError.RateLimited::class.java)
        assertThat(vault().status.state).isEqualTo(SiwcState.CONNECTED)
        assertThat(siwc.session.snapshot.value.toProviderState()).isInstanceOf(AiProviderState.Connected::class.java)
        assertThat(server.refreshCount()).isEqualTo(0)
        assertThat(siwc.token().value()).isEqualTo("at_1")
    }

    @Test
    fun `an ID token for another account in a refresh answer discards the rotated tokens`() = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(ChatGptScenario.REFRESH_ACCOUNT_MISMATCH)
        clock.advanceBy(1.hours)

        assertThat(siwc.token().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "account_mismatch"))

        assertThat(vault().registration!!.tokens).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.ACCOUNT_MISMATCH))
        eventually("the rotated refresh token to be revoked") { "rt_2" in server.revokedTokens() }
    }

    @ParameterizedTest(name = "R06 9.3 refresh variant: {0}")
    @EnumSource(names = ["REFRESH_WITH_ID_TOKEN", "REFRESH_WITH_SCOPE"])
    fun `refresh answer variants of the same account are accepted`(row: ChatGptScenario) = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(row)
        clock.advanceBy(1.hours)

        assertThat(siwc.token().value()).isEqualTo("at_2")
        assertThat(vault().registration!!.tokens!!.planUsageGranted).isTrue()
        assertThat(vault().registration!!.sub).isEqualTo(ChatGptFixtures.SUB)
    }

    @Test
    fun `a captive portal answering the refresh keeps the tokens and reports the network as unavailable`() = runTest {
        val siwc = graph()
        siwc.connect()
        val saved = vault().registration!!.tokens
        clock.advanceBy(1.hours)
        server.failNext(FakeRoute.TOKEN_REFRESH, 200, "<html><body>Accept the Wi-Fi terms</body></html>", contentType = "text/html")

        assertThat(siwc.token().error()).isEqualTo(AppError.NetworkUnavailable("captive_portal"))

        assertThat(vault().registration!!.tokens).isEqualTo(saved)
        assertThat(vault().registration!!.pendingRotation).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.CAPTIVE_PORTAL))
        assertThat(siwc.session.snapshot.value.toProviderState()).isEqualTo(AiProviderState.Unavailable("network_unavailable"))
        assertThat(siwc.token().value()).isEqualTo("at_2")
    }

    @Test
    fun `TLS interception of the refresh keeps the tokens and reports the network as unavailable (simulated)`() = runTest {
        val tls = TlsInterception()
        val siwc = graph(http = { it.withAuthInterceptor(tls) })
        siwc.connect()
        val saved = vault().registration!!.tokens
        clock.advanceBy(1.hours)
        tls.failHandshakes("/oauth/token")

        assertThat(siwc.token().error()).isEqualTo(AppError.NetworkUnavailable("tls"))

        assertThat(vault().registration!!.tokens).isEqualTo(saved)
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.TLS_FAILURE))
        tls.stop()
        assertThat(siwc.token().value()).isEqualTo("at_2")
    }

    @Test
    fun `fake and client share one clock, so the client refreshes before the fake would reject the token`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(1.hours)

        assertThat(siwc.models.models().value().map { it.slug }).containsExactly(ChatGptFixtures.MODEL)

        assertThat(server.requests().filter { it.route == FakeRoute.MODELS }.map { it.status }).containsExactly(200)
        assertThat(server.refreshCount()).isEqualTo(1)
    }

    @Test
    fun `R06 9_4 refresh failure (terminal) row invalid_grant asks for a new sign-in and keeps the registration`() = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(ChatGptScenario.REFRESH_INVALID_GRANT)
        clock.advanceBy(1.hours)

        assertThat(siwc.token().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "refresh_rejected"))
        assertThat(vault().registration!!.tokens).isNull()
        assertThat(vault().registration!!.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.REFRESH_REJECTED))
    }

    @Test
    fun `R06 9_3 earliest_refresh_at given as an ISO-8601 instant is stored like epoch seconds`() = runTest {
        scenario(ChatGptScenario.EXCHANGE_EARLIEST_ISO)
        val signedInAt = clock.now()

        graph().connect()

        assertThat(vault().registration!!.tokens!!.earliestRefreshAtEpochMs).isEqualTo((signedInAt + 3000.seconds).toEpochMilliseconds())
    }

    @Test
    fun `R06 9_3 without earliest_refresh_at a 401 forces one refresh and one retry at once`() = runTest {
        scenario(ChatGptScenario.EXCHANGE_NO_EARLIEST)
        val siwc = graph()
        siwc.connect()
        assertThat(vault().registration!!.tokens!!.earliestRefreshAtEpochMs).isNull()
        val seen = mutableListOf<String>()

        val result = siwc.session.withAccessToken { token ->
            seen += token
            if (seen.size == 1) {
                SiwcResult.Failed(SiwcFailure(null, AppError.TokenExpired("chatgpt"), unauthorized = true))
            } else {
                SiwcResult.Ok(token)
            }
        }

        assertThat(result.value()).isEqualTo("at_2")
        assertThat(seen).containsExactly("at_1", "at_2").inOrder()
        assertThat(server.refreshCount()).isEqualTo(1)
    }

    @Test
    fun `an unreadable credential store is wiped and asks for a new sign-in`() = runTest {
        graph().connect()
        store.unreadable = true

        val snapshot = graph().session.start()

        assertThat(snapshot.status).isEqualTo(SiwcStatus(SiwcState.REAUTH_REQUIRED, SiwcReason.LOCAL_CREDENTIALS_UNREADABLE))
        assertThat(vault().registration).isNull()
    }

    companion object {
        @JvmStatic
        fun terminalCodes(): List<Arguments> =
            ChatGptFixtures.TERMINAL_REFRESH_CODES.flatMap { listOf(Arguments.of(it, "oauth"), Arguments.of(it, "object")) }
    }
}

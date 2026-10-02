package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.AppError
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/** Disconnect and revocation (docs/research/06 §2.12, §9.4 "Revocation scenarios"; red team privacy-ai-09). */
class DisconnectTest : SiwcFakeTest() {
    @Test
    fun `disconnect revokes the refresh token, clears the tokens and keeps the issued client id`() = runTest {
        val siwc = graph()
        siwc.connect()

        assertThat(siwc.signIn.disconnect()).isEqualTo(DisconnectOutcome.Disconnected)

        assertThat(server.revokedTokens()).containsExactly("rt_1")
        assertThat(server.liveRefreshTokens()).isEmpty()
        val registration = vault().registration!!
        assertThat(registration.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(registration.tokens).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus.DISCONNECTED)
        assertThat(siwc.session.snapshot.value.toProviderState()).isEqualTo(AiProviderState.Disconnected)
        assertThat(server.hygieneViolations()).isEmpty()

        val requests = server.requests().size
        val after = siwc.session.withAccessToken { SiwcResult.Ok(it) }
        assertThat(after.error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "not_connected"))
        assertThat(server.requests()).hasSize(requests)

        siwc.connect()
        assertThat(browser.launched.last().queryParameter("client_id")).isEqualTo(ChatGptFixtures.CLIENT_ID)
    }

    @Test
    fun `forgetting the registration removes the issued client id as well`() = runTest {
        val siwc = graph()
        siwc.connect()

        assertThat(siwc.signIn.disconnect(forgetRegistration = true)).isEqualTo(DisconnectOutcome.Disconnected)

        assertThat(vault().registration).isNull()
        assertThat(vault().pendingRegistration).isNull()
        siwc.connect()
        assertThat(browser.launched.last().queryParameter("client_id")).isEqualTo("dynamic_agent_client")
    }

    @Test
    fun `a failed revocation is retried once and reported as unconfirmed, and the tokens are cleared anyway`() = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(ChatGptScenario.REVOCATION_FAILURE)

        assertThat(siwc.signIn.disconnect()).isEqualTo(DisconnectOutcome.RevocationUnconfirmed)

        assertThat(calls(FakeRoute.REVOKE)).isEqualTo(2)
        assertThat(vault().registration!!.tokens).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus.DISCONNECTED)
        assertThat(DisconnectOutcome.RevocationUnconfirmed.MESSAGE).isEqualTo(
            "Local credentials were removed, but remote disconnection could not be confirmed. Disconnect the app in ChatGPT Settings.",
        )
    }

    @Test
    fun `without a discovered revocation endpoint the disconnect is unconfirmed and nothing is sent`() = runTest {
        scenario(ChatGptScenario.DISCOVERY_NO_REVOCATION)
        val siwc = graph()
        siwc.connect()

        assertThat(siwc.signIn.disconnect()).isEqualTo(DisconnectOutcome.RevocationUnconfirmed)

        assertThat(calls(FakeRoute.REVOKE)).isEqualTo(0)
        assertThat(vault().registration!!.tokens).isNull()
    }

    @Test
    fun `a disconnect cancels calls in flight`() = runTest {
        val siwc = graph()
        siwc.connect()
        val started = CompletableDeferred<Unit>()
        val call = async {
            siwc.session.withAccessToken<Unit> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        siwc.signIn.disconnect()

        assertThat(call.await().error()).isEqualTo(AppError.Cancelled("disconnected"))
    }

    @Test
    fun `a refresh in flight when the disconnect starts never writes tokens afterwards`() = runTest {
        val gated = GatedStore(store)
        val siwc = graph(credentialStore = gated)
        siwc.connect()
        clock.advanceBy(1.hours)
        gated.holdWhen { it.registration?.pendingRotation != null }
        val call = async { siwc.session.withAccessToken { SiwcResult.Ok(it) } }
        gated.reached.await()

        val disconnect = async { siwc.signIn.disconnect() }
        runCurrent()
        gated.release()

        assertThat(disconnect.await()).isEqualTo(DisconnectOutcome.Disconnected)
        assertThat(call.await().error()).isEqualTo(AppError.Cancelled("disconnected"))
        eventually("the refresh unit to finish") { server.liveRefreshTokens().isEmpty() }
        assertThat(server.revokedTokens()).contains("rt_2")
        assertThat(vault().registration!!.tokens).isNull()
        assertThat(vault().registration!!.pendingRotation).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus.DISCONNECTED)
        assertThat(siwc.session.snapshot.value.status).isEqualTo(SiwcStatus.DISCONNECTED)
    }

    @Test
    fun `disconnect before any sign-in is confirmed without a request`() = runTest {
        val siwc = graph()

        assertThat(siwc.signIn.disconnect()).isEqualTo(DisconnectOutcome.Disconnected)

        assertThat(server.requests()).isEmpty()
    }
}

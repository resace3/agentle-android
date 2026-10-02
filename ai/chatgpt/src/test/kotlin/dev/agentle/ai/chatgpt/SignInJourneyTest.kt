package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.fakes.browser.FakeBrowserLauncher
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.ConnectException
import java.net.Socket
import kotlin.time.Duration.Companion.minutes

/** Sign-in rows of docs/research/06 §9.4 and §8.4, the real authorizer and coordinator against the fake. */
class SignInJourneyTest : SiwcFakeTest() {
    @Test
    fun `first sign-in registers through the bootstrap client and stores the issued client id with its tokens`() = runTest {
        val siwc = graph()

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))
        val url = browser.launched.single()
        assertThat(url.queryParameter("client_id")).isEqualTo("dynamic_agent_client")
        assertThat(url.queryParameter("agent_name_hint")).isEqualTo("Agentle")
        assertThat(
            url.queryParameter("ext_agent_host_id"),
        ).matches("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        assertThat(url.queryParameter("resource")).isEqualTo("https://api.openai.com/v1")
        assertThat(url.queryParameter("scope")).isEqualTo("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
        assertThat(url.queryParameterNames).containsNoneOf("login_hint", "prompt", "id_token_hint", "force_reconsent")
        val registration = vault().registration!!
        assertThat(registration.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(registration.sub).isEqualTo(ChatGptFixtures.SUB)
        assertThat(registration.tokens!!.planUsageGranted).isTrue()
        assertThat(vault().pendingRegistration).isNull()
        assertThat(vault().signInMarker).isNull()
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
        assertThat(siwc.session.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.CONNECTED, accountLabel = ChatGptFixtures.EMAIL))
        assertThat(server.exchangeCount()).isEqualTo(1)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `re-authentication reuses the issued client id with login_hint and never sends the agent name again`() = runTest {
        val siwc = graph()
        siwc.connect()

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))
        val (first, second) = browser.launched
        assertThat(second.queryParameter("client_id")).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(second.queryParameter("login_hint")).isEqualTo(ChatGptFixtures.EMAIL)
        assertThat(second.queryParameter("agent_name_hint")).isNull()
        assertThat(second.queryParameter("ext_agent_host_id")).isEqualTo(first.queryParameter("ext_agent_host_id"))
        assertThat(second.queryParameter("state")).isNotEqualTo(first.queryParameter("state"))
        assertThat(second.queryParameter("nonce")).isNotEqualTo(first.queryParameter("nonce"))
        assertThat(vault().registration!!.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `declined plan scopes block inference locally until the user re-enables plan usage with prompt=consent`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.PLAN_SCOPE_DECLINED)

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.PlanUsageNotGranted)
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.NOT_ELIGIBLE, SiwcReason.PLAN_USAGE_NOT_GRANTED))
        val blocked = siwc.session.withAccessToken<Unit> { error("must not be called without the plan scopes") }
        assertThat(blocked.error()).isEqualTo(AppError.NotEligible("plan_usage_not_granted"))

        scenario(ChatGptScenario.HAPPY)
        val outcome = siwc.signIn.signIn(SignInRequest(enablePlanUsage = true))

        assertThat(outcome).isInstanceOf(SignInOutcome.Connected::class.java)
        assertThat(browser.launched.first().queryParameter("prompt")).isNull()
        assertThat(browser.launched.last().queryParameter("prompt")).isEqualTo("consent")
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `denied consent leaves everything as it was`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.CONSENT_DENIED)

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.NotCompleted)

        assertThat(store.snapshot()?.registration).isNull()
        assertThat(store.snapshot()?.pendingRegistration).isNull()
        assertThat(store.snapshot()?.signInMarker).isNull()
        assertThat(siwc.session.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED))
        assertThat(server.exchangeCount()).isEqualTo(0)
    }

    @Test
    fun `a tampered state keeps the attempt waiting and cancelling it ends the sign-in as not completed`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.INVALID_STATE)

        val attempt = async { siwc.signIn.signIn() }
        launches.receive()
        assertThat(browser.hopStatuses).containsExactly(400)
        assertThat(siwc.session.snapshot.value.connecting).isTrue()
        siwc.signIn.cancelSignIn()

        assertThat(attempt.await()).isEqualTo(SignInOutcome.NotCompleted)
        assertThat(siwc.session.snapshot.value.connecting).isFalse()
        assertThat(server.exchangeCount()).isEqualTo(0)
    }

    @Test
    fun `a repeated tap re-opens the live attempt and both callers get its outcome`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.INVALID_STATE)
        val first = async { siwc.signIn.signIn() }
        launches.receive()

        val second = async { siwc.signIn.signIn() }

        assertThat(first.await()).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))
        assertThat(second.await()).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))
        assertThat(browser.launched).hasSize(2)
        assertThat(browser.launched[1]).isEqualTo(browser.launched[0])
        assertThat(server.exchangeCount()).isEqualTo(1)
    }

    @Test
    fun `a tab closed without finishing times out on the shared clock as not completed`() = runTest {
        val siwc = graph()
        browser.behavior = FakeBrowserLauncher.Behavior.CLOSE_TAB
        val attempt = async { siwc.signIn.signIn() }
        launches.receive()

        clock.advanceBy(11.minutes)

        assertThat(attempt.await()).isEqualTo(SignInOutcome.NotCompleted)
        assertThat(vault().signInMarker).isNull()
    }

    @Test
    fun `no browser gives NO_BROWSER and releases the loopback port`() = runTest {
        val siwc = graph()
        browser.behavior = FakeBrowserLauncher.Behavior.NO_BROWSER

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.NoBrowser)

        val port = browser.launched.single().queryParameter("redirect_uri")!!.substringAfter("127.0.0.1:").substringBefore('/').toInt()
        val refused = runCatching { Socket("127.0.0.1", port).close() }.exceptionOrNull()
        assertThat(refused).isInstanceOf(ConnectException::class.java)
        assertThat(siwc.session.snapshot.value.connecting).isFalse()
        assertThat(vault().signInMarker).isNull()
        assertThat(calls(FakeRoute.AUTHORIZE)).isEqualTo(0)
    }

    @Test
    fun `a browser that cannot start is reported as a failure`() = runTest {
        val siwc = graph()
        browser.behavior = FakeBrowserLauncher.Behavior.FAIL

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.Failed(SiwcReason.NONE, AppError.UnsupportedFeature("browser")))
    }

    @Test
    fun `a callback without the issued client id is an incomplete registration`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.REGISTRATION_INCOMPLETE)

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.RegistrationIncomplete)
        assertThat(store.snapshot()?.pendingRegistration).isNull()
        assertThat(server.exchangeCount()).isEqualTo(0)
    }

    @Test
    fun `the issued client id is persisted before the code is redeemed and reused after a failed exchange`() = runTest {
        val siwc = graph()
        server.failNext(FakeRoute.TOKEN_EXCHANGE, 503, ChatGptFixtures.REFRESH_TEMPORARILY_UNAVAILABLE)

        val failed = siwc.signIn.signIn()

        assertThat(failed).isInstanceOf(SignInOutcome.Failed::class.java)
        assertThat((failed as SignInOutcome.Failed).reason).isEqualTo(SiwcReason.AUTH_SERVER)
        assertThat(vault().pendingRegistration?.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(vault().registration).isNull()

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))

        val retry = browser.launched.last()
        assertThat(retry.queryParameter("client_id")).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(retry.queryParameter("agent_name_hint")).isNull()
        assertThat(vault().pendingRegistration).isNull()
        assertThat(vault().registration!!.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `an invalid_grant at the exchange restarts the authorization once with the issued client id`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.EXCHANGE_INVALID_GRANT_ONCE)

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))

        assertThat(browser.launched.map { it.queryParameter("client_id") })
            .containsExactly("dynamic_agent_client", ChatGptFixtures.CLIENT_ID)
            .inOrder()
        assertThat(server.exchangeCount()).isEqualTo(2)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `a second invalid_grant ends the attempt without registering again`() = runTest {
        val siwc = graph()
        scenario(ChatGptScenario.EXCHANGE_INVALID_GRANT)

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(
            SignInOutcome.Failed(SiwcReason.NONE, AppError.AuthenticationRequired("chatgpt", "authorization_code_rejected")),
        )
        assertThat(browser.launched).hasSize(2)
        assertThat(vault().pendingRegistration?.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
    }

    @Test
    fun `invalid_client at the exchange drops the pending registration`() = runTest {
        val siwc = graph()
        server.failNext(FakeRoute.TOKEN_EXCHANGE, 401, ChatGptFixtures.TOKEN_INVALID_CLIENT)

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(
            SignInOutcome.Failed(SiwcReason.REGISTRATION_INVALID, AppError.AuthenticationRequired("chatgpt", "registration_invalid")),
        )
        assertThat(vault().pendingRegistration).isNull()
        assertThat(vault().registration).isNull()
    }

    @ParameterizedTest(name = "R06 9.2 JWKS outage: {0}")
    @EnumSource(names = ["JWKS_UNAVAILABLE", "JWKS_EMPTY", "JWKS_MALFORMED", "JWKS_UNKNOWN_KID"])
    fun `a JWKS outage at sign-in is retryable and stores no tokens`(outage: ChatGptScenario) = runTest {
        val siwc = graph()
        scenario(outage)

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.IdentityVerificationUnavailable)

        assertThat(vault().registration).isNull()
        assertThat(vault().pendingRegistration?.clientId).isEqualTo(ChatGptFixtures.CLIENT_ID)
        eventually("the unverified refresh token to be revoked") { server.revokedTokens().isNotEmpty() }
        assertThat(server.liveRefreshTokens()).isEmpty()
    }

    @Test
    fun `another account on re-authentication is refused, the saved account stays and the new tokens are revoked`() = runTest {
        var changes = 0
        val siwc = graph(accountChanges = { changes += 1 })
        siwc.connect()
        val saved = vault().registration!!.tokens
        scenario(ChatGptScenario.OTHER_ACCOUNT)

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.AccountMismatch)

        assertThat(vault().registration!!.sub).isEqualTo(ChatGptFixtures.SUB)
        assertThat(vault().registration!!.tokens).isEqualTo(saved)
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
        assertThat(changes).isEqualTo(0)
        eventually("the other account's refresh token to be revoked") { server.revokedTokens().isNotEmpty() }
        assertThat(server.revokedTokens()).doesNotContain(saved!!.refreshToken!!.value)
    }

    @Test
    fun `adding an account replaces the saved one only after the consent grants were invalidated`() = runTest {
        var changes = 0
        val siwc = graph(accountChanges = { changes += 1 })
        siwc.connect()
        scenario(ChatGptScenario.OTHER_ACCOUNT)

        val outcome = siwc.signIn.signIn(SignInRequest(addAccount = true))

        assertThat(outcome).isInstanceOf(SignInOutcome.Connected::class.java)
        assertThat(changes).isEqualTo(1)
        assertThat(vault().registration!!.sub).isEqualTo(ChatGptFixtures.OTHER_SUB)
        assertThat(browser.launched.last().queryParameter("client_id")).isEqualTo("dynamic_agent_client")
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `a failing credential store ends the attempt with a storage failure`() = runTest {
        val siwc = graph()
        store.failWrites = true

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(SignInOutcome.Failed(SiwcReason.STORAGE, AppError.DatabaseError("credential_write_failed")))
        assertThat(server.exchangeCount()).isEqualTo(0)
    }

    @Test
    fun `disconnecting during a live attempt cancels it`() = runTest {
        val siwc = graph()
        browser.behavior = FakeBrowserLauncher.Behavior.CLOSE_TAB
        val attempt = async { siwc.signIn.signIn() }
        launches.receive()

        assertThat(siwc.signIn.disconnect()).isEqualTo(DisconnectOutcome.Disconnected)

        assertThat(attempt.await()).isEqualTo(SignInOutcome.NotCompleted)
        assertThat(siwc.session.snapshot.value.toProviderState()).isEqualTo(dev.agentle.ai.api.AiProviderState.Disconnected)
    }
}

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
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The red team testing-build round 3 scenarios: a wrong device clock (testing-build-18), captive portals and TLS
 * interception on discovery, token exchange and refresh (tokens kept, never signed out), process death during sign-in
 * (testing-build-08), and one injected clock for fake and client (testing-build-19). The crash point between a refresh
 * answer and the vault write is in [RefreshRobustnessTest].
 */
class NetworkAndClockFaultTest : SiwcFakeTest() {
    @ParameterizedTest(name = "fake clock offset {0}: signs in")
    @ValueSource(strings = ["2m", "-2m", "4m"])
    fun `a device clock a few minutes off still signs in`(offset: String) = runTest {
        server.clockOffset = Duration.parse(offset)

        assertThat(graph().signIn.signIn()).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))
    }

    @ParameterizedTest(name = "fake clock offset {0}: DEVICE_CLOCK_WRONG")
    @ValueSource(strings = ["2h", "-2h", "6m"])
    fun `a device clock hours off fails sign-in with DEVICE_CLOCK_WRONG and stores nothing`(offset: String) = runTest {
        server.clockOffset = Duration.parse(offset)
        val siwc = graph()

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.DeviceClockWrong)

        assertThat(vault().registration).isNull()
        assertThat(siwc.session.snapshot.value.status).isEqualTo(SiwcStatus.DISCONNECTED)
    }

    @Test
    fun `a device clock that went wrong after sign-in keeps the rotated refresh token until it is fixed`() = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(ChatGptScenario.REFRESH_WITH_ID_TOKEN)
        clock.advanceBy(1.hours)
        server.clockOffset = 2.hours

        val failed = siwc.session.withAccessToken { SiwcResult.Ok(it) }

        assertThat(failed.error()).isEqualTo(AppError.Unexpected("device_clock_wrong"))
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.DEVICE_CLOCK_WRONG))
        assertThat(vault().registration!!.pendingRotation).isNotNull()
        assertThat(server.refreshCount()).isEqualTo(1)

        // The user sets the device clock right: device and server agree again, and the checkpointed ID token fits.
        server.clockOffset = Duration.ZERO
        clock.setWallClock(clock.now() + 2.hours)
        assertThat(siwc.session.withAccessToken { SiwcResult.Ok(it) }.value()).isEqualTo("at_2")
        assertThat(server.refreshCount()).isEqualTo(1)
    }

    @ParameterizedTest(name = "captive portal on {0}")
    @MethodSource("signInRoutes")
    fun `a captive portal during re-authentication fails the attempt and keeps the saved tokens`(route: FakeRoute, ignored: String) =
        runTest {
            graph().connect()
            val saved = vault().registration!!.tokens
            val siwc = graph()
            server.failNext(route, 200, PORTAL_PAGE, contentType = "text/html")

            val outcome = siwc.signIn.signIn()

            assertThat(outcome).isEqualTo(SignInOutcome.Failed(SiwcReason.CAPTIVE_PORTAL, AppError.NetworkUnavailable("captive_portal")))
            assertThat(vault().registration!!.tokens).isEqualTo(saved)
            assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
        }

    @ParameterizedTest(name = "TLS interception on {0}")
    @MethodSource("signInRoutes")
    fun `TLS interception during re-authentication fails the attempt and keeps the saved tokens (simulated)`(
        route: FakeRoute,
        pathSuffix: String,
    ) = runTest {
        graph().connect()
        val saved = vault().registration!!.tokens
        val tls = TlsInterception()
        val siwc = graph(http = { it.withAuthInterceptor(tls) })
        tls.failHandshakes(pathSuffix)
        val before = server.requests().size

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(SignInOutcome.Failed(SiwcReason.TLS_FAILURE, AppError.NetworkUnavailable("tls")))
        assertThat(vault().registration!!.tokens).isEqualTo(saved)
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
        assertThat(server.requests().drop(before).none { it.route == route }).isTrue()
    }

    @Test
    fun `a captive portal on the API keeps the tokens and reports the network as unavailable`() = runTest {
        val siwc = graph()
        siwc.connect()
        server.failNext(FakeRoute.MODELS, 200, PORTAL_PAGE, contentType = "text/html")

        assertThat(siwc.models.models().error()).isEqualTo(AppError.NetworkUnavailable("captive_portal"))

        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.CAPTIVE_PORTAL))
        assertThat(vault().registration!!.tokens).isNotNull()
    }

    @Test
    fun `TLS interception on the API keeps the tokens and reports the network as unavailable (simulated)`() = runTest {
        val tls = TlsInterception()
        val siwc = graph(http = { it.withApiInterceptor(tls) })
        siwc.connect()
        tls.failHandshakes("/responses")

        val result = siwc.provider.analyze(envelope())

        assertThat(result.error()).isEqualTo(AppError.NetworkUnavailable("tls"))
        assertThat(vault().status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.TLS_FAILURE))
        assertThat(vault().registration!!.tokens).isNotNull()
    }

    @Test
    fun `process death during sign-in is detected once on the next start and explained as INTERRUPTED`() = runTest {
        browser.behavior = FakeBrowserLauncher.Behavior.CLOSE_TAB
        val dying = graph()
        val attempt = async { dying.signIn.signIn() }
        launches.receive()
        assertThat(vault().signInMarker?.firstRegistration).isTrue()

        // A new process over the same persisted vault: no live attempt, but the marker survived.
        val restarted = graph()

        assertThat(restarted.signIn.recoverInterruptedSignIn()).isEqualTo(SignInOutcome.Interrupted(firstRegistration = true))
        assertThat(restarted.signIn.recoverInterruptedSignIn()).isNull()
        assertThat(vault().signInMarker).isNull()

        dying.signIn.cancelSignIn()
        assertThat(attempt.await()).isEqualTo(SignInOutcome.NotCompleted)
    }

    @Test
    fun `a live attempt is not reported as interrupted`() = runTest {
        browser.behavior = FakeBrowserLauncher.Behavior.CLOSE_TAB
        val siwc = graph()
        val attempt = async { siwc.signIn.signIn() }
        launches.receive()

        assertThat(siwc.signIn.recoverInterruptedSignIn()).isNull()

        siwc.signIn.cancelSignIn()
        assertThat(attempt.await()).isEqualTo(SignInOutcome.NotCompleted)
    }

    @Test
    fun `an interrupted re-authentication is not a first registration`() = runTest {
        graph().connect()
        launches.receive() // the first sign-in's own browser launch
        browser.behavior = FakeBrowserLauncher.Behavior.CLOSE_TAB
        val dying = graph()
        val attempt = async { dying.signIn.signIn() }
        launches.receive()

        assertThat(graph().signIn.recoverInterruptedSignIn()).isEqualTo(SignInOutcome.Interrupted(firstRegistration = false))

        dying.signIn.cancelSignIn()
        attempt.await()
    }

    companion object {
        private const val PORTAL_PAGE = "<html><head><title>Wi-Fi login</title></head><body>Accept the terms</body></html>"

        @JvmStatic
        fun signInRoutes(): List<Arguments> = listOf(
            Arguments.of(FakeRoute.DISCOVERY, "/openid-configuration"),
            Arguments.of(FakeRoute.TOKEN_EXCHANGE, "/oauth/token"),
        )
    }
}

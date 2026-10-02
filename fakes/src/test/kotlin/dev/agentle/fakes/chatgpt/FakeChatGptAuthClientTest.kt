package dev.agentle.fakes.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.chatgpt.DisconnectOutcome
import dev.agentle.ai.chatgpt.SignInOutcome
import dev.agentle.ai.chatgpt.SignInRequest
import dev.agentle.ai.chatgpt.SiwcReason
import dev.agentle.ai.chatgpt.SiwcSnapshot
import dev.agentle.ai.chatgpt.SiwcState
import dev.agentle.ai.chatgpt.SiwcStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** [FakeChatGptAuthClient]: the scripted port for screen and view-model unit tests (red team oauth-security-14). */
class FakeChatGptAuthClientTest {
    @Test
    fun `scripted outcomes come first, then the default, and only connecting outcomes change the snapshot`() = runTest {
        val client = FakeChatGptAuthClient()
        client.enqueue(SignInOutcome.NotCompleted, SignInOutcome.PlanUsageNotGranted)

        assertThat(client.signIn(SignInRequest())).isEqualTo(SignInOutcome.NotCompleted)
        assertThat(client.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED))
        assertThat(client.signIn(SignInRequest(enablePlanUsage = true))).isEqualTo(SignInOutcome.PlanUsageNotGranted)
        assertThat(client.snapshot.value.status).isEqualTo(SiwcStatus(SiwcState.NOT_ELIGIBLE, SiwcReason.PLAN_USAGE_NOT_GRANTED))
        assertThat(client.signIn(SignInRequest(addAccount = true))).isEqualTo(SignInOutcome.Connected(ChatGptFixtures.EMAIL))
        assertThat(client.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.CONNECTED, accountLabel = ChatGptFixtures.EMAIL))
        assertThat(client.signInRequests)
            .containsExactly(SignInRequest(), SignInRequest(enablePlanUsage = true), SignInRequest(addAccount = true))
            .inOrder()
    }

    @Test
    fun `a changed default outcome is returned until the script has entries again`() = runTest {
        val client = FakeChatGptAuthClient()
        client.defaultOutcome = SignInOutcome.NoBrowser

        assertThat(client.signIn(SignInRequest())).isEqualTo(SignInOutcome.NoBrowser)
        client.enqueue(SignInOutcome.DeviceClockWrong)
        assertThat(client.signIn(SignInRequest())).isEqualTo(SignInOutcome.DeviceClockWrong)
        assertThat(client.signIn(SignInRequest())).isEqualTo(SignInOutcome.NoBrowser)
        assertThat(client.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED))
    }

    @Test
    fun `disconnect keeps the account label unless the registration is forgotten`() = runTest {
        val client = FakeChatGptAuthClient(SiwcSnapshot(SiwcStatus.CONNECTED, accountLabel = "a@example.invalid"))
        client.disconnectOutcome = DisconnectOutcome.RevocationUnconfirmed

        assertThat(client.disconnect(forgetRegistration = false)).isEqualTo(DisconnectOutcome.RevocationUnconfirmed)
        assertThat(client.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED, accountLabel = "a@example.invalid"))
        client.disconnect(forgetRegistration = true)
        assertThat(client.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED))
        assertThat(client.disconnectRequests).containsExactly(false, true).inOrder()
    }

    @Test
    fun `an interrupted attempt is reported once, cancels are counted and emitted states are published`() = runTest {
        val client = FakeChatGptAuthClient()
        client.interrupted = SignInOutcome.Interrupted(firstRegistration = true)

        assertThat(client.recoverInterruptedSignIn()).isEqualTo(SignInOutcome.Interrupted(firstRegistration = true))
        assertThat(client.recoverInterruptedSignIn()).isNull()
        client.cancelSignIn()
        client.cancelSignIn()
        assertThat(client.cancelCount).isEqualTo(2)
        client.emit(SiwcSnapshot(SiwcStatus.DISCONNECTED, connecting = true))
        assertThat(client.snapshot.value.connecting).isTrue()
    }
}

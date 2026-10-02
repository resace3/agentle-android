package dev.agentle.fakes.chatgpt

import dev.agentle.ai.chatgpt.ChatGptAuthClient
import dev.agentle.ai.chatgpt.DisconnectOutcome
import dev.agentle.ai.chatgpt.SignInOutcome
import dev.agentle.ai.chatgpt.SignInRequest
import dev.agentle.ai.chatgpt.SiwcReason
import dev.agentle.ai.chatgpt.SiwcSnapshot
import dev.agentle.ai.chatgpt.SiwcState
import dev.agentle.ai.chatgpt.SiwcStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A scripted [ChatGptAuthClient] for unit tests of screens and view models only (red team oauth-security-14). It
 * implements the port and nothing else: no HTTP, no wire format. Journeys and the fake flavor run the real
 * `SignInCoordinator` against [FakeChatGptServer] with `FakeBrowserLauncher` instead.
 *
 * Sign-in outcomes come from [enqueue] in order, then [defaultOutcome]; a [SignInOutcome.Connected] or
 * [SignInOutcome.PlanUsageNotGranted] updates [snapshot] the way the real coordinator does, every other outcome leaves
 * it unchanged.
 */
public class FakeChatGptAuthClient(initial: SiwcSnapshot = SiwcSnapshot(SiwcStatus.DISCONNECTED)) : ChatGptAuthClient {
    private val state = MutableStateFlow(initial)
    private val lock = Any()
    private val scripted = ArrayDeque<SignInOutcome>()
    private val requests = ArrayList<SignInRequest>()
    private val disconnects = ArrayList<Boolean>()

    override val snapshot: StateFlow<SiwcSnapshot> = state.asStateFlow()

    /** The outcome once the script is empty. */
    @Volatile public var defaultOutcome: SignInOutcome = SignInOutcome.Connected(ChatGptFixtures.EMAIL)

    @Volatile public var disconnectOutcome: DisconnectOutcome = DisconnectOutcome.Disconnected

    /** Returned once by [recoverInterruptedSignIn] (a marker that survived process death), then cleared. */
    @Volatile public var interrupted: SignInOutcome.Interrupted? = null

    @Volatile public var cancelCount: Int = 0
        private set

    /** Every [signIn] request, in order. */
    public val signInRequests: List<SignInRequest> get() = synchronized(lock) { requests.toList() }

    /** The `forgetRegistration` flag of every [disconnect], in order. */
    public val disconnectRequests: List<Boolean> get() = synchronized(lock) { disconnects.toList() }

    public fun enqueue(vararg outcomes: SignInOutcome) {
        synchronized(lock) { scripted.addAll(outcomes) }
    }

    /** Pushes a state as if the session manager had published it. */
    public fun emit(snapshot: SiwcSnapshot) {
        state.value = snapshot
    }

    override suspend fun signIn(request: SignInRequest): SignInOutcome {
        val outcome = synchronized(lock) {
            requests += request
            scripted.removeFirstOrNull() ?: defaultOutcome
        }
        when (outcome) {
            is SignInOutcome.Connected ->
                state.update { it.copy(status = SiwcStatus.CONNECTED, connecting = false, accountLabel = outcome.accountLabel) }

            SignInOutcome.PlanUsageNotGranted ->
                state.update { it.copy(status = SiwcStatus(SiwcState.NOT_ELIGIBLE, SiwcReason.PLAN_USAGE_NOT_GRANTED), connecting = false) }

            else -> Unit
        }
        return outcome
    }

    override fun cancelSignIn() {
        cancelCount += 1
    }

    override suspend fun recoverInterruptedSignIn(): SignInOutcome? = interrupted.also { interrupted = null }

    override suspend fun disconnect(forgetRegistration: Boolean): DisconnectOutcome {
        synchronized(lock) { disconnects += forgetRegistration }
        state.update { SiwcSnapshot(SiwcStatus.DISCONNECTED, accountLabel = it.accountLabel.takeUnless { forgetRegistration }) }
        return disconnectOutcome
    }
}

package dev.agentle.feature.connections.testing

import dev.agentle.ai.api.AiProviderState
import dev.agentle.core.common.Outcome
import dev.agentle.feature.connections.port.ChatGptConnectRequest
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptConnectionPort
import dev.agentle.feature.connections.port.ChatGptConnectionState
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import dev.agentle.feature.connections.port.PlanUsageAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * A scripted [ChatGptConnectionPort] that behaves like Sign in with ChatGPT: [connect] marks the attempt as waiting for
 * the browser until the test answers it ([answer], [cancelConnect]) or, unless [holdSignIn] is set, returns
 * [connectResult] at once. Concurrent calls share the live attempt (single flight).
 */
internal class FakeChatGptPort(initial: ChatGptConnectionState = ConnectionsFixtures.chatGptDisconnected()) :
    ChatGptConnectionPort {
    val current = MutableStateFlow(initial)

    var failState: Boolean = false

    override val state: Flow<ChatGptConnectionState> = flow {
        check(!failState) { "scripted state failure" }
        emitAll(current)
    }

    /** When true, [connect] waits for [answer] or [cancelConnect]: the browser is open. */
    var holdSignIn: Boolean = false
    var connectResult: ChatGptConnectResult = ChatGptConnectResult.Connected(ConnectionsFixtures.CHATGPT_ACCOUNT)

    /** The state a successful sign-in leaves. */
    var connectedState: ChatGptConnectionState = ConnectionsFixtures.chatGptConnected(noticeAcknowledged = false)
    var interrupted: ChatGptConnectResult.Interrupted? = null
    var acknowledgeOutcome: Outcome<Unit> = Outcome.Success(Unit)
    var disconnectResult: ChatGptDisconnectResult = ChatGptDisconnectResult.Disconnected

    /** When false, a disconnect does not change [current] (a port that is slow to show it). */
    var showDisconnect: Boolean = true

    val connectRequests = mutableListOf<ChatGptConnectRequest>()
    val disconnectCalls = mutableListOf<Boolean>()
    var cancelCalls: Int = 0
        private set
    var acknowledgeCalls: Int = 0
        private set

    private var attempt: CompletableDeferred<ChatGptConnectResult>? = null

    override suspend fun connect(request: ChatGptConnectRequest): ChatGptConnectResult {
        connectRequests += request
        val live = attempt ?: CompletableDeferred<ChatGptConnectResult>().also { deferred ->
            attempt = deferred
            current.update { it.copy(signInInProgress = true) }
            if (!holdSignIn) deferred.complete(connectResult)
        }
        val result = live.await()
        if (attempt === live) {
            attempt = null
            current.update { it.copy(signInInProgress = false) }
            when (result) {
                is ChatGptConnectResult.Connected -> current.value = connectedState
                ChatGptConnectResult.PlanUsageNotGranted -> current.value = connectedState.copy(
                    provider = AiProviderState.NotEligible("plan_usage_not_granted"),
                    planUsage = PlanUsageAvailability.NotGranted,
                )
                else -> Unit
            }
        }
        return result
    }

    /** The browser answers the live attempt. */
    fun answer(result: ChatGptConnectResult) {
        checkNotNull(attempt) { "no sign-in is waiting for the browser" }.complete(result)
    }

    override fun cancelConnect() {
        cancelCalls++
        attempt?.complete(ChatGptConnectResult.Cancelled)
    }

    override suspend fun takeInterruptedConnect(): ChatGptConnectResult.Interrupted? = interrupted.also { interrupted = null }

    override suspend fun acknowledgePlanNotice(): Outcome<Unit> {
        acknowledgeCalls++
        if (acknowledgeOutcome is Outcome.Success) current.update { it.copy(planNoticeAcknowledged = true) }
        return acknowledgeOutcome
    }

    override suspend fun disconnect(forgetRegistration: Boolean): ChatGptDisconnectResult {
        disconnectCalls += forgetRegistration
        val result = disconnectResult
        if (result !is ChatGptDisconnectResult.Failed && showDisconnect) {
            current.value = ConnectionsFixtures.chatGptDisconnected()
        }
        return result
    }
}

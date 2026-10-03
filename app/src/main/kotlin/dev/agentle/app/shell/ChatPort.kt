package dev.agentle.app.shell

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** One line of the chat. */
data class ChatMessage(val fromUser: Boolean, val text: String)

/** ChatGPT's answer; [dashboard] is set when the user asked for a dashboard and the answer validated. */
data class ChatReply(val text: String, val dashboard: DashboardSpec? = null)

/** The main chat tab's link to ChatGPT. */
interface ChatPort {
    /** True once Sign in with ChatGPT is connected. */
    val connected: Flow<Boolean>

    suspend fun send(history: List<ChatMessage>): Outcome<ChatReply>
}

/** This draft has no Sign in with ChatGPT yet, so the chat says so and offers the connection screen. */
class UnavailableChatPort @Inject constructor() : ChatPort {
    override val connected: Flow<Boolean> = flowOf(false)

    override suspend fun send(history: List<ChatMessage>): Outcome<ChatReply> = Outcome.failure(AppError.UnsupportedFeature("chatgpt"))
}

package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The chat tab: sends the conversation to ChatGPT and saves any screen it makes to the sidebar. While [editing] is set
 * (from "Change with ChatGPT" or an answer's "Change it"), questions ask to change that dashboard and its new screen
 * replaces it.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(private val chat: ChatPort, private val dashboards: DashboardStore) : ViewModel() {
    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val mutableSending = MutableStateFlow(false)

    val messages: StateFlow<List<ChatMessage>> = mutableMessages.asStateFlow()
    val sending: StateFlow<Boolean> = mutableSending.asStateFlow()
    val connected: StateFlow<Boolean> = chat.connected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    val sharingAllowed: StateFlow<Boolean> = chat.sharingAllowed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    /** The dashboard the chat is changing, or null. */
    val editing: StateFlow<DashboardSpec?> =
        combine(dashboards.editing, dashboards.dashboards) { id, all -> all.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** Questions from now on change the dashboard [id]. */
    fun change(id: String) = dashboards.edit(id)

    /** Questions from now on make new screens. */
    fun stopChanging() = dashboards.edit(null)

    fun allowSharing() {
        viewModelScope.launch { chat.allowSharing() }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || mutableSending.value) return
        mutableMessages.update { it + ChatMessage(fromUser = true, text = trimmed) }
        mutableSending.value = true
        // "Make a new dashboard" while changing one starts a new screen instead.
        if (NEW_SCREEN.containsMatchIn(trimmed)) dashboards.edit(null)
        val target = dashboards.editing.value?.let { id -> dashboards.dashboards.value.firstOrNull { it.id == id } }
        viewModelScope.launch {
            val answer = try {
                when (val outcome = chat.send(mutableMessages.value, target)) {
                    is Outcome.Success -> {
                        outcome.value.dashboard?.let(dashboards::add)
                        ChatMessage(fromUser = false, text = outcome.value.text, dashboard = outcome.value.dashboard)
                    }

                    is Outcome.Failure -> failure(outcome.error)
                }
            } catch (e: CancellationException) {
                mutableSending.value = false
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                unexpected(e)
            } catch (e: LinkageError) {
                // A class that failed to load (say, a pattern Android's regex engine rejects) must not kill the app either.
                unexpected(e)
            }
            mutableMessages.update { it + answer }
            mutableSending.value = false
        }
    }

    /** What went wrong in words, with the error code for support. */
    private fun failure(error: AppError): ChatMessage = answer(
        when (error) {
            is AppError.RateLimited -> error.retryAfter?.let { wait ->
                "ChatGPT can't take a question right now. Try again in ${wait.inWholeMinutes + 1} min (${error.code})."
            } ?: "ChatGPT can't take a question right now. Try again soon (${error.code})."

            is AppError.NetworkUnavailable -> "Couldn't reach ChatGPT. Check the phone's connection and try again (${error.code})."

            is AppError.AuthenticationRequired, is AppError.TokenExpired ->
                "Sign in to ChatGPT again under More, ChatGPT connection (${error.code})."

            is AppError.ValidationError ->
                "ChatGPT's answer didn't pass Agentle's checks, so nothing was saved. Try asking in other words " +
                    "(${error.codes.joinToString(", ")})."

            else -> "ChatGPT couldn't answer (${error.code})."
        },
    )

    /** A failure no layer turned into an [AppError]: the app keeps running and says so. */
    private fun unexpected(e: Throwable) = answer("ChatGPT couldn't answer (unexpected_${e::class.simpleName}).")

    private fun answer(text: String) = ChatMessage(fromUser = false, text = text)

    private companion object {
        const val STOP_MS = 5_000L
        val NEW_SCREEN = Regex("\\b(new|another)\\s+(\\w+\\s+)?(dashboard|screen)", RegexOption.IGNORE_CASE)
    }
}

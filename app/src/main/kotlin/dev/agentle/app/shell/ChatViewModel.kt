package dev.agentle.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The chat tab: sends the conversation to ChatGPT and saves any dashboard it makes to the sidebar. */
@HiltViewModel
class ChatViewModel @Inject constructor(private val chat: ChatPort, private val dashboards: DashboardStore) : ViewModel() {
    private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val mutableSending = MutableStateFlow(false)

    val messages: StateFlow<List<ChatMessage>> = mutableMessages.asStateFlow()
    val sending: StateFlow<Boolean> = mutableSending.asStateFlow()
    val connected: StateFlow<Boolean> = chat.connected.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || mutableSending.value) return
        mutableMessages.update { it + ChatMessage(fromUser = true, text = trimmed) }
        mutableSending.value = true
        viewModelScope.launch {
            val answer = when (val outcome = chat.send(mutableMessages.value)) {
                is Outcome.Success -> {
                    outcome.value.dashboard?.let(dashboards::add)
                    outcome.value.text
                }

                is Outcome.Failure -> "ChatGPT couldn't answer (${outcome.error::class.simpleName})."
            }
            mutableMessages.update { it + ChatMessage(fromUser = false, text = answer) }
            mutableSending.value = false
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

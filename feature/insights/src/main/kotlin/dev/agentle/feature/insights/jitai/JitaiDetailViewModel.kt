package dev.agentle.feature.insights.jitai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.port.DeliveryRecord
import dev.agentle.feature.insights.port.JitaiListPort
import dev.agentle.feature.insights.port.JitaiOverview
import dev.agentle.jitai.dsl.render.RuleRenderer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One rule (`AppRoute.JitaiDetail`): its sentence, counters, actions, content and recent decisions. */
@HiltViewModel(assistedFactory = JitaiDetailViewModel.Factory::class)
internal class JitaiDetailViewModel @AssistedInject constructor(
    @Assisted private val jitaiId: String,
    private val port: JitaiListPort,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val confirmingDisable = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)

    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val content: Flow<Load<JitaiDetailContent?>> = reload.flatMapLatest {
        combine(port.jitais(), port.history()) { overview, history -> detailOf(overview, history) }.asLoad()
    }

    val state: StateFlow<JitaiDetailUiState> = combine(content, confirmingDisable, busy) { load, confirming, working ->
        JitaiDetailUiState(
            load = when (load) {
                is Load.Loaded -> load.value?.let { Load.Loaded(it) } ?: Load.Error(LoadError(LoadError.NOT_FOUND))
                is Load.Error -> load
                Load.Loading -> Load.Loading
            },
            confirmingDisable = confirming,
            busy = working,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(InsightListViewModel.STOP_TIMEOUT_MILLIS), JitaiDetailUiState())

    fun retry() {
        reload.update { it + 1 }
    }

    fun pause() = act { port.pause(jitaiId).message(UserMessage.PAUSED) }

    fun resume() = act { port.resume(jitaiId).message(UserMessage.RESUMED, resume = true) }

    fun requestDisable() {
        confirmingDisable.value = true
    }

    fun dismissDisable() {
        confirmingDisable.value = false
    }

    fun confirmDisable() {
        confirmingDisable.value = false
        act(leaveOnSuccess = true) { port.disable(jitaiId).message(UserMessage.DISABLED) }
    }

    fun edit() {
        effectChannel.trySend(ScreenEffect.Navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.MANUAL, editJitaiId = jitaiId)))
    }

    fun openDecision(decisionKey: String) {
        effectChannel.trySend(ScreenEffect.Navigate(AppRoute.InterventionDetail(decisionKey)))
    }

    private fun act(leaveOnSuccess: Boolean = false, action: suspend () -> UserMessage) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val message = action()
                effectChannel.send(ScreenEffect.Message(message))
                if (leaveOnSuccess && message == UserMessage.DISABLED) effectChannel.send(ScreenEffect.Back)
            } finally {
                busy.value = false
            }
        }
    }

    private fun detailOf(overview: JitaiOverview, history: List<DeliveryRecord>): JitaiDetailContent? {
        val summary = overview.rules.firstOrNull { it.definition.id == jitaiId } ?: return null
        val definition = summary.definition
        val content = RuleRenderer.content(definition, overview.renderOptions).map { it.text }
        return JitaiDetailContent(
            card = summary.toCard(overview.renderOptions),
            description = definition.description,
            content = content.toImmutableList(),
            history = HistoryContent(
                records = history.filter { it.jitaiId == jitaiId }.take(RECENT_DECISIONS).toImmutableList(),
                zone = overview.renderOptions.zone,
                use24HourClock = overview.renderOptions.use24HourClock,
            ),
            notificationsAllowed = overview.notificationsAllowed,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(jitaiId: String): JitaiDetailViewModel
    }

    private companion object {
        const val RECENT_DECISIONS = 20
    }
}

internal data class JitaiDetailUiState(
    val load: Load<JitaiDetailContent> = Load.Loading,
    val confirmingDisable: Boolean = false,
    val busy: Boolean = false,
)

/**
 * @property content the rule's message texts as written by the user (placeholders shown as written).
 * @property history the rule's latest decisions with the zone to show them in.
 */
internal data class JitaiDetailContent(
    val card: RuleCard,
    val description: String,
    val content: ImmutableList<String>,
    val history: HistoryContent,
    val notificationsAllowed: Boolean,
)

package dev.agentle.feature.insights.jitai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.port.DeliveryRecord
import dev.agentle.feature.insights.port.JitaiListPort
import dev.agentle.feature.insights.port.JitaiOverview
import dev.agentle.feature.insights.port.SuggestedJitai
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
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
import kotlinx.datetime.TimeZone

/**
 * The JITAI list with tabs Active, Suggested, Paused and History (spec §22). Active and Paused show the renderer's
 * sentence, the channel and today's deliveries against the caps with pause, resume, disable and edit; Suggested lists
 * AI-discovered proposals; History lists deliveries and suppressions and opens the intervention detail.
 */
@HiltViewModel(assistedFactory = JitaiListViewModel.Factory::class)
internal class JitaiListViewModel @AssistedInject constructor(@Assisted initialTab: JitaiTab, private val port: JitaiListPort) :
    ViewModel() {
    private val tab = MutableStateFlow(initialTab)
    private val reload = MutableStateFlow(0)
    private val pendingDisable = MutableStateFlow<PendingDisable?>(null)
    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)

    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val overview: Flow<Load<JitaiOverview>> = reload.flatMapLatest { port.jitais().asLoad() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val suggestions: Flow<Load<List<SuggestedJitai>>> = reload.flatMapLatest { port.suggestions().asLoad() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val history: Flow<Load<List<DeliveryRecord>>> = reload.flatMapLatest { port.history().asLoad() }

    private val flags: Flow<Pair<PendingDisable?, Set<String>>> = combine(pendingDisable, busy) { pending, ids -> pending to ids }

    val state: StateFlow<JitaiListUiState> = combine(
        tab,
        overview,
        suggestions,
        history,
        flags,
    ) { selected, rules, proposals, decisions, ui ->
        JitaiListUiState(
            tab = selected,
            rules = rules.rules(),
            suggestions = proposals.map { it.toImmutableList() },
            history = historyOf(rules, decisions),
            pendingDisable = ui.first,
            busyIds = ui.second.toImmutableSet(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(InsightListViewModel.STOP_TIMEOUT_MILLIS),
        JitaiListUiState(initialTab),
    )

    fun selectTab(selected: JitaiTab) {
        tab.value = selected
    }

    fun retry() {
        reload.update { it + 1 }
    }

    fun pause(jitaiId: String) = act(jitaiId) { port.pause(jitaiId).message(UserMessage.PAUSED) }

    fun resume(jitaiId: String) = act(jitaiId) { port.resume(jitaiId).message(UserMessage.RESUMED, resume = true) }

    /** Asks for confirmation before turning a rule off for good. */
    fun requestDisable(jitaiId: String, name: String) {
        pendingDisable.value = PendingDisable(jitaiId, name)
    }

    fun dismissDisable() {
        pendingDisable.value = null
    }

    fun confirmDisable() {
        val pending = pendingDisable.value ?: return
        pendingDisable.value = null
        act(pending.jitaiId) { port.disable(pending.jitaiId).message(UserMessage.DISABLED) }
    }

    fun edit(jitaiId: String) = navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.MANUAL, editJitaiId = jitaiId))

    fun open(jitaiId: String) = navigate(AppRoute.JitaiDetail(jitaiId))

    fun openSuggestion(proposalId: String) = navigate(AppRoute.ProposalReview(proposalId))

    fun openDecision(decisionKey: String) = navigate(AppRoute.InterventionDetail(decisionKey))

    fun create(mode: JitaiBuilderMode) = navigate(AppRoute.JitaiBuilder(mode))

    fun fixNotifications() = navigate(AppRoute.NotificationSettings)

    private fun navigate(route: AppRoute) {
        effectChannel.trySend(ScreenEffect.Navigate(route))
    }

    private fun act(jitaiId: String, action: suspend () -> UserMessage) {
        if (jitaiId in busy.value) return
        busy.update { it + jitaiId }
        viewModelScope.launch {
            try {
                effectChannel.send(ScreenEffect.Message(action()))
            } finally {
                busy.update { it - jitaiId }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(initialTab: JitaiTab): JitaiListViewModel
    }
}

internal data class JitaiListUiState(
    val tab: JitaiTab,
    val rules: Load<RuleLists> = Load.Loading,
    val suggestions: Load<ImmutableList<SuggestedJitai>> = Load.Loading,
    val history: Load<HistoryContent> = Load.Loading,
    val pendingDisable: PendingDisable? = null,
    val busyIds: ImmutableSet<String> = persistentSetOf(),
)

/**
 * The rules of the Active and Paused tabs. [notificationsAllowed] false shows the fix; [deliveredToday] counts all
 * rules against [globalMaxPerDay].
 */
internal data class RuleLists(
    val active: ImmutableList<RuleCard>,
    val paused: ImmutableList<RuleCard>,
    val notificationsAllowed: Boolean,
    val deliveredToday: Int,
    val globalMaxPerDay: Int,
)

/** History rows with the zone and clock format to show their times in. */
internal data class HistoryContent(val records: ImmutableList<DeliveryRecord>, val zone: TimeZone, val use24HourClock: Boolean)

internal data class PendingDisable(val jitaiId: String, val name: String)

private fun Load<JitaiOverview>.rules(): Load<RuleLists> = map { overview ->
    val cards = overview.rules.map { it.toCard(overview.renderOptions) }
    RuleLists(
        active = cards.filter { it.status in ACTIVE_STATUSES }.toImmutableList(),
        paused = cards.filter { it.status in PAUSED_STATUSES }.toImmutableList(),
        notificationsAllowed = overview.notificationsAllowed,
        deliveredToday = overview.deliveredToday,
        globalMaxPerDay = overview.globalMaxPerDay,
    )
}

private fun historyOf(overview: Load<JitaiOverview>, history: Load<List<DeliveryRecord>>): Load<HistoryContent> = when {
    history is Load.Error -> history

    overview is Load.Error -> overview

    history is Load.Loaded && overview is Load.Loaded -> Load.Loaded(
        HistoryContent(
            records = history.value.toImmutableList(),
            zone = overview.value.renderOptions.zone,
            use24HourClock = overview.value.renderOptions.use24HourClock,
        ),
    )

    else -> Load.Loading
}

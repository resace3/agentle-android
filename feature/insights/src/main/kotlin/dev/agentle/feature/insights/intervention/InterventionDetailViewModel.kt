package dev.agentle.feature.insights.intervention

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.jitai.Load
import dev.agentle.feature.insights.jitai.asLoad
import dev.agentle.feature.insights.port.DeliveredContent
import dev.agentle.feature.insights.port.DeliveryResult
import dev.agentle.feature.insights.port.Feedback
import dev.agentle.feature.insights.port.InterventionDetail
import dev.agentle.feature.insights.port.InterventionPort
import dev.agentle.feature.insights.port.OutcomeReason
import dev.agentle.feature.insights.port.TraceResult
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.rule.Condition
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime

/**
 * One decision (`AppRoute.InterventionDetail`, the notification's deep-link target): what was suggested and when, why
 * (the trace's conditions with what Agentle saw), the outcome with its reason, and "helpful / not helpful" feedback.
 * Showing the content of a decision that waits as an in-app card counts as displaying that card.
 */
@HiltViewModel(assistedFactory = InterventionDetailViewModel.Factory::class)
internal class InterventionDetailViewModel @AssistedInject constructor(
    @Assisted private val decisionKey: String,
    private val port: InterventionPort,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val sending = MutableStateFlow(false)
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)
    private var cardMarked = false

    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val detail: Flow<Load<InterventionDetail?>> = reload.flatMapLatest {
        port.intervention(decisionKey).onEach(::markCardShown).asLoad()
    }

    val state: StateFlow<InterventionDetailUiState> = combine(detail, sending) { load, busy ->
        InterventionDetailUiState(
            load = when (load) {
                Load.Loading -> Load.Loading
                is Load.Error -> load
                is Load.Loaded -> load.value?.let { Load.Loaded(it.toContent()) } ?: Load.Error(LoadError(LoadError.NOT_FOUND))
            },
            sendingFeedback = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(InsightListViewModel.STOP_TIMEOUT_MILLIS), InterventionDetailUiState())

    fun retry() {
        reload.update { it + 1 }
    }

    /** "Helpful" or "Not helpful"; a second answer replaces the first. */
    fun sendFeedback(helpful: Boolean) {
        val content = (state.value.load as? Load.Loaded)?.value ?: return
        if (!content.canGiveFeedback || sending.value) return
        sending.value = true
        viewModelScope.launch {
            val result = port.sendFeedback(decisionKey, helpful)
            sending.value = false
            val message = if (result is Outcome.Success) UserMessage.FEEDBACK_SAVED else UserMessage.FEEDBACK_FAILED
            effectChannel.send(ScreenEffect.Message(message))
        }
    }

    /** Opens the rule this decision belongs to (when it still exists). */
    fun openRule() {
        val jitaiId = (state.value.load as? Load.Loaded)?.value?.jitaiId ?: return
        effectChannel.trySend(ScreenEffect.Navigate(AppRoute.JitaiDetail(jitaiId)))
    }

    fun fix(route: AppRoute) {
        effectChannel.trySend(ScreenEffect.Navigate(route))
    }

    /** The screen is showing the content of a pending in-app card: that card counts as displayed (once). */
    private fun markCardShown(detail: InterventionDetail?) {
        if (cardMarked || detail?.result != DeliveryResult.CARD_PENDING || detail.content == null) return
        cardMarked = true
        viewModelScope.launch { port.markCardShown(decisionKey) }
    }

    @AssistedFactory
    interface Factory {
        fun create(decisionKey: String): InterventionDetailViewModel
    }
}

internal data class InterventionDetailUiState(val load: Load<InterventionContent> = Load.Loading, val sendingFeedback: Boolean = false)

/**
 * One decision as the screen shows it, times in the user's zone.
 *
 * @property conditions the trace's conditions, those that held first.
 * @property appLabels package -> app label, for observed foreground apps.
 * @property canGiveFeedback the suggestion was shown (or may have been), so it can be rated.
 */
internal data class InterventionContent(
    val decisionKey: String,
    val jitaiId: String?,
    val jitaiName: String,
    val channel: DeliveryChannel,
    val decidedAt: LocalDateTime,
    val deliveredAt: LocalDateTime?,
    val result: DeliveryResult,
    val reason: OutcomeReason?,
    val content: DeliveredContent?,
    val conditions: ImmutableList<TraceLine>,
    val feedback: Feedback?,
    val canGiveFeedback: Boolean,
    val use24HourClock: Boolean,
    val appLabels: ImmutableMap<String, String>,
)

/**
 * One condition of the trace: the renderer's phrase, its result, and the value Agentle saw (null for time windows).
 *
 * @property featureId the feature of a leaf, for the unit of [observed]; null for a time window.
 * @property overridden the result came from the condition's "when unknown" setting.
 */
internal data class TraceLine(
    val text: String,
    val result: TraceResult,
    val featureId: String?,
    val observed: FeatureValue?,
    val overridden: Boolean,
)

/** Decisions whose suggestion was shown (or may have been) and can be rated. */
internal val RATEABLE_RESULTS: Set<DeliveryResult> = setOf(
    DeliveryResult.DELIVERED,
    DeliveryResult.OPENED,
    DeliveryResult.DISMISSED,
    DeliveryResult.SNOOZED,
    DeliveryResult.IGNORED,
    DeliveryResult.CARD_PENDING,
    DeliveryResult.DELIVERY_UNCERTAIN,
)

private fun InterventionDetail.toContent(): InterventionContent {
    val zone = renderOptions.zone
    val lines = trace.map { item ->
        TraceLine(
            text = RuleRenderer.condition(item.condition, renderOptions),
            result = item.result,
            featureId = (item.condition as? Condition.FeatureLeaf)?.feature,
            observed = item.observed,
            overridden = item.overridden,
        )
    }
    return InterventionContent(
        decisionKey = decisionKey,
        jitaiId = jitaiId,
        jitaiName = jitaiName,
        channel = channel,
        decidedAt = decidedAt.toLocalDateTime(zone),
        deliveredAt = deliveredAt?.toLocalDateTime(zone),
        result = result,
        reason = reason,
        content = content,
        conditions = lines.sortedBy { it.result.ordinal }.toImmutableList(),
        feedback = feedback,
        canGiveFeedback = result in RATEABLE_RESULTS,
        use24HourClock = renderOptions.use24HourClock,
        appLabels = renderOptions.appLabels.toImmutableMap(),
    )
}

package dev.agentle.feature.insights.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.core.ui.navigation.JitaiTab
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.common.UserMessage
import dev.agentle.feature.insights.insight.InsightListViewModel
import dev.agentle.feature.insights.jitai.Load
import dev.agentle.feature.insights.port.BuilderEnvironment
import dev.agentle.feature.insights.port.ProposalRejection
import dev.agentle.feature.insights.port.ProposalReviewData
import dev.agentle.feature.insights.port.ProposalReviewPort
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.LifecycleEvent
import dev.agentle.jitai.dsl.validation.RuleValidator
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Review of a natural-language or AI-discovered proposal (R10 §11.5, §14.8; Journeys 6 and 7). The proposal is
 * validated here with the current environment and the user's app choices (C01); the rendered sentence, the data it
 * reads, channel, caps, the source (request or evidence), the AI's text as untrusted plain text and, for an edit, the
 * rule before and after are shown. "Turn on" is enabled only without errors and with every confirm item resolved;
 * activation re-validates once more, so an invalid proposal can never be activated.
 */
@HiltViewModel(assistedFactory = ProposalReviewViewModel.Factory::class)
internal class ProposalReviewViewModel @AssistedInject constructor(
    @Assisted private val proposalId: String,
    private val port: ProposalReviewPort,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val selections = MutableStateFlow<Map<String, String>>(emptyMap())
    private val acknowledged = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)

    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inputs: Flow<Load<Pair<ProposalReviewData?, BuilderEnvironment>>> = reload.flatMapLatest {
        flow { emit(port.environment()) }.flatMapLatest { environment ->
            when (environment) {
                is Outcome.Failure -> flow { emit(Load.Error(LoadError.of(environment.error))) }
                is Outcome.Success -> port.proposal(proposalId).asPairLoad(environment.value)
            }
        }
    }

    /** Validation runs when the proposal, the environment or an app choice changes, not on every checkbox. */
    private val model: Flow<Load<ReviewModel>> = combine(inputs, selections) { load, chosen ->
        when (load) {
            Load.Loading -> Load.Loading

            is Load.Error -> load

            is Load.Loaded -> load.value.first?.let { data -> Load.Loaded(ReviewModel.of(data, load.value.second, chosen)) }
                ?: Load.Error(LoadError(LoadError.NOT_FOUND))
        }
    }

    val state: StateFlow<ProposalReviewUiState> = combine(model, acknowledged, busy) { load, acks, working ->
        ProposalReviewUiState(load = load, acknowledged = acks.toImmutableSet(), busy = working)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(InsightListViewModel.STOP_TIMEOUT_MILLIS), ProposalReviewUiState())

    fun retry() {
        reload.update { it + 1 }
    }

    /** C01: the installed app the user means by [appLabel]. */
    fun chooseApp(appLabel: String, packageName: String) {
        selections.update { it + (appLabel to packageName) }
    }

    /** C02-C04: the user allows a voice or video reminder, quiet-hours delivery, or accepts an assumption. */
    fun setAcknowledged(key: String, value: Boolean) {
        acknowledged.update { if (value) it + key else it - key }
    }

    fun activate() {
        val current = state.value
        val model = (current.load as? Load.Loaded)?.value ?: return
        if (!model.canActivate(current.acknowledged) || busy.value) return
        val definition = model.report.definition ?: return
        busy.value = true
        viewModelScope.launch {
            val verdict = RuleValidator.revalidate(definition, model.environment.context.mediaLibrary)
            val result = if (verdict.isValid) {
                JitaiLifecycle.apply(definition, LifecycleEvent.APPROVE, model.environment.context.clock, model.report.rendering)
                    .flatMap { approved -> port.activate(proposalId, approved) }
            } else {
                Outcome.failure(AppError.ValidationError(verdict.codes.map { it.name }))
            }
            busy.value = false
            when (result) {
                is Outcome.Success -> {
                    effectChannel.send(ScreenEffect.Message(UserMessage.ACTIVATED))
                    effectChannel.send(ScreenEffect.Navigate(AppRoute.Jitais(JitaiTab.ACTIVE)))
                }

                is Outcome.Failure -> effectChannel.send(ScreenEffect.Message(activationFailure(result.error)))
            }
        }
    }

    /** "Discard" (natural language), "Not now" or "Never suggest this" (discovered). */
    fun reject(rejection: ProposalRejection) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val result = port.reject(proposalId, rejection)
            busy.value = false
            when (result) {
                is Outcome.Success -> {
                    effectChannel.send(ScreenEffect.Message(UserMessage.PROPOSAL_REJECTED))
                    effectChannel.send(ScreenEffect.Back)
                }

                is Outcome.Failure -> effectChannel.send(ScreenEffect.Message(activationFailure(result.error)))
            }
        }
    }

    /** Opens the proposal in the manual builder. */
    fun edit() {
        val model = (state.value.load as? Load.Loaded)?.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val result = port.draftForEditing(proposalId, model.report.definition)
            busy.value = false
            when (result) {
                is Outcome.Success -> effectChannel.send(
                    ScreenEffect.Navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.MANUAL, editJitaiId = result.value)),
                )

                is Outcome.Failure -> effectChannel.send(ScreenEffect.Message(activationFailure(result.error)))
            }
        }
    }

    fun fix(route: AppRoute) {
        effectChannel.trySend(ScreenEffect.Navigate(route))
    }

    private fun activationFailure(error: AppError): UserMessage = when {
        error is AppError.ValidationError && NOT_PENDING in error.codes -> UserMessage.PROPOSAL_ALREADY_HANDLED
        error is AppError.ValidationError -> UserMessage.SAVE_BLOCKED
        else -> UserMessage.SAVE_FAILED
    }

    @AssistedFactory
    interface Factory {
        fun create(proposalId: String): ProposalReviewViewModel
    }

    private companion object {
        const val NOT_PENDING = "proposal_not_pending"
    }
}

internal data class ProposalReviewUiState(
    val load: Load<ReviewModel> = Load.Loading,
    val acknowledged: ImmutableSet<String> = persistentSetOf(),
    val busy: Boolean = false,
) {
    val canActivate: Boolean get() = !busy && ((load as? Load.Loaded)?.value?.canActivate(acknowledged) ?: false)
}

private fun Flow<ProposalReviewData?>.asPairLoad(
    environment: BuilderEnvironment,
): Flow<Load<Pair<ProposalReviewData?, BuilderEnvironment>>> = flow<Load<Pair<ProposalReviewData?, BuilderEnvironment>>> {
    collect { emit(Load.Loaded(it to environment)) }
}.onStart { emit(Load.Loading) }.catch { emit(Load.Error(LoadError.of(it))) }

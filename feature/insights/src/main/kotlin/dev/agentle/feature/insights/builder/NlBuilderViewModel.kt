package dev.agentle.feature.insights.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.core.ui.navigation.JitaiBuilderMode
import dev.agentle.feature.insights.common.AiProblem
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.port.JitaiBuilderPort
import dev.agentle.feature.insights.port.NlConversion
import dev.agentle.jitai.dsl.nl.NlContract
import dev.agentle.jitai.dsl.nl.Question
import dev.agentle.jitai.dsl.nl.QuestionId
import dev.agentle.jitai.dsl.nl.UnsupportedReason
import dev.agentle.jitai.dsl.validation.ValidationIssue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The natural-language builder (spec §18, R10 §13; Journey 6): the user describes a reminder, the port turns it into
 * a validated proposal and the review screen opens. Covers clarification questions, refusals, output that still fails
 * validation (with the problems and a manual builder prefilled from the request) and the AI states (not connected,
 * consent missing, usage limit, offline).
 */
@HiltViewModel
internal class NlBuilderViewModel @Inject constructor(private val port: JitaiBuilderPort) : ViewModel() {
    private val mutableState = MutableStateFlow(NlBuilderUiState())
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)

    val state: StateFlow<NlBuilderUiState> = mutableState.asStateFlow()
    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    /** The request text; at most [NlContract.MAX_REQUEST_CHARS] characters are kept. */
    fun updateText(text: String) {
        mutableState.update { it.copy(text = text.take(NlContract.MAX_REQUEST_CHARS)) }
    }

    /** Sends the request (also the retry after a problem). */
    fun submit() {
        val current = mutableState.value
        if (!current.canSubmit) return
        convert { port.convertNaturalLanguage(current.text.trim()) }
    }

    fun answer(questionId: QuestionId, answer: String) {
        mutableState.update { state ->
            val phase = state.phase as? NlPhase.Clarifying ?: return@update state
            state.copy(phase = phase.copy(answers = (phase.answers + (questionId to answer)).toImmutableMap()))
        }
    }

    fun submitAnswers() {
        val phase = mutableState.value.phase as? NlPhase.Clarifying ?: return
        if (!phase.complete) return
        convert { port.answerClarification(phase.conversationId, phase.answers) }
    }

    /** Opens the manual builder: prefilled with the stored draft when the port kept one. */
    fun editManually() {
        val draftId = (mutableState.value.phase as? NlPhase.Invalid)?.draftId
        effectChannel.trySend(ScreenEffect.Navigate(AppRoute.JitaiBuilder(JitaiBuilderMode.MANUAL, editJitaiId = draftId)))
    }

    /** Back to editing the request after a refusal or a problem. */
    fun startOver() {
        mutableState.update { it.copy(phase = NlPhase.Editing) }
    }

    fun fix(route: AppRoute) {
        effectChannel.trySend(ScreenEffect.Navigate(route))
    }

    private fun convert(call: suspend () -> Outcome<NlConversion>) {
        mutableState.update { it.copy(phase = NlPhase.Converting) }
        viewModelScope.launch {
            val phase = when (val result = call()) {
                is Outcome.Failure -> NlPhase.Problem(AiProblem.of(result.error))

                is Outcome.Success -> when (val conversion = result.value) {
                    is NlConversion.Proposed -> {
                        effectChannel.send(ScreenEffect.Navigate(AppRoute.ProposalReview(conversion.proposalId)))
                        NlPhase.Editing
                    }

                    is NlConversion.NeedsClarification -> NlPhase.Clarifying(
                        conversationId = conversion.conversationId,
                        questions = conversion.questions.toImmutableList(),
                    )

                    // HEALTH_OR_SAFETY shows a fixed message only, never the model's text (R10 §13.1 step 8).
                    is NlConversion.Refused -> NlPhase.Refused(
                        conversion.reason,
                        conversion.detail?.takeUnless { conversion.reason == UnsupportedReason.HEALTH_OR_SAFETY },
                    )

                    is NlConversion.Invalid -> NlPhase.Invalid(conversion.problems.toImmutableList(), conversion.draftId)
                }
            }
            mutableState.update { it.copy(phase = phase) }
        }
    }
}

internal data class NlBuilderUiState(val text: String = "", val phase: NlPhase = NlPhase.Editing) {
    val canSubmit: Boolean
        get() = NlContract.isRequestAcceptable(text) && (phase is NlPhase.Editing || phase is NlPhase.Problem)
}

internal sealed interface NlPhase {
    data object Editing : NlPhase

    data object Converting : NlPhase

    /** Questions with their options; free text is allowed too (R10 §13.1 step 7). */
    data class Clarifying(
        val conversationId: String,
        val questions: ImmutableList<Question>,
        val answers: ImmutableMap<QuestionId, String> = persistentMapOf(),
    ) : NlPhase {
        val complete: Boolean get() = questions.all { !answers[it.id].isNullOrBlank() }
    }

    /** The model could not express the request; [detail] is untrusted plain text (or null). */
    data class Refused(val reason: UnsupportedReason, val detail: String?) : NlPhase

    /** "Agentle could not turn this into a safe rule" with the validator's problems. */
    data class Invalid(val problems: ImmutableList<ValidationIssue>, val draftId: String?) : NlPhase

    data class Problem(val problem: AiProblem) : NlPhase
}

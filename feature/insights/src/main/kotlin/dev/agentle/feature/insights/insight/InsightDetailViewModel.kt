package dev.agentle.feature.insights.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.feature.insights.common.AiProblem
import dev.agentle.feature.insights.common.LoadError
import dev.agentle.feature.insights.common.ScreenEffect
import dev.agentle.feature.insights.port.InsightChart
import dev.agentle.feature.insights.port.InsightDetail
import dev.agentle.feature.insights.port.InsightMethod
import dev.agentle.feature.insights.port.InsightsPort
import dev.agentle.feature.insights.port.InterpretationEvent
import dev.agentle.feature.insights.port.InterpretationPreview
import dev.agentle.feature.insights.port.SavedInterpretation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * One insight with its method, chart and "Interpret with AI" (spec §16, §22). The AI flow is: show exactly what would
 * be sent ([AiPanel.Preview]), send only after confirmation, stream the answer ([AiPanel.Streaming]), and keep only a
 * completed answer; a failure or cancellation discards the partial text and offers a retry ([AiPanel.Problem]).
 */
@HiltViewModel(assistedFactory = InsightDetailViewModel.Factory::class)
internal class InsightDetailViewModel @AssistedInject constructor(
    @Assisted private val insightId: String,
    private val port: InsightsPort,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val ai = MutableStateFlow<AiPanel>(AiPanel.Idle)
    private val effectChannel = Channel<ScreenEffect>(Channel.BUFFERED)
    private var streamJob: Job? = null

    val effects: Flow<ScreenEffect> = effectChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val detail: Flow<DetailLoad> = reload.flatMapLatest {
        port.insightDetail(insightId)
            .map<InsightDetail?, DetailLoad> { detail -> detail?.let { DetailLoad.Loaded(it.toContent()) } ?: DetailLoad.NotFound }
            .onStart { emit(DetailLoad.Loading) }
            .catch { emit(DetailLoad.Error(LoadError.of(it))) }
    }

    val state: StateFlow<InsightDetailUiState> = combine(detail, ai) { load, panel -> InsightDetailUiState(load, panel) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(InsightListViewModel.STOP_TIMEOUT_MILLIS),
            InsightDetailUiState(DetailLoad.Loading, AiPanel.Idle),
        )

    fun retry() {
        reload.update { it + 1 }
    }

    /** Prepares the request and shows what would be sent; nothing is sent yet. Also the retry after a failure. */
    fun requestInterpretation() {
        if (ai.value is AiPanel.Preparing || ai.value is AiPanel.Streaming) return
        ai.value = AiPanel.Preparing
        viewModelScope.launch {
            ai.value = when (val result = port.interpretationPreview(insightId)) {
                is Outcome.Success -> AiPanel.Preview(result.value)
                is Outcome.Failure -> AiPanel.Problem(AiProblem.of(result.error))
            }
        }
    }

    /** Closes the preview without sending. */
    fun cancelPreview() {
        if (ai.value is AiPanel.Preview || ai.value is AiPanel.Problem) ai.value = AiPanel.Idle
    }

    /** Sends exactly the previewed request and streams the answer. */
    fun confirmSend() {
        val preview = (ai.value as? AiPanel.Preview)?.preview ?: return
        ai.value = AiPanel.Streaming(preview, "")
        streamJob = viewModelScope.launch {
            var finished = false
            port.interpret(preview.previewId)
                .catch { error -> emit(InterpretationEvent.Failed(AppError.Unexpected(error::class.simpleName))) }
                .collect { event ->
                    if (finished) return@collect
                    when (event) {
                        is InterpretationEvent.Delta -> ai.update { panel ->
                            if (panel is AiPanel.Streaming) panel.copy(text = panel.text + event.text) else panel
                        }

                        is InterpretationEvent.Completed -> {
                            finished = true
                            ai.value = AiPanel.Completed(event.interpretation)
                        }

                        is InterpretationEvent.Failed -> {
                            finished = true
                            ai.value = AiPanel.Problem(AiProblem.of(event.error))
                        }
                    }
                }
            // A stream that ended without a result is a failure: the partial answer is not kept.
            if (!finished) ai.value = AiPanel.Problem(AiProblem.FAILED)
        }
    }

    /** Stops a running answer; the partial text is discarded and nothing is saved. */
    fun cancelStreaming() {
        if (ai.value !is AiPanel.Streaming) return
        streamJob?.cancel()
        streamJob = null
        ai.value = AiPanel.Idle
    }

    /** Opens the screen that fixes an AI problem (ChatGPT connection or AI data sharing). */
    fun fix(route: AppRoute) {
        effectChannel.trySend(ScreenEffect.Navigate(route))
    }

    @AssistedFactory
    interface Factory {
        fun create(insightId: String): InsightDetailViewModel
    }
}

internal data class InsightDetailUiState(val load: DetailLoad, val ai: AiPanel) {
    /** The interpretation to show: the one just completed, else the saved one. */
    val interpretation: SavedInterpretation?
        get() = (ai as? AiPanel.Completed)?.interpretation ?: (load as? DetailLoad.Loaded)?.content?.interpretation
}

internal sealed interface DetailLoad {
    data object Loading : DetailLoad

    data object NotFound : DetailLoad

    data class Error(val error: LoadError) : DetailLoad

    data class Loaded(val content: InsightDetailContent) : DetailLoad
}

internal data class InsightDetailContent(
    val card: InsightCard,
    val method: InsightMethod?,
    val chart: InsightChart?,
    val interpretation: SavedInterpretation?,
    val zone: TimeZone,
)

/** The "Interpret with AI" panel. */
internal sealed interface AiPanel {
    data object Idle : AiPanel

    data object Preparing : AiPanel

    data class Preview(val preview: InterpretationPreview) : AiPanel

    /** [text] is the answer so far: untrusted model text, plain text only, never saved. */
    data class Streaming(val preview: InterpretationPreview, val text: String) : AiPanel

    data class Completed(val interpretation: SavedInterpretation) : AiPanel

    data class Problem(val problem: AiProblem) : AiPanel
}

private fun InsightDetail.toContent(): InsightDetailContent = InsightDetailContent(
    card = insight.toCard(zone, interpretation != null),
    method = method,
    chart = chart,
    interpretation = interpretation,
    zone = zone,
)

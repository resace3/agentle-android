package dev.agentle.feature.settings.debug

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.EventType
import dev.agentle.feature.settings.R
import dev.agentle.feature.settings.port.DebugToolsPort
import dev.agentle.feature.settings.port.DebugToolsState
import dev.agentle.feature.settings.port.FailureKind
import dev.agentle.feature.settings.port.UserTimeZonePort
import dev.agentle.feature.settings.ui.EffectViewModel
import dev.agentle.feature.settings.ui.Loadable
import dev.agentle.feature.settings.ui.SettingsEffect
import dev.agentle.feature.settings.ui.WhileUiSubscribed
import dev.agentle.feature.settings.ui.reloading
import dev.agentle.feature.settings.ui.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import javax.inject.Inject
import kotlin.time.Duration

internal sealed interface DebugPanelUiState {
    /** This build has no debug tools: the screen says so and offers no control. */
    data object NotAvailable : DebugPanelUiState

    data class Available(
        val tools: Loadable<DebugToolsState> = Loadable.Loading,
        /** The generator's event type; null until chosen (the first offered type is used). */
        val eventType: EventType? = null,
        val eventCount: Int = DebugPanelViewModel.EVENT_COUNTS[1],
        /** A tool is running; the controls wait for it. */
        val running: Boolean = false,
        val choosingEventType: Boolean = false,
        val confirmingClear: Boolean = false,
        val zone: TimeZone = TimeZone.UTC,
    ) : DebugPanelUiState {
        val effectiveEventType: EventType? get() = eventType ?: tools.valueOrNull()?.syntheticEventTypes?.firstOrNull()
    }
}

internal sealed interface DebugAction {
    data object Retry : DebugAction

    data class SelectWearableScenario(val id: String) : DebugAction

    data class SelectChatGptScenario(val id: String) : DebugAction

    data object ChooseEventType : DebugAction

    data object DismissEventTypes : DebugAction

    data class SelectEventType(val type: EventType) : DebugAction

    data class StepEventCount(val up: Boolean) : DebugAction

    data object GenerateEvents : DebugAction

    data object GenerateDataset : DebugAction

    data class SimulateJitai(val jitaiId: String) : DebugAction

    /** Moves the app clock by [delta] more (adds to the current override). */
    data class ShiftTime(val delta: Duration) : DebugAction

    data object ClearTimeOffset : DebugAction

    data class ForceSync(val connectorId: String) : DebugAction

    data class ForceWorker(val uniqueName: String) : DebugAction

    data object RequestClearDatabase : DebugAction

    data object DismissClearDatabase : DebugAction

    data object ConfirmClearDatabase : DebugAction

    data class SetFailure(val kind: FailureKind, val enabled: Boolean) : DebugAction
}

/**
 * The debug panel (spec §56). When [DebugToolsPort.available] is false (every build but fake debug), the state is
 * [DebugPanelUiState.NotAvailable] for good and this ViewModel never touches the port again.
 */
@HiltViewModel
internal class DebugPanelViewModel @Inject constructor(
    private val port: DebugToolsPort,
    private val zone: UserTimeZonePort,
) : EffectViewModel() {
    private data class Local(
        val eventType: EventType? = null,
        val eventCount: Int = EVENT_COUNTS[1],
        val running: Boolean = false,
        val choosingEventType: Boolean = false,
        val confirmingClear: Boolean = false,
    )

    private val available: Boolean = port.available
    private val reloads = MutableStateFlow(0)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<DebugPanelUiState> = if (!available) {
        MutableStateFlow<DebugPanelUiState>(DebugPanelUiState.NotAvailable)
    } else {
        combine(reloads.reloading { port.state }, local) { tools, pending ->
            DebugPanelUiState.Available(
                tools = tools,
                eventType = pending.eventType,
                eventCount = pending.eventCount,
                running = pending.running,
                choosingEventType = pending.choosingEventType,
                confirmingClear = pending.confirmingClear,
                zone = zone.zone(),
            )
        }.stateIn(viewModelScope, WhileUiSubscribed, DebugPanelUiState.Available(zone = zone.zone()))
    }

    fun onAction(action: DebugAction) {
        if (!available) return
        when (action) {
            DebugAction.Retry -> reloads.update { it + 1 }
            is DebugAction.SelectWearableScenario -> runTool(Done) { port.selectWearableScenario(action.id) }
            is DebugAction.SelectChatGptScenario -> runTool(Done) { port.selectChatGptScenario(action.id) }
            DebugAction.ChooseEventType -> local.update { it.copy(choosingEventType = true) }
            DebugAction.DismissEventTypes -> local.update { it.copy(choosingEventType = false) }
            is DebugAction.SelectEventType -> local.update { it.copy(eventType = action.type, choosingEventType = false) }
            is DebugAction.StepEventCount -> local.update { it.copy(eventCount = stepCount(it.eventCount, action.up)) }
            DebugAction.GenerateEvents -> generateEvents()
            DebugAction.GenerateDataset -> runTool({ count: Int -> message(R.string.settings_debug_dataset_done, count) }) {
                port.generateDataset(DATASET_DAYS)
            }

            is DebugAction.SimulateJitai -> runTool({ code: String -> message(R.string.settings_debug_jitai_result, code) }) {
                port.simulateJitaiTrigger(action.jitaiId)
            }

            is DebugAction.ShiftTime -> shiftTime(action.delta)
            DebugAction.ClearTimeOffset -> runTool(Done) { port.clearTimeOffset() }
            is DebugAction.ForceSync -> runTool(Done) { port.forceSync(action.connectorId) }
            is DebugAction.ForceWorker -> runTool(Done) { port.forceWorker(action.uniqueName) }
            DebugAction.RequestClearDatabase -> local.update { it.copy(confirmingClear = true) }
            DebugAction.DismissClearDatabase -> local.update { it.copy(confirmingClear = false) }
            DebugAction.ConfirmClearDatabase -> {
                local.update { it.copy(confirmingClear = false) }
                runTool(Done) { port.clearDatabase() }
            }

            is DebugAction.SetFailure -> runTool(Done) { port.setFailureInjection(action.kind, action.enabled) }
        }
    }

    private fun generateEvents() {
        val current = state.value as? DebugPanelUiState.Available ?: return
        val type = current.effectiveEventType ?: return
        runTool({ count: Int -> message(R.string.settings_debug_events_done, count) }) { port.generateSyntheticEvents(type, current.eventCount) }
    }

    private fun shiftTime(delta: Duration) {
        val tools = (state.value as? DebugPanelUiState.Available)?.tools?.valueOrNull() ?: return
        val offset = (tools.timeOffset ?: Duration.ZERO) + delta
        runTool(Done) { port.setTimeOffset(offset) }
    }

    /** Runs one tool at a time and reports its result as a message. */
    private fun <T> runTool(onSuccess: (T) -> SettingsEffect.Message, call: suspend () -> Outcome<T>) {
        if (local.value.running) return
        local.update { it.copy(running = true) }
        viewModelScope.launch {
            when (val result = call()) {
                is Outcome.Success -> send(onSuccess(result.value))
                is Outcome.Failure -> report(result.error)
            }
            local.update { it.copy(running = false) }
        }
    }

    internal companion object {
        /** Counts the synthetic event generator offers. */
        val EVENT_COUNTS: List<Int> = listOf(1, 10, 100, 1_000)

        /** The synthetic user's length (spec §56: "generate 90-day dataset"). */
        const val DATASET_DAYS: Int = 90

        private fun stepCount(current: Int, up: Boolean): Int {
            val index = EVENT_COUNTS.indexOf(current).coerceAtLeast(0)
            return EVENT_COUNTS[(if (up) index + 1 else index - 1).coerceIn(EVENT_COUNTS.indices)]
        }

        /** The message of a tool without a result to show. */
        private val Done: (Any?) -> SettingsEffect.Message = { SettingsEffect.Message(R.string.settings_debug_done) }

        private fun message(@StringRes text: Int, value: Any): SettingsEffect.Message = SettingsEffect.Message(text, listOf(value.toString()))
    }
}

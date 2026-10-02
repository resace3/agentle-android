package dev.agentle.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.PermissionState
import dev.agentle.core.ui.permission.PermissionDialogResult
import dev.agentle.feature.onboarding.port.OnboardingPort
import dev.agentle.feature.onboarding.port.OnboardingProgress
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A permission the onboarding explains before Android's dialog. */
internal enum class Primer(val capabilityId: String, val permission: String, val minSdk: Int) {
    NOTIFICATIONS("post_notifications_jitai", Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU),
    ACTIVITY("activity_recognition_transitions", Manifest.permission.ACTIVITY_RECOGNITION, Build.VERSION_CODES.Q),
}

internal data class PrimerUi(val primer: Primer, val state: PermissionState?)

internal data class OnboardingUiState(
    val loading: Boolean = true,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val sources: Set<OnboardingSource> = emptySet(),
    val primers: List<PrimerUi> = emptyList(),
    val primerIndex: Int = 0,
    val error: AppError? = null,
) {
    val currentPrimer: PrimerUi? get() = primers.getOrNull(primerIndex)
}

internal sealed interface OnboardingEffect {
    data class RequestPermission(val permission: String) : OnboardingEffect

    data object OpenAppSettings : OnboardingEffect

    data object Finished : OnboardingEffect
}

@HiltViewModel
internal class OnboardingViewModel internal constructor(private val port: OnboardingPort, sdkInt: Int) : ViewModel() {
    @Inject constructor(port: OnboardingPort) : this(port, Build.VERSION.SDK_INT)

    private val primers = Primer.entries.filter { sdkInt >= it.minSdk }
    private val local = MutableStateFlow(Local())
    private val effects = Channel<OnboardingEffect>(Channel.BUFFERED)

    val effect: Flow<OnboardingEffect> = effects.receiveAsFlow()

    val state: StateFlow<OnboardingUiState> = combine(port.progress, local) { progress, local -> progress.toUi(local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    private data class Local(val primerIndex: Int = 0, val error: AppError? = null)

    private fun OnboardingProgress.toUi(local: Local) = OnboardingUiState(
        loading = false,
        step = step,
        sources = selectedSources,
        primers = primers.map { PrimerUi(it, permissionStates[it.capabilityId]) },
        primerIndex = local.primerIndex,
        error = local.error,
    )

    fun next() {
        val current = state.value.step
        val next = OnboardingStep.entries.getOrNull(current.ordinal + 1) ?: return
        val target = if (next == OnboardingStep.PERMISSIONS && primers.isEmpty()) OnboardingStep.SUMMARY else next
        local.value = local.value.copy(primerIndex = 0)
        run { port.saveStep(target) }
    }

    fun back() {
        val current = state.value.step
        val previous = OnboardingStep.entries.getOrNull(current.ordinal - 1) ?: return
        val target = if (previous == OnboardingStep.PERMISSIONS && primers.isEmpty()) OnboardingStep.SOURCES else previous
        local.value = local.value.copy(primerIndex = 0)
        run { port.saveStep(target) }
    }

    fun toggleSource(source: OnboardingSource) {
        val selected = source !in state.value.sources
        run { port.setSourceSelected(source, selected) }
    }

    /** "Continue" on a primer: shows Android's dialog. */
    fun requestCurrent() {
        val primer = state.value.currentPrimer ?: return
        effects.trySend(OnboardingEffect.RequestPermission(primer.primer.permission))
    }

    /** "Not now" on a primer: nothing is requested and the next primer (or the summary) follows. */
    fun skipCurrent() = advancePrimer()

    fun openSettings() {
        effects.trySend(OnboardingEffect.OpenAppSettings)
    }

    fun onPermissionResult(result: PermissionDialogResult) {
        if (result.cancelled) return
        val primer = state.value.currentPrimer?.primer ?: return
        val granted = result.granted[primer.permission] ?: return
        val rationale = result.showRationale[primer.permission] ?: false
        viewModelScope.launch {
            when (val outcome = port.reportPermissionResult(primer.capabilityId, primer.permission, granted, rationale)) {
                is Outcome.Success -> if (granted) advancePrimer()
                is Outcome.Failure -> local.value = local.value.copy(error = outcome.error)
            }
        }
    }

    /** Re-checks on resume: a permission granted in Android Settings is reported and its primer completes. */
    fun onResume(held: Map<String, Boolean>) {
        val ui = state.value
        ui.primers.filter { held[it.primer.permission] == true && it.state != null && it.state != PermissionState.ALLOWED }
            .forEach { primerUi ->
                viewModelScope.launch {
                    val primer = primerUi.primer
                    val outcome = port.reportPermissionResult(primer.capabilityId, primer.permission, granted = true, showRationale = false)
                    if (outcome is Outcome.Success && state.value.currentPrimer?.primer == primer) advancePrimer()
                }
            }
    }

    fun finish() {
        viewModelScope.launch {
            when (val outcome = port.complete()) {
                is Outcome.Success -> effects.send(OnboardingEffect.Finished)
                is Outcome.Failure -> local.value = local.value.copy(error = outcome.error)
            }
        }
    }

    fun dismissError() {
        local.value = local.value.copy(error = null)
    }

    private fun advancePrimer() {
        val index = local.value.primerIndex + 1
        if (index >= primers.size) {
            local.value = local.value.copy(primerIndex = 0)
            run { port.saveStep(OnboardingStep.SUMMARY) }
        } else {
            local.value = local.value.copy(primerIndex = index)
        }
    }

    private fun run(action: suspend () -> Outcome<Unit>) {
        viewModelScope.launch {
            val outcome = action()
            if (outcome is Outcome.Failure) local.value = local.value.copy(error = outcome.error)
        }
    }
}

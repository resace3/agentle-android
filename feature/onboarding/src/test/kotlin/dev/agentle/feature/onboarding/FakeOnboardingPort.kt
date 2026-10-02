package dev.agentle.feature.onboarding

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.PermissionState
import dev.agentle.feature.onboarding.port.OnboardingPort
import dev.agentle.feature.onboarding.port.OnboardingProgress
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory port that applies the DENIED / DENIED_PERMANENTLY rule the wiring implements. */
internal class FakeOnboardingPort(initial: OnboardingProgress = OnboardingProgress()) : OnboardingPort {
    override val progress = MutableStateFlow(initial)
    val reports = mutableListOf<Triple<String, Boolean, Boolean>>()
    var failWith: AppError? = null
    var completed = false

    private fun result(): Outcome<Unit> = failWith?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)

    override suspend fun saveStep(step: OnboardingStep): Outcome<Unit> =
        result().also { if (it is Outcome.Success) progress.value = progress.value.copy(step = step) }

    override suspend fun setSourceSelected(source: OnboardingSource, selected: Boolean): Outcome<Unit> = result().also {
        if (it is Outcome.Success) {
            val sources = progress.value.selectedSources
            progress.value = progress.value.copy(selectedSources = if (selected) sources + source else sources - source)
        }
    }

    override suspend fun reportPermissionResult(
        capabilityId: String,
        permission: String,
        granted: Boolean,
        showRationale: Boolean,
    ): Outcome<Unit> = result().also {
        if (it is Outcome.Success) {
            reports += Triple(capabilityId, granted, showRationale)
            val state = when {
                granted -> PermissionState.ALLOWED
                showRationale -> PermissionState.DENIED
                else -> PermissionState.DENIED_PERMANENTLY
            }
            progress.value = progress.value.copy(permissionStates = progress.value.permissionStates + (capabilityId to state))
        }
    }

    override suspend fun complete(): Outcome<Unit> = result().also { if (it is Outcome.Success) completed = true }
}

package dev.agentle.app.staging

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.feature.onboarding.port.OnboardingPort
import dev.agentle.feature.onboarding.port.OnboardingProgress
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Placeholder bindings for UI-HUB's ports until APP-WIRING implements them. No data, every action unsupported. */
@Module
@InstallIn(SingletonComponent::class)
internal object UiHubStagingModule {
    @Provides
    fun onboardingPort(): OnboardingPort = UnavailableOnboardingPort
}

private fun unavailable(feature: String): Outcome<Nothing> = Outcome.Failure(AppError.UnsupportedFeature(feature))

internal object UnavailableOnboardingPort : OnboardingPort {
    override val progress: Flow<OnboardingProgress> = flowOf(OnboardingProgress())

    override suspend fun saveStep(step: OnboardingStep): Outcome<Unit> = unavailable("onboarding")

    override suspend fun setSourceSelected(source: OnboardingSource, selected: Boolean): Outcome<Unit> = unavailable("onboarding")

    override suspend fun reportPermissionResult(capabilityId: String, permission: String, granted: Boolean, showRationale: Boolean): Outcome<Unit> =
        unavailable("onboarding")

    override suspend fun complete(): Outcome<Unit> = unavailable("onboarding")
}

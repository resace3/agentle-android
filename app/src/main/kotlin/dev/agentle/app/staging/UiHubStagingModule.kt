package dev.agentle.app.staging

import android.content.Intent
import androidx.paging.PagingData
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.SystemAgentleClock
import dev.agentle.feature.hub.port.CapabilityItem
import dev.agentle.feature.hub.port.DashboardData
import dev.agentle.feature.hub.port.DashboardPort
import dev.agentle.feature.hub.port.DataSourceItem
import dev.agentle.feature.hub.port.DataSourcesPort
import dev.agentle.feature.hub.port.DayCoverage
import dev.agentle.feature.hub.port.HubClockPort
import dev.agentle.feature.hub.port.PermissionCenterPort
import dev.agentle.feature.hub.port.TimelineFilter
import dev.agentle.feature.hub.port.TimelinePort
import dev.agentle.feature.onboarding.port.OnboardingPort
import dev.agentle.feature.onboarding.port.OnboardingProgress
import dev.agentle.feature.onboarding.port.OnboardingSource
import dev.agentle.feature.onboarding.port.OnboardingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate
import javax.inject.Singleton
import kotlin.time.Instant

/** Placeholder bindings for UI-HUB's ports until APP-WIRING implements them. No data, every action unsupported. */
@Module
@InstallIn(SingletonComponent::class)
internal object UiHubStagingModule {
    @Provides
    fun onboardingPort(): OnboardingPort = UnavailableOnboardingPort

    @Provides
    fun dashboardPort(): DashboardPort = UnavailableDashboardPort

    @Provides
    fun dataSourcesPort(): DataSourcesPort = UnavailableDataSourcesPort

    @Provides
    fun permissionCenterPort(): PermissionCenterPort = UnavailablePermissionCenterPort

    @Provides
    fun timelinePort(): TimelinePort = UnavailableTimelinePort

    @Provides
    @Singleton
    fun hubClockPort(): HubClockPort = object : HubClockPort {
        override val clock: AgentleClock = SystemAgentleClock()
    }
}

private fun unavailable(feature: String): Outcome<Nothing> = Outcome.Failure(AppError.UnsupportedFeature(feature))

internal object UnavailableOnboardingPort : OnboardingPort {
    override val progress: Flow<OnboardingProgress> = flowOf(OnboardingProgress())

    override suspend fun saveStep(step: OnboardingStep): Outcome<Unit> = unavailable("onboarding")

    override suspend fun setSourceSelected(source: OnboardingSource, selected: Boolean): Outcome<Unit> = unavailable("onboarding")

    override suspend fun reportPermissionResult(
        capabilityId: String,
        permission: String,
        granted: Boolean,
        showRationale: Boolean,
    ): Outcome<Unit> = unavailable("onboarding")

    override suspend fun complete(): Outcome<Unit> = unavailable("onboarding")
}

internal object UnavailableDashboardPort : DashboardPort {
    override val dashboard: Flow<DashboardData> = flowOf(DashboardData())

    override suspend fun snooze(decisionKey: String): Outcome<Unit> = unavailable("dashboard")

    override suspend fun notNow(decisionKey: String): Outcome<Unit> = unavailable("dashboard")

    override suspend fun stopJitai(jitaiId: String): Outcome<Unit> = unavailable("dashboard")
}

internal object UnavailableDataSourcesPort : DataSourcesPort {
    override val sources: Flow<List<DataSourceItem>> = flowOf(emptyList())

    override suspend fun setEnabled(connectorId: String, enabled: Boolean): Outcome<Unit> = unavailable("data_sources")

    override suspend fun syncNow(connectorId: String): Outcome<Unit> = unavailable("data_sources")
}

internal object UnavailablePermissionCenterPort : PermissionCenterPort {
    override val capabilities: Flow<List<CapabilityItem>> = flowOf(emptyList())

    override suspend fun refresh(rationale: Map<String, Boolean>): Outcome<Unit> = unavailable("permission_center")

    override suspend fun reportPermissionResult(
        capabilityId: String,
        granted: Map<String, Boolean>,
        showRationale: Map<String, Boolean>,
    ): Outcome<Unit> = unavailable("permission_center")

    override suspend fun setCollectionEnabled(capabilityId: String, enabled: Boolean): Outcome<Unit> = unavailable("permission_center")

    override fun settingsIntent(capabilityId: String): Intent? = null
}

internal object UnavailableTimelinePort : TimelinePort {
    override fun events(filter: TimelineFilter, upperBound: Instant): Flow<PagingData<PersonalEvent>> = flowOf(PagingData.empty())

    override fun dayCoverage(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, DayCoverage>> = flowOf(emptyMap())

    override val sources: Flow<List<String>> = flowOf(emptyList())
}

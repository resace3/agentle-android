package dev.agentle.app.wiring

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.background.port.Collectors
import dev.agentle.background.port.Maintenance
import dev.agentle.core.time.AgentleClock
import dev.agentle.feature.hub.port.DashboardPort
import dev.agentle.feature.hub.port.DataSourcesPort
import dev.agentle.feature.hub.port.HubClockPort
import dev.agentle.feature.hub.port.TimelinePort
import dev.agentle.feature.settings.port.UserTimeZonePort
import javax.inject.Singleton

/** Real implementations of feature and scheduler ports; everything not listed here is still in `staging`. */
@Module
@InstallIn(SingletonComponent::class)
internal interface WiringModule {
    @Binds @Singleton
    fun timeline(impl: RoomTimelinePort): TimelinePort

    @Binds @Singleton
    fun dashboard(impl: DraftDashboardPort): DashboardPort

    @Binds @Singleton
    fun dataSources(impl: GraphDataSourcesPort): DataSourcesPort

    @Binds @Singleton
    fun collectors(impl: GraphCollectors): Collectors

    @Binds @Singleton
    fun maintenance(impl: DraftMaintenance): Maintenance

    companion object {
        @Provides @Singleton
        fun hubClock(clock: AgentleClock): HubClockPort = object : HubClockPort {
            override val clock: AgentleClock = clock
        }

        @Provides
        fun userTimeZone(clock: AgentleClock): UserTimeZonePort = UserTimeZonePort { clock.zone() }
    }
}

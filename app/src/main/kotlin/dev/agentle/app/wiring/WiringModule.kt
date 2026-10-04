package dev.agentle.app.wiring

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.ai.api.AiPurpose
import dev.agentle.background.port.Collectors
import dev.agentle.background.port.Maintenance
import dev.agentle.core.datastore.ConsentVocabulary
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.time.SystemAgentleClock
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
    fun chat(impl: SiwcChatPort): dev.agentle.app.shell.ChatPort

    @Binds @Singleton
    fun chatGptConnection(impl: SiwcConnectionPort): dev.agentle.feature.connections.port.ChatGptConnectionPort

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
        /** The one wall clock of the app; tests and debug builds replace it through their own bindings. */
        @Provides @Singleton
        fun clock(): AgentleClock = SystemAgentleClock()

        /** What AI consent can name: every AI data category and purpose, under consent terms version 1. */
        @Provides @Singleton
        fun consentVocabulary(): ConsentVocabulary = ConsentVocabulary(
            categories = AiDataCategory.entries.map { it.name }.toSet(),
            purposes = AiPurpose.entries.map { it.name }.toSet(),
            termsVersion = CONSENT_TERMS_VERSION,
        )

        private const val CONSENT_TERMS_VERSION = 1

        @Provides @Singleton
        fun hubClock(appClock: AgentleClock): HubClockPort = object : HubClockPort {
            override val clock: AgentleClock = appClock
        }

        @Provides
        fun userTimeZone(clock: AgentleClock): UserTimeZonePort = UserTimeZonePort { clock.zone() }
    }
}

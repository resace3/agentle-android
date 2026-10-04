package dev.agentle.app.staging

import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.AiPurpose
import dev.agentle.connectors.api.SyncResult
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.PermissionState
import dev.agentle.feature.connections.port.AiConsentChange
import dev.agentle.feature.connections.port.AiConsentState
import dev.agentle.feature.connections.port.AiDataSharingPort
import dev.agentle.feature.connections.port.AiRequestPreview
import dev.agentle.feature.connections.port.AiRequestRecord
import dev.agentle.feature.connections.port.AiSharingCategory
import dev.agentle.feature.connections.port.ChatGptConnectRequest
import dev.agentle.feature.connections.port.ChatGptConnectResult
import dev.agentle.feature.connections.port.ChatGptConnectionPort
import dev.agentle.feature.connections.port.ChatGptConnectionState
import dev.agentle.feature.connections.port.ChatGptDisconnectResult
import dev.agentle.feature.connections.port.DisplayZonePort
import dev.agentle.feature.connections.port.WearableAuthorizationPurpose
import dev.agentle.feature.connections.port.WearableAuthorizationResult
import dev.agentle.feature.connections.port.WearableConnectionPort
import dev.agentle.feature.connections.port.WearableConnectionState
import dev.agentle.feature.connections.port.WearableDisconnectReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * STAGING (UI-CONNECTIONS): placeholder bindings for the ports of `:feature:connections` so the app graph compiles
 * before APP-WIRING implements them on the real modules (`:connectors:googlehealth`, `:ai:chatgpt`, `:ai:context`).
 * Every placeholder reports "not available in this build" and fails its actions with `unsupported_feature`; none
 * emits sample data. APP-WIRING deletes the `staging` package.
 */
@Module
@InstallIn(SingletonComponent::class)
public object ConnectionsStagingModule {
    @Provides
    public fun wearableConnectionPort(): WearableConnectionPort = UnavailableWearableConnectionPort

    @Provides
    public fun aiDataSharingPort(): AiDataSharingPort = UnavailableAiDataSharingPort

    /** No times are shown while every connection is unavailable; APP-WIRING binds `AgentleClock.zone()`. */
    @Provides
    public fun displayZonePort(): DisplayZonePort = DisplayZonePort { TimeZone.UTC }
}

private const val WEARABLE_FEATURE = "wearable"
private const val CHATGPT_FEATURE = "chatgpt"
private const val AI_SHARING_FEATURE = "ai_data_sharing"

/** No sync ran, so a skipped result carries no real time. */
private val NO_RUN: Instant = Instant.fromEpochMilliseconds(0)

internal object UnavailableWearableConnectionPort : WearableConnectionPort {
    override val state: Flow<WearableConnectionState> = flowOf(
        WearableConnectionState(
            metadata = ConnectorMetadata(
                connectorId = ConnectorIds.GOOGLE_HEALTH,
                name = "Google Health",
                enabled = false,
                connection = ConnectionStatus.UNAVAILABLE,
                permissionSummary = PermissionState.UNAVAILABLE,
            ),
            blockers = listOf(Blocker.NOT_IN_THIS_BUILD),
        ),
    )

    override suspend fun authorize(purpose: WearableAuthorizationPurpose): WearableAuthorizationResult =
        WearableAuthorizationResult.Unavailable(Blocker.NOT_IN_THIS_BUILD)

    override suspend fun completeAuthorization(resultCode: Int, data: Intent?): WearableAuthorizationResult =
        WearableAuthorizationResult.Unavailable(Blocker.NOT_IN_THIS_BUILD)

    override suspend fun syncNow(): SyncResult =
        SyncResult(ConnectorIds.GOOGLE_HEALTH, SyncResult.Status.SKIPPED_NOT_CONNECTED, startedAt = NO_RUN, finishedAt = NO_RUN)

    override suspend fun disconnect(deleteSyncedData: Boolean): Outcome<WearableDisconnectReport> =
        Outcome.Failure(AppError.UnsupportedFeature(WEARABLE_FEATURE))
}

internal object UnavailableChatGptConnectionPort : ChatGptConnectionPort {
    override val state: Flow<ChatGptConnectionState> =
        flowOf(ChatGptConnectionState(available = false, provider = AiProviderState.Unavailable("not_in_this_build")))

    override suspend fun connect(request: ChatGptConnectRequest): ChatGptConnectResult =
        ChatGptConnectResult.Failed(AppError.UnsupportedFeature(CHATGPT_FEATURE))

    override fun cancelConnect() = Unit

    override suspend fun takeInterruptedConnect(): ChatGptConnectResult.Interrupted? = null

    override suspend fun acknowledgePlanNotice(): Outcome<Unit> = Outcome.Failure(AppError.UnsupportedFeature(CHATGPT_FEATURE))

    override suspend fun disconnect(forgetRegistration: Boolean): ChatGptDisconnectResult =
        ChatGptDisconnectResult.Failed(AppError.UnsupportedFeature(CHATGPT_FEATURE))
}

internal object UnavailableAiDataSharingPort : AiDataSharingPort {
    override val consent: Flow<AiConsentState> =
        flowOf(AiConsentState(available = false, consentVersion = 0, categories = emptyList()))

    override val history: Flow<List<AiRequestRecord>> = flowOf(emptyList())

    override suspend fun setAllowed(category: AiSharingCategory, allowed: Boolean): Outcome<AiConsentChange> =
        Outcome.Failure(AppError.UnsupportedFeature(AI_SHARING_FEATURE))

    override suspend fun preview(purpose: AiPurpose): Outcome<AiRequestPreview> =
        Outcome.Failure(AppError.UnsupportedFeature(AI_SHARING_FEATURE))
}

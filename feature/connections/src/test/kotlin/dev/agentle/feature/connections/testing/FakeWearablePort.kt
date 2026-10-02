package dev.agentle.feature.connections.testing

import android.content.Intent
import dev.agentle.connectors.api.SyncResult
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.SyncStatus
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.feature.connections.port.WearableAuthorizationPurpose
import dev.agentle.feature.connections.port.WearableAuthorizationResult
import dev.agentle.feature.connections.port.WearableConnectionPort
import dev.agentle.feature.connections.port.WearableConnectionState
import dev.agentle.feature.connections.port.WearableDisconnectReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/**
 * A scripted [WearableConnectionPort] that behaves like the connection: a successful authorization connects, a sync
 * of a connected account updates the last sync, a disconnect leaves it not connected, and a sync after that is skipped
 * without fetching anything (Journey 8).
 */
internal class FakeWearablePort(
    initial: WearableConnectionState = ConnectionsFixtures.wearableNotConnected(),
    private val clock: TestAgentleClock = ConnectionsFixtures.clock(),
) : WearableConnectionPort {
    val current = MutableStateFlow(initial)

    /** When true, collecting [state] fails (the screen shows its load error). */
    var failState: Boolean = false

    override val state: Flow<WearableConnectionState> = flow {
        check(!failState) { "scripted state failure" }
        emitAll(current)
    }

    /** When set, every call waits for it, so a test can look at the screen while the call runs. */
    var gate: CompletableDeferred<Unit>? = null

    /** The result of [authorize]; a [WearableAuthorizationResult.Connected] result also connects. */
    var authorizeResult: WearableAuthorizationResult = WearableAuthorizationResult.Connected()

    /** The result of [completeAuthorization] for `RESULT_OK`; any other result code is a cancel. */
    var consentResult: WearableAuthorizationResult = WearableAuthorizationResult.Connected()

    /** The state a successful authorization leaves. */
    var connectedState: WearableConnectionState = ConnectionsFixtures.wearableConnected()

    /** Overrides the sync result of a connected account. */
    var syncResult: SyncResult? = null

    /** Overrides the disconnect outcome (a failure leaves the state as it is). */
    var disconnectOutcome: Outcome<WearableDisconnectReport>? = null

    val authorizeCalls = mutableListOf<WearableAuthorizationPurpose>()
    val consentResultCodes = mutableListOf<Int>()
    val disconnectCalls = mutableListOf<Boolean>()
    var syncCalls: Int = 0
        private set

    /** Syncs that fetched data (a skipped sync does not count). */
    var syncRuns: Int = 0
        private set

    override suspend fun authorize(purpose: WearableAuthorizationPurpose): WearableAuthorizationResult {
        gate?.await()
        authorizeCalls += purpose
        return apply(authorizeResult)
    }

    override suspend fun completeAuthorization(resultCode: Int, data: Intent?): WearableAuthorizationResult {
        gate?.await()
        consentResultCodes += resultCode
        return apply(if (resultCode == ConnectionsFixtures.RESULT_OK) consentResult else WearableAuthorizationResult.Cancelled)
    }

    private fun apply(result: WearableAuthorizationResult): WearableAuthorizationResult {
        if (result is WearableAuthorizationResult.Connected) current.value = connectedState
        return result
    }

    override suspend fun syncNow(): SyncResult {
        gate?.await()
        syncCalls++
        val now = clock.now()
        if (current.value.metadata.connection != ConnectionStatus.CONNECTED) {
            return SyncResult(ConnectorIds.GOOGLE_HEALTH, SyncResult.Status.SKIPPED_NOT_CONNECTED, now, now)
        }
        syncRuns++
        val result = syncResult ?: SyncResult(
            connectorId = ConnectorIds.GOOGLE_HEALTH,
            status = SyncResult.Status.SUCCESS,
            startedAt = now,
            finishedAt = now,
            fetched = 120,
            committed = 118,
        )
        current.update {
            it.copy(
                metadata = it.metadata.copy(
                    syncState = when (result.status) {
                        SyncResult.Status.SUCCESS -> SyncStatus.SUCCEEDED
                        SyncResult.Status.PARTIAL -> SyncStatus.PARTIAL
                        else -> SyncStatus.FAILED
                    },
                    lastSuccessfulCollection =
                    if (result.status == SyncResult.Status.SUCCESS) now else it.metadata.lastSuccessfulCollection,
                    lastAttemptedCollection = now,
                ),
            )
        }
        return result
    }

    override suspend fun disconnect(deleteSyncedData: Boolean): Outcome<WearableDisconnectReport> {
        gate?.await()
        disconnectCalls += deleteSyncedData
        disconnectOutcome?.let { return it }
        current.value = ConnectionsFixtures.wearableNotConnected()
        return Outcome.Success(WearableDisconnectReport(dataDeleted = deleteSyncedData, accessRevoked = true))
    }
}

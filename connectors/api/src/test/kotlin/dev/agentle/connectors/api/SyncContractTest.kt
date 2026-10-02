package dev.agentle.connectors.api

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorMetadata
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.PersonalEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class SyncContractTest {
    private val at = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `syncStream defaults to a full sync and connectors list no streams`() = runTest {
        val triggers = mutableListOf<SyncTrigger>()
        val connector = object : Connector {
            override val id = "x"
            override val name = "X"
            override val supportedEventTypes = setOf(EventType.STEP_SAMPLE)
            override val capabilityIds = emptyList<String>()
            override val metadata: StateFlow<ConnectorMetadata> =
                MutableStateFlow(ConnectorMetadata("x", "X", true, ConnectionStatus.CONNECTED, PermissionState.ALLOWED))

            override suspend fun sync(trigger: SyncTrigger): SyncResult {
                triggers += trigger
                return SyncResult("x", SyncResult.Status.SUCCESS, at, at)
            }

            override suspend fun setEnabled(enabled: Boolean) = Unit
        }
        assertThat(connector.streamIds).isEmpty()
        assertThat(connector.syncStream("steps").status).isEqualTo(SyncResult.Status.SUCCESS)
        connector.syncStream("steps", SyncTrigger.EVENT)
        assertThat(triggers).containsExactly(SyncTrigger.MANUAL, SyncTrigger.EVENT).inOrder()
    }

    @Test
    fun `sinks without a floor return none, and the additions default to the old behavior`() = runTest {
        val sink = object : EventSink {
            override suspend fun commit(events: List<PersonalEvent>, cursor: SyncCursor?, coverage: StreamCoverage?) = CommitResult.EMPTY

            override suspend fun replaceWindow(
                source: DataSourceId,
                windowStart: Instant,
                windowEnd: Instant,
                events: List<PersonalEvent>,
                cursor: SyncCursor?,
                coverage: StreamCoverage?,
                accountId: String?,
            ) = CommitResult.EMPTY

            override suspend fun cursor(connectorId: String, stream: String): SyncCursor? = null
        }
        assertThat(sink.importFloor(DataSourceId("googlehealth.steps"))).isNull()
        assertThat(sink.commit(emptyList())).isEqualTo(CommitResult.EMPTY)
        val cursor = SyncCursor("googlehealth", "steps")
        assertThat(cursor.accountId).isNull()
        assertThat(cursor.generation).isEqualTo(0)
        val coverage = StreamCoverage("googlehealth", "steps", at)
        assertThat(coverage.accountId).isNull()
        assertThat(coverage.deviceLastSync).isNull()
        assertThat(CommitResult(1, 2, 3).rejected).isFalse()
        assertThat(CommitResult(1, 2, 3).written).isEqualTo(3)
        assertThat(SyncResult.Status.entries.last()).isEqualTo(SyncResult.Status.ACCOUNT_CHANGED)
    }
}

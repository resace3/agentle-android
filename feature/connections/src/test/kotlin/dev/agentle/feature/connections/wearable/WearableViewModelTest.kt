package dev.agentle.feature.connections.wearable

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.api.SyncResult
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.Blocker
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.ErrorInfo
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.feature.connections.port.WearableAccountProblem
import dev.agentle.feature.connections.port.WearableAuthorizationPurpose
import dev.agentle.feature.connections.port.WearableAuthorizationResult
import dev.agentle.feature.connections.port.WearableDisconnectReport
import dev.agentle.feature.connections.testing.ConnectionsFixtures
import dev.agentle.feature.connections.testing.ConnectionsFixtures.NOW
import dev.agentle.feature.connections.testing.FakeWearablePort
import dev.agentle.feature.connections.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class WearableViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.viewModel(port: FakeWearablePort): WearableViewModel {
        val viewModel = WearableViewModel(port, ConnectionsFixtures.zonePort)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun WearableViewModel.state(): WearableUiState = uiState.value

    @Test
    fun `starts loading until the connection reports`() = runTest(main.dispatcher) {
        val viewModel = WearableViewModel(FakeWearablePort(), ConnectionsFixtures.zonePort)

        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.LOADING)
    }

    @Test
    fun `a failing connection state shows the load error and retry reloads it`() = runTest(main.dispatcher) {
        val port = FakeWearablePort().apply { failState = true }
        val viewModel = viewModel(port)
        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.LOAD_FAILED)

        port.failState = false
        viewModel.onAction(WearableAction.Retry)
        runCurrent()

        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.NOT_CONNECTED)
    }

    @Test
    fun `nothing connected shows not connected without account, data access or sync`() = runTest(main.dispatcher) {
        val state = viewModel(FakeWearablePort()).state()

        assertThat(state.phase).isEqualTo(WearablePhase.NOT_CONNECTED)
        assertThat(state.bound).isFalse()
        assertThat(state.accountLabel).isNull()
        assertThat(state.dataAccess).isEmpty()
        assertThat(state.sync).isNull()
        assertThat(state.canSync).isFalse()
        assertThat(state.canDisconnect).isFalse()
        assertThat(state.zone).isEqualTo(ConnectionsFixtures.ZONE)
    }

    @Test
    fun `the feature flag off shows not available in this build`() = runTest(main.dispatcher) {
        val state = viewModel(FakeWearablePort(ConnectionsFixtures.wearableNotAvailable())).state()

        assertThat(state.phase).isEqualTo(WearablePhase.NOT_AVAILABLE)
        assertThat(state.unavailableReason).isEqualTo(Blocker.NOT_IN_THIS_BUILD)
        assertThat(state.canSync).isFalse()
    }

    @Test
    fun `missing Play services shows not available with that reason`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableNotAvailable(Blocker.PLAY_SERVICES_MISSING))

        val state = viewModel(port).state()

        assertThat(state.phase).isEqualTo(WearablePhase.NOT_AVAILABLE)
        assertThat(state.unavailableReason).isEqualTo(Blocker.PLAY_SERVICES_MISSING)
    }

    @Test
    fun `connect shows connecting while Google answers, then connected`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val port = FakeWearablePort().apply { this.gate = gate }
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.Connect)
        runCurrent()
        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.CONNECTING)
        assertThat(viewModel.state().busy).isEqualTo(WearableBusy.CONNECTING)

        gate.complete(Unit)
        runCurrent()
        val state = viewModel.state()
        assertThat(port.authorizeCalls).containsExactly(WearableAuthorizationPurpose.CONNECT)
        assertThat(state.phase).isEqualTo(WearablePhase.CONNECTED)
        assertThat(state.accountLabel).isEqualTo(ConnectionsFixtures.GOOGLE_ACCOUNT)
        assertThat(state.sharedCount).isEqualTo(ConnectionsFixtures.WEARABLE_TYPES.size)
        assertThat(state.notice).isEqualTo(WearableNotice.Connected(missingCount = 0, accountChanged = false))
        assertThat(state.busy).isNull()
    }

    @Test
    fun `a partial grant connects and lists the declined data types`() = runTest(main.dispatcher) {
        val port = FakeWearablePort().apply {
            authorizeResult = WearableAuthorizationResult.Connected(missingDataTypes = ConnectionsFixtures.PARTIAL_DENIED)
            connectedState = ConnectionsFixtures.wearableConnected(denied = ConnectionsFixtures.PARTIAL_DENIED)
        }
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.Connect)
        runCurrent()

        val state = viewModel.state()
        assertThat(state.phase).isEqualTo(WearablePhase.CONNECTED)
        assertThat(state.partial).isTrue()
        assertThat(state.sharedCount).isEqualTo(ConnectionsFixtures.WEARABLE_TYPES.size - 4)
        assertThat(state.notice).isEqualTo(WearableNotice.Connected(missingCount = 4, accountChanged = false))
    }

    @Test
    fun `grant more asks Google again for the declined data types`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected(denied = ConnectionsFixtures.PARTIAL_DENIED))
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.GrantMore)
        runCurrent()

        assertThat(port.authorizeCalls).containsExactly(WearableAuthorizationPurpose.GRANT_MORE)
        assertThat(viewModel.state().partial).isFalse()
    }

    @Test
    fun `each authorization outcome without a connection leaves a message and nothing bound`() = runTest(main.dispatcher) {
        val outcomes = mapOf(
            WearableAuthorizationResult.Denied to WearableNotice.ConsentDenied,
            WearableAuthorizationResult.Cancelled to WearableNotice.ConsentCancelled,
            WearableAuthorizationResult.NothingShared to WearableNotice.NothingShared,
            WearableAuthorizationResult.Unavailable(Blocker.PLAY_SERVICES_MISSING) to
                WearableNotice.Unavailable(Blocker.PLAY_SERVICES_MISSING),
            WearableAuthorizationResult.Failed(AppError.NetworkUnavailable()) to
                WearableNotice.AuthorizationFailed(AppError.NetworkUnavailable()),
            WearableAuthorizationResult.Failed(AppError.Unexpected("developer_error")) to
                WearableNotice.AuthorizationFailed(AppError.Unexpected("developer_error")),
        )
        for ((result, notice) in outcomes) {
            val viewModel = viewModel(FakeWearablePort().apply { authorizeResult = result })

            viewModel.onAction(WearableAction.Connect)
            runCurrent()

            assertThat(viewModel.state().notice).isEqualTo(notice)
            assertThat(viewModel.state().phase).isEqualTo(WearablePhase.NOT_CONNECTED)
            assertThat(viewModel.state().bound).isFalse()
        }
    }

    @Test
    fun `an account problem on the first connection shows what to fix on Google's side`() = runTest(main.dispatcher) {
        val problem = WearableAccountProblem.NotLinked(actionUrl = "https://health.google.example/link")
        val viewModel = viewModel(
            FakeWearablePort().apply { authorizeResult = WearableAuthorizationResult.AccountProblem(problem) },
        )

        viewModel.onAction(WearableAction.Connect)
        runCurrent()

        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.ACCOUNT_PROBLEM)
        assertThat(viewModel.state().accountProblem).isEqualTo(problem)
        assertThat(viewModel.state().canDisconnect).isFalse()
    }

    @Test
    fun `an account change reported by the connection offers reconnect and disconnect`() = runTest(main.dispatcher) {
        val connected = ConnectionsFixtures.wearableConnected(connection = ConnectionStatus.ERROR)
        val port = FakeWearablePort(connected.copy(accountProblem = WearableAccountProblem.AccountChanged))

        val state = viewModel(port).state()

        assertThat(state.phase).isEqualTo(WearablePhase.ACCOUNT_PROBLEM)
        assertThat(state.accountProblem).isEqualTo(WearableAccountProblem.AccountChanged)
        assertThat(state.canDisconnect).isTrue()
        assertThat(state.canSync).isFalse()
    }

    @Test
    fun `a connection error without an account problem shows the error and its code`() = runTest(main.dispatcher) {
        val error = ErrorInfo(code = "remote_server_error", at = NOW - 5.minutes)
        val port = FakeWearablePort(
            ConnectionsFixtures.wearableConnected(
                connection = ConnectionStatus.ERROR,
                syncState = SyncStatus.FAILED,
                lastError = error,
            ),
        )

        val state = viewModel(port).state()

        assertThat(state.phase).isEqualTo(WearablePhase.CONNECTION_ERROR)
        assertThat(state.sync?.errorCode).isEqualTo("remote_server_error")
        assertThat(state.canDisconnect).isTrue()
    }

    @Test
    fun `a paused connection shows paused`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected(connection = ConnectionStatus.DISABLED))

        assertThat(viewModel(port).state().phase).isEqualTo(WearablePhase.PAUSED)
    }

    @Test
    fun `needs re-authorization offers reconnect, which asks Google again`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableNeedsReauth())
        val viewModel = viewModel(port)
        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.NEEDS_REAUTH)
        assertThat(viewModel.state().canSync).isFalse()

        viewModel.onAction(WearableAction.Reconnect)
        runCurrent()

        assertThat(port.authorizeCalls).containsExactly(WearableAuthorizationPurpose.RECONNECT)
        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.CONNECTED)
    }

    @Test
    fun `sync now shows the running sync, then its result and the new last sync`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected()).apply { this.gate = gate }
        val viewModel = viewModel(port)
        assertThat(viewModel.state().canSync).isTrue()

        viewModel.onAction(WearableAction.SyncNow)
        runCurrent()
        assertThat(viewModel.state().syncRunning).isTrue()
        assertThat(viewModel.state().canSync).isFalse()

        gate.complete(Unit)
        runCurrent()
        val state = viewModel.state()
        assertThat(state.notice).isEqualTo(WearableNotice.SyncFinished(SyncResult.Status.SUCCESS, committed = 118, error = null))
        assertThat(state.sync?.state).isEqualTo(SyncStatus.SUCCEEDED)
        assertThat(state.sync?.lastSuccess).isEqualTo(NOW)
        assertThat(port.syncRuns).isEqualTo(1)
    }

    @Test
    fun `a failed sync reports its reason code`() = runTest(main.dispatcher) {
        val failure = AppError.RateLimited()
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected()).apply {
            syncResult = SyncResult(ConnectorIds.GOOGLE_HEALTH, SyncResult.Status.FAILED, NOW, NOW, error = failure)
        }
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.SyncNow)
        runCurrent()

        assertThat(viewModel.state().notice).isEqualTo(WearableNotice.SyncFinished(SyncResult.Status.FAILED, 0, failure))
        assertThat(viewModel.state().sync?.state).isEqualTo(SyncStatus.FAILED)
    }

    @Test
    fun `a sync the connection runs in the background disables sync now`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected(syncState = SyncStatus.RUNNING))

        val state = viewModel(port).state()

        assertThat(state.syncRunning).isTrue()
        assertThat(state.canSync).isFalse()
    }

    @Test
    fun `rate limited shows when syncing resumes and disables sync now`() = runTest(main.dispatcher) {
        val until = NOW + 20.minutes
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected().copy(rateLimitedUntil = until))

        val state = viewModel(port).state()

        assertThat(state.phase).isEqualTo(WearablePhase.CONNECTED)
        assertThat(state.rateLimitedUntil).isEqualTo(until)
        assertThat(state.canSync).isFalse()
    }

    @Test
    fun `offline disables sync now`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected().copy(networkAvailable = false))

        val state = viewModel(port).state()

        assertThat(state.offline).isTrue()
        assertThat(state.canSync).isFalse()
    }

    @Test
    fun `one action at a time - connect is ignored while a sync runs`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val port = FakeWearablePort(ConnectionsFixtures.wearableNeedsReauth()).apply { this.gate = gate }
        val viewModel = viewModel(port)
        viewModel.onAction(WearableAction.SyncNow)
        runCurrent()

        viewModel.onAction(WearableAction.Reconnect)
        viewModel.onAction(WearableAction.RequestDisconnect)
        gate.complete(Unit)
        runCurrent()

        assertThat(port.authorizeCalls).isEmpty()
        assertThat(viewModel.state().disconnectDialog).isNull()
    }

    @Test
    fun `disconnect asks first, then deletes the synced data when chosen`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected())
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.RequestDisconnect)
        runCurrent()
        assertThat(viewModel.state().disconnectDialog).isEqualTo(WearableDisconnectDialog(deleteData = false))
        viewModel.onAction(WearableAction.SetDeleteData(true))
        runCurrent()
        assertThat(viewModel.state().disconnectDialog).isEqualTo(WearableDisconnectDialog(deleteData = true))
        viewModel.onAction(WearableAction.ConfirmDisconnect)
        runCurrent()

        val state = viewModel.state()
        assertThat(port.disconnectCalls).containsExactly(true)
        assertThat(state.phase).isEqualTo(WearablePhase.NOT_CONNECTED)
        assertThat(state.disconnectDialog).isNull()
        assertThat(state.notice).isEqualTo(WearableNotice.Disconnected(WearableDisconnectReport(true, accessRevoked = true)))
    }

    @Test
    fun `dismissing the disconnect dialog keeps the connection`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected())
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.RequestDisconnect)
        viewModel.onAction(WearableAction.DismissDisconnect)
        runCurrent()

        assertThat(port.disconnectCalls).isEmpty()
        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.CONNECTED)
        assertThat(viewModel.state().disconnectDialog).isNull()
    }

    @Test
    fun `a disconnect shows not connected at once, even before the connection reports it`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected()).apply {
            disconnectOutcome = Outcome.Success(WearableDisconnectReport(dataDeleted = false, accessRevoked = false))
        }
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.RequestDisconnect)
        viewModel.onAction(WearableAction.ConfirmDisconnect)
        runCurrent()

        val state = viewModel.state()
        assertThat(port.current.value.metadata.connection).isEqualTo(ConnectionStatus.CONNECTED)
        assertThat(state.phase).isEqualTo(WearablePhase.NOT_CONNECTED)
        assertThat(state.accountLabel).isNull()
        assertThat(state.canSync).isFalse()
        assertThat(state.notice).isEqualTo(WearableNotice.Disconnected(WearableDisconnectReport(false, accessRevoked = false)))
    }

    @Test
    fun `no sync runs after a disconnect`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected())
        val viewModel = viewModel(port)
        viewModel.onAction(WearableAction.RequestDisconnect)
        viewModel.onAction(WearableAction.ConfirmDisconnect)
        runCurrent()

        viewModel.onAction(WearableAction.SyncNow)
        runCurrent()

        assertThat(port.syncRuns).isEqualTo(0)
        assertThat(viewModel.state().notice)
            .isEqualTo(WearableNotice.SyncFinished(SyncResult.Status.SKIPPED_NOT_CONNECTED, committed = 0, error = null))
    }

    @Test
    fun `a failed disconnect keeps the connection and says why`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected()).apply {
            disconnectOutcome = Outcome.Failure(AppError.DatabaseError())
        }
        val viewModel = viewModel(port)

        viewModel.onAction(WearableAction.RequestDisconnect)
        viewModel.onAction(WearableAction.ConfirmDisconnect)
        runCurrent()

        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.CONNECTED)
        assertThat(viewModel.state().notice).isEqualTo(WearableNotice.DisconnectFailed(AppError.DatabaseError()))
    }

    @Test
    fun `connecting again after a disconnect shows the new connection`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected())
        val viewModel = viewModel(port)
        viewModel.onAction(WearableAction.RequestDisconnect)
        viewModel.onAction(WearableAction.ConfirmDisconnect)
        runCurrent()

        viewModel.onAction(WearableAction.Connect)
        runCurrent()

        assertThat(viewModel.state().phase).isEqualTo(WearablePhase.CONNECTED)
    }

    @Test
    fun `dismissing a message clears it`() = runTest(main.dispatcher) {
        val viewModel = viewModel(FakeWearablePort().apply { authorizeResult = WearableAuthorizationResult.Denied })
        viewModel.onAction(WearableAction.Connect)
        runCurrent()

        viewModel.onAction(WearableAction.DismissNotice)
        runCurrent()

        assertThat(viewModel.state().notice).isNull()
    }

    @Test
    fun `the screen state never prints the account`() = runTest(main.dispatcher) {
        val state = viewModel(FakeWearablePort(ConnectionsFixtures.wearableConnected())).state()

        assertThat(state.toString()).doesNotContain(ConnectionsFixtures.GOOGLE_ACCOUNT)
        assertThat(ConnectionsFixtures.wearableConnected().toString()).doesNotContain(ConnectionsFixtures.GOOGLE_ACCOUNT)
    }

    @Test
    fun `data access lists every data type with whether it is shared`() = runTest(main.dispatcher) {
        val port = FakeWearablePort(ConnectionsFixtures.wearableConnected(denied = setOf(EventType.WEIGHT)))

        val access = viewModel(port).state().dataAccess

        assertThat(access.map { it.type }).containsExactlyElementsIn(ConnectionsFixtures.WEARABLE_TYPES).inOrder()
        assertThat(access.single { it.type == EventType.WEIGHT }.permission)
            .isEqualTo(PermissionState.DENIED)
    }
}

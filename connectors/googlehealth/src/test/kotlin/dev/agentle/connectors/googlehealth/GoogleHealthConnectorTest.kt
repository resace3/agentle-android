package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.ConnectionStatus
import dev.agentle.core.model.PermissionState
import dev.agentle.core.model.SyncStatus
import dev.agentle.fakes.googlehealth.FakeAuthorization
import dev.agentle.fakes.googlehealth.FakeResponse
import dev.agentle.fakes.googlehealth.GoogleHealthFixtures
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Connection, authorization and account handling (docs/research/05 §7.6, §8.7; round-2 corrections 1 to 3). */
class GoogleHealthConnectorTest {
    private val identityPath = "/v4/users/me/identity"

    @Test
    fun `R05 8 7 S01 connect binds only a hash of the user id`() = runTest {
        harness(GhHarness()) {
            val connected = connect() as GoogleHealthConnectResult.Connected
            assertThat(connected.accountChanged).isFalse()
            assertThat(connected.grantedStreams).containsExactlyElementsIn(GoogleHealthStreams.ALL)
            val binding = requireNotNull(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM))
            assertThat(binding.accountId).isEqualTo(GhHarness.ACCOUNT)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.CONNECTED)
            assertThat(connector.metadata.value.permissionSummary).isEqualTo(PermissionState.ALLOWED)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            val stored = sink.events().joinToString("\n") + sink.cursorOf("steps") + binding
            assertThat(stored).doesNotContainMatch(GhHarness.RAW_USER_ID)
            assertThat(stored).doesNotContain("A1B2C3")
            assertClean()
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["account-not-linked", "profile-not-ready", "legacy-fitbit-account"])
    fun `R05 8 7 S02 S03 S04 connect reports the account problem and binds nothing`(scenario: String) = runTest {
        harness(GhHarness(scenario = scenario)) {
            val failed = connector.connect() as GoogleHealthConnectResult.Failed
            val expected = when (scenario) {
                "account-not-linked" -> GoogleHealthAccountProblem.AccountNotLinked("https://fitbit.google.com/auth/signup")
                "profile-not-ready" -> GoogleHealthAccountProblem.ProfileNotReady
                else -> GoogleHealthAccountProblem.LegacyFitbitAccount
            }
            assertThat(failed.problem).isEqualTo(expected)
            assertThat(failed.error).isInstanceOf(AppError.NotEligible::class.java)
            assertThat(requests().map { it.path }).containsExactly(identityPath)
            assertThat(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM)).isNull()
            assertThat(sync().status).isEqualTo(SyncResult.Status.SKIPPED_NOT_CONNECTED)
            assertClean()
        }
    }

    @Test
    fun `R05 8 7 S05 a legacy 403 on data calls is unsupported, not an account problem`() = runTest {
        harness(GhHarness(scenario = "legacy-forbidden")) {
            connect()
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isInstanceOf(AppError.UnsupportedFeature::class.java)
            assertThat(connector.accountProblem.value).isNull()
            assertThat(sink.events()).isEmpty()
            assertClean()
        }
    }

    @Test
    fun `R05 8 7 S06 testing-build-01 a partial grant syncs only the granted streams`() = runTest {
        harness(GhHarness()) {
            auth.grantPartial(listOf("sleep.readonly"))
            val connected = connect() as GoogleHealthConnectResult.Connected
            assertThat(connected.grantedStreams).containsExactly(GoogleHealthStreams.SLEEP)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(requests().map { it.path }.toSet()).containsExactly(identityPath, "/v4/users/me/dataTypes/sleep/dataPoints")
            val metadata = connector.metadata.value
            assertThat(metadata.permissionSummary).isEqualTo(PermissionState.PARTIALLY_ALLOWED)
            assertThat(metadata.streamPermissions[GoogleHealthStreams.SLEEP]).isEqualTo(PermissionState.ALLOWED)
            assertThat(metadata.streamPermissions[GoogleHealthStreams.STEPS]).isEqualTo(PermissionState.DENIED)
            assertThat(events(GoogleHealthStreams.SLEEP)).hasSize(2)
            assertThat(sink.events().map { it.source.stream }.toSet()).containsExactly(GoogleHealthStreams.SLEEP)
            assertClean()
        }
    }

    @Test
    fun `a grant without any Google Health scope connects nothing and syncs nothing`() = runTest {
        harness(GhHarness()) {
            auth.grantPartial(emptyList())
            val failed = connector.connect() as GoogleHealthConnectResult.Failed
            assertThat(failed.error).isInstanceOf(AppError.PermissionDenied::class.java)
            auth.issue("fake-valid")
            connect()
            auth.grantPartial(emptyList())
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.SKIPPED_NO_PERMISSION)
            assertThat(connector.metadata.value.permissionSummary).isEqualTo(PermissionState.DENIED)
            assertThat(requests().map { it.path }).containsExactly(identityPath)
        }
    }

    @Test
    fun `R05 8 7 S21 an expired token is invalidated once and the call retried`() = runTest {
        harness(GhHarness(scenario = "token-expired")) {
            connect()
            assertThat(auth.invalidations).isEqualTo(1)
            assertThat(requests().map { it.path }).containsExactly(identityPath, identityPath)
            auth.expireOnce()
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(auth.invalidations).isEqualTo(2)
            assertClean()
        }
    }

    @Test
    fun `R05 8 7 S22 a second 401 needs reauth, persists it and sends no third request`() = runTest {
        harness(GhHarness()) {
            connect()
            fake.defaultScenario = "token-revoked"
            val before = requests().size
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isEqualTo(AppError.AuthenticationRequired("googlehealth"))
            assertThat(result.error?.retryable).isFalse()
            assertThat(requests().size - before).isEqualTo(2)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.NEEDS_REAUTH)
            val restarted = newConnector()
            restarted.refreshState()
            assertThat(restarted.metadata.value.connection).isEqualTo(ConnectionStatus.NEEDS_REAUTH)
            assertClean()
        }
    }

    @Test
    fun `round-2 1 a background worker that gets NeedsResolution stops without UI and without requests`() = runTest {
        harness(GhHarness()) {
            connect()
            auth.needResolutionInBackground()
            val before = requests().size
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isInstanceOf(AppError.AuthenticationRequired::class.java)
            assertThat(result.error?.retryable).isFalse()
            assertThat(requests().size).isEqualTo(before)
            assertThat(auth.calls.filter { it.startsWith("token") }.last()).isEqualTo("token(interactive=false)")
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.NEEDS_REAUTH)
            // The user resolves it in the UI: connect asks interactively, then background runs work again.
            assertThat(connector.connect()).isInstanceOf(GoogleHealthConnectResult.Connected::class.java)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.CONNECTED)
            assertClean()
        }
    }

    @Test
    fun `connect hands a pending resolution to the UI and reports a denial`() = runTest {
        harness(GhHarness()) {
            auth.requireConsent()
            auth.interactiveOutcome = FakeAuthorization.NeedsResolution
            val pending = connector.connect() as GoogleHealthConnectResult.NeedsResolution
            assertThat(pending.handle).isEqualTo(BridgedAuthorizer.RESOLUTION_HANDLE)
            auth.interactiveOutcome = FakeAuthorization.Denied
            val denied = connector.connect() as GoogleHealthConnectResult.Failed
            assertThat(denied.error).isEqualTo(AppError.AuthenticationRequired("googlehealth", "denied"))
            assertThat(requests()).isEmpty()
        }
    }

    @Test
    fun `testing-build-01 access revoked upstream ends in NEEDS_REAUTH after one request`() = runTest {
        harness(GhHarness()) {
            connect()
            auth.revokeAccessUpstream()
            val before = requests().size
            val result = sync()
            assertThat(result.error).isEqualTo(AppError.AuthenticationRequired("googlehealth"))
            assertThat(requests().size - before).isEqualTo(1)
            assertThat(auth.invalidations).isEqualTo(1)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.NEEDS_REAUTH)
            assertClean()
        }
    }

    @Test
    fun `testing-build-01 a network error from the authorizer is retryable and clears on the next run`() = runTest {
        harness(GhHarness()) {
            connect()
            auth.failWithNetworkError()
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isEqualTo(AppError.NetworkUnavailable("auth_status_7"))
            assertThat(result.error?.retryable).isTrue()
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.CONNECTED)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
        }
    }

    @Test
    fun `testing-build-01 missing Play services make the source unavailable until they are back`() = runTest {
        harness(GhHarness()) {
            auth.playServicesMissing()
            val failed = connector.connect() as GoogleHealthConnectResult.Failed
            assertThat(failed.error).isEqualTo(AppError.UnsupportedFeature("googlehealth.play_services", "auth_status_1"))
            auth.playServicesAvailable()
            connect()
            auth.playServicesMissing(FakeAuthorization.SERVICE_VERSION_UPDATE_REQUIRED)
            val result = sync()
            assertThat(result.error?.retryable).isFalse()
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.UNAVAILABLE)
            assertThat(connector.disconnect()).isInstanceOf(Outcome.Failure::class.java)
            auth.playServicesAvailable()
            connect()
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.CONNECTED)
        }
    }

    @Test
    fun `a misconfigured OAuth client is an error state`() = runTest {
        harness(GhHarness()) {
            connect()
            auth.enqueue(FakeAuthorization.Failure(FakeAuthorization.DEVELOPER_ERROR))
            sync()
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.ERROR)
            assertThat(connector.metadata.value.lastError?.code).isEqualTo("unsupported_feature")
        }
    }

    @Test
    fun `round-2 3 another account stops syncing until the user reconnects, then starts fresh`() = runTest {
        harness(GhHarness()) {
            connect()
            sync()
            val firstAccountRows = sink.events().size
            fake.config = fake.config.copy(healthUserId = "5550001111")
            clock.advanceBy(1.hours)
            val before = requests().size
            val changed = sync()
            assertThat(changed.status).isEqualTo(SyncResult.Status.ACCOUNT_CHANGED)
            assertThat(requests().drop(before).map { it.path }).containsExactly(identityPath)
            assertThat(connector.accountProblem.value).isEqualTo(GoogleHealthAccountProblem.AccountChanged)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.NEEDS_REAUTH)
            assertThat(sync().status).isEqualTo(SyncResult.Status.ACCOUNT_CHANGED)
            val restarted = newConnector()
            restarted.refreshState()
            assertThat(restarted.accountProblem.value).isEqualTo(GoogleHealthAccountProblem.AccountChanged)

            val reconnected = connector.connect() as GoogleHealthConnectResult.Connected
            assertThat(reconnected.accountChanged).isTrue()
            assertThat(connector.accountProblem.value).isNull()
            val newAccount = GoogleHealthConnector.accountIdOf("5550001111")
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(sink.cursorOf(GoogleHealthStreams.STEPS)?.accountId).isEqualTo(newAccount)
            val keys = sink.events().map { it.dedupKey }
            fun keysOf(account: String) = keys.filter { it.startsWith("gh|$account|") }.map { it.substringAfter("gh|$account|") }
            assertThat(keysOf(GhHarness.ACCOUNT)).hasSize(firstAccountRows)
            // Device keys come from resource names, which carry the user: each account has its own device rows.
            assertThat(keysOf(newAccount).filterNot { it.startsWith("devices|") })
                .containsExactlyElementsIn(keysOf(GhHarness.ACCOUNT).filterNot { it.startsWith("devices|") })
            assertThat(keysOf(newAccount).count { it.startsWith("devices|") }).isEqualTo(2)
            assertClean()
        }
    }

    @Test
    fun `disconnect revokes, unbinds and keeps the data`() = runTest {
        harness(GhHarness()) {
            connect()
            sync()
            val rows = sink.events()
            assertThat(connector.disconnect()).isEqualTo(Outcome.Success(Unit))
            assertThat(auth.calls).contains("revoke()")
            assertThat(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM)?.accountId).isNull()
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.NOT_CONNECTED)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SKIPPED_NOT_CONNECTED)
            assertThat(sink.events()).isEqualTo(rows)
        }
    }

    @Test
    fun `round-2 2 the live API stays off behind its flag and nothing is sent`() = runTest {
        harness(GhHarness()) {
            val production = newConnector(GoogleHealthConfig())
            assertThat(production.metadata.value.connection).isEqualTo(ConnectionStatus.UNAVAILABLE)
            val result = production.sync(SyncTrigger.SCHEDULED)
            assertThat(result.status).isEqualTo(SyncResult.Status.SKIPPED_DISABLED)
            assertThat(result.error).isEqualTo(AppError.UnsupportedFeature(GoogleHealthConnector.LIVE_API_FEATURE))
            assertThat((production.connect() as GoogleHealthConnectResult.Failed).error)
                .isEqualTo(AppError.UnsupportedFeature(GoogleHealthConnector.LIVE_API_FEATURE))
            assertThat(auth.calls).isEmpty()
            assertThat(fake.journal).isEmpty()
        }
    }

    @Test
    fun `round-2 8 the client is pinned to its host and never follows a redirect`() = runTest {
        val production = GoogleHealthConfig(liveApiEnabled = true).httpClientConfig()
        assertThat(production.allowedHosts).containsExactly("health.googleapis.com")
        assertThat(production.allowCleartextLoopback).isFalse()
        harness(GhHarness()) {
            assertThat(config.httpClientConfig().allowedHosts).containsExactly("127.0.0.1")
            fake.inject(10) { _, _ -> FakeResponse(302, "", null, mapOf("Location" to "https://example.com/v4/users/me/identity")) }
            val failed = connector.connect() as GoogleHealthConnectResult.Failed
            assertThat(failed.error).isEqualTo(AppError.NetworkUnavailable("http_302"))
            assertThat(fake.journal.all { it.path == identityPath }).isTrue()
            assertThat(fake.journal).hasSize(4)
        }
    }

    @Test
    fun `disabling stops collection and enabling restores the state`() = runTest {
        harness(GhHarness()) {
            connect()
            connector.setEnabled(false)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.DISABLED)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SKIPPED_DISABLED)
            assertThat(requests().map { it.path }).containsExactly(identityPath)
            connector.setEnabled(true)
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.CONNECTED)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
        }
    }

    @Test
    fun `a broken store fails the run with the exception class only`() = runTest {
        harness(GhHarness()) {
            connect()
            sink.failNext = IllegalStateException("secret-detail fake-valid")
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isEqualTo(AppError.DatabaseError("IllegalStateException"))
            assertThat(result.toString()).doesNotContain("secret-detail")
            assertThat(logs.joinToString { "${it.message} ${it.fields}" }).doesNotContain("secret-detail")
            assertThat(connector.metadata.value.syncState).isEqualTo(SyncStatus.FAILED)
            sink.failNext = IllegalStateException("secret-detail")
            assertThat(
                (connector.connect() as GoogleHealthConnectResult.Failed).error,
            ).isEqualTo(AppError.DatabaseError("IllegalStateException"))
        }
    }

    @Test
    fun `refreshState rebuilds connection and coverage after a restart`() = runTest {
        harness(GhHarness()) {
            val before = newConnector()
            before.refreshState()
            assertThat(before.metadata.value.connection).isEqualTo(ConnectionStatus.NOT_CONNECTED)
            connect()
            sync()
            val restarted = newConnector()
            restarted.refreshState()
            val metadata = restarted.metadata.value
            assertThat(metadata.connection).isEqualTo(ConnectionStatus.CONNECTED)
            assertThat(metadata.coverageThrough).isEqualTo(connector.metadata.value.coverageThrough)
            assertThat(metadata.permissionSummary).isEqualTo(PermissionState.ALLOWED)
            assertThat(metadata.lastSuccessfulCollection).isNotNull()
        }
    }

    @Test
    fun `R05 8 7 S28 S30 a short Retry-After is waited out in virtual time`() = runTest {
        harness(GhHarness(scenario = "rate-limited-retry-after")) {
            val start = clock.now()
            connect()
            assertThat(clock.now() - start).isAtLeast(7.seconds)
            val identity = requests().map { it.at }
            assertThat(identity).hasSize(2)
            assertThat(identity[1] - identity[0]).isEqualTo(7.seconds)
        }
        harness(GhHarness()) {
            fake.inject(1, "E429", retryAfter = GoogleHealthFixtures.RETRY_AFTER_C)
            val start = clock.now()
            connect()
            assertThat(clock.now() - start).isAtLeast(30.seconds)
            assertThat(clock.now() - start).isLessThan(31.seconds)
        }
    }

    @Test
    fun `R05 8 7 S29 repeated 429s stop the run and later runs wait for nextAllowedAt`() = runTest {
        harness(GhHarness(scenario = "rate-limited")) {
            connect()
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isInstanceOf(AppError.RateLimited::class.java)
            assertThat(result.error?.retryable).isTrue()
            val settings = requests().filter { it.path.endsWith("/settings") }.map { it.at }
            assertThat(settings).hasSize(3)
            assertThat(settings[1] - settings[0]).isIn(com.google.common.collect.Range.closed(1600.milliseconds, 2400.milliseconds))
            assertThat(settings[2] - settings[1]).isIn(com.google.common.collect.Range.closed(3200.milliseconds, 4800.milliseconds))
            val backoff = GhBackoff.decode(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM)?.lastAttemptCursor)
            assertThat(backoff.failures).isEqualTo(1)
            assertThat(requireNotNull(backoff.nextAllowedAt) - clock.now()).isAtMost(1.minutes)
            val count = requests().size
            val waiting = sync()
            assertThat(waiting.error).isInstanceOf(AppError.RateLimited::class.java)
            assertThat(requests().size).isEqualTo(count)
            fake.defaultScenario = "happy"
            clock.advanceBy(2.minutes)
            val healthy = newConnector(config.copy(baseUrl = fake.rootUrl()))
            assertThat(healthy.sync(SyncTrigger.SCHEDULED).status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(GhBackoff.decode(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM)?.lastAttemptCursor)).isEqualTo(GhBackoff())
        }
    }

    @Test
    fun `R05 8 7 S31 a Retry-After beyond 60 seconds is not waited in-run but sets nextAllowedAt`() = runTest {
        harness(GhHarness()) {
            connect()
            fake.defaultScenario = "rate-limited-long"
            val start = clock.now()
            val result = sync()
            assertThat(clock.now() - start).isLessThan(5.seconds)
            assertThat((result.error as AppError.RateLimited).retryAfter).isEqualTo(3600.seconds)
            val backoff = GhBackoff.decode(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM)?.lastAttemptCursor)
            assertThat(backoff.nextAllowedAt).isEqualTo(GhPlanner.truncate(clock.now()) + 3600.seconds)
        }
    }

    @Test
    fun `R05 8 7 S35 an account unlinked later stops the source and is persisted`() = runTest {
        harness(GhHarness()) {
            connect()
            fake.defaultScenario = "account-not-linked"
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(result.error).isEqualTo(AppError.NotEligible("account_not_linked"))
            assertThat(connector.accountProblem.value)
                .isEqualTo(GoogleHealthAccountProblem.AccountNotLinked("https://fitbit.google.com/auth/signup"))
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.ERROR)
            val restarted = newConnector()
            restarted.refreshState()
            assertThat(restarted.accountProblem.value).isEqualTo(GoogleHealthAccountProblem.AccountNotLinked(null))
            fake.defaultScenario = "happy"
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(connector.accountProblem.value).isNull()
            assertThat(connector.metadata.value.connection).isEqualTo(ConnectionStatus.CONNECTED)
        }
    }
}

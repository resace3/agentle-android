package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.api.StreamCoverage
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.connectors.googlehealth.GoogleHealthStreams.DAILY_STEPS
import dev.agentle.connectors.googlehealth.GoogleHealthStreams.SLEEP
import dev.agentle.connectors.googlehealth.GoogleHealthStreams.STEPS
import dev.agentle.core.common.AppError
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DailyTotalPayload
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.StepsPayload
import dev.agentle.fakes.googlehealth.GhDataTypes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** End-to-end sync against the fake server (docs/research/05 §8.6, §8.7). */
class GoogleHealthSyncTest {
    private val reconcileSteps = "/v4/users/me/dataTypes/steps/dataPoints:reconcile"
    private val listSleep = "/v4/users/me/dataTypes/sleep/dataPoints"
    private val trackerSync = Instant.parse("2026-10-01T11:58:03Z")
    private val lower = Regex(""">= "([^"]+)"""")

    private fun counts(events: List<PersonalEvent>) = events.map { (it.payload as StepsPayload).count }

    private fun dailySteps(h: GhHarness) = (h.events(DAILY_STEPS).single().payload as DailyTotalPayload).value

    @Test
    fun `first sync stores the documented day, one method per data type, with coverage in every window write`() = runTest {
        harness(GhHarness()) {
            connect()
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(result.committed).isEqualTo(26)
            assertThat(counts(events(STEPS))).containsExactly(0L, 87L, 98L, 112L).inOrder()
            assertThat(events(STEPS).all { it.metadata.provenance == null }).isTrue()
            assertThat(dailySteps(this)).isEqualTo(297.0)
            val methods = requests().filter { "/dataTypes/" in it.path }
                .groupBy({ it.path.substringAfter("/dataTypes/").substringBefore('/') }, { it.path.substringAfterLast('/') })
                .mapValues { it.value.toSet() }
            val interval = setOf("dataPoints:reconcile", "dataPoints:dailyRollUp")
            assertThat(methods).containsExactlyEntriesIn(
                mapOf(
                    "steps" to interval, "distance" to interval, "active-energy-burned" to interval, "floors" to interval,
                    "heart-rate" to setOf("dataPoints:rollUp"), "total-calories" to setOf("dataPoints:dailyRollUp"),
                    "sleep" to setOf("dataPoints"), "exercise" to setOf("dataPoints"), "daily-resting-heart-rate" to setOf("dataPoints"),
                    "weight" to setOf("dataPoints"), "body-fat" to setOf("dataPoints"),
                ),
            )
            assertThat(sink.coverageOf(STEPS))
                .isEqualTo(StreamCoverage(ConnectorIds.GOOGLE_HEALTH, STEPS, trackerSync, GhHarness.ACCOUNT, trackerSync))
            assertThat(sink.coverageOf(GoogleHealthStreams.DAILY_TOTAL_CALORIES)?.coverageThrough).isEqualTo(trackerSync)
            assertThat(events(GoogleHealthStreams.DAILY_TOTAL_CALORIES)).isEmpty()
            val windows = sink.writes.filter { it.start != null }
            assertThat(windows.all { it.coverage?.stream == it.stream && !it.rejected }).isTrue()
            assertThat(sink.events().joinToString()).doesNotContain("A0:B1:C2")
            assertClean()
        }
    }

    @Test
    fun `idempotent - later runs and a deep re-sync write and delete nothing`() = runTest {
        harness(GhHarness()) {
            connect()
            sync()
            val stored = sink.events()
            clock.advanceBy(20.minutes)
            assertThat(sync().committed).isEqualTo(0)
            clock.advanceBy(20.minutes)
            assertThat(sync(SyncTrigger.DEEP_RESYNC).committed).isEqualTo(0)
            assertThat(sink.deleted).isEqualTo(0)
            assertThat(sink.events()).isEqualTo(stored)
            assertClean()
        }
    }

    @Test
    fun `R6a REALTIME-FEATURES-R1-4 a walk recorded by a watch and the phone counts once`() = runTest {
        harness(GhHarness(scenario = "overlapping-devices")) {
            connect()
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(counts(events(STEPS))).containsExactly(0L, 87L, 98L, 112L, 3000L).inOrder()
            assertThat(dailySteps(this)).isEqualTo(3297.0)
            assertClean()
        }
    }

    @Test
    fun `S34 R8f four 503s on page 2 commit nothing for that window and the next run converges`() = runTest {
        harness(GhHarness(configure = { it.copy(pageSize = 2) })) {
            connect()
            fake.inject(4, "E503") { it.path == reconcileSteps && it.param("pageToken") != null }
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.PARTIAL)
            assertThat(result.error).isEqualTo(AppError.RemoteServerError(503, "http_503"))
            assertThat(counts(events(STEPS))).containsExactly(0L)
            assertThat(sink.cursorOf(STEPS)?.lastErrorCode).isEqualTo("remote_server_error")
            clock.advanceBy(20.minutes)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(counts(events(STEPS))).containsExactly(0L, 87L, 98L, 112L).inOrder()
            assertClean()
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("malformed, PARTIAL, 0", "captive-portal, FAILED, 0", "disconnect-mid-body, SUCCESS, 26", "no-content-type, SUCCESS, 26")
    fun `S36 broken bodies are retried once and never committed`(scenario: String, status: SyncResult.Status, rows: Int) = runTest {
        harness(GhHarness()) {
            connect()
            fake.defaultScenario = scenario
            val result = sync()
            assertThat(result.status).isEqualTo(status)
            assertThat(sink.events()).hasSize(rows)
            if (status != SyncResult.Status.SUCCESS) assertThat(result.error).isEqualTo(AppError.NetworkUnavailable("malformed_body"))
        }
    }

    @Test
    fun `R7d upstream deletions apply by window replace, a failed window deletes nothing`() = runTest {
        harness(GhHarness()) {
            connect()
            sync()
            fake.dataset = fake.dataset
                .minus { it.type == GhDataTypes.STEPS && it.start == Instant.parse("2026-09-30T12:01:00Z") }
                .minus { it.id == "7821966286120953002" }
            fake.inject(4, "E503") { it.path == listSleep }
            clock.advanceBy(20.minutes)
            assertThat(sync().status).isEqualTo(SyncResult.Status.PARTIAL)
            assertThat(sink.deleted).isEqualTo(0)
            clock.advanceBy(20.minutes)
            sync()
            assertThat(events(SLEEP)).hasSize(1)
            clock.advanceBy(20.minutes)
            sync(SyncTrigger.DEEP_RESYNC)
            assertThat(sink.deleted).isEqualTo(2)
            assertThat(counts(events(STEPS))).containsExactly(0L, 87L, 112L).inOrder()
            assertThat(dailySteps(this)).isEqualTo(199.0)
        }
    }

    @Test
    fun `round-2 5 after a deletion nothing older than the floor is fetched or stored again`() = runTest {
        harness(GhHarness()) {
            connect()
            sync()
            val floor = Instant.parse("2026-09-30T12:01:30Z")
            sink.deleteBefore(DataSourceId.of(ConnectorIds.GOOGLE_HEALTH, STEPS), floor)
            fake.reset()
            clock.advanceBy(20.minutes)
            sync(SyncTrigger.DEEP_RESYNC)
            val bounds = requests().filter { it.path == reconcileSteps }
                .map { Instant.parse(requireNotNull(lower.find(it.param("filter").orEmpty())).groupValues[1]) }
            assertThat(bounds.first()).isEqualTo(floor)
            assertThat(bounds.all { it >= floor }).isTrue()
            assertThat(counts(events(STEPS))).containsExactly(112L)
        }
    }

    @Test
    fun `rate limit - a 90-day backfill keeps 4 per second and 200 per minute in virtual time`() = runTest(timeout = 3.minutes) {
        harness(GhHarness()) {
            connect()
            val started = clock.elapsed()
            assertThat(sync(SyncTrigger.BACKFILL).status).isEqualTo(SyncResult.Status.SUCCESS)
            val times = requests().map { it.at }.sorted()
            assertThat(times.size).isGreaterThan(400)
            assertThat(times.indices.all { i -> times.count { it >= times[i] && it < times[i] + 1.seconds } <= 4 }).isTrue()
            assertThat(clock.elapsed() - started).isAtLeast(((times.size - 1) / 200).minutes)
            val state = GhStreamState.decode(sink.cursorOf(STEPS)?.lastSuccessCursor)
            assertThat(state.backfilledFrom).isEqualTo(GhHarness.START - 90.days)
            assertThat(sink.events()).hasSize(26)
            assertClean()
        }
    }

    @Test
    fun `R2-1 a disconnect during a run stops its writes and publishes nothing`() = runTest {
        harness(GhHarness()) {
            connect()
            var at = -1
            sink.onWrite = { stream ->
                if (stream == STEPS && at < 0) {
                    at = sink.writes.size
                    kotlinx.coroutines.runBlocking { connector.disconnect() }
                }
            }
            val result = sync()
            assertThat(result.status).isEqualTo(SyncResult.Status.SKIPPED_NOT_CONNECTED)
            assertThat(sink.writes.count { it.stream == STEPS && it.start != null }).isEqualTo(1)
            assertThat(sink.writes.drop(at).none { it.stream != STEPS && it.stream != GoogleHealthConnector.ACCOUNT_STREAM }).isTrue()
            assertThat(sink.cursorOf(GoogleHealthConnector.ACCOUNT_STREAM)?.accountId).isNull()
            assertThat(connector.metadata.value.connection).isEqualTo(dev.agentle.core.model.ConnectionStatus.NOT_CONNECTED)
            assertThat(connector.metadata.value.coverageThrough).isEmpty()
        }
    }

    @Test
    fun `R1-2 an interrupted overlap re-read keeps the old fetchedAt so the next run re-reads again`() = runTest {
        harness(GhHarness(configure = { it.copy(streams = setOf(STEPS)) })) {
            connect()
            sync()
            clock.advanceBy(20.minutes)
            fake.inject(4, "E503") { it.path == reconcileSteps && it.param("filter").orEmpty().contains(">= \"2026-09-30T12:00:00Z\"") }
            assertThat(sync().status).isEqualTo(SyncResult.Status.FAILED)
            assertThat(GhStreamState.decode(sink.cursorOf(STEPS)?.lastSuccessCursor).fetchedAt).isEqualTo(GhHarness.START)
        }
    }

    @Test
    fun `R3-1 a backward clock jump beyond the overlap keeps the cursor, makes no empty window and changes nothing`() = runTest {
        harness(GhHarness()) {
            connect()
            sync()
            val stored = sink.events()
            clock.setWallClock(GhHarness.START - 5.days)
            assertThat(sync().status).isEqualTo(SyncResult.Status.SUCCESS)
            assertThat(GhStreamState.decode(sink.cursorOf(STEPS)?.lastSuccessCursor).through).isEqualTo(GhHarness.START)
            assertThat(sink.violations).isEmpty()
            assertThat(sink.writes.filter { it.start != null }.all { it.start!! < it.end!! }).isTrue()
            assertThat(sink.deleted).isEqualTo(0)
            assertThat(sink.events()).isEqualTo(stored)
        }
    }

    @Test
    fun `R3-1 R6c re-segmented minutes converge to the new segments`() = runTest {
        harness(GhHarness(configure = { it.copy(streams = setOf(STEPS)) })) {
            connect()
            sync()
            val old = fake.dataset.of(GhDataTypes.STEPS).single { it.start == Instant.parse("2026-09-30T12:01:00Z") }
            fake.dataset = fake.dataset.replacing(
                { it === old },
                listOf(
                    old.copy(end = old.start + 30.seconds, amount = 50.0, raw = null),
                    old.copy(start = old.start + 30.seconds, amount = 48.0, raw = null),
                ),
            )
            clock.advanceBy(1.hours)
            sync()
            assertThat(counts(events(STEPS))).containsExactly(0L, 87L, 50L, 48L, 112L).inOrder()
            assertThat(sink.deleted).isEqualTo(1)
        }
    }

    @Test
    fun `sleep probe - a session starting before the window, in the lead region, is not deleted when not returned`() = runTest {
        harness(GhHarness(configure = { it.copy(streams = setOf(SLEEP)) })) {
            val early = dev.agentle.fakes.googlehealth.FakePoint(
                GhDataTypes.SLEEP,
                Instant.parse("2026-09-23T11:00:00Z"),
                Instant.parse("2026-09-23T13:00:00Z"),
                name = "users/1234567890/dataTypes/sleep/dataPoints/42",
            )
            fake.dataset = fake.dataset + listOf(early)
            connect()
            sync()
            assertThat(events(SLEEP)).hasSize(3)
            fake.dataset = fake.dataset.minus { it.id == "42" }
            clock.advanceBy(20.minutes)
            sync()
            assertThat(events(SLEEP)).hasSize(3)
        }
    }
}

package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.api.SyncResult
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.core.common.AppError
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.fakes.googlehealth.FakeAuthorization
import dev.agentle.fakes.googlehealth.FakeDataset
import dev.agentle.fakes.googlehealth.FakeGoogleAuthorizer
import dev.agentle.fakes.googlehealth.FakeGoogleHealthConfig
import dev.agentle.fakes.googlehealth.FakeGoogleHealthServer
import dev.agentle.fakes.googlehealth.FakeRequest
import kotlinx.datetime.TimeZone
import java.util.Collections
import kotlin.random.Random
import kotlin.time.Instant

/**
 * The scripted fake authorizer behind the connector's port: the adapter the app's fake flavor binds as well (the fake
 * keeps its own result shapes, so `:fakes` never depends on this module).
 */
internal class BridgedAuthorizer(val fake: FakeGoogleAuthorizer) : GoogleHealthAuthorizer {
    override suspend fun token(interactive: Boolean): GoogleAuthorization = when (val result = fake.token(interactive)) {
        is FakeAuthorization.Token -> GoogleAuthorization.Token(result.value, result.grantedScopes)
        FakeAuthorization.NeedsResolution -> GoogleAuthorization.NeedsResolution(RESOLUTION_HANDLE)
        FakeAuthorization.Denied -> GoogleAuthorization.Denied
        is FakeAuthorization.Failure -> GoogleAuthorization.Failure(result.statusCode)
    }

    override suspend fun invalidate(token: String) = fake.invalidate(token)

    override suspend fun grantedScopes(): Set<String> = fake.grantedScopes()

    override suspend fun revoke(): Outcome<Unit> =
        if (fake.revoke()) Outcome.Success(Unit) else Outcome.Failure(AppError.UnsupportedFeature("googlehealth.play_services"))

    companion object {
        /** Stands in for the `PendingIntent` a real resolution carries. */
        const val RESOLUTION_HANDLE: String = "resolution-handle"
    }
}

/**
 * One connector wired to the fake server, the scripted authorizer and an in-memory sink, on a virtual clock: every
 * wait (rate limiter, retries) advances [clock] instead of sleeping.
 */
internal class GhHarness(
    start: Instant = START,
    zone: TimeZone = TimeZone.UTC,
    dataset: FakeDataset? = null,
    datasetFor: ((TestAgentleClock) -> FakeDataset)? = null,
    fakeConfig: FakeGoogleHealthConfig = FakeGoogleHealthConfig(),
    scenario: String? = null,
    private val seed: Long = SEED,
    configure: (GoogleHealthConfig) -> GoogleHealthConfig = { it },
) : AutoCloseable {
    val clock = TestAgentleClock(start, zone)
    val fake = FakeGoogleHealthServer(clock, fakeConfig, dataset ?: datasetFor?.invoke(clock) ?: FakeDataset.documented()).start()
    val auth = FakeGoogleAuthorizer()
    val sink = TestSink()
    val logs: MutableList<LogRecord> = Collections.synchronizedList(ArrayList())
    val logger = Logger(listOf(LogSink { logs += it }), { clock.now().toEpochMilliseconds() }, Severity.DEBUG)
    val config: GoogleHealthConfig = configure(GoogleHealthConfig(baseUrl = scenario?.let { fake.scenarioUrl(it) } ?: fake.rootUrl()))
    val connector = newConnector()

    fun newConnector(config: GoogleHealthConfig = this.config): GoogleHealthConnector =
        GoogleHealthConnector(config, BridgedAuthorizer(auth), sink, clock, logger, sleep = { clock.advanceBy(it) }, random = Random(seed))

    suspend fun connect(): GoogleHealthConnectResult = connector.connect().also {
        assertThat(it).isInstanceOf(GoogleHealthConnectResult.Connected::class.java)
    }

    suspend fun sync(trigger: SyncTrigger = SyncTrigger.SCHEDULED): SyncResult = connector.sync(trigger)

    fun events(stream: String): List<PersonalEvent> = sink.events(stream)

    /** API requests (`/v4/...`) received so far. */
    fun requests(): List<FakeRequest> = fake.journal.filter { "/v4/" in it.path }

    fun requests(type: String): List<FakeRequest> = requests().filter { it.path.contains("/dataTypes/$type/") }

    /**
     * S40 and the security rules: hygiene H1 to H11 hold for every request, the sink saw no contract violation, and no
     * log line carries a token, a bearer header or the raw Google Health user id.
     */
    fun assertClean() {
        assertThat(fake.hygieneViolations()).isEmpty()
        assertThat(sink.violations).isEmpty()
        val text = logs.joinToString("\n") { "${it.message} ${it.fields} ${it.errorCode}" }
        // Every fake token starts with "fake-" (so does the fake's own path prefix, which may be logged).
        assertThat(text).doesNotContainMatch("fake-(?!googlehealth)")
        assertThat(text).doesNotContain("Bearer")
        assertThat(text).doesNotContainMatch(RAW_USER_ID)
    }

    override fun close() {
        fake.close()
    }

    companion object {
        val START: Instant = Instant.parse("2026-10-01T12:00:00Z")
        const val SEED: Long = 42L
        const val HEALTH_USER_ID: String = "1234567890"

        /** The raw user id as a whole number (data point ids may contain its digits). */
        const val RAW_USER_ID: String = "(?<![0-9])$HEALTH_USER_ID(?![0-9])"

        /** The account id of the fake's default user. */
        val ACCOUNT: String = GoogleHealthConnector.accountIdOf(HEALTH_USER_ID)
    }
}

/** Runs [block] with a started harness and closes it. */
internal inline fun <T> harness(harness: GhHarness, block: GhHarness.() -> T): T = harness.use { it.block() }

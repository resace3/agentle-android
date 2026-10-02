package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.ContextBlock
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.model.DataCategory
import dev.agentle.core.oauth.BrowserLauncher
import dev.agentle.core.oauth.OAuthRandom
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.fakes.browser.FakeBrowserLauncher
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeChatGptServer
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import okhttp3.HttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.fail
import java.security.SecureRandom
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Captures every sanitized log record (the canary tests read them). */
class CapturingSink : LogSink {
    val records: MutableList<LogRecord> = Collections.synchronizedList(mutableListOf())

    override fun write(record: LogRecord) {
        records += record
    }

    fun text(): String = synchronized(records) {
        records.joinToString("\n") { "${it.component} ${it.message} ${it.errorCode} ${it.eventId} ${it.fields}" }
    }
}

/** A seeded [SecureRandom] that remembers what it produced, so tests can recompute state, nonce and PKCE verifier. */
class RecordingSecureRandom(seed: Long) : SecureRandom() {
    private val delegate = java.util.Random(seed)
    val produced: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    override fun nextBytes(bytes: ByteArray) {
        synchronized(delegate) { delegate.nextBytes(bytes) }
        produced += bytes.copyOf()
    }

    /** Every attempt secret drawn so far, base64url-encoded as `OAuthRandom` encodes them. */
    fun secrets(): List<String> = synchronized(produced) { produced.map { dev.agentle.core.oauth.Base64Url.encode(it) } }
}

/**
 * Base of the journey and contract tests: the real SIWC components (through [SiwcGraph]) against [FakeChatGptServer] on
 * 127.0.0.1, with [FakeBrowserLauncher] playing the browser. Fake and client share one [TestAgentleClock] (red team
 * testing-build-19), whose zone is deliberately not UTC (testing-build-04): nothing here may depend on a zone.
 */
abstract class SiwcFakeTest(tokenPrefix: String = "", clockOffset: Duration = Duration.ZERO) {
    protected val clock: TestAgentleClock = TestAgentleClock(zone = TimeZone.of("Asia/Kathmandu"))
    protected val server: FakeChatGptServer = FakeChatGptServer(clock, tokenPrefix, clockOffset = clockOffset).start()
    protected val store: InMemoryCredentialStore = InMemoryCredentialStore()
    protected val sink: CapturingSink = CapturingSink()
    protected val logger: Logger = Logger(listOf(sink), { clock.now().toEpochMilliseconds() }, Severity.VERBOSE)
    protected val browser: FakeBrowserLauncher = FakeBrowserLauncher()
    protected val random: RecordingSecureRandom = RecordingSecureRandom(SEED)
    protected val installIds: InMemoryInstallIdProvider = InMemoryInstallIdProvider(java.util.Random(SEED))

    /** Every URL handed to the browser, as soon as the launch returned. */
    protected val launches: Channel<HttpUrl> = Channel(Channel.UNLIMITED)

    protected val launcher: BrowserLauncher = BrowserLauncher { url -> browser.launch(url).also { launches.trySend(url) } }

    @AfterEach
    fun closeServer() {
        server.close()
    }

    protected fun baseConfig(): SiwcConfig = SiwcConfig(
        issuer = server.authBaseUrl(),
        apiBaseUrl = server.apiBaseUrl(),
        allowCleartextLoopback = true,
        loopbackPollInterval = 20.milliseconds,
    )

    /** A fresh object graph (a fresh process) over [credentialStore]; its app scope is the test's background scope. */
    protected fun TestScope.graph(
        config: SiwcConfig = baseConfig(),
        credentialStore: CredentialStore = store,
        http: (SiwcHttpClients) -> SiwcHttpClients = { it },
        egressCheck: EgressCheck = EgressCheck.UNCHECKED,
        accountChanges: AccountChangeListener = AccountChangeListener.NONE,
    ): SiwcGraph = SiwcGraph(
        config = config,
        store = credentialStore,
        installIds = installIds,
        browser = launcher,
        clock = clock,
        scope = backgroundScope,
        egressCheck = egressCheck,
        accountChanges = accountChanges,
        logger = logger,
        http = http(SiwcHttpClients.create(config, clock, logger)),
        random = OAuthRandom(random),
    )

    /** Signs in on the HAPPY path and checks it worked. */
    protected suspend fun SiwcGraph.connect(request: SignInRequest = SignInRequest()): SignInOutcome {
        val outcome = signIn.signIn(request)
        assertThat(outcome).isInstanceOf(SignInOutcome.Connected::class.java)
        return outcome
    }

    /** Runs [scenario] for the rest of the test (production-shaped URLs, so the issuer stays the same). */
    protected fun scenario(scenario: ChatGptScenario) {
        server.defaultScenario = scenario
    }

    protected fun vault(): SiwcVault = checkNotNull(store.snapshot()) { "nothing persisted" }

    protected fun calls(route: FakeRoute): Int = server.requests().count { it.route == route }

    /** Waits in real time (not the test's virtual time) for work on other threads, such as a revocation in flight. */
    protected suspend fun eventually(what: String, condition: () -> Boolean) {
        repeat(WAIT_STEPS) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(WAIT_STEP) }
        }
        fail("timed out waiting for $what")
    }

    protected fun envelope(
        userText: String? = "How did I sleep?",
        blocks: List<ContextBlock> = listOf(ContextBlock(DataCategory.SLEEP, "Sleep last week", "avg 7h 10m", untrusted = false, rawEvents = false)),
        instructions: String = "Explain the user's sleep pattern in two sentences.",
    ): AiRequestEnvelope = AiRequestEnvelope(
        requestId = "req-1",
        purpose = AiPurpose.SLEEP_INSIGHT,
        instructions = instructions,
        userText = userText,
        blocks = blocks,
        categories = blocks.map { it.category }.toSet(),
        rangeStart = null,
        rangeEnd = null,
        createdAt = clock.now(),
    )

    protected companion object {
        const val SEED = 20261002L
        const val WAIT_STEPS = 500
        val WAIT_STEP = 10.milliseconds

        fun <T> Outcome<T>.value(): T = (this as? Outcome.Success)?.value ?: fail("expected success, got $this")

        fun Outcome<*>.error(): dev.agentle.core.common.AppError = (this as? Outcome.Failure)?.error ?: fail("expected failure, got $this")
    }
}

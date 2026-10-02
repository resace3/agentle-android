@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiEnvelopeConstruction
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.ai.api.BlockKind
import dev.agentle.ai.api.ContextBlock
import dev.agentle.ai.api.ContextItem
import dev.agentle.ai.api.DataItem
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.Severity
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.oauth.BrowserLauncher
import dev.agentle.core.oauth.OAuthRandom
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.fakes.browser.FakeBrowserLauncher
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeChatGptServer
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.CoroutineDispatcher
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
open class SiwcFakeTest(tokenPrefix: String = "", clockOffset: Duration = Duration.ZERO) {
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
        sendVerifier: AiSendVerifier = DIGEST_ONLY,
        accountChanges: AccountChangeListener = AccountChangeListener.NONE,
    ): SiwcGraph = SiwcGraph(
        config = config,
        store = credentialStore,
        installIds = installIds,
        browser = launcher,
        clock = clock,
        scope = backgroundScope,
        sendVerifier = sendVerifier,
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
    protected suspend fun eventually(what: String, realTime: CoroutineDispatcher = Dispatchers.Default, condition: () -> Boolean) {
        repeat(WAIT_STEPS) {
            if (condition()) return
            withContext(realTime) { delay(WAIT_STEP) }
        }
        fail("timed out waiting for $what")
    }

    protected fun envelope(
        userText: String? = "How did I sleep?",
        blocks: List<ContextBlock> = listOf(sleepBlock()),
        instructions: String = "Explain the user's sleep pattern in two sentences.",
        maxOutputTokens: Int? = null,
    ): AiRequestEnvelope = AiRequestEnvelope(
        requestId = "req-1",
        purpose = AiPurpose.SLEEP_INSIGHT,
        mode = AiRequestMode.USER_INITIATED,
        instructions = instructions,
        userText = userText?.let { UntrustedText(it, TextOrigin.USER_REQUEST) },
        blocks = blocks,
        rangeStart = null,
        rangeEnd = null,
        createdAt = clock.now(),
        consentVersion = 1,
        maxOutputTokens = maxOutputTokens,
    )

    protected fun sleepBlock(): ContextBlock = ContextBlock(
        "sleep_summary_7d",
        AiDataCategory.SLEEP,
        BlockKind.AGGREGATES,
        listOf(
            ContextItem(
                DataItem.Quantity("sleep.minutes_avg_7d", SLEEP_MINUTES, "min"),
                DataLineage.of(AiDataCategory.SLEEP, SourceFamily.HEALTH_CONNECT),
            ),
        ),
    )

    protected companion object {
        const val SEED = 20261002L
        const val SLEEP_MINUTES = 430.0

        /** Stands in for EgressGuard's digest check: accepts only bytes that hash to the approved input. */
        val DIGEST_ONLY = AiSendVerifier { envelope, sent ->
            if (sent == envelope.inputSha256) {
                Outcome.Success(Unit)
            } else {
                Outcome.Failure(dev.agentle.core.common.AppError.ConsentViolation(emptySet(), "input_hash_mismatch"))
            }
        }
        const val WAIT_STEPS = 500
        val WAIT_STEP = 10.milliseconds

        fun <T> Outcome<T>.value(): T = (this as? Outcome.Success)?.value ?: fail("expected success, got $this")

        fun Outcome<*>.error(): dev.agentle.core.common.AppError = (this as? Outcome.Failure)?.error ?: fail("expected failure, got $this")
    }
}

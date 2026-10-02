package dev.agentle.fakes.chatgpt

import dev.agentle.core.time.AgentleClock
import kotlinx.datetime.TimeZone
import mockwebserver3.MockResponse
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** The endpoint a fake call hit; used by the journal and by [FakeChatGptServer.failNext]. */
public enum class FakeRoute { DISCOVERY, JWKS, AUTHORIZE, TOKEN_EXCHANGE, TOKEN_REFRESH, REVOKE, MODELS, RESPONSES, OTHER }

/**
 * One journaled call. Parameter and header values are not kept, only names (the fake never needs to show a token
 * back to a test; assertions use [FakeChatGptServer]'s counters and token sets).
 */
public data class FakeCall(
    val scenario: ChatGptScenario,
    val route: FakeRoute,
    val method: String,
    val path: String,
    val status: Int,
    val parameterNames: Set<String>,
)

internal data class CodeGrant(
    val clientId: String,
    val challenge: String,
    val redirectUri: String,
    val nonce: String,
    val scope: String,
    val expiresAt: Instant,
    var used: Boolean = false,
)

internal data class RefreshGrant(
    val clientId: String,
    val sub: String,
    val scope: String,
    var used: Boolean = false,
    var revoked: Boolean = false,
)

internal data class AccessGrant(val clientId: String, val sub: String, val expiresAt: Instant)

internal data class InjectedFailure(val status: Int, val body: String, val contentType: String?, val headers: Map<String, String>)

/** Mutable server state; every access holds [lock] (MockWebServer dispatches on several threads). */
internal class FakeChatGptState {
    val lock = Any()
    var tokenCounter = 0
    var codeCounter = 0
    var refreshCount = 0
    var exchangeCount = 0
    val codes = HashMap<String, CodeGrant>()
    val refreshTokens = HashMap<String, RefreshGrant>()
    val accessTokens = HashMap<String, AccessGrant>()
    val attempts = HashMap<String, Int>()
    val journal = ArrayList<FakeCall>()
    val violations = ArrayList<String>()
    val revoked = ArrayList<String>()
    val injected = HashMap<FakeRoute, ArrayDeque<InjectedFailure>>()

    /** 1-based count of calls to [route] in [scenario], so "fail the first N calls" stays deterministic. */
    fun attempt(scenario: ChatGptScenario, route: FakeRoute): Int {
        val key = "${scenario.id}|$route"
        val next = (attempts[key] ?: 0) + 1
        attempts[key] = next
        return next
    }

    fun takeInjected(route: FakeRoute): MockResponse? {
        val failure = injected[route]?.removeFirstOrNull() ?: return null
        val builder = MockResponse.Builder().code(failure.status).body(failure.body)
        failure.contentType?.let { builder.setHeader("Content-Type", it) }
        failure.headers.forEach { (name, value) -> builder.setHeader(name, value) }
        return builder.build()
    }

    fun violation(code: String) {
        if (code !in violations) violations += code
    }

    fun reset() {
        tokenCounter = 0
        codeCounter = 0
        refreshCount = 0
        exchangeCount = 0
        codes.clear()
        refreshTokens.clear()
        accessTokens.clear()
        attempts.clear()
        journal.clear()
        violations.clear()
        revoked.clear()
        injected.clear()
    }
}

/** Response builders shared by the endpoints. */
internal object FakeResponses {
    const val JSON = "application/json"
    const val EVENT_STREAM = "text/event-stream"

    fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()): MockResponse {
        val builder = MockResponse.Builder().code(code).setHeader("Content-Type", JSON).body(body)
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        return builder.build()
    }

    fun html(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).setHeader("Content-Type", "text/html").body(body).build()

    fun empty(code: Int): MockResponse = MockResponse.Builder().code(code).build()

    fun redirect(location: String): MockResponse = MockResponse.Builder().code(FOUND).setHeader("Location", location).build()

    private const val FOUND = 302
}

/** The injected clock shifted by [offset]: the server's view of time when the device clock is wrong. */
internal class OffsetClock(private val base: AgentleClock, private val offset: () -> Duration) : AgentleClock {
    override val wall: Clock = object : Clock {
        override fun now(): Instant = base.now() + offset()
    }

    override fun zone(): TimeZone = base.zone()

    override fun elapsed(): Duration = base.elapsed()
}

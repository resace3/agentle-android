package dev.agentle.fakes.chatgpt

import dev.agentle.core.time.AgentleClock
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import kotlin.time.Duration

/**
 * The Sign in with ChatGPT fake of docs/research/06 §9 and docs/research/08 §5.4, on one `mockwebserver3`
 * server bound to 127.0.0.1. It serves both "hosts" (§9.1): give the client `authBaseUrl()` as its issuer and
 * `apiBaseUrl()` as its API base.
 *
 * Two ways to pick a scenario (R08 §5.2): [defaultScenario] for production-shaped URLs, or the scenario-prefix mode
 * where `authBaseUrl(scenario)` and `apiBaseUrl(scenario)` carry `/fake-chatgpt/scenario/<id>`. `GET
 * /fake-chatgpt/scenarios` lists the ids. Attempts are counted per (scenario, endpoint), so "fail the first call" stays
 * deterministic when tests share a server.
 *
 * Time comes from [clock], the same `AgentleClock` the code under test gets (red team testing-build-19), shifted by
 * [clockOffset] to model a device clock that is wrong (codes expire after 60 s, access tokens after 3600 s, ID-token
 * `iat`/`exp` follow the server's time). Issued tokens are `<tokenPrefix>at_<n>` and `<tokenPrefix>rt_<n>`, so leak
 * tests can use canary prefixes the log redactor does not know. The fake never logs and keeps only parameter names in
 * its journal.
 */
public class FakeChatGptServer(
    clock: AgentleClock,
    tokenPrefix: String = "",
    keySeed: Long = DEFAULT_KEY_SEED,
    clockOffset: Duration = Duration.ZERO,
) : AutoCloseable {
    /** Scenario for production-shaped URLs; tests and the debug menu change it at runtime. */
    @Volatile public var defaultScenario: ChatGptScenario = ChatGptScenario.HAPPY

    /**
     * How far the server's clock is ahead of [clock] (negative: behind). `+2.hours` models a device whose clock is two
     * hours slow (red team testing-build-18).
     */
    @Volatile public var clockOffset: Duration = clockOffset

    private val serverClock: AgentleClock = OffsetClock(clock) { this.clockOffset }
    private val state = FakeChatGptState()
    private val auth = FakeAuthEndpoints(state, FakeJwtSigner.forSeed(keySeed), serverClock, tokenPrefix)
    private val api = FakeApiEndpoints(state, serverClock)
    private val server = MockWebServer()

    /** The router; mount it on another `MockWebServer` to share one port with other fakes. */
    public val dispatcher: Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
    }

    /** Binds 127.0.0.1 on a free port. Call off the main thread (it opens a socket). */
    public fun start(): FakeChatGptServer {
        server.dispatcher = dispatcher
        server.start(InetAddress.getByAddress(byteArrayOf(LOOPBACK_FIRST_OCTET, 0, 0, 1)), 0)
        return this
    }

    public val port: Int get() = server.port

    /** `http://127.0.0.1:<port>/`. */
    public fun rootUrl(): HttpUrl = "http://127.0.0.1:$port/".toHttpUrl()

    /** The issuer and auth base (R06 §9.1), without a trailing slash. */
    public fun authBaseUrl(scenario: ChatGptScenario? = null): String = base(scenario) + "/auth"

    /** The API base (R06 §9.1), without a trailing slash. */
    public fun apiBaseUrl(scenario: ChatGptScenario? = null): String = base(scenario) + "/api/v1"

    private fun base(scenario: ChatGptScenario?): String = "http://127.0.0.1:$port" + (scenario?.let { "$SCENARIO_PREFIX${it.id}" } ?: "")

    /** Refresh-token grant requests received (R08 §5.7 T-TOK-01: exactly one for 50 concurrent callers). */
    public fun refreshCount(): Int = synchronized(state.lock) { state.refreshCount }

    /** Authorization-code grant requests received. */
    public fun exchangeCount(): Int = synchronized(state.lock) { state.exchangeCount }

    public fun requests(): List<FakeCall> = synchronized(state.lock) { state.journal.toList() }

    public fun requestsFor(scenario: ChatGptScenario): List<FakeCall> = requests().filter { it.scenario == scenario }

    /** Requests the server accepted but R06 tells clients not to send (`H-...` codes); empty for an honest client. */
    public fun hygieneViolations(): List<String> = synchronized(state.lock) { state.violations.toList() }

    /** Tokens passed to the revocation endpoint, in order. */
    public fun revokedTokens(): List<String> = synchronized(state.lock) { state.revoked.toList() }

    /** Refresh tokens that are issued, unused and not revoked. */
    public fun liveRefreshTokens(): Set<String> = synchronized(state.lock) {
        state.refreshTokens.filterValues { !it.used && !it.revoked }.keys.toSet()
    }

    /** Access tokens issued so far, in order. */
    public fun issuedAccessTokens(): List<String> = synchronized(state.lock) { state.accessTokens.keys.toList() }

    /**
     * Answers the next [times] calls to [route] with [status] and [body] before any other check (the "custom error"
     * knob for table-driven tests, for example each terminal refresh code in both body shapes).
     */
    public fun failNext(
        route: FakeRoute,
        status: Int,
        body: String,
        times: Int = 1,
        contentType: String? = FakeResponses.JSON,
        headers: Map<String, String> = emptyMap(),
    ) {
        require(times > 0) { "times must be positive" }
        synchronized(state.lock) {
            val queue = state.injected.getOrPut(route) { ArrayDeque() }
            repeat(times) { queue += InjectedFailure(status, body, contentType, headers) }
        }
    }

    /** Forgets every code, token, counter, injected failure and journal entry; back to [ChatGptScenario.HAPPY]. */
    public fun reset() {
        synchronized(state.lock) { state.reset() }
        defaultScenario = ChatGptScenario.HAPPY
    }

    /**
     * The browser hop of R06 §9.1 for tests that play the browser: GETs [authorizeUrl] without following redirects
     * and returns the `Location` the fake answered with, or null (for example a 400 for a bad `redirect_uri`).
     */
    public fun browserHop(authorizeUrl: HttpUrl, client: OkHttpClient = HOP_CLIENT): HttpUrl? =
        client.newCall(Request.Builder().url(authorizeUrl).get().build()).execute().use { response ->
            response.header("Location")?.let { authorizeUrl.resolve(it) }.takeIf { response.isRedirect }
        }

    override fun close() {
        server.close()
    }

    private fun handle(recorded: RecordedRequest): MockResponse {
        val path = recorded.url.encodedPath
        if (path == "/fake-chatgpt/scenarios") {
            val ids = ChatGptScenario.entries.joinToString(",", "{\"scenarios\":[", "]}") { "\"${it.id}\"" }
            return FakeResponses.json(OK, ids)
        }
        val origin = "${recorded.url.scheme}://${recorded.url.host}:${recorded.url.port}"
        val routed = when {
            path.startsWith(SCENARIO_PREFIX) -> {
                val after = path.removePrefix(SCENARIO_PREFIX)
                val id = after.substringBefore('/')
                val scenario = ChatGptScenario.fromId(id)
                    ?: return FakeResponses.json(BAD_REQUEST, """{"error":"unknown_scenario"}""")
                Triple(scenario, "$origin$SCENARIO_PREFIX$id", "/" + after.substringAfter('/', ""))
            }

            path.startsWith("/fake-chatgpt/") -> return FakeResponses.json(NOT_FOUND, """{"error":"unknown_service"}""")

            else -> Triple(defaultScenario, origin, path)
        }
        val (scenario, base, rest) = routed
        val request = FakeRequest(recorded, scenario, base)
        val (route, response) = route(request, rest)
        synchronized(state.lock) {
            state.journal += FakeCall(scenario, route, recorded.method, rest, response.code, request.parameterNames)
        }
        return response
    }

    private fun route(request: FakeRequest, rest: String): Pair<FakeRoute, MockResponse> {
        val method = request.recorded.method
        val endpoint = ENDPOINTS[rest]
        val routeFor = when {
            endpoint != null && endpoint.second == method -> endpoint.first
            rest.startsWith("/api/v1/") -> FakeRoute.OTHER
            else -> null
        } ?: return FakeRoute.OTHER to FakeResponses.json(NOT_FOUND, """{"detail":"Not Found"}""")
        if (routeFor == FakeRoute.TOKEN_EXCHANGE) return auth.token(request)
        val injected = synchronized(state.lock) { state.takeInjected(routeFor) }
        val response = injected ?: when (routeFor) {
            FakeRoute.DISCOVERY -> auth.discovery(request)
            FakeRoute.JWKS -> auth.jwks(request)
            FakeRoute.AUTHORIZE -> auth.authorize(request)
            FakeRoute.REVOKE -> auth.revoke(request)
            FakeRoute.MODELS -> api.models(request)
            FakeRoute.RESPONSES -> api.responses(request)
            else -> api.otherRoute()
        }
        return routeFor to response
    }

    public companion object {
        public const val DEFAULT_KEY_SEED: Long = 42L
        public const val SCENARIO_PREFIX: String = "/fake-chatgpt/scenario/"
        private const val LOOPBACK_FIRST_OCTET: Byte = 127
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private val HOP_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        }

        /** Path below the base -> (route, method). The token endpoint is split into exchange/refresh by grant type. */
        private val ENDPOINTS: Map<String, Pair<FakeRoute, String>> = mapOf(
            "/auth/.well-known/openid-configuration" to (FakeRoute.DISCOVERY to "GET"),
            "/auth/.well-known/jwks.json" to (FakeRoute.JWKS to "GET"),
            "/auth/api/accounts/authorize" to (FakeRoute.AUTHORIZE to "GET"),
            "/auth/api/accounts/oauth/token" to (FakeRoute.TOKEN_EXCHANGE to "POST"),
            "/auth/api/accounts/oauth/revoke" to (FakeRoute.REVOKE to "POST"),
            "/api/v1/models" to (FakeRoute.MODELS to "GET"),
            "/api/v1/responses" to (FakeRoute.RESPONSES to "POST"),
        )
    }
}

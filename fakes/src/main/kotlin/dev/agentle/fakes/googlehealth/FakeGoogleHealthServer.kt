package dev.agentle.fakes.googlehealth

import dev.agentle.core.time.AgentleClock
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Fake Google Health API v4 (`health.googleapis.com`) for JVM tests and the app's fake flavor, built from the
 * contract in docs/research/05 §8 and the topology of docs/research/08 §5.2. Only the Google Health API is faked;
 * nothing here speaks the legacy Fitbit Web API. The fake validates bearer tokens only: the OAuth server of fallback
 * flow B is not part of it (tokens come from [FakeGoogleAuthorizer]).
 *
 * Order of checks for every request (§8.1, docs/research/08 §5.3): route (else E404-HTML with the path) -> injected
 * faults -> scenario faults -> auth (401) -> scope (403) -> account state -> V9 rate limit (429) -> validation V1-V8
 * (400) -> replay -> data (model mode). The scenario's transport faults then apply to the response.
 *
 * Scenario selection: a base URL of [scenarioUrl] (`/fake-googlehealth/scenario/<name>/`) or production paths with
 * [defaultScenario]. `GET /fake-googlehealth/scenarios` lists the names; an unknown scenario returns 400. Attempts are
 * counted per (scenario, path).
 */
public class FakeGoogleHealthServer(
    private val clock: AgentleClock,
    config: FakeGoogleHealthConfig = FakeGoogleHealthConfig(),
    dataset: FakeDataset = FakeDataset.documented(),
) : Closeable {
    private class Injection(var remaining: Int, val match: (FakeRequest) -> Boolean, val response: (FakeRequest, FakeRoute) -> FakeResponse)

    /** The knobs of §8.8; a scenario may override some of them per request. */
    @Volatile public var config: FakeGoogleHealthConfig = config

    /** The model-mode dataset; replace it between sync runs to simulate upstream changes (§8.6 R7). */
    @Volatile public var dataset: FakeDataset = dataset

    /** Scenario for production-shaped paths (no `/fake-googlehealth/scenario/<name>/` prefix). */
    @Volatile public var defaultScenario: String = FakeScenarios.HAPPY
        set(value) {
            require(FakeScenarios.byName(value) != null) { "unknown scenario: $value" }
            field = value
        }

    /** Replay mode: exact requests or matchers mapped to canned fixtures (§8.1). Empty by default. */
    public val replay: FakeReplay = FakeReplay()

    private val lock = Any()
    private val exchanges = ArrayList<FakeExchange>()
    private val attempts = HashMap<String, Int>()
    private val scenarioCounts = HashMap<String, Int>()
    private val recentByToken = HashMap<String, ArrayDeque<Duration>>()
    private val injections = ArrayList<Injection>()

    @Volatile private var effective: FakeGoogleHealthConfig = config

    @Volatile private var effectiveData: FakeDataset = dataset
    private val engine = ModelEngine({ effectiveData }, { effective }, { clock.now() })
    private var server: MockWebServer? = null

    /** Starts a MockWebServer bound to 127.0.0.1 (never a public interface). */
    public fun start(): FakeGoogleHealthServer {
        val s = MockWebServer()
        s.dispatcher = SocketDispatcher()
        s.start(InetAddress.getByName(LOOPBACK), 0)
        server = s
        return this
    }

    /** `http://127.0.0.1:<port>/`, for production-shaped requests (`v4/...`). */
    public fun rootUrl(): String = "http://$LOOPBACK:${requireNotNull(server) { "start() the fake first" }.port}/"

    /** `http://127.0.0.1:<port>/fake-googlehealth/scenario/<name>/`. */
    public fun scenarioUrl(name: String): String {
        require(FakeScenarios.byName(name) != null) { "unknown scenario: $name" }
        return rootUrl() + "${PREFIX.removePrefix("/")}/scenario/$name/"
    }

    override fun close() {
        server?.close()
        server = null
    }

    /** Every request the fake answered, oldest first. */
    public val journal: List<FakeRequest> get() = synchronized(lock) { exchanges.map { it.request } }

    /** Every request with the response it got. */
    public fun exchanges(): List<FakeExchange> = synchronized(lock) { exchanges.toList() }

    public fun requestsFor(scenario: String): List<FakeRequest> = journal.filter { it.scenario == scenario }

    /** Hygiene checks H1 to H11 over everything received so far (§8.7); run after each client test. */
    public fun hygieneViolations(perMinuteBudget: Int = GoogleHealthHygiene.DEFAULT_BUDGET): List<String> =
        GoogleHealthHygiene.check(exchanges(), perMinuteBudget)

    /** Forgets the journal, attempt counters, rate-limit windows, injections and replay rules. */
    public fun reset() {
        synchronized(lock) {
            exchanges.clear()
            attempts.clear()
            scenarioCounts.clear()
            recentByToken.clear()
            injections.clear()
        }
        replay.clear()
    }

    /**
     * Makes the next [times] routed requests that match [match] return fixture [fixtureId] (an error fixture with its
     * §8.5 status and headers, or any other fixture as a 200), optionally with [retryAfter] and a [delay].
     */
    public fun inject(
        times: Int,
        fixtureId: String,
        retryAfter: String? = null,
        delay: Duration = Duration.ZERO,
        match: (FakeRequest) -> Boolean = { true },
    ) {
        val isError = GoogleHealthFixtures.status(fixtureId) != OK
        inject(times, match) { req, route ->
            val response = if (isError) {
                GoogleHealthFixtures.error(fixtureId, path = req.path, retryAfter = retryAfter, rpc = route.kind.fullRpc)
            } else {
                GoogleHealthFixtures.ok(fixtureId)
            }
            response.copy(delay = delay)
        }
    }

    /** Makes the next [times] routed requests that match [match] return [response]. */
    public fun inject(times: Int, match: (FakeRequest) -> Boolean = { true }, response: (FakeRequest, FakeRoute) -> FakeResponse) {
        synchronized(lock) { injections += Injection(times, match, response) }
    }

    /**
     * Answers one request without sockets. [target] is a path with an optional (unencoded or encoded) query, either
     * production-shaped (`/v4/...`) or scenario-prefixed.
     */
    public fun call(method: String, target: String, headers: Map<String, String> = emptyMap(), body: String = ""): FakeResponse {
        val path = target.substringBefore('?')
        val query = LinkedHashMap<String, MutableList<String>>()
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val name = java.net.URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
            val value = java.net.URLDecoder.decode(pair.substringAfter('=', "").replace("+", "%2B"), Charsets.UTF_8)
            query.getOrPut(name) { ArrayList() } += value
        }
        return receive(method, java.net.URLDecoder.decode(path, Charsets.UTF_8), query, headers.mapKeys { it.key.lowercase() }, body)
    }

    private fun receive(
        method: String,
        rawPath: String,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
        body: String,
    ): FakeResponse {
        if (rawPath == "$PREFIX/scenarios") {
            return FakeResponse(OK, FakeScenarios.NAMES.joinToString(",", "{\"scenarios\": [", "]}") { "\"$it\"" })
        }
        val scenario: String
        val path: String
        if (rawPath.startsWith("$PREFIX/scenario/")) {
            val rest = rawPath.removePrefix("$PREFIX/scenario/")
            scenario = rest.substringBefore('/')
            if (FakeScenarios.byName(scenario) == null) return FakeResponse(BAD_REQUEST, UNKNOWN_SCENARIO)
            path = "/" + rest.substringAfter('/', "")
        } else if (rawPath.startsWith("$PREFIX/")) {
            return GoogleHealthFixtures.error("E404-HTML", path = rawPath)
        } else {
            scenario = defaultScenario
            path = rawPath
        }
        return synchronized(lock) {
            val key = "$scenario $path"
            val attempt = (attempts[key] ?: 0) + 1
            attempts[key] = attempt
            handleLocked(FakeRequest(method, path, query, headers, body, scenario, attempt, clock.now()))
        }
    }

    /** The pure core: answers a fully formed [request] (its [FakeRequest.attempt] is taken as given). */
    public fun handle(request: FakeRequest): FakeResponse = synchronized(lock) { handleLocked(request) }

    private fun handleLocked(req: FakeRequest): FakeResponse {
        val scenario = requireNotNull(FakeScenarios.byName(req.scenario)) { "unknown scenario: ${req.scenario}" }
        val count = (scenarioCounts[req.scenario] ?: 0) + 1
        scenarioCounts[req.scenario] = count
        effective = scenario.configure(config)
        effectiveData = scenario.dataset(dataset)
        val route = FakeRoutes.match(req.method, req.path, effective.healthUserId)
        val response = if (route == null) {
            GoogleHealthFixtures.error("E404-HTML", path = req.path)
        } else {
            val call = ScenarioCall(req, route, count)
            scenario.after(call, dispatch(req, route, scenario, call))
        }
        exchanges += FakeExchange(req, response)
        return response
    }

    @Suppress("ReturnCount")
    private fun dispatch(req: FakeRequest, route: FakeRoute, scenario: FakeScenario, call: ScenarioCall): FakeResponse {
        takeInjection(req)?.let { return it(req, route) }
        scenario.fault(call)?.let { return it }
        val token = req.bearerToken
            ?: return GoogleHealthFixtures.error(if ("key" in req.query) "E401-APIKEY" else "E401-MISSING", rpc = route.kind.fullRpc)
        val scopes = FakeTokens.scopesOf(token) ?: return GoogleHealthFixtures.error("E401-INVALID")
        val required = route.requiredScopes()
        val missing = if (required == null) {
            if (scopes.any { it in GhScopes.V1 || it == GhScopes.LOCATION }) emptySet() else GhScopes.V1
        } else {
            required - scopes
        }
        if (missing.isNotEmpty()) {
            return GoogleHealthFixtures.error("E403-SCOPE-A", scope = missing.first(), rpc = route.kind.fullRpc)
        }
        FakeTokens.accountStateFixture(token)?.let { return GoogleHealthFixtures.error(it) }
        if (!withinRateLimit(token)) return GoogleHealthFixtures.error("E429")
        engine.validate(route, req)?.let { return it }
        replay.match(req)?.let { return it }
        scenario.data(call)?.let { return it }
        return engine.serve(route, req)
    }

    private fun takeInjection(req: FakeRequest): ((FakeRequest, FakeRoute) -> FakeResponse)? {
        val injection = injections.firstOrNull { it.remaining > 0 && it.match(req) } ?: return null
        injection.remaining--
        return injection.response
    }

    /** V9: at most `ratePerMinute` requests per token in any rolling minute of monotonic time. */
    private fun withinRateLimit(token: String): Boolean {
        val now = clock.elapsed()
        val recent = recentByToken.getOrPut(token) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() >= 1.minutes) recent.removeFirst()
        if (recent.size >= effective.ratePerMinute) return false
        recent.addLast(now)
        return true
    }

    private inner class SocketDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            val path = "/" + url.pathSegments.joinToString("/")
            val query = LinkedHashMap<String, List<String>>()
            url.queryParameterNames.forEach { name -> query[name] = url.queryParameterValues(name).map { it.orEmpty() } }
            val headers = LinkedHashMap<String, String>()
            request.headers.names().forEach { name -> headers[name.lowercase()] = request.headers.values(name).joinToString(", ") }
            val response = receive(request.method, path, query, headers, request.body?.utf8().orEmpty())
            return toMockResponse(response)
        }
    }

    public companion object {
        public const val PREFIX: String = "/fake-googlehealth"
        private const val LOOPBACK = "127.0.0.1"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val THROTTLE_BYTES = 1024L
        private const val THROTTLE_PERIOD_MS = 100L
        private const val UNKNOWN_SCENARIO =
            "{\"error\": {\"code\": 400, \"message\": \"Unknown scenario.\", \"status\": \"INVALID_ARGUMENT\"}}"

        /** The MockWebServer form of [response], with its transport fault applied (docs/research/08 §5.5). */
        public fun toMockResponse(response: FakeResponse): MockResponse {
            val builder = MockResponse.Builder().code(response.code)
            response.headers.forEach { (name, value) -> builder.setHeader(name, value) }
            if (response.contentType != null && response.transport != TransportFault.NO_CONTENT_TYPE) {
                builder.setHeader("Content-Type", response.contentType)
            }
            builder.body(response.body)
            if (response.delay > Duration.ZERO) builder.headersDelay(response.delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            when (response.transport) {
                TransportFault.THROTTLE -> builder.throttleBody(THROTTLE_BYTES, THROTTLE_PERIOD_MS, TimeUnit.MILLISECONDS)
                TransportFault.DISCONNECT_MID_BODY -> builder.onResponseBody(SocketEffect.CloseSocket())
                TransportFault.STALL -> builder.onResponseStart(SocketEffect.Stall)
                TransportFault.NONE, TransportFault.NO_CONTENT_TYPE -> Unit
            }
            return builder.build()
        }
    }
}

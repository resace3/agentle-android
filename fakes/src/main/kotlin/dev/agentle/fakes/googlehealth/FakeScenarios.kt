package dev.agentle.fakes.googlehealth

import kotlin.time.Instant

/** What a scenario sees of a request: the request, its route, and how many requests the scenario has received. */
public class ScenarioCall internal constructor(
    public val request: FakeRequest,
    public val route: FakeRoute,
    /** 1 for the first request of this scenario (any path), 2 for the second, ... */
    public val count: Int,
)

/**
 * A named server behavior selected by URL prefix or `defaultScenario` (docs/research/08 §5.2). [fault] runs right
 * after routing (before auth, as injected faults do, docs/research/05 §8.1); [data] replaces the data step;
 * [after] post-processes every response (transport faults of docs/research/08 §5.5); [dataset] extends the
 * model-mode dataset for the scenario's requests.
 */
public class FakeScenario(
    public val name: String,
    /** The docs/research/05 rows the scenario serves (§8.6, §8.7). */
    public val rows: String,
    internal val configure: (FakeGoogleHealthConfig) -> FakeGoogleHealthConfig = { it },
    internal val fault: (ScenarioCall) -> FakeResponse? = { null },
    internal val data: (ScenarioCall) -> FakeResponse? = { null },
    internal val after: (ScenarioCall, FakeResponse) -> FakeResponse = { _, response -> response },
    internal val dataset: (FakeDataset) -> FakeDataset = { it },
)

/** The scenario catalog: the 19 scenarios of docs/research/08 §5.3 plus a few for rows that table leaves out. */
public object FakeScenarios {
    public const val HAPPY: String = "happy"

    /**
     * A 16:10-16:40 walk on 2026-09-30 recorded twice: by a watch (3,000 steps) and by the phone's own step counter
     * (2,800), both on the Fitbit platform. `list` returns both records; `:reconcile` and the rollups keep the watch's.
     */
    public val OVERLAPPING_WALK: List<FakePoint> = listOf(
        FakePoint(
            GhDataTypes.STEPS,
            Instant.parse("2026-09-30T16:10:00Z"),
            Instant.parse("2026-09-30T16:40:00Z"),
            amount = 3000.0,
            source = FakeSource.WATCH,
        ),
        FakePoint(
            GhDataTypes.STEPS,
            Instant.parse("2026-09-30T16:10:00Z"),
            Instant.parse("2026-09-30T16:40:00Z"),
            amount = 2800.0,
            source = FakeSource.PHONE_TRACKER,
        ),
    )

    private fun dataOnly(call: ScenarioCall, response: () -> FakeResponse): FakeResponse? = if (call.route.isData) response() else null

    private fun emptyBody(call: ScenarioCall): FakeResponse? = when {
        !call.route.isData -> null
        call.route.kind == RouteKind.DAILY_ROLL_UP -> FakeResponse(200, GoogleHealthFixtures.R3_DAILY_ROLLUP_BODY)
        else -> FakeResponse(200, "{}")
    }

    private fun truncated(response: FakeResponse): FakeResponse = if (response.code == 200 &&
        response.body.length > 2
    ) {
        response.copy(body = response.body.substring(0, response.body.length / 2))
    } else {
        response
    }

    public val ALL: List<FakeScenario> = listOf(
        FakeScenario(HAPPY, "S01, S07, S13, S16, S17"),
        FakeScenario("empty", "S37, R3", data = ::emptyBody),
        FakeScenario("small-pages", "S07, R8b, R8h", configure = { it.copy(maxPageSize = 50, endOfPages = EndOfPages.OMITTED) }),
        FakeScenario("rate-limited", "S29", fault = { if (it.count >= 3) GoogleHealthFixtures.error("E429") else null }),
        FakeScenario(
            "rate-limited-retry-after",
            "S28",
            fault = {
                if (it.request.attempt ==
                    1
                ) {
                    GoogleHealthFixtures.error("E429", retryAfter = GoogleHealthFixtures.RETRY_AFTER_A)
                } else {
                    null
                }
            },
        ),
        FakeScenario(
            "rate-limited-long",
            "S31",
            fault = { GoogleHealthFixtures.error("E429", retryAfter = GoogleHealthFixtures.RETRY_AFTER_D) },
        ),
        FakeScenario("token-expired", "S21", fault = { if (it.count == 1) GoogleHealthFixtures.error("E401-INVALID") else null }),
        FakeScenario("token-revoked", "S22", fault = { GoogleHealthFixtures.error("E401-INVALID") }),
        FakeScenario(
            "permission-denied",
            "S23",
            fault = { call ->
                if (call.route.type == GhDataTypes.STEPS) {
                    GoogleHealthFixtures.error("E403-SCOPE-A", scope = GhScopes.ACTIVITY, rpc = call.route.kind.fullRpc)
                } else {
                    null
                }
            },
        ),
        FakeScenario("server-flaky", "S32, R8e", fault = { if (it.request.attempt <= 2) GoogleHealthFixtures.error("E503") else null }),
        FakeScenario("server-down", "S34, R8f", fault = { call -> dataOnly(call) { GoogleHealthFixtures.error("E503") } }),
        FakeScenario("bad-gateway-html", "S32", fault = { if (it.request.attempt == 1) GoogleHealthFixtures.error("E502") else null }),
        FakeScenario("captive-portal", "R2, S36", fault = { GoogleHealthFixtures.ok("R2") }),
        FakeScenario("throttled", "R08 5.5", after = { _, r -> r.copy(transport = TransportFault.THROTTLE) }),
        FakeScenario(
            "disconnect-mid-body",
            "R1b, S36",
            after = { call, r -> if (call.request.attempt == 1) r.copy(transport = TransportFault.DISCONNECT_MID_BODY) else r },
        ),
        FakeScenario("timeout", "S33", after = { _, r -> r.copy(transport = TransportFault.STALL) }),
        FakeScenario("malformed", "R1a, S36", after = { call, r -> if (call.route.isData) truncated(r) else r }),
        FakeScenario("no-content-type", "R08 5.5", after = { _, r -> r.copy(transport = TransportFault.NO_CONTENT_TYPE) }),
        FakeScenario(
            "sleep-filter-rejected",
            "S26",
            configure = { it.copy(sleepFilter = SleepFilterMode.REJECT_ALL, filterErrorStyle = FilterErrorStyle.REASON_ONLY) },
        ),
        FakeScenario("account-not-linked", "S02, S35", fault = { GoogleHealthFixtures.error("E400-ACCOUNT-NOT-LINKED") }),
        FakeScenario("profile-not-ready", "S03", fault = { GoogleHealthFixtures.error("E412") }),
        FakeScenario("legacy-fitbit-account", "S04", fault = { GoogleHealthFixtures.error("E403-LEGACY-A") }),
        FakeScenario("legacy-forbidden", "S05", fault = { call -> dataOnly(call) { GoogleHealthFixtures.error("E403-LEGACY-B") } }),
        FakeScenario("daily-end-exclusive", "S11", configure = { it.copy(dailyRollUpEnd = DailyRollUpEnd.EXCLUSIVE) }),
        FakeScenario("ascending-pages", "R7a", configure = { it.copy(listOrder = ListOrder.ASCENDING) }),
        FakeScenario("overlapping-devices", "R6a (two devices of one platform)", dataset = { it + OVERLAPPING_WALK }),
    )

    private val byName = ALL.associateBy { it.name }

    public val NAMES: List<String> = ALL.map { it.name }

    public fun byName(name: String): FakeScenario? = byName[name]
}

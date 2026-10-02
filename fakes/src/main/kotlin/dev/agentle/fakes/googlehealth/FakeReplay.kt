package dev.agentle.fakes.googlehealth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Replay mode (docs/research/05 §8.1): maps an exact request (method, path, decoded and sorted query, or canonical
 * JSON body) to a canned fixture, plus matcher rules for looser cases. Replay runs after auth, scope, account state
 * and request validation, so a replayed request is still checked like any other; page tokens (V4) are not checked
 * for replayed requests, because the fixtures carry their own (`pt-steps-2`, `pd-2`).
 *
 * An exact entry without `pageSize` matches any `pageSize`; the client's page size is a config value (§8.1).
 */
public class FakeReplay {
    private class Exact(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: JsonObject?,
        val response: () -> FakeResponse,
    )

    private class Rule(var remaining: Int, val match: (FakeRequest) -> Boolean, val response: () -> FakeResponse)

    private val exact = ArrayList<Exact>()
    private val rules = ArrayList<Rule>()

    /** Serves [fixtureId] for the request `method target` (target = path plus an unencoded query) with [body]. */
    public fun exact(method: String, target: String, fixtureId: String, body: String? = null): FakeReplay =
        exact(method, target, body) { GoogleHealthFixtures.ok(fixtureId) }

    public fun exact(method: String, target: String, body: String? = null, response: () -> FakeResponse): FakeReplay {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val parsed = body?.let { json.parseToJsonElement(it) as JsonObject }
        synchronized(this) { exact += Exact(method, path, query, parsed, response) }
        return this
    }

    /** Serves [response] for the next [times] requests that match [match] (after the checks above). */
    public fun rule(times: Int = Int.MAX_VALUE, match: (FakeRequest) -> Boolean, response: () -> FakeResponse): FakeReplay {
        synchronized(this) { rules += Rule(times, match, response) }
        return this
    }

    /** Serves [fixtureId] for every `GET` list request on [typeId]. */
    public fun list(typeId: String, fixtureId: String): FakeReplay =
        rule(match = { it.method == "GET" && it.path.endsWith("/dataTypes/$typeId/dataPoints") }) { GoogleHealthFixtures.ok(fixtureId) }

    public fun clear() {
        synchronized(this) {
            exact.clear()
            rules.clear()
        }
    }

    internal fun match(req: FakeRequest): FakeResponse? = synchronized(this) {
        exact.firstOrNull { matches(it, req) }?.let { return it.response() }
        val rule = rules.firstOrNull { it.remaining > 0 && it.match(req) } ?: return null
        rule.remaining--
        return rule.response()
    }

    private fun matches(e: Exact, req: FakeRequest): Boolean {
        if (e.method != req.method || e.path != req.path) return false
        val query = req.query.mapValues { it.value.firstOrNull().orEmpty() }
            .filterKeys { "pageSize" in e.query || it != "pageSize" }
        if (e.body == null) return query == e.query
        val body = runCatching { json.parseToJsonElement(req.body) as? JsonObject }.getOrNull() ?: return false
        val comparable = if ("pageSize" in e.body) body else body.copyWithout("pageSize")
        return ModelEngine.canonical(comparable) == ModelEngine.canonical(e.body)
    }

    public companion object {
        private val json = Json

        private const val STEPS = "/v4/users/me/dataTypes/steps/dataPoints"
        private const val STEPS_FILTER =
            "filter=steps.interval.start_time >= \"2026-09-30T11:59:00Z\" AND steps.interval.start_time < \"2026-09-30T12:05:00Z\""
        private const val DAILY_BODY =
            """{"range":{"start":{"date":{"year":2026,"month":9,"day":29}},""" +
                """"end":{"date":{"year":2026,"month":9,"day":30}}},"windowSizeDays":1}"""
        private const val SLEEP_FILTER =
            "filter=sleep.interval.end_time >= \"2026-09-29T12:00:00Z\" AND sleep.interval.end_time < \"2026-10-01T12:00:00Z\""

        /** A replay table holding [addDocumented]'s entries. */
        public fun documented(): FakeReplay = FakeReplay().addDocumented()
    }

    /** Adds every request §8.4 documents, mapped to its fixture; list fixtures without a documented window match any query. */
    public fun addDocumented(): FakeReplay = this
        .exact("GET", "/v4/users/me/identity", "F-IDENTITY")
        .exact("GET", "/v4/users/me/settings", "F-SETTINGS")
        .exact("GET", "/v4/users/me/profile", "F-PROFILE")
        .exact("GET", "/v4/users/me/pairedDevices?pageSize=1", "F-DEVICES-P1")
        .exact("GET", "/v4/users/me/pairedDevices?pageSize=1&pageToken=pd-2", "F-DEVICES-P2")
        .exact("GET", "$STEPS?$STEPS_FILTER&pageSize=3", "F-STEPS-P1")
        .exact("GET", "$STEPS?$STEPS_FILTER&pageSize=3&pageToken=pt-steps-2", "F-STEPS-P2")
        .exact("GET", "$STEPS:reconcile?$STEPS_FILTER", "F-STEPS-RECONCILE")
        .exact(
            "POST",
            "$STEPS:rollUp",
            "F-STEPS-ROLLUP",
            """{"range":{"startTime":"2026-09-30T11:59:00Z","endTime":"2026-09-30T12:03:00Z"},"windowSize":"60s"}""",
        )
        .exact("POST", "$STEPS:dailyRollUp", "F-DAILY-STEPS", DAILY_BODY)
        .exact("POST", "/v4/users/me/dataTypes/total-calories/dataPoints:dailyRollUp", "F-DAILY-TOTALCAL", DAILY_BODY)
        .exact(
            "GET",
            "/v4/users/me/dataTypes/floors/dataPoints:reconcile?filter=floors.interval.start_time >= \"2026-09-30T00:00:00Z\" " +
                "AND floors.interval.start_time < \"2026-10-01T00:00:00Z\"",
            "F-FLOORS-RECONCILE",
        )
        .exact(
            "GET",
            "/v4/users/me/dataTypes/heart-rate/dataPoints?filter=heart_rate.sample_time.physical_time >= \"2026-09-30T12:01:00Z\" " +
                "AND heart_rate.sample_time.physical_time < \"2026-09-30T12:02:00Z\"",
            "F-HR",
        )
        .exact(
            "POST",
            "/v4/users/me/dataTypes/heart-rate/dataPoints:rollUp",
            "F-HR-ROLLUP",
            """{"range":{"startTime":"2026-09-30T12:00:00Z","endTime":"2026-09-30T12:02:00Z"},"windowSize":"60s"}""",
        )
        .exact(
            "GET",
            "/v4/users/me/dataTypes/daily-resting-heart-rate/dataPoints?filter=daily_resting_heart_rate.date >= \"2026-09-29\" " +
                "AND daily_resting_heart_rate.date < \"2026-10-01\"",
            "F-RHR",
        )
        .exact("GET", "/v4/users/me/dataTypes/sleep/dataPoints?pageSize=25&$SLEEP_FILTER", "F-SLEEP")
        .exact("GET", "/v4/users/me/dataTypes/sleep/dataPoints?pageSize=25", "F-SLEEP")
        .exact(
            "GET",
            "/v4/users/me/dataTypes/exercise/dataPoints?pageSize=25&filter=exercise.interval.civil_start_time >= " +
                "\"2026-09-29T20:00:00\" AND exercise.interval.civil_start_time < \"2026-10-01T00:00:00\"",
            "F-EXERCISE",
        )
        .list("distance", "F-DISTANCE")
        .list("active-energy-burned", "F-AEB")
        .list("weight", "F-WEIGHT")
        .list("body-fat", "F-BODYFAT")
}

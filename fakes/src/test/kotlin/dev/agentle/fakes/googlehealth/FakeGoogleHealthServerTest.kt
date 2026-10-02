package dev.agentle.fakes.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FakeGoogleHealthServerTest {
    private val clock = TestAgentleClock()
    private val fake = FakeGoogleHealthServer(clock)

    private fun headers(token: String?) = buildMap {
        put("Accept", "application/json")
        if (token != null) put("Authorization", "Bearer $token")
    }

    private fun get(target: String, token: String? = FakeTokens.VALID) = fake.call("GET", target, headers(token))

    private fun post(target: String, body: String, token: String? = FakeTokens.VALID) = fake.call("POST", target, headers(token), body)

    private fun FakeResponse.json(): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun FakeResponse.errorStatus(): String = json().getValue("error").jsonObject.getValue("status").jsonPrimitive.content

    private fun FakeResponse.reason(): String? = json().getValue("error").jsonObject["details"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("reason")?.jsonPrimitive?.content

    private fun FakeResponse.detailedReason(): String? = json().getValue("error").jsonObject["details"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("metadata")?.jsonObject?.get("detailedReasons")?.jsonPrimitive?.content

    private fun FakeResponse.points(key: String = "dataPoints"): JsonArray = json()[key]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonArray.counts(union: String = "steps", field: String = "count"): List<String?> =
        map { it.jsonObject.getValue(union).jsonObject[field]?.jsonPrimitive?.content }

    private val steps = "/v4/users/me/dataTypes/steps/dataPoints"
    private val stepsFilter =
        "steps.interval.start_time >= \"2026-09-30T11:59:00Z\" AND steps.interval.start_time < \"2026-09-30T12:05:00Z\""

    private val dailyBody = DAILY_BODY

    @Test
    fun `R05 8 1 routing runs before auth and unrouted paths get the probed HTML 404`() {
        val notFound = get("/v4/nonexistent", token = null)
        assertThat(notFound.code).isEqualTo(404)
        assertThat(notFound.contentType).isEqualTo(FakeResponse.HTML)
        assertThat(notFound.body).isEqualTo(GoogleHealthFixtures.text("E404-HTML"))
        assertThat(get("/v4/users/me/devices", token = null).body).contains("<code>/v4/users/me/devices</code>")
        assertThat(post("$steps:reconcile", "{}", token = null).code).isEqualTo(404)
        assertThat(post(steps, "{}").code).isEqualTo(404)
        assertThat(get("/v4/users/me/dataTypes/not-a-type/dataPoints", token = null).code).isEqualTo(401)
        assertThat(get("$steps:rollUp").code).isEqualTo(404)
    }

    @Test
    fun `R05 8 1 tokens - missing, API key, invalid, expired and revoked`() {
        val missing = get(steps, token = null)
        assertThat(missing.code).isEqualTo(401)
        assertThat(missing.reason()).isEqualTo("CREDENTIALS_MISSING")
        assertThat(missing.headers["www-authenticate"]).isEqualTo(GoogleHealthFixtures.WWW_AUTH)
        assertThat(get("$steps?key=abc", token = null).body).contains("API keys are not supported")
        for (token in listOf(FakeTokens.EXPIRED, FakeTokens.REVOKED, "some-unknown-token")) {
            val invalid = get(steps, token)
            assertThat(invalid.code).isEqualTo(401)
            assertThat(invalid.headers["www-authenticate"]).isEqualTo(GoogleHealthFixtures.WWW_AUTH_INVALID)
            assertThat(invalid.json().getValue("error").jsonObject["details"]).isNull()
        }
    }

    @Test
    fun `R05 8 1 scope gates per data type and identity needs any read scope`() {
        val sleepOnly = FakeTokens.scoped(listOf(GhScopes.SLEEP))
        assertThat(get("/v4/users/me/dataTypes/sleep/dataPoints", sleepOnly).code).isEqualTo(200)
        val denied = get(steps, sleepOnly)
        assertThat(denied.code).isEqualTo(403)
        assertThat(denied.reason()).isEqualTo("ACCESS_TOKEN_SCOPE_INSUFFICIENT")
        assertThat(denied.headers["www-authenticate"]).contains("googlehealth.activity_and_fitness.readonly")
        assertThat(get("/v4/users/me/identity", sleepOnly).code).isEqualTo(200)
        assertThat(get("/v4/users/me/settings", sleepOnly).code).isEqualTo(403)
        assertThat(get("/v4/users/me/identity", "fake-scope-").code).isEqualTo(403)
        val tcx = get("/v4/users/me/dataTypes/exercise/dataPoints/6661252888799707001:exportExerciseTcx?alt=media")
        assertThat(tcx.headers["www-authenticate"]).contains("location.readonly")
    }

    @Test
    fun `R05 8 1 account state tokens fail every routed call`() {
        val notLinked = get("/v4/users/me/identity", FakeTokens.NOT_LINKED)
        assertThat(notLinked.code).isEqualTo(400)
        assertThat(notLinked.reason()).isEqualTo("ACCOUNT_NOT_LINKED")
        assertThat(get(steps, FakeTokens.NO_PROFILE).code).isEqualTo(412)
        val legacy = get(steps, FakeTokens.LEGACY)
        assertThat(legacy.code).isEqualTo(403)
        assertThat(legacy.errorStatus()).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `R05 8 3 V1 unknown types and unsupported methods are invalid arguments`() {
        assertThat(get("/v4/users/me/dataTypes/not-a-type/dataPoints").errorStatus()).isEqualTo("INVALID_ARGUMENT")
        assertThat(get("/v4/users/me/dataTypes/floors/dataPoints").code).isEqualTo(400)
        assertThat(get("/v4/users/me/dataTypes/total-calories/dataPoints").code).isEqualTo(400)
        assertThat(get("/v4/users/me/dataTypes/total-calories/dataPoints:reconcile").code).isEqualTo(400)
        assertThat(get("/v4/users/me/dataTypes/floors/dataPoints:reconcile").code).isEqualTo(200)
        assertThat(get("$steps/123").code).isEqualTo(400)
        assertThat(get("/v4/users/me/dataTypes/sleep/dataPoints/999").code).isEqualTo(404)
        val sleep = get("/v4/users/1234567890/dataTypes/sleep/dataPoints/7821966286120953001")
        assertThat(sleep.json().getValue("sleep").jsonObject.getValue("stages").jsonArray).hasSize(9)
        assertThat(post("/v4/users/me/dataTypes/sleep/dataPoints:rollUp", "{}").code).isEqualTo(400)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "steps|steps.interval.start_time >= \"2026-09-30T00:00:00Z\" OR steps.interval.start_time < \"2026-10-01T00:00:00Z\"|" +
                "INVALID_DATA_POINT_FILTER_EXPRESSION_STRUCTURE",
            "steps|steps.interval.start_time >= 2026-09-30|INVALID_DATA_POINT_FILTER",
            "steps|steps.interval.start_time >= \"yesterday\"|INVALID_DATA_POINT_FILTER",
            "steps|steps.interval.start_time > \"2026-09-30T00:00:00Z\"|INVALID_DATA_POINT_FILTER_RESTRICTION_COMPARATOR",
            "active-energy-burned|active-energy-burned.interval.start_time >= \"2026-09-30T00:00:00Z\"|INVALID_DATA_POINT_FILTER",
            "steps|distance.interval.start_time >= \"2026-09-30T00:00:00Z\"|INVALID_DATA_POINT_FILTER_COLLECTION_MISMATCH",
            "steps|steps.interval.end_time >= \"2026-09-30T00:00:00Z\"|INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER",
            "exercise|exercise.interval.start_time >= \"2026-09-30T00:00:00Z\"|INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER",
            "steps|steps.interval.start_time >= \"2026-09-30T00:00:00Z\" AND steps.interval.civil_start_time < \"2026-10-01\"|" +
                "INVALID_DATA_POINT_FILTER_MIXED_TIME_RESTRICTIONS",
            "steps|steps.interval.start_time >= \"2026-10-01T00:00:00Z\" AND steps.interval.start_time < \"2026-10-01T00:00:00Z\"|" +
                "INVALID_TIME_RANGE",
            "daily-resting-heart-rate|daily_resting_heart_rate.date >= \"2026-09-30\" AND daily_resting_heart_rate.date < \"2026-09-29\"|" +
                "INVALID_TIME_RANGE",
        ],
    )
    fun `R05 8 3 V2 filter rules in the documented order`(type: String, filter: String, reason: String) {
        val response = get("/v4/users/me/dataTypes/$type/dataPoints?filter=$filter")
        assertThat(response.code).isEqualTo(400)
        assertThat(response.reason()).isEqualTo("INVALID_DATA_POINT_FILTER")
        assertThat(response.detailedReason()).isEqualTo(reason)
    }

    @Test
    fun `R05 8 3 V2 knobs - rejected members, reason-only style and sleep filters`() {
        fake.config = FakeGoogleHealthConfig(
            rejectFilterMembers = setOf("interval.start_time"),
            filterErrorStyle = FilterErrorStyle.REASON_ONLY,
        )
        val rejected = get("$steps?filter=$stepsFilter")
        assertThat(rejected.reason()).isEqualTo("INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER")
        assertThat(rejected.detailedReason()).isNull()
        val civil = get("$steps?filter=steps.interval.civil_start_time >= \"2026-09-29T21:59:00\"")
        assertThat(civil.points().counts()).containsExactly("112", "98", "240", "87", null).inOrder()

        fake.config = FakeGoogleHealthConfig()
        val sleepOr = "sleep.interval.end_time >= \"2026-09-30T18:00:00Z\" OR sleep.interval.end_time < \"2026-09-30T11:00:00Z\""
        assertThat(get("/v4/users/me/dataTypes/sleep/dataPoints?filter=$sleepOr").points()).hasSize(2)
        val sleepEnd = "sleep.interval.end_time >= \"2026-09-30T11:00:00Z\" AND sleep.interval.end_time < \"2026-10-01T12:00:00Z\""
        assertThat(get("/v4/users/me/dataTypes/sleep/dataPoints?filter=$sleepEnd").points()).hasSize(1)
        fake.config = FakeGoogleHealthConfig(sleepFilter = SleepFilterMode.REJECT_ALL)
        assertThat(get("/v4/users/me/dataTypes/sleep/dataPoints?filter=$sleepEnd").code).isEqualTo(400)
        assertThat(get("/v4/users/me/dataTypes/sleep/dataPoints?pageSize=25").points()).hasSize(2)
    }

    @Test
    fun `R05 8 3 V3 and V4 model pages match F-STEPS-P1 and P2 with bound page tokens`() {
        val first = get("$steps?filter=$stepsFilter&pageSize=3")
        assertThat(first.points().counts()).containsExactly("112", "98", "240").inOrder()
        val token = first.json().getValue("nextPageToken").jsonPrimitive.content
        val second = get("$steps?filter=$stepsFilter&pageSize=3&pageToken=$token")
        assertThat(second.points().counts()).containsExactly("87", null).inOrder()
        assertThat(second.json().getValue("nextPageToken").jsonPrimitive.content).isEmpty()
        assertThat(get("$steps?pageSize=3&pageToken=$token").body).isEqualTo(GoogleHealthFixtures.text("E400-PAGE-TOKEN"))
        assertThat(get("$steps?pageSize=-1").code).isEqualTo(400)
        assertThat(get("$steps?pageSize=0").points()).hasSize(5)
        val clamped = get("/v4/users/me/pairedDevices?pageSize=500")
        assertThat(clamped.json()["pairedDevices"]?.jsonArray).hasSize(2)
        assertThat(clamped.json()["nextPageToken"]).isNull()
    }

    @Test
    fun `R05 8 3 V5 data source families`() {
        assertThat(get("$steps?dataSourceFamily=google-wearables").code).isEqualTo(400)
        val sleepWithFamily = get("/v4/users/me/dataTypes/sleep/dataPoints?dataSourceFamily=users/me/dataSourceFamilies/all-sources")
        assertThat(sleepWithFamily.code).isEqualTo(400)
        val wearables = get("$steps?dataSourceFamily=users/me/dataSourceFamilies/google-wearables")
        assertThat(wearables.points().counts()).containsExactly("112", "98", "87", null).inOrder()
        assertThat(get("$steps?dataSourceFamily=users/me/dataSourceFamilies/self-sources").body).isEqualTo("{}")
    }

    @Test
    fun `R05 8 2 reconcile merges sources and drops provenance`() {
        val reconciled = get("$steps:reconcile?filter=$stepsFilter")
        assertThat(reconciled.points().counts()).containsExactly(null, "87", "98", "112").inOrder()
        assertThat(reconciled.points().all { "dataSource" !in it.jsonObject }).isTrue()
        val weights = get("/v4/users/me/dataTypes/weight/dataPoints:reconcile").points()
        assertThat(weights.first().jsonObject.getValue("dataPointName").jsonPrimitive.content)
            .isEqualTo("users/1234567890/dataTypes/weight/dataPoints/6685309773198399002")
    }

    @Test
    fun `R05 8 3 V6 rollUp windows equal the documented rollups`() {
        val stepsRollUp = post(
            "$steps:rollUp",
            """{"range":{"startTime":"2026-09-30T11:59:00Z","endTime":"2026-09-30T12:03:00Z"},"windowSize":"60s"}""",
        )
        assertThat(stepsRollUp.points("rollupDataPoints").counts(field = "countSum")).containsExactly("0", "87", "98", "112").inOrder()
        val hr = post(
            "/v4/users/me/dataTypes/heart-rate/dataPoints:rollUp",
            """{"range":{"startTime":"2026-09-30T12:00:00Z","endTime":"2026-09-30T12:02:00Z"},"windowSize":"60s"}""",
        ).points("rollupDataPoints")
        val window = hr.single().jsonObject.getValue("heartRate").jsonObject
        assertThat(window.getValue("beatsPerMinuteMin").jsonPrimitive.content).isEqualTo("72")
        assertThat(window.getValue("beatsPerMinuteAvg").jsonPrimitive.content).isEqualTo("73")
        assertThat(window.getValue("beatsPerMinuteMax").jsonPrimitive.content).isEqualTo("74")
        val tooLong = """{"range":{"startTime":"2026-09-01T00:00:00Z","endTime":"2026-09-16T00:00:00Z"},"windowSize":"60s"}"""
        assertThat(post("/v4/users/me/dataTypes/heart-rate/dataPoints:rollUp", tooLong).code).isEqualTo(400)
        assertThat(post("$steps:rollUp", tooLong).code).isEqualTo(200)
        val subSecond = """{"range":{"startTime":"2026-09-30T12:00:00Z","endTime":"2026-09-30T12:02:00Z"},"windowSize":"0.5s"}"""
        assertThat(post("$steps:rollUp", subSecond).code).isEqualTo(400)
        val backwards = """{"range":{"startTime":"2026-09-30T12:02:00Z","endTime":"2026-09-30T12:00:00Z"},"windowSize":"60s"}"""
        assertThat(post("$steps:rollUp", backwards).code).isEqualTo(400)
        assertThat(post("$steps:rollUp", "not json").body).isEqualTo(GoogleHealthFixtures.text("E400-BAD-JSON"))
    }

    @Test
    fun `R05 8 3 V6 rollUp pages need the identical body`() {
        val body = """{"range":{"startTime":"2026-09-30T11:59:00Z","endTime":"2026-09-30T12:03:00Z"},"windowSize":"60s","pageSize":2}"""
        val first = post("$steps:rollUp", body)
        assertThat(first.points("rollupDataPoints")).hasSize(2)
        val token = first.json().getValue("nextPageToken").jsonPrimitive.content
        val second = post("$steps:rollUp", body.replace("\"pageSize\":2", "\"pageSize\":2,\"pageToken\":\"$token\""))
        assertThat(second.points("rollupDataPoints").counts(field = "countSum")).containsExactly("98", "112").inOrder()
        assertThat(second.json()["nextPageToken"]).isNull()
        val changed = body.replace("12:03:00Z", "12:04:00Z").replace("\"pageSize\":2", "\"pageSize\":2,\"pageToken\":\"$token\"")
        assertThat(post("$steps:rollUp", changed).code).isEqualTo(400)
    }

    @Test
    fun `R05 8 3 V7 dailyRollUp - inclusive end by default, exclusive by knob, no leading zeros`() {
        val day = { d: Int -> "2026-09-${d}T13:00:00Z" }
        fake.dataset = FakeDataset(
            listOf(29, 30).map { d ->
                FakePoint(GhDataTypes.STEPS, Instant.parse(day(d)), Instant.parse(day(d)) + 1.minutes, amount = d * 100.0)
            },
        )
        val body = dailyBody
        val inclusive = post("$steps:dailyRollUp", body).points("rollupDataPoints")
        assertThat(inclusive.counts(field = "countSum")).containsExactly("2900", "3000").inOrder()
        assertThat(inclusive.first().jsonObject.getValue("civilStartTime").jsonObject.getValue("time").jsonObject).isEmpty()
        fake.config = FakeGoogleHealthConfig(dailyRollUpEnd = DailyRollUpEnd.EXCLUSIVE)
        assertThat(post("$steps:dailyRollUp", body).points("rollupDataPoints").counts(field = "countSum")).containsExactly("2900")
        assertThat(post("$steps:dailyRollUp", body.replace("\"month\":9", "\"month\":09")).body)
            .isEqualTo(GoogleHealthFixtures.text("E400-BAD-JSON"))
        assertThat(post("$steps:dailyRollUp", body.replace("\"windowSizeDays\":1", "\"windowSizeDays\":0")).code).isEqualTo(400)
        val long = """{"range":{"start":{"date":{"year":2026,"month":1,"day":1}},"end":{"date":{"year":2026,"month":9,"day":30}}}}"""
        assertThat(post("$steps:dailyRollUp", long).code).isEqualTo(400)
    }

    @Test
    fun `R05 8 3 V9 rate limit per token over a rolling minute`() {
        fake.config = FakeGoogleHealthConfig(ratePerMinute = 3)
        repeat(3) { assertThat(get("/v4/users/me/identity").code).isEqualTo(200) }
        val limited = get("/v4/users/me/identity")
        assertThat(limited.code).isEqualTo(429)
        assertThat(limited.headers).doesNotContainKey("Retry-After")
        assertThat(get("/v4/users/me/identity", FakeTokens.scoped(GhScopes.V1)).code).isEqualTo(200)
        clock.advanceBy(60.seconds)
        assertThat(get("/v4/users/me/identity").code).isEqualTo(200)
    }

    @Test
    fun `R05 8 4 identity, settings and devices follow the config and the clock`() {
        assertThat(get("/v4/users/me/identity").body).isEqualTo(GoogleHealthFixtures.text("F-IDENTITY"))
        assertThat(get("/v4/users/me/settings").body).isEqualTo(GoogleHealthFixtures.text("F-SETTINGS"))
        assertThat(get("/v4/users/me/profile").body).isEqualTo(GoogleHealthFixtures.text("F-PROFILE"))
        fake.config = FakeGoogleHealthConfig(healthUserId = "555", timeZone = "Europe/Berlin")
        assertThat(get("/v4/users/me/identity").json().getValue("healthUserId").jsonPrimitive.content).isEqualTo("555")
        assertThat(get("/v4/users/me/settings").json().getValue("utcOffset").jsonPrimitive.content).isEqualTo("7200s")
        assertThat(get("/v4/users/555/settings").code).isEqualTo(200)
        assertThat(get("/v4/users/1234567890/settings").code).isEqualTo(404)
        val first = get("/v4/users/me/pairedDevices?pageSize=1").json()
        val charge = first.getValue("pairedDevices").jsonArray.single().jsonObject
        assertThat(charge.getValue("batteryLevel").jsonPrimitive.content).isEqualTo("82")
        val token = first.getValue("nextPageToken").jsonPrimitive.content
        val second = get("/v4/users/me/pairedDevices?pageSize=1&pageToken=$token").json()
        assertThat(second.getValue("pairedDevices").jsonArray.single().jsonObject["features"]).isNull()
        assertThat(get("/v4/users/me/pairedDevices/3141592653").code).isEqualTo(200)
        assertThat(get("/v4/users/me/pairedDevices/1").code).isEqualTo(404)
    }

    @Test
    fun `device sync lag hides recent tracker data and moves the tracker's lastSyncTime`() {
        fake.dataset = FakeDataset(
            listOf(
                FakePoint(GhDataTypes.STEPS, Instant.parse("2026-10-01T11:00:00Z"), Instant.parse("2026-10-01T11:01:00Z"), amount = 10.0),
                FakePoint(
                    GhDataTypes.STEPS,
                    Instant.parse("2026-10-01T11:00:00Z"),
                    Instant.parse("2026-10-01T11:01:00Z"),
                    amount = 9.0,
                    source = FakeSource.PHONE_VIA_HEALTH_CONNECT,
                ),
                FakePoint(GhDataTypes.STEPS, Instant.parse("2026-10-01T13:00:00Z"), Instant.parse("2026-10-01T13:01:00Z"), amount = 99.0),
            ),
            listOf(FakeDevice.CHARGE_6.copy(tracker = true)),
        )
        fake.config = FakeGoogleHealthConfig(deviceSyncLag = 2.minutes.times(60))
        assertThat(get(steps).points().counts()).containsExactly("9")
        val device = get("/v4/users/me/pairedDevices").json().getValue("pairedDevices").jsonArray.single().jsonObject
        assertThat(device.getValue("lastSyncTime").jsonPrimitive.content).isEqualTo("2026-10-01T10:00:00Z")
        fake.config = FakeGoogleHealthConfig()
        assertThat(get(steps).points().counts()).containsExactly("10", "9").inOrder()
    }

    @Test
    fun `R05 8 1 replay mode serves the documented fixtures byte-for-byte`() {
        val server = FakeGoogleHealthServer(clock, dataset = FakeDataset.EMPTY)
        server.replay.addDocumented()
        val ok = headers(FakeTokens.VALID)
        assertThat(server.call("GET", "$steps?filter=$stepsFilter&pageSize=3", ok).body).isEqualTo(GoogleHealthFixtures.text("F-STEPS-P1"))
        assertThat(server.call("GET", "$steps?filter=$stepsFilter&pageSize=3&pageToken=pt-steps-2", ok).body)
            .isEqualTo(GoogleHealthFixtures.text("F-STEPS-P2"))
        val daily = dailyBody
        assertThat(server.call("POST", "/v4/users/me/dataTypes/total-calories/dataPoints:dailyRollUp", ok, daily).body)
            .isEqualTo(GoogleHealthFixtures.text("F-DAILY-TOTALCAL"))
        val rollUp = """{"windowSize":"60s","range":{"endTime":"2026-09-30T12:02:00Z","startTime":"2026-09-30T12:00:00Z"},"pageSize":3}"""
        assertThat(server.call("POST", "/v4/users/me/dataTypes/heart-rate/dataPoints:rollUp", ok, rollUp).body)
            .isEqualTo(GoogleHealthFixtures.text("F-HR-ROLLUP"))
        assertThat(server.call("GET", "/v4/users/me/dataTypes/distance/dataPoints?pageSize=3", ok).body)
            .isEqualTo(GoogleHealthFixtures.text("F-DISTANCE"))
        assertThat(server.call("GET", "/v4/users/me/dataTypes/sleep/dataPoints?pageSize=25", ok).body)
            .isEqualTo(GoogleHealthFixtures.text("F-SLEEP"))
        assertThat(server.call("GET", "/v4/users/me/pairedDevices?pageSize=1&pageToken=pd-2", ok).body)
            .isEqualTo(GoogleHealthFixtures.text("F-DEVICES-P2"))
        assertThat(server.call("GET", "$steps?pageSize=3", ok).body).isEqualTo("{}")
        server.replay.rule(times = 1, match = { it.path.endsWith("/heart-rate/dataPoints") }) { GoogleHealthFixtures.ok("R4-STEPS") }
        assertThat(server.call("GET", "/v4/users/me/dataTypes/heart-rate/dataPoints", ok).body)
            .isEqualTo(GoogleHealthFixtures.text("R4-STEPS"))
        assertThat(server.call("GET", "/v4/users/me/dataTypes/heart-rate/dataPoints", ok).body).isEqualTo("{}")
    }

    @Test
    fun `R05 8 1 injected faults apply after routing and before auth`() {
        fake.inject(2, "E503", match = { it.path.endsWith("/identity") })
        assertThat(get("/v4/users/me/identity", token = null).code).isEqualTo(503)
        assertThat(get("/v4/users/me/settings").code).isEqualTo(200)
        assertThat(get("/v4/users/me/identity").code).isEqualTo(503)
        assertThat(get("/v4/users/me/identity").code).isEqualTo(200)
        fake.inject(1, "E429", retryAfter = GoogleHealthFixtures.RETRY_AFTER_C)
        assertThat(get(steps).headers["Retry-After"]).isEqualTo("Thu, 01 Oct 2026 12:00:30 GMT")
        fake.inject(1, "F-HR", delay = 3.seconds)
        val delayed = get(steps)
        assertThat(delayed.delay).isEqualTo(3.seconds)
        assertThat(delayed.body).isEqualTo(GoogleHealthFixtures.text("F-HR"))
        assertThat(get("/v4/nonexistent").code).isEqualTo(404)
    }

    @Test
    fun `R08 5 2 scenario prefix, catalog, unknown scenarios and attempts per scenario and path`() {
        assertThat(get("/fake-googlehealth/scenarios").body).contains("\"rate-limited\"")
        assertThat(get("/fake-googlehealth/scenario/nope/v4/users/me/identity").code).isEqualTo(400)
        assertThat(get("/fake-googlehealth/other").code).isEqualTo(404)
        val flaky = "/fake-googlehealth/scenario/server-flaky/v4/users/me/identity"
        assertThat(listOf(get(flaky).code, get(flaky).code, get(flaky).code)).containsExactly(503, 503, 200).inOrder()
        assertThat(get("/fake-googlehealth/scenario/server-flaky/v4/users/me/settings").code).isEqualTo(503)
        assertThat(fake.requestsFor("server-flaky").map { it.attempt }).containsExactly(1, 2, 3, 1).inOrder()
        assertThat(fake.journal.first().path).isEqualTo("/v4/users/me/identity")
        fake.defaultScenario = "token-expired"
        assertThat(get("/v4/users/me/identity").code).isEqualTo(401)
        assertThat(get("/v4/users/me/identity").code).isEqualTo(200)
        fake.reset()
        assertThat(fake.journal).isEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        "rate-limited, 200, 200, 429",
        "rate-limited-retry-after, 429, 429, 200",
        "rate-limited-long, 429, 429, 429",
        "token-revoked, 401, 401, 401",
        "server-down, 200, 503, 503",
        "bad-gateway-html, 502, 502, 200",
        "account-not-linked, 400, 400, 400",
        "profile-not-ready, 412, 412, 412",
        "legacy-fitbit-account, 403, 403, 403",
        "legacy-forbidden, 200, 403, 403",
        "captive-portal, 200, 200, 200",
    )
    fun `R08 5 3 scenario status sequence (identity, then steps twice)`(scenario: String, first: Int, second: Int, third: Int) {
        val prefix = "/fake-googlehealth/scenario/$scenario"
        val codes = listOf(get("$prefix/v4/users/me/identity").code, get("$prefix$steps").code, get("$prefix$steps").code)
        assertThat(codes).containsExactly(first, second, third).inOrder()
    }

    @Test
    fun `R08 5 3 data-shaping scenarios`() {
        val prefix = "/fake-googlehealth/scenario"
        assertThat(get("$prefix/empty$steps").body).isEqualTo("{}")
        assertThat(get("$prefix/empty/v4/users/me/identity").body).isEqualTo(GoogleHealthFixtures.text("F-IDENTITY"))
        val small = get("$prefix/small-pages$steps").json()
        assertThat(small.keys).containsExactly("dataPoints")
        assertThat(get("$prefix/permission-denied$steps").code).isEqualTo(403)
        assertThat(get("$prefix/permission-denied/v4/users/me/dataTypes/sleep/dataPoints").code).isEqualTo(200)
        assertThat(get("$prefix/captive-portal$steps").contentType).isEqualTo(FakeResponse.HTML)
        val malformed = get("$prefix/malformed$steps")
        assertThat(runCatching { Json.parseToJsonElement(malformed.body) }.isFailure).isTrue()
        val sleepFilter = "sleep.interval.end_time >= \"2026-09-29T12:00:00Z\" AND sleep.interval.end_time < \"2026-10-01T12:00:00Z\""
        val rejected = get("$prefix/sleep-filter-rejected/v4/users/me/dataTypes/sleep/dataPoints?filter=$sleepFilter")
        assertThat(rejected.reason()).isEqualTo("INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER")
        assertThat(get("$prefix/ascending-pages$steps").points().counts().first()).isNull()
        assertThat(get("$prefix/throttled$steps").transport).isEqualTo(TransportFault.THROTTLE)
        assertThat(get("$prefix/timeout$steps").transport).isEqualTo(TransportFault.STALL)
        assertThat(get("$prefix/no-content-type$steps").transport).isEqualTo(TransportFault.NO_CONTENT_TYPE)
        val disconnect = "$prefix/disconnect-mid-body$steps"
        assertThat(listOf(get(disconnect).transport, get(disconnect).transport))
            .containsExactly(TransportFault.DISCONNECT_MID_BODY, TransportFault.NONE).inOrder()
        assertThat(FakeScenarios.NAMES).containsAtLeast(
            "happy", "empty", "small-pages", "rate-limited", "rate-limited-retry-after", "token-expired", "token-revoked",
            "permission-denied", "server-flaky", "bad-gateway-html", "captive-portal", "throttled", "disconnect-mid-body",
            "timeout", "malformed", "sleep-filter-rejected", "account-not-linked", "profile-not-ready", "legacy-fitbit-account",
        )
    }

    @Test
    fun `R05 8 7 hygiene checks flag every bad request kind`() {
        fake.call("GET", "$steps?startTime=x&key=k", emptyMap())
        get("$steps?filter=steps.interval.start_time > \"2026-09-30T00:00:00Z\"")
        get("/v4/users/me/dataTypes/floors/dataPoints")
        get("/v4/users/me/dataTypes/sleep/dataPoints?dataSourceFamily=users/me/dataSourceFamilies/all-sources&pageSize=100")
        post(steps, "{}")
        fake.call("PATCH", "/v4/users/me/dataTypes/weight/dataPoints/1", headers(FakeTokens.VALID), "{}")
        post("$steps:rollUp", """{"range":{"startTime":"2026-01-01T00:00:00Z","endTime":"2026-09-01T00:00:00Z"},"windowSize":"60s"}""")
        post(
            "$steps:dailyRollUp",
            """{"range":{"start":{"date":{"year":2026,"month":"9","day":1}},"end":{"date":{"year":2026,"month":9,"day":2}}},""" +
                """"windowSizeDays":2}""",
        )
        get("$steps?pageToken=never-issued")
        val ids = fake.hygieneViolations(perMinuteBudget = 5).map { it.substringBefore(' ') }.toSet()
        assertThat(ids).containsExactly("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "H9", "H10", "H11")
    }

    @Test
    fun `R05 8 7 H7 accepts a correct page sequence and rejects changed parameters`() {
        val first = get("$steps?filter=$stepsFilter&pageSize=3")
        val token = first.json().getValue("nextPageToken").jsonPrimitive.content
        get("$steps?filter=$stepsFilter&pageSize=3&pageToken=$token")
        assertThat(fake.hygieneViolations()).isEmpty()
        get("$steps?pageSize=3&pageToken=$token")
        assertThat(fake.hygieneViolations().single()).startsWith("H7")
    }

    private companion object {
        const val DAILY_BODY = """{"range":{"start":{"date":{"year":2026,"month":9,"day":29}},""" +
            """"end":{"date":{"year":2026,"month":9,"day":30}}},"windowSizeDays":1}"""
    }
}

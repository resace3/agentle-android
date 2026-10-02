package dev.agentle.fakes.googlehealth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The docs/research/05 §8.4-8.6 fixtures, one resource file per fixture id under `googlehealth/` (lower-cased id plus
 * `.json`, `.html` or `.txt`), copied byte-for-byte from the document. [GoogleHealthFixtures.error] turns an error
 * fixture into a response with the status and headers of the §8.5 table.
 *
 * Test rule (§8): assert on the HTTP status, `error.status`, `ErrorInfo.reason` and metadata, never on `message`.
 */
public object GoogleHealthFixtures {
    public const val DIRECTORY: String = "googlehealth/"

    public val SUCCESS_IDS: List<String> = listOf(
        "F-IDENTITY", "F-SETTINGS", "F-PROFILE", "F-DEVICES-P1", "F-DEVICES-P2", "F-STEPS-P1", "F-STEPS-P2",
        "F-STEPS-RECONCILE", "F-STEPS-ROLLUP", "F-DAILY-STEPS", "F-DAILY-TOTALCAL", "F-DISTANCE", "F-AEB",
        "F-FLOORS-RECONCILE", "F-HR", "F-HR-ROLLUP", "F-RHR", "F-SLEEP", "F-EXERCISE", "F-WEIGHT", "F-BODYFAT",
    )

    public val ERROR_IDS: List<String> = listOf(
        "E400-INVALID-ARGUMENT", "E400-FILTER-A", "E400-FILTER-B", "E400-ACCOUNT-NOT-LINKED", "E400-PAGE-TOKEN",
        "E400-BAD-JSON", "E401-MISSING", "E401-INVALID", "E401-APIKEY", "E403-SCOPE-A", "E403-SCOPE-B", "E403-LEGACY-A",
        "E403-LEGACY-B", "E404-HTML", "E404-JSON", "E412", "E429", "E500", "E502", "E503", "E504",
    )

    public val ROBUSTNESS_IDS: List<String> = listOf(
        "R1C", "R1D", "R2", "R4-STEPS", "R4-EXERCISE", "R4-SLEEP-STAGE", "R5A", "R5C", "R5D", "R5E", "R5F", "R5G", "R5H",
        "R5I", "R5J-1", "R5J-2", "R5J-3", "R5J-4", "R5K", "R6C", "R6D", "R9A", "R9B",
    )

    public val ALL_IDS: List<String> = SUCCESS_IDS + ERROR_IDS + ROBUSTNESS_IDS

    /** `Retry-After` of the E429 variants A, C and D (§8.5); B has none. C is an HTTP-date 30 s after the fixture clock. */
    public const val RETRY_AFTER_A: String = "7"
    public const val RETRY_AFTER_C: String = "Thu, 01 Oct 2026 12:00:30 GMT"
    public const val RETRY_AFTER_D: String = "3600"

    public const val WWW_AUTH: String = "Bearer realm=\"https://accounts.google.com/\""
    public const val WWW_AUTH_INVALID: String = "Bearer realm=\"https://accounts.google.com/\", error=\"invalid_token\""

    /** Valid empty results of R3 (§8.6). */
    public val R3_LIST_BODIES: List<String> = listOf("{}", "{\"dataPoints\": []}", "{\"dataPoints\": [], \"nextPageToken\": \"\"}")
    public const val R3_ROLLUP_BODY: String = "{}"
    public const val R3_DAILY_ROLLUP_BODY: String = "{\"rollupDataPoints\": []}"

    private const val DEFAULT_RPC = "google.devicesandservices.health.v4.DataPointsService.ListDataPoints"
    private const val PROBED_PATH = "/v4/nonexistent"
    private const val FILTER_A_REASON = "INVALID_DATA_POINT_FILTER_MIXED_TIME_RESTRICTIONS"
    private const val FILTER_A_MESSAGE = "Filter cannot contain both physical and civil time ranges"
    private const val FILTER_B_REASON = "INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER"
    private const val R6C_PLACEHOLDER = "\"<F-SLEEP main record, updateTime 2026-09-30T11:35:44.654321Z>\""
    private const val R1A_CUT = "\"steps\":{\"interval\":{\"startTime\":\"2026-09"

    private val cache = ConcurrentHashMap<String, String>()
    private val compact = Json { prettyPrint = false }

    /** The resource file name of [id], for example `f-steps-p1.json`. */
    public fun fileName(id: String): String {
        val extension = when (id) {
            "E404-HTML", "E502", "R2" -> "html"
            "R6C" -> "txt"
            else -> "json"
        }
        return "${id.lowercase()}.$extension"
    }

    /** The fixture body, exactly as in docs/research/05. */
    public fun text(id: String): String = cache.getOrPut(id) {
        val name = DIRECTORY + fileName(id)
        val stream = GoogleHealthFixtures::class.java.classLoader.getResourceAsStream(name)
            ?: throw IllegalArgumentException("unknown Google Health fixture: $id")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The content type a fixture is served with. */
    public fun contentType(id: String): String = if (fileName(id).endsWith(".html")) FakeResponse.HTML else FakeResponse.JSON

    /** HTTP status of an error fixture (§8.5). */
    public fun status(id: String): Int = when {
        id.startsWith("E400") -> 400
        id.startsWith("E401") -> 401
        id.startsWith("E403") -> 403
        id.startsWith("E404") -> 404
        id == "E412" -> 412
        id == "E429" -> 429
        id == "E500" -> 500
        id == "E502" -> 502
        id == "E503" -> 503
        id == "E504" -> 504
        else -> 200
    }

    /**
     * An error fixture as a response (§8.5): status, `Content-Type`, `www-authenticate` and `Retry-After` headers.
     *
     * @param path the request path, substituted into E404-HTML's `<code>` element
     * @param scope the scope suffix named by E403-SCOPE-A's `www-authenticate` header
     * @param rpc the gRPC method named in the `metadata.method` of E401-MISSING, E401-APIKEY and E403-SCOPE-A
     * @param detailedReason the filter reason of E400-FILTER-A (`metadata.detailedReasons`) or E400-FILTER-B (`reason`)
     */
    public fun error(
        id: String,
        path: String = PROBED_PATH,
        retryAfter: String? = null,
        scope: String = GhScopes.ACTIVITY,
        rpc: String? = null,
        detailedReason: String? = null,
    ): FakeResponse {
        var body = text(id)
        val headers = LinkedHashMap<String, String>()
        when (id) {
            "E401-MISSING", "E401-APIKEY" -> headers["www-authenticate"] = WWW_AUTH

            "E401-INVALID" -> headers["www-authenticate"] = WWW_AUTH_INVALID

            "E403-SCOPE-A" -> headers["www-authenticate"] =
                "Bearer realm=\"https://accounts.google.com/\", error=\"insufficient_scope\", scope=\"${GhScopes.full(scope)}\""

            "E404-HTML" -> body = body.replace("<code>$PROBED_PATH</code>", "<code>${escapeHtml(path)}</code>")

            "E400-FILTER-A" -> if (detailedReason != null) {
                body = body.replace(FILTER_A_REASON, detailedReason).replace(FILTER_A_MESSAGE, filterMessage(detailedReason))
            }

            "E400-FILTER-B" -> if (detailedReason != null) body = body.replace(FILTER_B_REASON, detailedReason)
        }
        if (rpc != null) body = body.replace(DEFAULT_RPC, rpc)
        if (retryAfter != null) headers["Retry-After"] = retryAfter
        return FakeResponse(status(id), body, contentType(id), headers)
    }

    /** The E400-FILTER-A `message` for a detailed reason (§8.5). */
    public fun filterMessage(reason: String): String = when (reason) {
        "INVALID_DATA_POINT_FILTER_EXPRESSION_STRUCTURE" ->
            "The filter must be a conjunction or sequence of restrictions. Found: DISJUNCTION"

        "INVALID_DATA_POINT_FILTER_COLLECTION_MISMATCH" -> "Data type in filter does not match parent data type collection"

        FILTER_A_REASON -> FILTER_A_MESSAGE

        "INVALID_TIME_RANGE" -> "Query end time must be strictly larger than start time"

        else -> "Invalid filter"
    }

    /** A 200 response that serves fixture [id] (any fixture: success, robustness or an HTML page). */
    public fun ok(id: String): FakeResponse = FakeResponse(200, text(id), contentType(id))

    /** R1a: the compact serialization of F-STEPS-P1, cut inside the second point (§8.6). */
    public fun r1aTruncated(): String {
        val full = compact.encodeToString(JsonElement.serializer(), compact.parseToJsonElement(text("F-STEPS-P1")))
        val first = full.indexOf(R1A_CUT)
        val second = full.indexOf(R1A_CUT, first + 1)
        check(first >= 0 && second > first) { "F-STEPS-P1 no longer has two step points" }
        return full.substring(0, second + R1A_CUT.length)
    }

    /** R6c with its placeholder replaced by the F-SLEEP main record (§8.6). */
    public fun r6c(): String {
        val main = compact.parseToJsonElement(text("F-SLEEP")).jsonObject.getValue("dataPoints").jsonArray[1]
        return text("R6C").replace(R6C_PLACEHOLDER, compact.encodeToString(JsonElement.serializer(), main))
    }

    /**
     * A `list` page made of single-point fixtures (R4-STEPS points, R5x, R9x ...), in the given order. A null
     * [nextPageToken] omits the key.
     */
    public fun page(pointIds: List<String>, nextPageToken: String? = ""): String {
        val points = pointIds.joinToString(",\n") { text(it).trimEnd() }
        val token = if (nextPageToken == null) "" else ",\n  \"nextPageToken\": \"$nextPageToken\""
        return "{\n  \"dataPoints\": [\n$points\n  ]$token\n}\n"
    }

    /** The data points of a list fixture, for building model-mode datasets from documented bodies. */
    public fun points(id: String): JsonArray =
        compact.parseToJsonElement(text(id)).jsonObject["dataPoints"]?.jsonArray ?: JsonArray(emptyList())

    private fun escapeHtml(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}

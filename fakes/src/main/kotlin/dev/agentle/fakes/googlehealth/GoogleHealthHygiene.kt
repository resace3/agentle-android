package dev.agentle.fakes.googlehealth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The request-journal hygiene checks H1 to H11 of docs/research/05 §8.7. Each violation is one line that starts with
 * the check id, names the request (method and path, never a token) and says what is wrong.
 */
public object GoogleHealthHygiene {
    public const val DEFAULT_BUDGET: Int = 200

    private val SNAKE_RESTRICTION = Regex("""^[a-z0-9_]+\.[a-z_.]+ (>=|<) "[^"]*"$""")
    private val LEADING_ZERO = Regex(""""(?:month|day)"\s*:\s*0\d""")
    private val FOURTEEN_DAY_TYPES = setOf("heart-rate", "total-calories", "active-minutes", "calories-in-heart-rate-zone")
    private val json = Json

    public fun check(exchanges: List<FakeExchange>, perMinuteBudget: Int = DEFAULT_BUDGET): List<String> {
        val api = exchanges.filter { it.request.path.startsWith("/v4/") }
        val out = ArrayList<String>()
        api.forEachIndexed { index, exchange -> out += checkOne(exchange.request, api.subList(0, index)) }
        out += rate(api.map { it.request }, perMinuteBudget)
        return out
    }

    @Suppress("CyclomaticComplexMethod")
    private fun checkOne(req: FakeRequest, earlier: List<FakeExchange>): List<String> {
        val out = ArrayList<String>()
        val what = "${req.method} ${req.path}"
        val type = req.path.substringAfter("/dataTypes/", "").substringBefore('/')
        val isList = req.method == "GET" && req.path.endsWith("/dataPoints") && "/dataTypes/" in req.path
        val isReconcile = req.method == "GET" && req.path.endsWith("/dataPoints:reconcile")
        // H1
        val auth = req.headers["authorization"].orEmpty()
        if (!auth.startsWith("Bearer ") || auth.removePrefix("Bearer ").isBlank()) out += "H1 $what: no bearer token"
        if (req.headers["accept"]?.contains("application/json") != true) out += "H1 $what: no Accept: application/json"
        if ("key" in req.query) out += "H1 $what: API key parameter"
        // H2
        if ((isList || isReconcile) && ("startTime" in req.query || "endTime" in req.query)) out += "H2 $what: startTime/endTime parameter"
        // H3
        req.param("filter")?.let { filter ->
            if (" OR " in filter) out += "H3 $what: OR in filter"
            if (filter.split(" AND ").any { !SNAKE_RESTRICTION.matches(it.trim()) }) {
                out +=
                    "H3 $what: filter restriction not snake_case with >= or <"
            }
        }
        // H4, H5, H6
        if (isList && (type == "floors" || type == "total-calories")) out += "H4 $what: list on $type"
        if (isList && type == "sleep" && "dataSourceFamily" in req.query) out += "H5 $what: dataSourceFamily on the sleep list"
        if ((isList || isReconcile) && (type == "sleep" || type == "exercise")) {
            val size = req.param("pageSize")?.toIntOrNull()
            if (size != null && size > GhDataType.SESSION_PAGE) out += "H6 $what: pageSize $size"
        }
        // H7
        pageToken(req)?.let { token -> h7(req, token, earlier)?.let { out += "H7 $what: $it" } }
        // H8, H9
        if (req.method == "POST" && req.path.endsWith(":rollUp")) h8(req, type)?.let { out += "H8 $what: $it" }
        if (req.method == "POST" && req.path.endsWith(":dailyRollUp")) h9(req)?.let { out += "H9 $what: $it" }
        // H11
        val write = req.method == "PATCH" || req.method == "DELETE" ||
            (req.method == "POST" && (req.path.endsWith("/dataPoints") || req.path.endsWith(":batchDelete")))
        if (write) out += "H11 $what: write method"
        return out
    }

    private fun pageToken(req: FakeRequest): String? =
        req.param("pageToken")?.takeIf { it.isNotEmpty() } ?: body(req)?.string("pageToken")?.takeIf { it.isNotEmpty() }

    private fun h7(req: FakeRequest, token: String, earlier: List<FakeExchange>): String? {
        val issuer = earlier.lastOrNull { e ->
            e.response.code == OK && runCatching { (json.parseToJsonElement(e.response.body) as? JsonObject)?.string("nextPageToken") }
                .getOrNull() == token
        }?.request ?: return "page token was not issued by an earlier response"
        if (issuer.method != req.method || issuer.path != req.path) return "page token reused on another request"
        return if (req.method == "GET") {
            if (issuer.query - "pageToken" != req.query - "pageToken") "parameters changed between pages" else null
        } else {
            val a = body(issuer)?.copyWithout("pageToken")
            val b = body(req)?.copyWithout("pageToken")
            if (a == null || b == null || ModelEngine.canonical(a) != ModelEngine.canonical(b)) "body changed between pages" else null
        }
    }

    private fun h8(req: FakeRequest, type: String): String? {
        val body = body(req) ?: return "unparseable body"
        val range = body.obj("range")
        val start = range?.instant("startTime")
        val end = range?.instant("endTime")
        val window = ModelEngine.parseDuration(body.string("windowSize"))
        val maxDays = if (type in FOURTEEN_DAY_TYPES) FOURTEEN else NINETY
        return when {
            start == null || end == null -> "range without startTime/endTime"
            end - start > maxDays.days -> "range longer than $maxDays days"
            window == null || window < 1.seconds -> "windowSize under 1s"
            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun h9(req: FakeRequest): String? {
        if (LEADING_ZERO.containsMatchIn(req.body)) return "leading zero in month or day"
        val body = body(req) ?: return "unparseable body"
        val range = body.obj("range")
        val dates = listOfNotNull(range?.obj("start")?.obj("date"), range?.obj("end")?.obj("date"))
        if (dates.size != 2) return "range without start and end dates"
        val numeric = dates.all { d -> listOf("year", "month", "day").all { (d[it] as? JsonPrimitive)?.isString == false } }
        if (!numeric) return "date members are not JSON numbers"
        val window = body["windowSizeDays"]?.let { (it as? JsonPrimitive)?.content }
        return if (window != null && window != "1") "windowSizeDays $window" else null
    }

    private fun rate(requests: List<FakeRequest>, budget: Int): List<String> = requests.groupBy { it.bearerToken.orEmpty() }
        .mapNotNull { (_, list) ->
            val times = list.map { it.at }.sorted()
            var from = 0
            var worst = 0
            for (i in times.indices) {
                while (times[i] - times[from] >= 60.seconds) from++
                worst = maxOf(worst, i - from + 1)
            }
            if (worst > budget) "H10 $worst requests in one minute with one token (budget $budget)" else null
        }

    private fun body(req: FakeRequest): JsonObject? =
        if (req.body.isBlank()) null else runCatching { json.parseToJsonElement(req.body) as? JsonObject }.getOrNull()

    private const val OK = 200
    private const val FOURTEEN = 14
    private const val NINETY = 90
}

package dev.agentle.fakes.googlehealth

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TreeMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Model mode (docs/research/05 §8.1): serves the endpoints of §8.2 from a [FakeDataset] and enforces the validation
 * rules V1 to V7 of §8.3. [validate] runs the request checks (all but V4); [serve] answers, checking page tokens (V4).
 */
internal class ModelEngine(
    private val dataset: () -> FakeDataset,
    private val config: () -> FakeGoogleHealthConfig,
    private val now: () -> Instant,
) {
    private class Checked<T>(val value: T? = null, val error: FakeResponse? = null)

    private class ListQuery(val type: GhDataType, val filter: ParsedFilter, val pageSize: Int, val family: String?)

    private class RollUpQuery(
        val type: GhDataType,
        val start: Instant,
        val end: Instant,
        val window: Duration,
        val pageSize: Int,
        val family: String?,
        val pageToken: String?,
        val key: String,
    )

    private class DailyQuery(val type: GhDataType, val first: LocalDate, val last: LocalDate, val windowDays: Int, val family: String?)

    fun validate(route: FakeRoute, req: FakeRequest): FakeResponse? = when (route.kind) {
        RouteKind.IDENTITY, RouteKind.SETTINGS, RouteKind.PROFILE, RouteKind.PAIRED_DEVICE -> null
        RouteKind.PAIRED_DEVICES -> pageSize(req.param("pageSize"), DEVICES_DEFAULT_PAGE, DEVICES_MAX_PAGE).error
        RouteKind.LIST, RouteKind.RECONCILE -> listQuery(route, req).error
        RouteKind.ROLL_UP -> rollUpQuery(route, req).error
        RouteKind.DAILY_ROLL_UP -> dailyQuery(route, req).error
        RouteKind.GET -> if (route.type?.identifiable == true) null else invalid()
        RouteKind.EXPORT_TCX -> if (route.type == GhDataTypes.EXERCISE) null else invalid()
        RouteKind.WRONG_VERB -> GoogleHealthFixtures.error("E404-HTML", req.path)
    }

    fun serve(route: FakeRoute, req: FakeRequest): FakeResponse = when (route.kind) {
        RouteKind.IDENTITY -> ok(identityBody())
        RouteKind.SETTINGS -> ok(settingsBody())
        RouteKind.PROFILE -> ok(GoogleHealthFixtures.text("F-PROFILE").replace(FIXTURE_USER, config().healthUserId))
        RouteKind.PAIRED_DEVICES -> devices(req)
        RouteKind.PAIRED_DEVICE -> device(route.itemId)
        RouteKind.LIST, RouteKind.RECONCILE -> list(route, req)
        RouteKind.ROLL_UP -> rollUp(route, req)
        RouteKind.DAILY_ROLL_UP -> daily(route, req)
        RouteKind.GET -> get(route)
        RouteKind.EXPORT_TCX -> GoogleHealthFixtures.error("E404-JSON")
        RouteKind.WRONG_VERB -> GoogleHealthFixtures.error("E404-HTML", req.path)
    }

    // ---------------------------------------------------------------- identity, settings, devices

    private fun identityBody(): String = GoogleHealthFixtures.text("F-IDENTITY")
        .replace(FIXTURE_USER, config().healthUserId)
        .replace(FIXTURE_LEGACY_USER, config().legacyUserId)

    private fun settingsBody(): String {
        val zone = config().timeZone
        val offset = TimeZone.of(zone).offsetAt(now()).totalSeconds
        return GoogleHealthFixtures.text("F-SETTINGS")
            .replace(FIXTURE_USER, config().healthUserId)
            .replace("\"timeZone\": \"America/New_York\"", "\"timeZone\": \"$zone\"")
            .replace("\"utcOffset\": \"-14400s\"", "\"utcOffset\": \"${offset}s\"")
    }

    private fun devices(req: FakeRequest): FakeResponse {
        val size = pageSize(req.param("pageSize"), DEVICES_DEFAULT_PAGE, DEVICES_MAX_PAGE).value ?: DEVICES_DEFAULT_PAGE
        val key = queryKey(req)
        val offset = offsetOf(req.param("pageToken"), key) ?: return GoogleHealthFixtures.error("E400-PAGE-TOKEN")
        val all = dataset().devices
        val page = all.drop(offset).take(size)
        if (page.isEmpty()) return ok("{}")
        val lagCut = now() - config().deviceSyncLag
        val body = buildJsonObject {
            put("pairedDevices", JsonArray(page.map { it.toJson(config().healthUserId, if (it.tracker) lagCut else it.lastSyncTime) }))
            if (offset + page.size < all.size) put("nextPageToken", token(offset + page.size, key))
        }
        return ok(body)
    }

    private fun device(id: String?): FakeResponse {
        val device = dataset().devices.firstOrNull { it.id == id } ?: return GoogleHealthFixtures.error("E404-JSON")
        val lagCut = now() - config().deviceSyncLag
        return ok(device.toJson(config().healthUserId, if (device.tracker) lagCut else device.lastSyncTime))
    }

    // ---------------------------------------------------------------- list, reconcile, get

    @Suppress("ReturnCount")
    private fun listQuery(route: FakeRoute, req: FakeRequest): Checked<ListQuery> {
        val type = route.type ?: return Checked(error = invalid())
        if (route.kind == RouteKind.LIST && !type.listable) return Checked(error = invalid())
        if (route.kind == RouteKind.RECONCILE && !type.reconcilable) return Checked(error = invalid())
        val filter = when (val parsed = FilterParser.parse(req.param("filter"), type, config())) {
            is FilterResult.Rejected -> return Checked(error = filterError(parsed.reason))
            is FilterResult.Ok -> parsed.filter
        }
        val size = pageSize(req.param("pageSize"), type.defaultPageSize, type.maxPageSize)
        size.error?.let { return Checked(error = it) }
        val family = req.param("dataSourceFamily")
        familyError(family, type, route.kind)?.let { return Checked(error = it) }
        return Checked(ListQuery(type, filter, requireNotNull(size.value), family))
    }

    private fun list(route: FakeRoute, req: FakeRequest): FakeResponse {
        val q = listQuery(route, req)
        val query = q.value ?: return requireNotNull(q.error)
        val key = queryKey(req)
        val offset = offsetOf(req.param("pageToken"), key) ?: return GoogleHealthFixtures.error("E400-PAGE-TOKEN")
        val reconcile = route.kind == RouteKind.RECONCILE
        val sourced = visible(query.type).filter { familyAllows(query.family, it) }
        val matching = sourced.filter { query.filter.matches(it) }
        val points = if (reconcile) reconciledSubset(sourced, matching) else matching
        // list: newest first (documented); :reconcile order is undocumented, the fake serves it ascending.
        val ordered = if (reconcile) points.sortedBy { it.start } else points.sortedByDescending { it.start }
        var page = ordered.drop(offset).take(query.pageSize)
        if (page.isEmpty()) return ok("{}")
        // R7a: the same pages, with the points inside each page in ascending order.
        if (!reconcile && config().listOrder == ListOrder.ASCENDING) page = page.sortedBy { it.start }
        val next = offset + page.size
        val body = buildJsonObject {
            put("dataPoints", JsonArray(page.map { if (reconcile) it.toReconcileJson() else it.toListJson() }))
            if (next < ordered.size) put("nextPageToken", token(next, key)) else endMarker()
        }
        return ok(body)
    }

    private fun get(route: FakeRoute): FakeResponse {
        val type = route.type ?: return invalid()
        val point = visible(type).firstOrNull { it.id == route.itemId } ?: return GoogleHealthFixtures.error("E404-JSON")
        return ok(point.toListJson())
    }

    // ---------------------------------------------------------------- rollUp

    @Suppress("ReturnCount")
    private fun rollUpQuery(route: FakeRoute, req: FakeRequest): Checked<RollUpQuery> {
        val type = route.type ?: return Checked(error = invalid())
        if (!type.rollUps) return Checked(error = invalid())
        val body = parseBody(req.body) ?: return Checked(error = GoogleHealthFixtures.error("E400-BAD-JSON"))
        val range = body.obj("range") ?: return Checked(error = invalid())
        val start = range.instant("startTime") ?: return Checked(error = invalid())
        val end = range.instant("endTime") ?: return Checked(error = invalid())
        val window = parseDuration(body.string("windowSize")) ?: return Checked(error = invalid())
        if (end <= start || window < 1.seconds || end - start > type.rollUpMaxDays.days) return Checked(error = invalid())
        val size = pageSize(body["pageSize"]?.let { (it as? JsonPrimitive)?.content }, type.defaultPageSize, type.maxPageSize)
        size.error?.let { return Checked(error = it) }
        val family = body.string("dataSourceFamily")
        familyError(family, type, route.kind)?.let { return Checked(error = it) }
        val key = "${req.method} ${req.path} ${canonical(body.copyWithout("pageToken"))}"
        return Checked(RollUpQuery(type, start, end, window, requireNotNull(size.value), family, body.string("pageToken"), key))
    }

    private fun rollUp(route: FakeRoute, req: FakeRequest): FakeResponse {
        val checked = rollUpQuery(route, req)
        val q = checked.value ?: return requireNotNull(checked.error)
        val offset = offsetOf(q.pageToken, q.key) ?: return GoogleHealthFixtures.error("E400-PAGE-TOKEN")
        val windowMs = q.window.inWholeMilliseconds
        val sourced = visible(q.type).filter { familyAllows(q.family, it) }
        val points = reconciledSubset(sourced, sourced.filter { it.start >= q.start && it.start < q.end })
        val windows = points.groupBy { (it.start - q.start).inWholeMilliseconds / windowMs }.toSortedMap()
        val all = windows.entries.toList()
        val page = all.drop(offset).take(q.pageSize)
        if (page.isEmpty()) return ok("{}")
        val body = buildJsonObject {
            put(
                "rollupDataPoints",
                JsonArray(
                    page.map { (index, inWindow) ->
                        val windowStart = q.start + (index * windowMs).milliseconds
                        buildJsonObject {
                            put("startTime", PointJson.timestamp(windowStart))
                            put("endTime", PointJson.timestamp(minOf(windowStart + q.window, q.end)))
                            put(q.type.unionKey, aggregate(q.type, inWindow))
                        }
                    },
                ),
            )
            if (offset + page.size < all.size) put("nextPageToken", token(offset + page.size, q.key))
        }
        return ok(body)
    }

    // ---------------------------------------------------------------- dailyRollUp

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun dailyQuery(route: FakeRoute, req: FakeRequest): Checked<DailyQuery> {
        val type = route.type ?: return Checked(error = invalid())
        if (!type.rollUps) return Checked(error = invalid())
        if (LEADING_ZERO.containsMatchIn(req.body)) return Checked(error = GoogleHealthFixtures.error("E400-BAD-JSON"))
        val body = parseBody(req.body) ?: return Checked(error = GoogleHealthFixtures.error("E400-BAD-JSON"))
        val range = body.obj("range") ?: return Checked(error = invalid())
        val first = range.obj("start")?.obj("date")?.let { PointJson.parseDate(it) } ?: return Checked(error = invalid())
        val end = range.obj("end")?.obj("date")?.let { PointJson.parseDate(it) } ?: return Checked(error = invalid())
        val windowDays = body["windowSizeDays"]?.let { (it as? JsonPrimitive)?.intOrNull ?: 0 } ?: 1
        if (windowDays < 1) return Checked(error = invalid())
        val last = if (config().dailyRollUpEnd == DailyRollUpEnd.INCLUSIVE) end else end.plus(DatePeriod(days = -1))
        val days = first.daysUntil(last) + 1
        if (days < 1 || days > type.rollUpMaxDays) return Checked(error = invalid())
        val family = body.string("dataSourceFamily")
        familyError(family, type, route.kind)?.let { return Checked(error = it) }
        return Checked(DailyQuery(type, first, last, windowDays, family))
    }

    private fun daily(route: FakeRoute, req: FakeRequest): FakeResponse {
        val checked = dailyQuery(route, req)
        val q = checked.value ?: return requireNotNull(checked.error)
        val sourced = visible(q.type).filter { familyAllows(q.family, it) }
        val dated = sourced.filter { dateOf(it) in q.first..q.last }
        val points = reconciledSubset(sourced, dated).map { p -> (q.first.daysUntil(dateOf(p)) / q.windowDays) to p }
        val windows = points.groupBy({ it.first }, { it.second }).toSortedMap()
        if (windows.isEmpty()) return ok("{}")
        val body = buildJsonObject {
            put(
                "rollupDataPoints",
                JsonArray(
                    windows.map { (index, inWindow) ->
                        val from = q.first.plus(DatePeriod(days = index * q.windowDays))
                        buildJsonObject {
                            put("civilStartTime", midnight(from))
                            put("civilEndTime", midnight(from.plus(DatePeriod(days = q.windowDays))))
                            put(q.type.unionKey, aggregate(q.type, inWindow))
                        }
                    },
                ),
            )
        }
        return ok(body)
    }

    /** The local date a point counts for: its own date (daily types), else the date of its start in its own offset. */
    private fun dateOf(p: FakePoint): LocalDate = p.date ?: PointJson.localTime(p.start, p.startOffsetSeconds).date

    private fun midnight(date: LocalDate): JsonObject = buildJsonObject {
        put("date", PointJson.date(date))
        put("time", JsonObject(emptyMap()))
    }

    // ---------------------------------------------------------------- shared

    /** Points upstream has: recorded before now, and for tracker data, uploaded by the tracker's last sync. */
    private fun visible(type: GhDataType): List<FakePoint> {
        val now = now()
        val lagCut = now - config().deviceSyncLag
        return dataset().of(type).filter { p -> p.end <= now && (p.source?.wearable != true || p.end <= lagCut) }
    }

    /**
     * `:reconcile` and the rollups merge every source into one stream that "deduplicates overlapping records across
     * devices and sync sessions" (§5.3): of points that overlap, only the one with the highest priority stays. The
     * priority is modeled (the real rule is undocumented): the Fitbit platform first, then a wearable, then the earlier
     * start, then dataset order. Points without a duration (samples) never overlap anything.
     */
    private fun reconciled(points: List<FakePoint>): List<FakePoint> {
        if (points.size < 2) return points
        val ranked = points.indices.sortedWith(
            compareBy<Int>({ if (points[it].isFitbit()) 0 else 1 }, { if (points[it].source?.wearable == true) 0 else 1 })
                .thenBy { points[it].start }
                .thenBy { it },
        )
        val taken = TreeMap<Instant, Instant>()
        val keep = BooleanArray(points.size)
        for (i in ranked) {
            val p = points[i]
            if (p.end > p.start) {
                val below = taken.floorEntry(p.start)
                val above = taken.higherEntry(p.start)
                if ((below != null && below.value > p.start) || (above != null && above.key < p.end)) continue
                taken[p.start] = p.end
            }
            keep[i] = true
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * The points of [matching] that survive reconciliation with every point of [all] that could overlap them, so the
     * result does not depend on the request window. Identical copies count once (identity, not equality).
     */
    private fun reconciledSubset(all: List<FakePoint>, matching: List<FakePoint>): List<FakePoint> {
        if (matching.isEmpty()) return matching
        val from = matching.minOf { it.start } - RECONCILE_MARGIN
        val to = matching.maxOf { it.end } + RECONCILE_MARGIN
        val kept = Collections.newSetFromMap(IdentityHashMap<FakePoint, Boolean>())
        kept += reconciled(all.filter { it.end >= from && it.start <= to })
        return matching.filter { it in kept }
    }

    private fun FakePoint.isFitbit(): Boolean = source == null || source.platform == null || source.platform == "FITBIT"

    private fun familyAllows(family: String?, p: FakePoint): Boolean = when (family?.substringAfterLast('/')) {
        null, "all-sources", "google-sources" -> true
        "google-wearables" -> p.isFitbit() && p.source?.recordingMethod != "MANUAL"
        else -> false // self-sources: Agentle never writes
    }

    private fun familyError(family: String?, type: GhDataType, kind: RouteKind): FakeResponse? = when {
        family == null -> null
        !FAMILY.matches(family) -> invalid()
        type == GhDataTypes.SLEEP && kind == RouteKind.LIST -> invalid()
        else -> null
    }

    private fun aggregate(type: GhDataType, points: List<FakePoint>): JsonObject {
        val values = points.map { it.amount ?: 0.0 }
        val sum = values.sum()
        return buildJsonObject {
            when (type.id) {
                "steps", "floors" -> put("countSum", sum.toLong().toString())

                "distance" -> put("millimetersSum", sum.toLong().toString())

                "active-energy-burned", "total-calories" -> put("kcalSum", PointJson.number(sum))

                "heart-rate" -> {
                    put("beatsPerMinuteMin", PointJson.number(values.min()))
                    put("beatsPerMinuteAvg", PointJson.number(sum / values.size))
                    put("beatsPerMinuteMax", PointJson.number(values.max()))
                }

                "weight" -> put("weightGramsAvg", PointJson.number(sum / values.size))

                "body-fat" -> put("bodyFatPercentageAvg", PointJson.number(sum / values.size))
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.endMarker() {
        when (config().endOfPages) {
            EndOfPages.EMPTY_STRING -> put("nextPageToken", "")
            EndOfPages.NULL -> put("nextPageToken", JsonNull)
            EndOfPages.OMITTED -> Unit
        }
    }

    /** V3: absent or 0 means the default, values above the maximum are clamped, negative values are rejected. */
    private fun pageSize(raw: String?, default: Int, max: Int): Checked<Int> {
        val cap = config().maxPageSize ?: Int.MAX_VALUE
        if (raw == null) return Checked(minOf(default, cap))
        val n = raw.toIntOrNull() ?: return Checked(error = invalid())
        return when {
            n < 0 -> Checked(error = invalid())
            n == 0 -> Checked(minOf(default, cap))
            else -> Checked(minOf(n, max, cap))
        }
    }

    private fun filterError(reason: String): FakeResponse = when (config().filterErrorStyle) {
        FilterErrorStyle.DETAILED_REASONS -> GoogleHealthFixtures.error("E400-FILTER-A", detailedReason = reason)
        FilterErrorStyle.REASON_ONLY -> GoogleHealthFixtures.error("E400-FILTER-B", detailedReason = reason)
    }

    private fun invalid(): FakeResponse = GoogleHealthFixtures.error("E400-INVALID-ARGUMENT")

    private fun ok(body: String): FakeResponse = FakeResponse(200, body)

    private fun ok(body: JsonObject): FakeResponse = FakeResponse(200, json.encodeToString(JsonElement.serializer(), body))

    private fun queryKey(req: FakeRequest): String = "${req.method} ${req.path}?" + req.query.filterKeys { it != "pageToken" }
        .toSortedMap().entries.joinToString("&") { (k, values) -> values.joinToString("&") { "$k=$it" } }

    private fun token(offset: Int, key: String): String = "pt$offset-${hash8(key)}"

    /** The offset a page token stands for: 0 without a token, null when the token was not issued for this request (V4). */
    private fun offsetOf(token: String?, key: String): Int? {
        if (token.isNullOrEmpty()) return 0
        val m = TOKEN.matchEntire(token) ?: return null
        return if (m.groupValues[2] == hash8(key)) m.groupValues[1].toInt() else null
    }

    private fun parseBody(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private val json = Json { prettyPrint = false }

    companion object {
        const val DEVICES_DEFAULT_PAGE = 5
        const val DEVICES_MAX_PAGE = 100

        /** Reconciliation looks this far around a request's points, which covers every realistic record length. */
        private val RECONCILE_MARGIN = 1.days
        const val FIXTURE_USER = "1234567890"
        const val FIXTURE_LEGACY_USER = "A1B2C3"
        private val TOKEN = Regex("""^pt(\d+)-([0-9a-f]{8})$""")
        private val FAMILY = Regex("""^users/me/dataSourceFamilies/(all-sources|google-wearables|google-sources|self-sources)$""")
        private val LEADING_ZERO = Regex(""""(?:year|month|day)"\s*:\s*-?0\d""")
        private val DURATION = Regex("""^(-?\d+)(\.\d+)?s$""")

        fun parseDuration(text: String?): Duration? {
            val m = DURATION.matchEntire(text ?: return null) ?: return null
            return (m.groupValues[1] + m.groupValues[2]).toDouble().seconds
        }

        fun canonical(element: JsonElement): String = when (element) {
            is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${canonical(v)}" }
            is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
            else -> element.toString()
        }

        fun hash8(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

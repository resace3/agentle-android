package dev.agentle.connectors.googlehealth

import dev.agentle.connectors.googlehealth.GhJson.array
import dev.agentle.connectors.googlehealth.GhJson.instant
import dev.agentle.connectors.googlehealth.GhJson.obj
import dev.agentle.connectors.googlehealth.GhJson.string
import dev.agentle.core.model.PersonalEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A half-open window `[start, end)`. */
internal data class GhRange(val start: Instant, val end: Instant) {
    init {
        require(end > start) { "empty window" }
    }

    operator fun contains(at: Instant): Boolean = at >= start && at < end
}

/** What one window holds upstream: its events (start inside the window, deduplicated) and the counts behind them. */
internal data class GhWindow(val range: GhRange, val events: List<PersonalEvent>, val fetched: Int, val skipped: Int)

/**
 * Reads windows of one stream (docs/research/05 §5.1, §5.2): the documented filter first; on a filter rejection, the
 * 3P-verified fallback once for the rest of the run (a lower bound only, or unfiltered recency paging for sleep), whose
 * result is clamped to each window. Pagination guards: absent, null or empty token ends; parameters (or the rollUp
 * body) are repeated unchanged with the token (H7); a repeated token or more than [MAX_PAGES] pages fails the window;
 * a rejected page token restarts the window once (R8d). Nothing is returned for a window unless all its pages succeeded.
 */
internal class GhFetcher(
    private val api: GhApi,
    private val config: GoogleHealthConfig,
    private val mapper: GhMapper,
    private val zone: TimeZone,
) {
    /** Per-run fallback state of one stream: set once its documented filter was rejected. */
    class StreamRun(val floor: Instant? = null) {
        var fallback: Boolean = false
    }

    sealed interface Result {
        data class Ok(val window: GhWindow) : Result

        data class Failed(val failure: GhFailure) : Result
    }

    /**
     * Fetches [windows] (ascending, contiguous or not) and hands each complete one to [commit], in order; stops at the
     * first failure or when [commit] returns false. Returns the failure, if any.
     */
    suspend fun fetchAll(stream: GhStream, windows: List<GhRange>, run: StreamRun, commit: suspend (GhWindow) -> Boolean): GhFailure? {
        var index = 0
        while (index < windows.size) {
            if (run.fallback && stream.hasFallback) return fallbackRest(stream, windows.subList(index, windows.size), run.floor, commit)
            when (val result = fetch(stream, windows[index], documented = true, run.floor)) {
                is Result.Ok -> if (!commit(result.window)) return null

                is Result.Failed -> {
                    val failure = result.failure
                    if (failure !is GhFailure.FilterRejected || !stream.hasFallback || run.fallback) return failure
                    run.fallback = true
                    continue
                }
            }
            index++
        }
        return null
    }

    /** One fallback read covering every remaining window, partitioned and committed window by window. */
    private suspend fun fallbackRest(
        stream: GhStream,
        windows: List<GhRange>,
        floor: Instant?,
        commit: suspend (GhWindow) -> Boolean,
    ): GhFailure? {
        val span = GhRange(windows.minOf { it.start }, windows.maxOf { it.end })
        val all = when (val result = fetch(stream, span, documented = false, floor)) {
            is Result.Ok -> result.window

            is Result.Failed -> return (result.failure as? GhFailure.FilterRejected)?.let { GhFailure.Unsupported(it.reason) }
                ?: result.failure
        }
        for (window in windows) {
            val events = all.events.filter { it.startTime in diffRange(stream, window, floor) }
            if (!commit(GhWindow(window, events, events.size, if (window === windows.first()) all.skipped else 0))) return null
        }
        return null
    }

    /** Fetches every page of [range] and maps it; nothing partial is ever returned. */
    suspend fun fetch(stream: GhStream, range: GhRange, documented: Boolean, floor: Instant? = null): Result = when (stream.method) {
        GhMethod.LIST, GhMethod.RECONCILE -> listWindow(stream, range, documented, floor)
        GhMethod.ROLL_UP -> rollUpWindow(stream, range)
        GhMethod.DAILY_ROLL_UP -> dailyRollUpWindow(stream, range)
        GhMethod.PAIRED_DEVICES -> Result.Failed(GhFailure.Unsupported("devices_window"))
    }

    private val GhStream.hasFallback: Boolean get() = method == GhMethod.LIST || method == GhMethod.RECONCILE

    // ---------------------------------------------------------------- list and :reconcile

    private suspend fun listWindow(stream: GhStream, range: GhRange, documented: Boolean, floor: Instant?): Result {
        val filter = if (documented) documentedFilter(stream, range) else fallbackFilter(stream, range)
        val pageSize = if (stream.pageSizeCapped) config.sessionPageSize else config.pageSize
        val request = GhRequest.Data(stream.method, stream.dataType, filter = filter, pageSize = pageSize)
        // Sleep without a filter: newest first, stop once a page reaches 24 h before the window (§5.1).
        val stopBefore = if (stream.kind == GhKind.SLEEP && filter == null) range.start - SESSION_LEAD else null
        val pages = when (
            val read = pages(request, "dataPoints") { page ->
                stopBefore != null &&
                    oldestSleepStart(page)?.let { it < stopBefore } == true
            }
        ) {
            is Pages.Read -> read.pages
            is Pages.Failed -> return Result.Failed(read.failure)
        }
        val points = ArrayList<JsonObject>()
        var received = 0
        var skipped = 0
        pages.forEach { page ->
            val array = page.array("dataPoints") ?: JsonArray(emptyList())
            received += array.size
            array.forEach { element -> (element as? JsonObject)?.let(points::add) ?: skipped++ }
        }
        val events = ArrayList<PersonalEvent>()
        for (point in points) {
            when (val mapped = mapper.point(stream, point)) {
                is Mapped.Event -> if (inWindow(stream, mapped.event, range, floor)) events += mapped.event
                is Mapped.Skip -> skipped++
            }
        }
        return Result.Ok(GhWindow(range, newestByKey(events), received, skipped))
    }

    /** The oldest session start on a sleep page; order inside the page does not matter (R7a). */
    private fun oldestSleepStart(page: JsonObject): Instant? = page.array("dataPoints").orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.obj("sleep")?.obj("interval")?.instant("startTime")
    }.minOrNull()

    /** The documented filter (§5.1 "try first"); sleep reads by end time, 24 h past the window, and keeps starts inside. */
    private fun documentedFilter(stream: GhStream, range: GhRange): String {
        val t = stream.filterName
        return when (stream.kind) {
            GhKind.INTERVAL -> physical("$t.interval.start_time", range.start, range.end)

            GhKind.SAMPLE -> physical("$t.sample_time.physical_time", range.start, range.end)

            GhKind.DAILY -> "$t.date >= \"${date(range.start)}\" AND $t.date < \"${date(range.end)}\""

            GhKind.SLEEP -> physical("$t.interval.end_time", range.start - SESSION_LEAD, range.end + SESSION_LEAD)

            GhKind.EXERCISE -> "$t.interval.civil_start_time >= \"${civil(range.start - CIVIL_WIDENING)}\" AND " +
                "$t.interval.civil_start_time < \"${civil(range.end + CIVIL_WIDENING)}\""

            GhKind.ROLL_UP, GhKind.DAILY_ROLL_UP, GhKind.DEVICES -> error("no list filter for ${stream.kind}")
        }
    }

    /** The 3P-verified fallback (§5.1): a lower bound only, widened by 14 h for civil fields; none for sleep. */
    private fun fallbackFilter(stream: GhStream, range: GhRange): String? {
        val t = stream.filterName
        return when (stream.kind) {
            GhKind.INTERVAL -> "$t.interval.civil_start_time >= \"${civil(range.start - CIVIL_WIDENING)}\""
            GhKind.SAMPLE -> "$t.sample_time.civil_time >= \"${civil(range.start - CIVIL_WIDENING)}\""
            GhKind.DAILY -> "$t.date >= \"${date(range.start)}\""
            GhKind.EXERCISE -> "$t.interval.civil_start_time >= \"${civil(range.start - CIVIL_WIDENING)}\""
            GhKind.SLEEP -> null
            GhKind.ROLL_UP, GhKind.DAILY_ROLL_UP, GhKind.DEVICES -> error("no list filter for ${stream.kind}")
        }
    }

    private fun physical(field: String, from: Instant, until: Instant): String =
        "$field >= \"${GhJson.physicalLiteral(from)}\" AND $field < \"${GhJson.physicalLiteral(until)}\""

    private fun civil(at: Instant): String = GhJson.civilLiteral(at, TimeZone.UTC)

    private fun date(at: Instant): LocalDate = at.toLocalDateTime(zone).date

    /** Civil-date events belong to the window of their date; everything else to the window of its start instant. */
    private fun inWindow(stream: GhStream, event: PersonalEvent, range: GhRange, floor: Instant?): Boolean =
        if (stream.civilDays) dayOf(event) in date(range.start)..<date(range.end) else event.startTime in diffRange(stream, range, floor)

    private fun dayOf(event: PersonalEvent): LocalDate = when (val payload = event.payload) {
        is dev.agentle.core.model.DailyTotalPayload -> payload.date
        is dev.agentle.core.model.RestingHeartRatePayload -> payload.date
        else -> date(event.startTime)
    }

    // ---------------------------------------------------------------- :rollUp

    private suspend fun rollUpWindow(stream: GhStream, range: GhRange): Result {
        val body = buildJsonObject {
            put(
                "range",
                buildJsonObject {
                    put("startTime", GhJson.physicalLiteral(range.start))
                    put("endTime", GhJson.physicalLiteral(range.end))
                },
            )
            put("windowSize", ROLLUP_WINDOW)
            put("pageSize", config.pageSize)
        }
        val request = GhRequest.Data(GhMethod.ROLL_UP, stream.dataType, body = body)
        val pages = when (val read = pages(request, "rollupDataPoints")) {
            is Pages.Read -> read.pages
            is Pages.Failed -> return Result.Failed(read.failure)
        }
        val points = ArrayList<JsonObject>()
        var received = 0
        var skipped = 0
        pages.forEach { page ->
            val array = page.array("rollupDataPoints") ?: JsonArray(emptyList())
            received += array.size
            array.forEach { element -> (element as? JsonObject)?.let(points::add) ?: skipped++ }
        }
        val events = ArrayList<PersonalEvent>()
        points.forEach { point ->
            when (val mapped = mapper.rollUp(stream, point)) {
                is Mapped.Event -> if (mapped.event.startTime in range) events += mapped.event
                is Mapped.Skip -> skipped++
            }
        }
        return Result.Ok(GhWindow(range, newestByKey(events), received, skipped))
    }

    // ---------------------------------------------------------------- :dailyRollUp

    /**
     * Days `[first, last]` of the window. The request ends one day after [last]: the end date is inclusive according to
     * 3P live use but closed-open according to discovery (U9), so both readings cover [last]; every returned day is
     * keyed by its own `civilStartTime.date` and days outside the window are dropped. No `nextPageToken` exists.
     */
    private suspend fun dailyRollUpWindow(stream: GhStream, range: GhRange): Result {
        val first = date(range.start)
        val end = date(range.end)
        val body = buildJsonObject {
            put(
                "range",
                buildJsonObject {
                    put("start", civilDate(first))
                    put("end", civilDate(end))
                },
            )
            put("windowSizeDays", 1)
        }
        val result = api.execute(GhRequest.Data(GhMethod.DAILY_ROLL_UP, stream.dataType, body = body)) {
            validArray(it, "rollupDataPoints")
        }
        val json = when (result) {
            is GhResult.Ok -> result.json
            is GhResult.Failed -> return Result.Failed(result.failure)
        }
        val array = json.array("rollupDataPoints") ?: JsonArray(emptyList())
        var skipped = 0
        val events = ArrayList<PersonalEvent>()
        array.forEach { element ->
            val point = element as? JsonObject
            if (point == null) {
                skipped++
                return@forEach
            }
            when (val mapped = mapper.dailyRollUp(stream, point)) {
                is Mapped.Event -> if (dayOf(mapped.event) in first..<end) events += mapped.event
                is Mapped.Skip -> skipped++
            }
        }
        return Result.Ok(GhWindow(range, newestByKey(events), array.size, skipped))
    }

    private fun civilDate(date: LocalDate) = buildJsonObject {
        put(
            "date",
            buildJsonObject {
                put("year", date.year)
                put("month", date.month.ordinal + 1)
                put("day", date.day)
            },
        )
    }

    // ---------------------------------------------------------------- paging

    /** Every paired device, all pages (`pageSize` repeated with each token, hygiene H7), or the failure. */
    suspend fun pairedDevices(): Pair<List<JsonObject>, GhFailure?> =
        when (val read = pages({ token -> GhRequest.PairedDevices(config.devicePageSize, token) }, "pairedDevices") { false }) {
            is Pages.Read -> read.pages.flatMap { page -> page.array("pairedDevices").orEmpty().mapNotNull { it as? JsonObject } } to null
            is Pages.Failed -> emptyList<JsonObject>() to read.failure
        }

    private sealed interface Pages {
        data class Read(val pages: List<JsonObject>) : Pages

        data class Failed(val failure: GhFailure) : Pages
    }

    /**
     * Reads every page of [request], all or nothing. [stopAfter] may end paging early (the sleep recency rule). A
     * rejected page token restarts from page 1 once (R8d); a second rejection, a repeated token (R8c) or more than
     * [MAX_PAGES] pages (R8j) fail the window.
     */
    private suspend fun pages(request: GhRequest.Data, arrayKey: String, stopAfter: (JsonObject) -> Boolean = { false }): Pages =
        pages({ token -> request.copy(pageToken = token) }, arrayKey, stopAfter)

    private suspend fun pages(requestFor: (String?) -> GhRequest, arrayKey: String, stopAfter: (JsonObject) -> Boolean): Pages {
        val first = readOnce(requestFor, arrayKey, stopAfter)
        if (first != Pages.Failed(GhFailure.PageTokenRejected)) return first
        val second = readOnce(requestFor, arrayKey, stopAfter)
        return if (second == Pages.Failed(GhFailure.PageTokenRejected)) Pages.Failed(GhFailure.Transient("page_token")) else second
    }

    /** One pass from page 1; stops at the first failed page. */
    private suspend fun readOnce(requestFor: (String?) -> GhRequest, arrayKey: String, stopAfter: (JsonObject) -> Boolean): Pages {
        val collected = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        var token: String? = null
        while (true) {
            val page = when (val result = api.execute(requestFor(token)) { validPage(it, arrayKey) }) {
                is GhResult.Ok -> result.json
                is GhResult.Failed -> return Pages.Failed(result.failure)
            }
            collected += page
            val next = page.string("nextPageToken")
            if (next.isNullOrEmpty() || stopAfter(page)) return Pages.Read(collected)
            val fault = when {
                !seen.add(next) -> GhFailure.Transient("repeated_page_token")
                collected.size >= MAX_PAGES -> GhFailure.Transient("too_many_pages")
                else -> null
            }
            if (fault != null) return Pages.Failed(fault)
            token = next
        }
    }

    private fun validPage(page: JsonObject, arrayKey: String): Boolean {
        val token = page["nextPageToken"]
        val tokenOk = token == null || token is JsonNull || (token is JsonPrimitive && token.isString)
        return tokenOk && validArray(page, arrayKey)
    }

    private fun validArray(page: JsonObject, arrayKey: String): Boolean {
        val array = page[arrayKey]
        return array == null || array is JsonNull || array is JsonArray
    }

    /** One copy per dedup key: the newest `updateTime` wins (R6c); identical repeats collapse (R6b, R6e). */
    private fun newestByKey(events: List<PersonalEvent>): List<PersonalEvent> {
        val byKey = LinkedHashMap<String, PersonalEvent>()
        for (event in events) {
            val kept = byKey[event.dedupKey]
            if (kept == null ||
                (event.metadata.upstreamUpdatedAt ?: Instant.DISTANT_PAST) >= (kept.metadata.upstreamUpdatedAt ?: Instant.DISTANT_PAST)
            ) {
                byKey[event.dedupKey] = event
            }
        }
        return byKey.values.sortedWith(compareBy({ it.startTime }, { it.dedupKey }))
    }

    companion object {
        /**
         * The part of the store a fetched window replaces. Sleep reads every session ending after `start - lead`, so
         * sessions starting in the 24 h lead are diffed too (an upstream deletion there converges).
         */
        fun diffRange(stream: GhStream, range: GhRange, floor: Instant? = null): GhRange = if (stream.kind == GhKind.SLEEP) {
            GhRange(maxOf(range.start - SESSION_LEAD, minOf(floor ?: Instant.DISTANT_PAST, range.start)), range.end)
        } else {
            range
        }

        const val MAX_PAGES: Int = 500
        private const val ROLLUP_WINDOW = "60s"

        /** Sleep is read 24 h past a window's end (by end time), and unfiltered paging stops 24 h before its start. */
        val SESSION_LEAD = 24.hours

        /** UTC windows become civil bounds widened by 14 h, which covers every UTC offset (§5.1). */
        private val CIVIL_WIDENING = 14.hours
    }
}

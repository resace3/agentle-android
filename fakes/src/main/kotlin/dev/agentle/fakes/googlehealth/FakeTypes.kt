package dev.agentle.fakes.googlehealth

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One request as the fake sees it (docs/research/05 §8.8). [path] is the production path (`/v4/...`) even when the
 * request came through a scenario prefix; [query] keeps repeated parameters; [headers] have lower-case names.
 */
public data class FakeRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, String>,
    val body: String,
    val scenario: String,
    /** 1 for the first request to ([scenario], [path]), 2 for the second, ... */
    val attempt: Int,
    val at: Instant,
) {
    public fun param(name: String): String? = query[name]?.firstOrNull()

    /** The bearer token, or null without an `Authorization: Bearer` header. */
    val bearerToken: String?
        get() = headers["authorization"]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(BEARER.length)?.trim()

    private companion object {
        const val BEARER = "Bearer "
    }
}

/** One journaled request and the response the fake gave it. */
public data class FakeExchange(val request: FakeRequest, val response: FakeResponse)

/** Socket-level misbehavior applied to a response (docs/research/08 §5.5). */
public enum class TransportFault {
    NONE,

    /** The body arrives at 1 KiB per 100 ms. */
    THROTTLE,

    /** The connection is closed half way through the body. */
    DISCONNECT_MID_BODY,

    /** Headers never arrive; the client times out. */
    STALL,

    /** The `Content-Type` header is omitted. */
    NO_CONTENT_TYPE,
}

/**
 * A response before it is turned into a MockWebServer response. A null [contentType] sends no `Content-Type`;
 * [delay] holds the headers back (a slow server, docs/research/05 §8.1 `inject(..., delay)`).
 */
public data class FakeResponse(
    val code: Int,
    val body: String,
    val contentType: String? = JSON,
    val headers: Map<String, String> = emptyMap(),
    val transport: TransportFault = TransportFault.NONE,
    val delay: Duration = Duration.ZERO,
) {
    public companion object {
        public const val JSON: String = "application/json; charset=UTF-8"
        public const val HTML: String = "text/html; charset=UTF-8"
    }
}

/** How sleep filters behave (docs/research/05 §5.1, U7). */
public enum class SleepFilterMode {
    /** The documented `sleep.interval.end_time` / `civil_end_time` members work (with `OR`). */
    DOCUMENTED,

    /** Every sleep filter member is rejected, as 3P observed live; unfiltered requests still work. */
    REJECT_ALL,
}

/** Shape of filter errors: E400-FILTER-A (generic reason, detailed reason in metadata) or -B (specific reason). */
public enum class FilterErrorStyle { DETAILED_REASONS, REASON_ONLY }

/** Reading of the dailyRollUp end date (docs/research/05 §4.5, U9). */
public enum class DailyRollUpEnd { INCLUSIVE, EXCLUSIVE }

/** How the last page marks the end of a paged list (docs/research/05 §5.2, R8b). */
public enum class EndOfPages { EMPTY_STRING, OMITTED, NULL }

/** Ordering inside `list` pages: newest first as documented, or ascending within each page (R7a); pages are always newest first. */
public enum class ListOrder { NEWEST_FIRST, ASCENDING }

/**
 * Knobs of the fake (docs/research/05 §8.8 `FakeConfig`, plus the account values of §8.1).
 *
 * @property deviceSyncLag how long before "now" the tracker last uploaded; wearable data recorded after that is not
 *   served yet, and `pairedDevices` reports it as `lastSyncTime`.
 * @property maxPageSize caps every page (the `small-pages` scenario uses 50); null keeps the documented maxima.
 */
public data class FakeGoogleHealthConfig(
    val sleepFilter: SleepFilterMode = SleepFilterMode.DOCUMENTED,
    val rejectFilterMembers: Set<String> = emptySet(),
    val filterErrorStyle: FilterErrorStyle = FilterErrorStyle.DETAILED_REASONS,
    val dailyRollUpEnd: DailyRollUpEnd = DailyRollUpEnd.INCLUSIVE,
    val endOfPages: EndOfPages = EndOfPages.EMPTY_STRING,
    val listOrder: ListOrder = ListOrder.NEWEST_FIRST,
    val ratePerMinute: Int = 300,
    val healthUserId: String = "1234567890",
    val legacyUserId: String = "A1B2C3",
    val timeZone: String = "America/New_York",
    val deviceSyncLag: Duration = Duration.ZERO,
    val maxPageSize: Int? = null,
)

package dev.agentle.connectors.android.collectors.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import dev.agentle.connectors.android.core.AndroidConnector
import dev.agentle.connectors.android.core.AndroidConnectorIds
import dev.agentle.connectors.android.core.AndroidSources
import dev.agentle.connectors.android.core.CollectOutcome
import dev.agentle.connectors.android.core.CollectorRuntime
import dev.agentle.connectors.android.core.CoverageIds
import dev.agentle.connectors.android.core.cursorSafely
import dev.agentle.connectors.android.core.epochSafely
import dev.agentle.connectors.android.core.importFloorSafely
import dev.agentle.connectors.android.core.writeWindows
import dev.agentle.connectors.api.CapabilityIds
import dev.agentle.connectors.api.CapabilityStatusProvider
import dev.agentle.connectors.api.ReplaceWindow
import dev.agentle.connectors.api.SyncCursor
import dev.agentle.connectors.api.SyncTrigger
import dev.agentle.connectors.api.WriteBatch
import dev.agentle.core.common.AppError
import dev.agentle.core.model.CalendarEventPayload
import dev.agentle.core.model.CapabilityStatus
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * One `CalendarContract.Instances` row, reduced to what Agentle stores (red team privacy-ai-16): times, busy/free,
 * all-day and the attendee count; the title only when the user opted in; never attendee identities, descriptions or
 * locations.
 */
public data class CalendarInstance(
    val eventId: Long,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val busy: Boolean,
    val attendeeCount: Int? = null,
    val title: String? = null,
)

/** `CalendarContract.Instances` behind a seam. Throws `SecurityException` without READ_CALENDAR. */
public fun interface CalendarSource {
    /** Visible, not cancelled instances overlapping `[beginMs, endMs)`; titles only when [includeTitles]. */
    public fun instances(beginMs: Long, endMs: Long, includeTitles: Boolean): List<CalendarInstance>
}

public class ContentResolverCalendarSource(private val context: Context) : CalendarSource {
    override fun instances(beginMs: Long, endMs: Long, includeTitles: Boolean): List<CalendarInstance> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, beginMs)
            ContentUris.appendId(it, endMs)
        }.build()
        val out = ArrayList<CalendarInstance>()
        context.contentResolver.query(
            uri,
            if (includeTitles) PROJECTION + CalendarContract.Instances.TITLE else PROJECTION,
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(COL_STATUS) == CalendarContract.Events.STATUS_CANCELED) continue
                    val declined = cursor.getInt(COL_SELF_STATUS) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                    out += CalendarInstance(
                        eventId = cursor.getLong(COL_EVENT_ID),
                        beginMs = cursor.getLong(COL_BEGIN),
                        endMs = cursor.getLong(COL_END),
                        allDay = cursor.getInt(COL_ALL_DAY) != 0,
                        busy = !declined && cursor.getInt(COL_AVAILABILITY) == CalendarContract.Events.AVAILABILITY_BUSY,
                        title = if (includeTitles) cursor.getString(COL_TITLE) else null,
                    )
                }
            }
        val counts = attendeeCounts(out.map { it.eventId }.distinct())
        return out.map { it.copy(attendeeCount = counts[it.eventId] ?: 0) }
    }

    /** Attendee rows per event; only the `EVENT_ID` column is read (never names or addresses). */
    private fun attendeeCounts(eventIds: List<Long>): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        eventIds.chunked(ID_CHUNK).forEach { chunk ->
            context.contentResolver.query(
                CalendarContract.Attendees.CONTENT_URI,
                arrayOf(CalendarContract.Attendees.EVENT_ID),
                "${CalendarContract.Attendees.EVENT_ID} IN (${chunk.joinToString(",") { "?" }})",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) counts.merge(cursor.getLong(0), 1, Int::plus)
            }
        }
        return counts
    }

    private companion object {
        val PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        const val COL_EVENT_ID = 0
        const val COL_BEGIN = 1
        const val COL_END = 2
        const val COL_ALL_DAY = 3
        const val COL_AVAILABILITY = 4
        const val COL_STATUS = 5
        const val COL_SELF_STATUS = 6
        const val COL_TITLE = 7
        const val ID_CHUNK = 200
    }
}

/**
 * Calendar instances (§6.4 "Calendar"): each sweep reads `[today - 7 days, today + 15 days)` of the clock's zone (red
 * team testing-build-04: never the JVM default zone) and replaces that window, so moved or deleted instances disappear.
 * All-day instances are stored at local midnight of their date (the provider keeps them at UTC midnight). Rows are
 * CALENDAR_EVENT keyed `cal|<event_id>|<begin>` (red team database-sync-17) with only a salted hash of the event id,
 * all-day, busy and attendee-count fields in the payload; titles only with `calendarTitles` (privacy-ai-16). More than 500 rows are split
 * into one window per local day.
 */
public class CalendarConnector(runtime: CollectorRuntime, permissions: CapabilityStatusProvider, private val source: CalendarSource) :
    AndroidConnector(
        id = AndroidConnectorIds.CALENDAR,
        name = "Calendar",
        supportedEventTypes = setOf(EventType.CALENDAR_EVENT),
        capabilityIds = listOf(CapabilityIds.CALENDAR_EVENTS),
        runtime = runtime,
        permissions = permissions,
    ) {
    override val coverageIds: List<String> = CoverageIds.CALENDAR

    override suspend fun collect(trigger: SyncTrigger, statuses: Map<String, CapabilityStatus>): CollectOutcome {
        val epoch = runtime.writer.epochSafely() ?: return CollectOutcome(error = AppError.DatabaseError("writer_unavailable"))
        val zone = runtime.clock.zone()
        val today = runtime.clock.today()
        val floor = runtime.writer.importFloorSafely(AndroidSources.CALENDAR)
        val windowStart = maxOf(today.minus(DatePeriod(days = PAST_DAYS)).atStartOfDayIn(zone), floor ?: Instant.DISTANT_PAST)
        val windowEnd = today.plus(DatePeriod(days = FUTURE_DAYS)).atStartOfDayIn(zone)
        if (windowStart >= windowEnd) return CollectOutcome.EMPTY
        // One extra day on both sides: all-day instances are stored at UTC midnight.
        val titles = runtime.settings.current().calendarTitles
        val instances =
            source.instances((windowStart - 1.days).toEpochMilliseconds(), (windowEnd + 1.days).toEpochMilliseconds(), titles)
        val events = instances.map { event(it, zone, titles) }.filter { it.startTime >= windowStart && it.startTime < windowEnd }
        val stored = runtime.writer.cursorSafely(AndroidSources.CURSOR_CONNECTOR, STREAM)
        val now = runtime.clock.now()
        val cursor = (stored ?: SyncCursor(AndroidSources.CURSOR_CONNECTOR, STREAM)).copy(
            lastSuccessCursor = "${windowStart.toEpochMilliseconds()}|${windowEnd.toEpochMilliseconds()}",
            syncStartedAt = now,
            syncFinishedAt = now,
            lastErrorCode = null,
        )
        val writes = runtime.writeWindows(coverageIds, epoch, windows(windowStart, windowEnd, events, zone), cursor)
        return CollectOutcome(fetched = instances.size, committed = writes.written, partial = writes.cursorRejected, error = writes.error)
    }

    private fun event(instance: CalendarInstance, zone: TimeZone, titles: Boolean): PersonalEvent {
        val start = if (instance.allDay) localMidnight(instance.beginMs, zone) else Instant.fromEpochMilliseconds(instance.beginMs)
        val end = if (instance.allDay) localMidnight(instance.endMs, zone) else Instant.fromEpochMilliseconds(instance.endMs)
        return runtime.events.create(
            type = EventType.CALENDAR_EVENT,
            source = AndroidSources.CALENDAR,
            start = start,
            end = end,
            payload = CalendarEventPayload(
                eventIdHash = runtime.hasher.shortHash(instance.eventId.toString()),
                allDay = instance.allDay,
                busy = instance.busy,
                attendeeCount = instance.attendeeCount,
                title = if (titles) instance.title else null,
            ),
            dedupKey = key(instance.eventId, instance.beginMs),
            zoneId = zone.id,
        )
    }

    public companion object {
        public const val STREAM: String = "calendar"
        public const val PAST_DAYS: Int = 7
        public const val FUTURE_DAYS: Int = 15

        public fun key(eventId: Long, beginMs: Long): String = "cal|$eventId|$beginMs"

        /** The local midnight of the UTC date of [utcMidnightMs] (how the provider stores all-day instances). */
        public fun localMidnight(utcMidnightMs: Long, zone: TimeZone): Instant =
            Instant.fromEpochMilliseconds(utcMidnightMs).toLocalDateTime(TimeZone.UTC).date.atStartOfDayIn(zone)

        /** One window when the rows fit one transaction, otherwise one window per local day (each capped at 500 rows). */
        public fun windows(
            start: Instant,
            end: Instant,
            events: List<PersonalEvent>,
            zone: TimeZone,
        ): List<Pair<ReplaceWindow, List<PersonalEvent>>> {
            if (events.size <= WriteBatch.MAX_ROWS) return listOf(ReplaceWindow(AndroidSources.CALENDAR, start, end) to events)
            val out = ArrayList<Pair<ReplaceWindow, List<PersonalEvent>>>()
            var from = start
            while (from < end) {
                val to = minOf(from.toLocalDateTime(zone).date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone), end)
                out +=
                    ReplaceWindow(AndroidSources.CALENDAR, from, to) to
                    events.filter { it.startTime >= from && it.startTime < to }.take(WriteBatch.MAX_ROWS)
                from = to
            }
            return out
        }
    }
}

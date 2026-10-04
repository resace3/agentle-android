package dev.agentle.app.shell

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.DailyTotalMetric
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.time.AgentleClock
import dev.agentle.data.events.DailyTotal
import dev.agentle.data.events.EventRepository
import dev.agentle.data.events.MinuteAggregation
import dev.agentle.data.events.MinuteValue
import dev.agentle.data.events.StoredEvent
import dev.agentle.data.events.StreamCount
import dev.agentle.data.events.TimelineKey
import dev.agentle.data.events.TimelinePage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DashboardCalculatorTest {
    private val zone = TimeZone.of("Europe/London")
    private val oct2 = LocalDate(2026, 10, 2)
    private val oct3 = LocalDate(2026, 10, 3)
    private val oct4 = LocalDate(2026, 10, 4)
    private val clock = FixedClock(at(oct4, 12), zone)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)

    private fun spec(vararg metrics: DashboardMetric, days: Int = 3) = DashboardSpec("id", "Mine", metrics.toList(), days)

    private fun data(events: EventRepository, spec: DashboardSpec) = runBlocking { DashboardCalculator(events, clock).data(spec).first() }

    @Test
    fun `each metric has one value per day ending today, and days without data are empty`() {
        val events = FakeEvents(listOf(event(EventType.DEVICE_UNLOCK, "android.screen", at(oct4, 8))))

        val series = data(events, spec(DashboardMetric.UNLOCKS)).series.single()

        assertThat(series.days).containsExactly(oct2 to null, oct3 to null, oct4 to 1L).inOrder()
    }

    @Test
    fun `a night counts on the day the user woke up, and the larger of two sources is shown`() {
        val events = FakeEvents(
            listOf(
                sleep("healthconnect.sleep", at(oct3, 23), at(oct4, 7), minutesAsleep = 420),
                sleep("googlehealth.sleep", at(oct3, 23, 10), at(oct4, 7), minutesAsleep = 430),
            ),
        )

        val days = data(events, spec(DashboardMetric.SLEEP_MINUTES)).series.single().days.toMap()

        assertThat(days[oct3]).isNull()
        assertThat(days[oct4]).isEqualTo(430L)
    }

    @Test
    fun `a source's daily step total wins over the samples for its day`() {
        val minutes = listOf(
            MinuteValue(at(oct3, 9), 500.0, "android.steps"),
            MinuteValue(at(oct3, 9, 1), 700.0, "android.steps"),
            MinuteValue(at(oct4, 9), 1_000.0, "android.steps"),
        )
        val totals = listOf(DailyTotal("googlehealth.steps", DailyTotalMetric.STEPS, oct4, null, 6_000.0))
        val events = FakeEvents(emptyList(), totals, mapOf(EventType.STEP_SAMPLE to minutes))

        val days = data(events, spec(DashboardMetric.STEPS)).series.single().days.toMap()

        assertThat(days).containsExactly(oct2, null, oct3, 1_200L, oct4, 6_000L)
    }

    @Test
    fun `screen time is in minutes`() {
        val events = FakeEvents(listOf(screen(at(oct4, 8), 30.minutes), screen(at(oct4, 9), 15.minutes)))

        assertThat(data(events, spec(DashboardMetric.SCREEN_TIME_MINUTES)).series.single().days.toMap()[oct4]).isEqualTo(45L)
    }

    @Test
    fun `stored data that cannot be read is reported, not thrown`() {
        val events = object : EventRepository by FakeEvents(emptyList()) {
            override fun changes(): Flow<Long> = flow { throw IllegalStateException("database locked") }
        }

        assertThat(data(events, spec(DashboardMetric.STEPS))).isEqualTo(DashboardData(emptyList(), unreadable = true))
    }

    private fun sleep(source: String, start: Instant, end: Instant, minutesAsleep: Long) =
        event(EventType.SLEEP_SESSION, source, start, end, SleepSessionPayload(minutesAsleep = minutesAsleep))

    private fun screen(start: Instant, length: Duration) = event(
        EventType.SCREEN_SESSION,
        "android.screen",
        start,
        start + length,
        ScreenPayload(interactive = true, durationMs = length.inWholeMilliseconds),
    )

    private fun event(type: EventType, source: String, start: Instant, end: Instant? = null, payload: EventPayload = NoPayload) =
        PersonalEvent(
            id = EventId("$source-$start"),
            type = type,
            source = DataSourceId(source),
            startTime = start,
            endTime = end,
            zoneId = zone.id,
            payload = payload,
            dedupKey = "$source-$start",
            metadata = EventMetadata(ingestedAt = start),
        )
}

private class FixedClock(private val at: Instant, private val tz: TimeZone) : AgentleClock {
    override val wall: Clock = object : Clock {
        override fun now(): Instant = at
    }

    override fun zone(): TimeZone = tz

    override fun elapsed(): Duration = Duration.ZERO
}

/** Stored events in memory; per-minute values and daily totals are given as the repository would compute them. */
private class FakeEvents(
    private val stored: List<PersonalEvent>,
    private val totals: List<DailyTotal> = emptyList(),
    private val minutes: Map<EventType, List<MinuteValue>> = emptyMap(),
) : EventRepository {
    override suspend fun timeline(upperBound: Instant, limit: Int, after: TimelineKey?, types: Set<EventType>?): TimelinePage =
        TimelinePage(emptyList(), null)

    override suspend fun range(type: EventType, from: Instant, to: Instant): List<StoredEvent> =
        stored.filter { it.type == type && it.startTime >= from && it.startTime < to }
            .mapIndexed { index, event -> StoredEvent(index.toLong(), index.toLong(), null, event) }

    override suspend fun overlapping(type: EventType, from: Instant, to: Instant): List<StoredEvent> = range(type, from, to)

    override suspend fun latest(type: EventType): StoredEvent? = range(type, Instant.DISTANT_PAST, Instant.DISTANT_FUTURE).lastOrNull()

    override suspend fun count(): Long = stored.size.toLong()

    override suspend fun countsPerSource(): List<StreamCount> = emptyList()

    override suspend fun countsPerType(): List<StreamCount> = emptyList()

    override suspend fun fusedMinutes(
        type: EventType,
        metric: String,
        from: Instant,
        to: Instant,
        aggregation: MinuteAggregation,
    ): List<MinuteValue> = minutes[type].orEmpty().filter { it.minuteStart >= from && it.minuteStart < to }

    override suspend fun dailyTotals(metric: DailyTotalMetric, from: LocalDate, to: LocalDate): List<DailyTotal> =
        totals.filter { it.metric == metric && it.date in from..to }

    override fun changes(): Flow<Long> = flowOf(1L)
}

package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.api.SyncTrigger
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GhPlannerTest {
    private val config = GoogleHealthConfig()
    private val catalog = GhStreams.catalog(config).associateBy { it.id }
    private val steps = catalog.getValue(GoogleHealthStreams.STEPS)
    private val sleep = catalog.getValue(GoogleHealthStreams.SLEEP)
    private val dailySteps = catalog.getValue(GoogleHealthStreams.DAILY_STEPS)
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val utc = TimeZone.UTC

    private fun plan(
        stream: GhStream = steps,
        state: GhStreamState = GhStreamState.EMPTY,
        trigger: SyncTrigger = SyncTrigger.SCHEDULED,
        floor: Instant? = null,
        zone: TimeZone = utc,
        at: Instant = now,
    ) = GhPlanner.plan(stream, state, trigger, at, floor, zone, config)

    private fun List<GhRange>.contiguousAscending(): Boolean = zipWithNext().all { (a, b) -> a.end == b.start }

    @Test
    fun `first sync hot-loads 14 days in 24-hour windows and leaves backfill to later runs`() {
        val plan = plan()
        assertThat(plan.firstSync).isTrue()
        assertThat(plan.deep).isFalse()
        assertThat(plan.forward).hasSize(14)
        assertThat(plan.forward.first().start).isEqualTo(now - 14.days)
        assertThat(plan.forward.last().end).isEqualTo(now)
        assertThat(plan.forward.contiguousAscending()).isTrue()
        assertThat(plan.forward.all { it.end - it.start <= 24.hours }).isTrue()
        assertThat(plan.backward).isEmpty()
    }

    @Test
    fun `a BACKFILL run walks down to the 90-day horizon`() {
        val plan = plan(trigger = SyncTrigger.BACKFILL)
        assertThat(plan.backward).hasSize(76)
        assertThat(plan.backward.first().end).isEqualTo(now - 14.days)
        assertThat(plan.backward.last().start).isEqualTo(now - 90.days)
        assertThat(plan.backward.zipWithNext().all { (a, b) -> b.end == a.start }).isTrue()
    }

    @Test
    fun `scheduled runs after the hot load backfill one 30-day chunk`() {
        val state = GhStreamState(
            through = now - 1.hours,
            backfilledFrom = now - 14.days,
            fetchedAt = now - 1.hours,
            deepResyncAt =
            now - 1.days,
        )
        val plan = plan(state = state)
        assertThat(plan.backward.first().end).isEqualTo(now - 14.days)
        assertThat(plan.backward.last().start).isEqualTo(now - 44.days)
        val manual = plan(state = state, trigger = SyncTrigger.MANUAL)
        assertThat(manual.backward).isEmpty()
    }

    @Test
    fun `incremental runs re-read 48 hours, bounded by the tracker's last upload`() {
        val through = now - 1.hours
        val unbounded =
            plan(state = GhStreamState(through = through, backfilledFrom = now - 90.days, fetchedAt = through, deepResyncAt = now))
        assertThat(unbounded.forward.first().start).isEqualTo(through - 48.hours)
        val bounded = plan(
            state = GhStreamState(through, now - 90.days, through, deepResyncAt = now, deviceLastSync = through - 10.minutes),
        )
        assertThat(bounded.forward.first().start).isEqualTo(through - 70.minutes)
        assertThat(bounded.forward.last().end).isEqualTo(now)
        // Sessions are not bounded by the device: they can be edited for days.
        val sessions = plan(stream = sleep, state = GhStreamState(through, now - 90.days, through, now, through - 10.minutes))
        assertThat(sessions.forward.first().start).isEqualTo(through - 7.days - 24.hours)
    }

    @Test
    fun `round-2 6 no overlap when the stream's last run started less than 15 minutes ago`() {
        val through = now - 5.minutes
        val plan =
            plan(state = GhStreamState(through = through, backfilledFrom = now - 90.days, fetchedAt = now - 10.minutes, deepResyncAt = now))
        assertThat(plan.forward.single()).isEqualTo(GhRange(through, now))
    }

    @Test
    fun `round-2 4 a cursor more than 5 minutes ahead restarts at now minus the overlap`() {
        val ahead =
            plan(
                state = GhStreamState(through = now + 2.days, backfilledFrom = now - 90.days, fetchedAt = now + 2.days, deepResyncAt = now),
            )
        assertThat(ahead.baseThrough).isNull()
        assertThat(ahead.forward.first().start).isEqualTo(now - 48.hours)
        assertThat(ahead.forward.last().end).isEqualTo(now)
        assertThat(ahead.forward.all { it.start < it.end }).isTrue()
        // Within the 5-minute skew the cursor is kept, and windows still end at now.
        val skewed =
            plan(
                state = GhStreamState(
                    through = now + 3.minutes,
                    backfilledFrom = now - 90.days,
                    fetchedAt = now - 1.hours,
                    deepResyncAt = now,
                ),
            )
        assertThat(skewed.baseThrough).isEqualTo(now)
        assertThat(skewed.forward.all { it.start < it.end && it.end <= now }).isTrue()
    }

    @Test
    fun `a long gap is fetched 45 windows per run`() {
        val through = now - 60.days
        val plan = plan(
            state = GhStreamState(
                through = through,
                backfilledFrom = now - 90.days,
                fetchedAt = through,
                deepResyncAt =
                now - 1.days,
            ),
        )
        assertThat(plan.forward).hasSize(config.maxChunksPerRun)
        assertThat(plan.forward.first().start).isEqualTo(through - 48.hours)
    }

    @Test
    fun `the weekly deep re-sync reaches 30 days back and DEEP_RESYNC forces it`() {
        val through = now - 1.hours
        val due = plan(state = GhStreamState(through, now - 90.days, through, deepResyncAt = now - 8.days))
        assertThat(due.deep).isTrue()
        assertThat(due.forward.first().start).isEqualTo(now - 30.days)
        assertThat(due.forward).hasSize(30)
        val forced =
            plan(state = GhStreamState(through, now - 90.days, through, deepResyncAt = now - 1.days), trigger = SyncTrigger.DEEP_RESYNC)
        assertThat(forced.deep).isTrue()
        val notDue = plan(state = GhStreamState(through, now - 90.days, through, deepResyncAt = now - 1.days))
        assertThat(notDue.deep).isFalse()
    }

    @Test
    fun `round-2 5 every window start is clamped to the import floor`() {
        val floor = now - 3.days
        val first = plan(floor = floor, trigger = SyncTrigger.BACKFILL)
        assertThat(first.forward.first().start).isEqualTo(floor)
        assertThat(first.backward).isEmpty()
        val through = now - 1.hours
        val deep = plan(state = GhStreamState(through, now - 90.days, through, deepResyncAt = now - 8.days), floor = floor)
        assertThat(deep.forward.first().start).isEqualTo(floor)
        val sleepPlan = plan(stream = sleep, floor = floor)
        assertThat(sleepPlan.forward.first().start).isEqualTo(floor)
    }

    @Test
    fun `R05 5 1 sleep windows start 24 hours earlier and are 7 days long`() {
        val plan = plan(stream = sleep)
        assertThat(plan.forward.first().start).isEqualTo(now - 15.days)
        assertThat(plan.forward.all { it.end - it.start <= 7.days }).isTrue()
    }

    @Test
    fun `testing-build-04 daily windows are whole civil days in the account zone`() {
        val zone = TimeZone.of("America/St_Johns")
        val plan = plan(stream = dailySteps, zone = zone)
        val first = plan.forward.first()
        assertThat(first.start.toLocalDateTime(zone).time.toString()).isEqualTo("00:00")
        assertThat(plan.forward.last().end).isEqualTo(GhPlanner.nextDayStart(now, zone))
        assertThat(plan.forward.all { it.end.toLocalDateTime(zone).time.toString() == "00:00" }).isTrue()
        assertThat(plan.forward.contiguousAscending()).isTrue()
        val backfill = plan(stream = dailySteps, zone = zone, trigger = SyncTrigger.BACKFILL, floor = now - 40.days - 30.minutes)
        // The floor falls inside a day: that day is skipped, so nothing before the floor is read.
        assertThat(backfill.backward.last().start).isAtLeast(now - 40.days - 30.minutes)
        assertThat(backfill.backward.all { it.start.toLocalDateTime(zone).time.toString() == "00:00" }).isTrue()
    }

    @Test
    fun `split helpers never produce empty windows`() {
        assertThat(GhPlanner.split(now, now, 1.hours)).isEmpty()
        assertThat(GhPlanner.split(now, now - 1.hours, 1.hours)).isEmpty()
        assertThat(GhPlanner.splitDown(now, now, 1.hours)).isEmpty()
        assertThat(GhPlanner.splitDays(now, now, utc, 1.days)).isEmpty()
        assertThat(GhPlanner.splitDaysDown(now, now, utc, 1.days)).isEmpty()
        assertThat(GhPlanner.truncate(Instant.parse("2026-10-01T12:00:00.999Z"))).isEqualTo(now)
    }
}

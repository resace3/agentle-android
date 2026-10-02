package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.testing.TestAgentleClock
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Incremental maintenance equals a full recompute: a synthetic 90-day user ([SyntheticDailyUser], own fixture) is
 * ingested as the data would arrive (next-morning batches, late sleep corrections, coverage that arrives with the data,
 * source claims that move forward, a trip whose zone change is learned late, a deletion at the end), refreshing from
 * the dirty dates only. The stored rows must then equal a from-scratch computation of the same dates.
 */
class DirtyDayEquivalenceTest {
    private class LearnedTimeline(base: InMemoryDailyInputs, var timeline: ZoneTimeline) : DailyInputs by base {
        override suspend fun zoneTimeline(range: ClosedOpenRange): ZoneTimeline = timeline
    }

    @Test
    fun `dirty-day refreshes give exactly the rows of a full recompute`() = runTest(timeout = 5.minutes) {
        val user = SyntheticDailyUser()
        val base = InMemoryDailyInputs(policy = CanonicalSourcePolicy.DEFAULT)
        val inputs = LearnedTimeline(base, ZoneTimeline.fixed(NEW_YORK))
        val clock = TestAgentleClock(user.ingestionTime(0), NEW_YORK)
        val store = InMemoryDailyFeatureStore()
        val engine = DailyFeatureEngine(inputs, store, clock)
        val claimStart = user.first.plusDays(-3).atStartOfDayIn(NEW_YORK)
        var claimed = claimStart
        var next = 0

        for (k in 0 until user.days) {
            val now = user.ingestionTime(k)
            clock.setWallClock(now)
            clock.setZone(user.timeline.zoneAt(now))
            val known = user.timeline.changes.filter { it.at <= now }
            if (known.size != inputs.timeline.changes.size) {
                inputs.timeline = ZoneTimeline(NEW_YORK, known)
                engine.recomputeAfterZoneChange(known.last().at).getOrThrow()
            }
            val batch = ArrayList<SyntheticDailyUser.Arrival>()
            while (next < user.arrivals.size && user.arrivals[next].at <= now) batch += user.arrivals[next++]
            val events = batch.mapNotNull { it.event }
            base.add(events)
            engine.markChanged(events)
            batch.mapNotNull { it.coverage }.forEach { (collector, range) ->
                base.addCoverage(collector, listOf(range))
                engine.markRangeChanged(range)
            }
            val through = now - 2.hours
            user.claimingSources.forEach { (family, source) ->
                base.setSourceCoverage(family, source, listOf(ClosedOpenRange(claimStart, through)))
            }
            engine.markRangeChanged(ClosedOpenRange(claimed, through))
            claimed = through
            engine.refresh().getOrThrow()
        }

        // Deleting three days of data afterwards makes them unknown, never zero.
        clock.setWallClock(user.ingestionTime(user.days - 1) + 1.hours)
        val deleted = ClosedOpenRange(user.first.plusDays(50).atStartOfDayIn(NEW_YORK), user.first.plusDays(53).atStartOfDayIn(NEW_YORK))
        base.remove { it.startTime in deleted }
        base.removeCoverage(deleted)
        engine.markRangeChanged(deleted)
        engine.refresh().getOrThrow()

        val deletedDay = store.dailyRows(user.first.plusDays(51), user.first.plusDays(51))
        assertThat(deletedDay.row("steps").status).isEqualTo(DailyRowStatus.MISSING)
        assertThat(deletedDay.row("screen_minutes").missingReason).isEqualTo(MissingReason.COLLECTOR_INACTIVE)
        assertThat(deletedDay.row("exercise_minutes").value).isNull()

        val fresh = InMemoryDailyFeatureStore()
        val dates = store.allDailyRows.map { it.date }.toSet()
        DailyFeatureEngine(inputs, fresh, clock).recompute(dates).getOrThrow()

        assertSameRows(store, fresh)
        assertThat(dates.size).isAtLeast(user.days)
        assertThat(store.allDailyRows.count { it.status == DailyRowStatus.FINAL }).isGreaterThan(store.allDailyRows.size / 2)
        assertThat(store.allDerivedRows.count { it.status == DerivedStatus.OK }).isGreaterThan(0)
    }

    private fun assertSameRows(incremental: InMemoryDailyFeatureStore, full: InMemoryDailyFeatureStore) {
        val a = incremental.allDailyRows.associateBy { it.date to it.metric }.mapValues { it.value.copy(computedAt = EPOCH) }
        val b = full.allDailyRows.associateBy { it.date to it.metric }.mapValues { it.value.copy(computedAt = EPOCH) }
        val dailyDiff = (a.keys + b.keys).filter { a[it] != b[it] }.take(5).map { "${a[it]} != ${b[it]}" }
        assertThat(dailyDiff).isEmpty()
        val x = incremental.allDerivedRows.associateBy {
            Triple(it.featureId, it.windowDays, it.anchorDate)
        }.mapValues { it.value.copy(computedAt = EPOCH) }
        val y = full.allDerivedRows.associateBy {
            Triple(it.featureId, it.windowDays, it.anchorDate)
        }.mapValues { it.value.copy(computedAt = EPOCH) }
        val derivedDiff = (x.keys + y.keys).filter { x[it] != y[it] }.take(5).map { "${x[it]} != ${y[it]}" }
        assertThat(derivedDiff).isEmpty()
        assertThat(a.size).isGreaterThan(5_000)
    }

    private companion object {
        val EPOCH: Instant = Instant.fromEpochMilliseconds(0)
    }
}

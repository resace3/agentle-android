package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.AppError
import dev.agentle.core.common.LogRecord
import dev.agentle.core.common.LogSink
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.EventType
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DailyFeatureEngineTest {
    private val day = date("2026-09-14")
    private val clock = TestAgentleClock(at(day.plusDays(3), 12))

    private fun inputs(events: List<dev.agentle.core.model.PersonalEvent> = emptyList()) =
        InMemoryDailyInputs(events, collectorCoverage = Col.ALL.associateWith { listOf(around(day.plusDays(-10), day.plusDays(5))) })

    @Test
    fun `refresh computes dirty dates, provisional dates, today and yesterday, then clears the marks`() = runTest {
        val store = InMemoryDailyFeatureStore()
        val engine = DailyFeatureEngine(inputs(listOf(Ev.unlock(at(day, 10)))), store, clock)
        store.markDirty(setOf(day))

        val report = engine.refresh().getOrThrow()

        assertThat(report.dates).containsExactly(day, day.plusDays(2), day.plusDays(3))
        assertThat(store.dirtyMarks()).isEmpty()
        assertThat(store.dailyRows(day, day).row("unlocks").value).isEqualTo(1.0)
        assertThat(report.dailyRows).isEqualTo(store.allDailyRows.size)
        assertThat(report.derivedRows).isGreaterThan(0)
        assertThat(store.provisionalDates()).contains(day.plusDays(3))
    }

    @Test
    fun `a date marked again during a refresh stays dirty`() = runTest {
        val base = InMemoryDailyFeatureStore()
        val store = object : DailyFeatureStore by base {
            var marked = false

            override suspend fun replaceDays(dates: Set<LocalDate>, rows: List<DailySummaryRow>) {
                base.replaceDays(dates, rows)
                if (!marked) {
                    marked = true
                    base.markDirty(setOf(day))
                }
            }
        }
        val engine = DailyFeatureEngine(inputs(), store, clock)
        base.markDirty(setOf(day))

        engine.refresh().getOrThrow()

        assertThat(base.dirtyMarks().map { it.date }).containsExactly(day)
        engine.refresh().getOrThrow()
        assertThat(base.dirtyMarks()).isEmpty()
    }

    @Test
    fun `deleted data reads as missing, never as zero`() = runTest {
        val data = inputs(listOf(Ev.screen(at(day, 10), at(day, 11)), Ev.steps(Src.GH_STEPS, at(day, 10), at(day, 11), 900)))
        val store = InMemoryDailyFeatureStore()
        val engine = DailyFeatureEngine(data, store, clock)
        engine.recompute(setOf(day)).getOrThrow()
        assertThat(store.dailyRows(day, day).row("screen_minutes").value).isEqualTo(60.0)

        val deleted = range(at(day, 0), at(day.plusDays(1), 4))
        data.remove { it.startTime in deleted }
        data.removeCoverage(deleted)
        engine.markRangeChanged(deleted)
        engine.refresh().getOrThrow()

        val rows = store.dailyRows(day, day)
        assertThat(rows.row("screen_minutes").status).isEqualTo(DailyRowStatus.MISSING)
        assertThat(rows.row("screen_minutes").missingReason).isEqualTo(MissingReason.COLLECTOR_INACTIVE)
        assertThat(rows.row("steps").status).isEqualTo(DailyRowStatus.MISSING)
        assertThat(rows.row("steps").value).isNull()
    }

    @Test
    fun `a coverage change alone marks the days it touches`() = runTest {
        val data = InMemoryDailyInputs()
        val store = InMemoryDailyFeatureStore()
        val engine = DailyFeatureEngine(data, store, clock)
        engine.recompute(setOf(day)).getOrThrow()
        assertThat(store.dailyRows(day, day).row("notifications").status).isEqualTo(DailyRowStatus.MISSING)

        val healthy = range(at(day, 0), at(day.plusDays(1), 6))
        data.addCoverage(Col.NOTIFICATIONS, listOf(healthy))
        engine.markRangeChanged(healthy)
        engine.refresh().getOrThrow()

        assertThat(store.dailyRows(day, day).row("notifications").value).isEqualTo(0.0)
        assertThat(store.dailyRows(day, day).row("notifications").status).isEqualTo(DailyRowStatus.FINAL)
    }

    @Test
    fun `recompute is idempotent and rolling windows follow changed days`() = runTest {
        val data = inputs(listOf(Ev.unlock(at(day, 10))))
        val store = InMemoryDailyFeatureStore()
        val engine = DailyFeatureEngine(data, store, clock)

        engine.recompute(setOf(day, day)).getOrThrow()
        val first = store.allDailyRows.map { it.copy(computedAt = Instant.DISTANT_PAST) }
        engine.recompute(setOf(day)).getOrThrow()
        assertThat(store.allDailyRows.map { it.copy(computedAt = Instant.DISTANT_PAST) }).containsExactlyElementsIn(first)

        val unlocks1 = store.derivedRows(day, day, "unlocks").single { it.windowDays == 1 }
        assertThat(unlocks1.value).isEqualTo(1.0)
        data.add(listOf(Ev.unlock(at(day, 11))))
        engine.markChanged(listOf(Ev.unlock(at(day, 11))))
        engine.refresh().getOrThrow()
        assertThat(store.derivedRows(day, day, "unlocks").single { it.windowDays == 1 }.value).isEqualTo(2.0)
        assertThat(store.derivedRows(day.plusDays(2), day.plusDays(2), "unlocks").single { it.windowDays == 3 }.coveredDays).isEqualTo(3)
    }

    @Test
    fun `bounded recomputes after a catalog or zone change and never after today`() = runTest {
        val store = InMemoryDailyFeatureStore()
        val engine = DailyFeatureEngine(inputs(), store, clock, DailyFeatureConfig(recomputeDays = 5))

        val recent = engine.recomputeRecent().getOrThrow()
        val zone = engine.recomputeAfterZoneChange(at(day.plusDays(2), 9)).getOrThrow()
        val old = engine.recomputeAfterZoneChange(at(day.plusDays(-30), 9)).getOrThrow()
        val future = engine.recompute(setOf(day.plusDays(10))).getOrThrow()

        assertThat(recent.dates).containsExactlyElementsIn(DirtyDays.datesBetween(day.plusDays(-1), day.plusDays(3)))
        assertThat(zone.dates).containsExactly(day, day.plusDays(1), day.plusDays(2), day.plusDays(3))
        assertThat(old.dates).hasSize(5)
        assertThat(future.dates).isEmpty()
        assertThat(future.derivedRows).isEqualTo(0)
    }

    @Test
    fun `a failing read is reported by class name only and never with its message`() = runTest {
        val records = mutableListOf<LogRecord>()
        val failing = object : DailyInputs by InMemoryDailyInputs() {
            override suspend fun events(query: EventQuery): List<dev.agentle.core.model.PersonalEvent> =
                throw IllegalStateException("payload {\"text\":\"secret message\"}")
        }
        val engine =
            DailyFeatureEngine(failing, InMemoryDailyFeatureStore(), clock, logger = Logger(listOf(LogSink { records += it }), { 0L }))

        val result = engine.recompute(setOf(day))

        val error = (result as Outcome.Failure).error
        assertThat(error).isEqualTo(AppError.Unexpected("IllegalStateException"))
        assertThat(records.single().errorCode).isEqualTo("unexpected")
        assertThat(records.toString()).doesNotContain("secret")
    }

    @Test
    fun `dirty days cover every date an interval can touch`() {
        val config = DailyFeatureConfig()
        val sleep = Ev.sleep(Src.GH_SLEEP, at(day, 23), at(day.plusDays(1), 7))

        assertThat(DirtyDays.of(sleep, config)).containsAtLeast(day, day.plusDays(1))
        assertThat(DirtyDays.of(Ev.restingHr(Src.GH_RHR, day.plusDays(9), 55.0, TOKYO))).contains(day.plusDays(9))
        assertThat(DirtyDays.overlapping(range(at(day, 12), at(day, 12)), config))
            .containsExactlyElementsIn(DirtyDays.datesBetween(day.plusDays(-2), day.plusDays(2)))
        assertThat(DirtyDays.datesBetween(day, day.plusDays(-1))).isEmpty()
        assertThat(DirtyDays.of(Ev.jitai(EventType.JITAI_DELIVERED, at(day, 3) - 2.hours))).contains(day.plusDays(-1))
    }
}

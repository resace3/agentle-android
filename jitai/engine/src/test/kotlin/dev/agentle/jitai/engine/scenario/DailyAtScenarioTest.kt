package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.missing
import dev.agentle.jitai.engine.pipeline.DailyAtStatus
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.schedule.RescheduleSignal
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.WorkInput
import dev.agentle.jitai.engine.schedule.WorkPolicy
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.stale
import dev.agentle.jitai.engine.testing.EngineHarness
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** R10 §12.D (engine side, R2 at 17:00) and the `daily_at` rows of §12.O (O1, O2, O6, O7). */
class DailyAtScenarioTest {
    private val date = LocalDate.parse("2026-10-01")
    private val key = "v1|R2|D|2026-10-01|17:00"

    private fun r2(at: String = "2026-10-01T17:00", steps: FeatureValue?): EngineHarness {
        val harness = F0.harness(F0.local(at), Rules.R2)
        if (steps != null) harness.features.set(Leaves.STEPS, steps)
        return harness
    }

    private suspend fun EngineHarness.run(time: String = "17:00", on: LocalDate = date) = engine.runDailyAt("R2", on, time).getOrThrow()

    @Test
    fun `D1 known 2,999 at 17_00 is DELIVERED with the template filled from the snapshot`() = runTest {
        val harness = r2(steps = int(2_999, F0.local("2026-10-01T16:31")))

        val report = harness.run()

        assertThat(report.status).isEqualTo(DailyAtStatus.EVALUATED)
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.delivery.posts.single().body).isEqualTo("Only 2,999 steps so far today.")
        assertThat(harness.delivery.posts.single().nonce).isEqualTo(harness.store.row(key)!!.nonce)
        // The worker planned the next date before evaluating (red team lifecycle-battery-04).
        assertThat(report.replan!!.input).isEqualTo(WorkInput.DailyAt("R2", LocalDate.parse("2026-10-02"), "17:00"))
        assertThat(report.replan!!.uniqueName).isEqualTo("jitai-at-R2-20261002-1700")
    }

    @Test
    fun `D2 known 3,000 is NOT_TRIGGERED`() = runTest {
        val harness = r2(steps = int(3_000, F0.local("2026-10-01T16:31")))

        harness.run()

        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.NOT_TRIGGERED)
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `D3 stale 3,200 from today - the lower bound gives F, NOT_TRIGGERED without a retry`() = runTest {
        val harness = r2(steps = stale(3_200, F0.local("2026-10-01T16:15")))

        val report = harness.run()

        assertThat(report.status).isEqualTo(DailyAtStatus.EVALUATED)
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.NOT_TRIGGERED)
    }

    @Test
    fun `D4 stale 2,800 - U at 17_00 and a sync, then known 2,950 at 17_10 is DELIVERED with decisionPointAt 17_10`() = runTest {
        val harness = r2(steps = stale(2_800, F0.local("2026-10-01T16:15")))

        val first = harness.run()

        assertThat(first.status).isEqualTo(DailyAtStatus.RETRY)
        assertThat(first.pass!!.retryAt).isEqualTo(F0.local("2026-10-01T17:10"))
        assertThat(first.pass!!.syncFeatures).containsExactly(Leaves.STEPS)
        val retry = first.pass!!.followUp.single { it.input is WorkInput.DailyAt }
        assertThat(retry.uniqueName).isEqualTo("jitai-at-R2-20261001-1700-r10")
        assertThat(retry.policy).isEqualTo(WorkPolicy.KEEP)
        assertThat(retry.runAt).isEqualTo(F0.local("2026-10-01T17:10"))
        val sync = first.pass!!.followUp.single { it.input is WorkInput.Prefetch }
        assertThat((sync.input as WorkInput.Prefetch).featureIds).containsExactly(Leaves.STEPS)
        assertThat(sync.tags).containsExactly(SchedulePlanner.jitaiTag("R2"), SchedulePlanner.TAG_PREFETCH)
        assertThat(harness.store.row(key)).isNull()

        // A sync at 17:08 brings coverage to 17:05 with 2,950.
        harness.clock.advanceTo(F0.local("2026-10-01T17:08"))
        harness.features.set(Leaves.STEPS, int(2_950, F0.local("2026-10-01T17:05")))
        harness.clock.advanceTo(F0.local("2026-10-01T17:10"))
        val second = harness.run()

        assertThat(second.status).isEqualTo(DailyAtStatus.EVALUATED)
        val row = harness.store.row(key)!!
        assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(row.decisionPointAt).isEqualTo(F0.local("2026-10-01T17:10"))
        assertThat(harness.delivery.posts.single().body).isEqualTo("Only 2,950 steps so far today.")
    }

    @Test
    fun `D5 no sync arrives - U at 17_00, 17_10 and 17_20, row UNKNOWN at 17_20 and no notification`() = runTest {
        val harness = r2(steps = stale(2_800, F0.local("2026-10-01T16:15")))

        assertThat(harness.run().status).isEqualTo(DailyAtStatus.RETRY)
        harness.clock.advanceTo(F0.local("2026-10-01T17:10"))
        val second = harness.run()
        assertThat(second.status).isEqualTo(DailyAtStatus.RETRY)
        assertThat(second.pass!!.retryAt).isEqualTo(F0.local("2026-10-01T17:20"))
        harness.clock.advanceTo(F0.local("2026-10-01T17:20"))
        val third = harness.run()

        assertThat(third.status).isEqualTo(DailyAtStatus.EVALUATED)
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.UNKNOWN)
        assertThat(harness.store.row(key)!!.decisionPointAt).isEqualTo(F0.local("2026-10-01T17:20"))
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `D6 no step interval today - Missing(NO_DATA), row UNKNOWN after the retries`() = runTest {
        val harness = r2(steps = missing(MissingReason.NO_DATA))

        assertThat(harness.run().status).isEqualTo(DailyAtStatus.RETRY)
        harness.clock.advanceTo(F0.local("2026-10-01T17:10"))
        assertThat(harness.run().status).isEqualTo(DailyAtStatus.RETRY)
        harness.clock.advanceTo(F0.local("2026-10-01T17:20"))
        harness.run()

        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.UNKNOWN)
    }

    @Test
    fun `a missing value a sync cannot fix (no permission) writes UNKNOWN at once`() = runTest {
        val harness = r2(steps = missing(MissingReason.NO_PERMISSION))

        assertThat(harness.run().status).isEqualTo(DailyAtStatus.EVALUATED)
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.UNKNOWN)
    }

    @Test
    fun `D7 a true zero is a value - known 0 is DELIVERED`() = runTest {
        val harness = r2(steps = int(0, F0.local("2026-10-01T16:59")))

        harness.run()

        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.delivery.posts.single().body).isEqualTo("Only 0 steps so far today.")
    }

    @Test
    fun `D9 steps_today gte 3000 with stale 3,200 from today is T`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T17:00"), Rules.R2.copy(conditions = Leaves.gte(Leaves.STEPS, 3_000)))
        harness.features.set(Leaves.STEPS, stale(3_200, F0.local("2026-10-01T16:15")))

        harness.run()

        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    // -- worker timing (red team lifecycle-battery-04) ------------------------------------------------------------------

    @Test
    fun `a worker that wakes more than 2 minutes early re-plans the slot and exits`() = runTest {
        val harness = r2(at = "2026-10-01T16:57", steps = int(100, F0.local("2026-10-01T16:50")))

        val report = harness.run()

        assertThat(report.status).isEqualTo(DailyAtStatus.TOO_EARLY)
        assertThat(report.replan!!.runAt).isEqualTo(F0.local("2026-10-01T17:00"))
        assertThat(report.replan!!.uniqueName).isEqualTo("jitai-at-R2-20261001-1700")
        assertThat(harness.store.rows()).isEmpty()
    }

    @Test
    fun `within 2 minutes before the slot the worker evaluates`() = runTest {
        val harness = r2(at = "2026-10-01T16:58", steps = int(100, F0.local("2026-10-01T16:50")))

        assertThat(harness.run().status).isEqualTo(DailyAtStatus.EVALUATED)
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `a worker later than maxLateness writes MISSED(TOO_LATE) and plans the next date`() = runTest {
        val harness = r2(at = "2026-10-01T17:31", steps = int(100, F0.local("2026-10-01T16:50")))

        val report = harness.run()

        assertThat(report.status).isEqualTo(DailyAtStatus.MISSED)
        val row = harness.store.row(key)!!
        assertThat(row.state).isEqualTo(DecisionState.MISSED)
        assertThat(row.reason).isEqualTo(ReasonCode.TOO_LATE)
        assertThat(row.reasonDetail).isEqualTo("late=1860s")
        assertThat(row.decisionPointAt).isEqualTo(F0.local("2026-10-01T17:00"))
        assertThat(report.replan!!.runAt).isEqualTo(F0.local("2026-10-02T17:00"))
    }

    @Test
    fun `a run for a removed time, an unknown JITAI or a paused one is NOT_SCHEDULED`() = runTest {
        val harness = r2(steps = int(100, F0.local("2026-10-01T16:50")))

        assertThat(harness.engine.runDailyAt("R2", date, "18:00").getOrThrow().status).isEqualTo(DailyAtStatus.NOT_SCHEDULED)
        assertThat(harness.engine.runDailyAt("nope", date, "17:00").getOrThrow().status).isEqualTo(DailyAtStatus.NOT_SCHEDULED)
        harness.repository.update("R2") { it.copy(status = dev.agentle.jitai.dsl.model.JitaiStatus.PAUSED) }
        assertThat(harness.run().status).isEqualTo(DailyAtStatus.NOT_SCHEDULED)
        assertThat(harness.store.rows()).isEmpty()
    }

    @Test
    fun `a daily_at slot outside the active window is logged OUTSIDE_WINDOW and writes no row`() = runTest {
        val harness = F0.harness(F0.local("2026-10-01T17:00"), Rules.R2.copy(activeWindow = ActiveWindow("18:00", "22:00")))
        harness.features.set(Leaves.STEPS, int(100, F0.local("2026-10-01T16:50")))

        val report = harness.run()

        assertThat(report.status).isEqualTo(DailyAtStatus.OUTSIDE_WINDOW)
        assertThat(harness.store.evalLog().single().outcome).isEqualTo(EvalOutcome.OUTSIDE_WINDOW)
        assertThat(harness.store.row(key)).isNull()
    }

    @Test
    fun `a second run of a resolved slot is a no-op`() = runTest {
        val harness = r2(steps = int(100, F0.local("2026-10-01T16:50")))
        harness.run()
        harness.clock.advanceBy(5.minutes)

        val again = harness.run()

        assertThat(again.pass!!.written).isEmpty()
        assertThat(harness.delivery.posts).hasSize(1)
    }

    // -- §12.O daily_at vectors -----------------------------------------------------------------------------------------

    private val at0230 = Rules.R2.copy(id = "N", trigger = Trigger.DailyAt(listOf("02:30"), maxLatenessMinutes = 30), conditions = null)

    @Test
    fun `O1 daily_at 02_30 on the spring gap day runs at 03_30+02_00 (01_30Z) with key D 2026-03-29 02_30`() = runTest {
        val plan = SchedulePlanner.plan(listOf(at0230), Instant.parse("2026-03-28T12:00:00Z"), F0.BERLIN, F0.SETTINGS.tickProfile)
        val work = plan.work.single { it.input is WorkInput.DailyAt }
        assertThat(work.runAt).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"))
        assertThat(work.uniqueName).isEqualTo("jitai-at-N-20260329-0230")

        val harness = F0.harness(Instant.parse("2026-03-29T01:30:00Z"), at0230)
        val report = harness.engine.runDailyAt("N", LocalDate.parse("2026-03-29"), "02:30").getOrThrow()

        assertThat(report.slotAt).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"))
        assertThat(harness.store.row("v1|N|D|2026-03-29|02:30")!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `O2 daily_at 02_30 on the autumn overlap runs once at 00_30Z, nothing at the second 02_30 (01_30Z)`() = runTest {
        val edited = Instant.parse("2026-10-24T12:00:00Z")
        val harness = F0.harness(Instant.parse("2026-10-25T00:30:00Z"), at0230.copy(createdAt = edited, modifiedAt = edited))
        val day = LocalDate.parse("2026-10-25")

        val first = harness.engine.runDailyAt("N", day, "02:30").getOrThrow()
        harness.clock.advanceTo(Instant.parse("2026-10-25T01:30:00Z"))
        val second = harness.engine.runDailyAt("N", day, "02:30").getOrThrow()
        val plan = harness.engine.plan(RescheduleSignal.PROCESS_START).getOrThrow().plan

        assertThat(first.slotAt).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"))
        assertThat(second.pass!!.written).isEmpty()
        assertThat(harness.delivery.posts).hasSize(1)
        assertThat(plan.work.map { it.uniqueName }).containsExactly("jitai-at-N-20261026-0230")
        assertThat(plan.missed).isEmpty()
    }

    @Test
    fun `O6 westward - R2 delivered in Berlin at 15_00Z, at 17_00 New York (21_00Z) the used key prevents a second delivery`() = runTest {
        val harness = r2(steps = int(100, F0.local("2026-10-01T16:50")))
        harness.run()
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DELIVERED)

        harness.clock.advanceTo(Instant.parse("2026-10-01T18:00:00Z"))
        harness.clock.setZone(F0.NEW_YORK)
        val plan = harness.engine.plan(RescheduleSignal.TIMEZONE_CHANGED).getOrThrow().plan
        harness.clock.advanceTo(Instant.parse("2026-10-01T21:00:00Z"))
        val stale = harness.run()

        assertThat(plan.cancelTags).containsExactly(SchedulePlanner.TAG_AT, SchedulePlanner.TAG_PREFETCH)
        assertThat(plan.work.filter { it.input is WorkInput.DailyAt }.map { it.uniqueName }).containsExactly("jitai-at-R2-20261002-1700")
        assertThat(stale.pass!!.written).isEmpty()
        assertThat(harness.delivery.posts).hasSize(1)
    }

    @Test
    fun `O7 eastward - 07_00 Berlin is 60 min late at the zone change - MISSED, next run 2026-10-04 07_00+02_00`() = runTest {
        val at0700 = Rules.R2.copy(id = "M", trigger = Trigger.DailyAt(listOf("07:00"), maxLatenessMinutes = 30), conditions = null)
        val harness = EngineHarness(Instant.parse("2026-10-02T22:00:00Z"), F0.NEW_YORK, listOf(at0700), F0.SETTINGS)
        harness.seedCounted(
            "M",
            Instant.parse("2026-10-02T11:00:00Z"),
            key = DecisionKeys.dailyAt("M", LocalDate.parse("2026-10-02"), "07:00"),
        )

        harness.clock.advanceTo(Instant.parse("2026-10-03T06:00:00Z"))
        harness.clock.setZone(F0.BERLIN)
        val report = harness.engine.plan(RescheduleSignal.TIMEZONE_CHANGED).getOrThrow()

        assertThat(report.plan.missed.single().decisionKey).isEqualTo("v1|M|D|2026-10-03|07:00")
        assertThat(harness.store.row("v1|M|D|2026-10-03|07:00")!!.state).isEqualTo(DecisionState.MISSED)
        assertThat(harness.store.row("v1|M|D|2026-10-03|07:00")!!.reason).isEqualTo(ReasonCode.TOO_LATE)
        assertThat(report.plan.work.single { it.input is WorkInput.DailyAt }.runAt).isEqualTo(Instant.parse("2026-10-04T05:00:00Z"))
    }

    @Test
    fun `O7 with daily_at 07_45 the slot is 15 min late at the zone change and is evaluated immediately`() = runTest {
        val at0745 = Rules.R2.copy(id = "M", trigger = Trigger.DailyAt(listOf("07:45"), maxLatenessMinutes = 30), conditions = null)
        val harness = EngineHarness(Instant.parse("2026-10-02T22:00:00Z"), F0.NEW_YORK, listOf(at0745), F0.SETTINGS)
        harness.features.set("device_interactive", FeatureValue.Known(FeatureScalar.BoolValue(true), F0.CREATED))
        harness.seedCounted(
            "M",
            Instant.parse("2026-10-02T11:45:00Z"),
            key = DecisionKeys.dailyAt("M", LocalDate.parse("2026-10-02"), "07:45"),
        )

        harness.clock.advanceTo(Instant.parse("2026-10-03T06:00:00Z"))
        harness.clock.setZone(F0.BERLIN)
        val plan = harness.engine.plan(RescheduleSignal.TIMEZONE_CHANGED).getOrThrow().plan
        val catchUp = plan.work.single { it.runAt == Instant.parse("2026-10-03T06:00:00Z") }
        val input = catchUp.input as WorkInput.DailyAt
        val report = harness.engine.runDailyAt("M", input.date, input.time).getOrThrow()

        assertThat(plan.missed).isEmpty()
        assertThat(input.date).isEqualTo(LocalDate.parse("2026-10-03"))
        assertThat(report.status).isEqualTo(DailyAtStatus.EVALUATED)
        assertThat(harness.store.row("v1|M|D|2026-10-03|07:45")!!.state).isEqualTo(DecisionState.DELIVERED)
    }
}

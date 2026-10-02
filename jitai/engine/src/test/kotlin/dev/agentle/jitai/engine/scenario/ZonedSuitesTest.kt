package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.ports.QuietHours
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.schedule.PlanInput
import dev.agentle.jitai.engine.schedule.ReplanReason
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.timer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

/**
 * testing-build-04: planner, evaluator and gates run in every zone of [dev.agentle.jitai.engine.ZONES] while the test
 * JVM default is America/St_Johns; the engine reads only the clock's zone, so local wall times must match each zone.
 */
class ZonedSuitesTest {
    @ParameterizedTest(name = "planner in {0}")
    @FieldSource("dev.agentle.jitai.engine.FixturesKt#ZONES")
    fun `the planner aims daily_at and interval slots at local wall times of the clock's zone`(zoneId: String) = runTest {
        val zone = TimeZone.of(zoneId)
        val harness = F0.harness(F0.local("2026-10-01T20:10", zone), Rules.R1, Rules.R2, zone = zone)

        val plan = SchedulePlanner.replan(
            ReplanReason.DEFINITION_CHANGED,
            harness.clock.stamp(),
            zone,
            PlanInput(listOf(Rules.R1, Rules.R2)),
        )

        val slots = plan.rows.filter { it.kind == TimerKind.SLOT }
        assertWithMessage(zoneId).that(
            slots.filter {
                it.jitaiId == "R1" && it.dueAt >= harness.clock.now()
            }.minOf { it.dueAt },
        ).isEqualTo(F0.local("2026-10-01T20:15", zone))
        assertWithMessage(zoneId).that(
            slots.filter {
                it.jitaiId == "R2" && it.dueAt >= harness.clock.now()
            }.minOf { it.dueAt },
        ).isEqualTo(F0.local("2026-10-02T17:00", zone))
    }

    @ParameterizedTest(name = "evaluator in {0}")
    @FieldSource("dev.agentle.jitai.engine.FixturesKt#ZONES")
    fun `local_time is read in the clock's zone - R1 delivers at local 22_30 and not at local 21_30`(zoneId: String) = runTest {
        val zone = TimeZone.of(zoneId)
        val late = F0.harness(F0.local("2026-10-01T22:30", zone), Rules.R1, zone = zone)
        late.features.set(Leaves.SCREEN, int(50, late.clock.now()))
        late.timer()
        val early = F0.harness(F0.local("2026-10-01T21:30", zone), Rules.R1, zone = zone)
        early.features.set(Leaves.SCREEN, int(50, early.clock.now()))
        early.timer()

        assertWithMessage(zoneId).that(late.delivery.posts).hasSize(1)
        assertWithMessage(zoneId).that(early.delivery.posts).isEmpty()
    }

    @ParameterizedTest(name = "gates in {0}")
    @FieldSource("dev.agentle.jitai.engine.FixturesKt#ZONES")
    fun `quiet hours 22_00-07_00 hold in the clock's zone`(zoneId: String) = runTest {
        val zone = TimeZone.of(zoneId)
        val settings = F0.SETTINGS.copy(quietHours = QuietHours("22:00", "07:00", enabled = true))
        val harness = F0.harness(F0.local("2026-10-01T23:00", zone), Rules.R1, settings = settings, zone = zone)
        harness.features.set(Leaves.SCREEN, int(50, harness.clock.now()))

        harness.timer()

        assertWithMessage(zoneId).that(harness.row("v1|R1|I|2026-10-01|12").reason).isEqualTo(ReasonCode.QUIET_HOURS)
    }
}

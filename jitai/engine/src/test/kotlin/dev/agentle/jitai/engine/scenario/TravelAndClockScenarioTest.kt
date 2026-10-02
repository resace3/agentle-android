package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.schedule.ReplanReason
import dev.agentle.jitai.engine.schedule.TimerKeys
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** R10 §12.O6, O7, O10b and O14: travel, zone changes and wall-clock changes through `jitai-timer` and `replan`. */
class TravelAndClockScenarioTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_O travel and clock changes`(vector: Vector) = runTest { vector.body(this) }

    companion object {
        private fun z(text: String): Instant = Instant.parse(text)

        @JvmStatic
        fun vectors(): List<Vector> = listOf(
            Vector("O6", "westward: R2 delivered in Berlin at 17:00+02:00; in New York at 17:00-04:00 the key exists, no second delivery") {
                val harness = F0.harness(z("2026-10-01T15:00:00Z"), Rules.R2)
                harness.features.set(Leaves.STEPS, int(2_000, z("2026-10-01T14:40:00Z")))
                harness.timer()
                assertThat(harness.row("v1|R2|D|2026-10-01|17:00").state).isEqualTo(DecisionState.DELIVERED)

                harness.clock.advanceTo(z("2026-10-01T15:30:00Z"))
                harness.clock.setZone(F0.NEW_YORK)
                val plan = harness.engine.replan(ReplanReason.TIMEZONE).getOrThrow()
                harness.runTimerUntil(z("2026-10-01T22:00:00Z"))

                assertThat(harness.delivery.posts).hasSize(1)
                assertThat(plan.rows.single { it.kind == TimerKind.SLOT }.decisionKey).isEqualTo("v1|R2|D|2026-10-02|17:00")
                assertThat(harness.store.timer(TimerKeys.slot("v1|R2|D|2026-10-02|17:00"))!!.dueAt).isEqualTo(z("2026-10-02T21:00:00Z"))
            },
            Vector("O7", "eastward: 07:00 Berlin is 60 min late at the 06:00Z zone change: MISSED; next run 2026-10-04 07:00+02:00") {
                val rule = Rules.rule("O7", Trigger.DailyAt(listOf("07:00")), createdAt = z("2026-10-02T20:00:00Z"))
                val harness = F0.harness(z("2026-10-02T22:00:00Z"), rule, zone = F0.NEW_YORK)
                harness.timer()
                assertThat(harness.store.timer(TimerKeys.slot("v1|O7|D|2026-10-03|07:00"))!!.dueAt).isEqualTo(z("2026-10-03T11:00:00Z"))

                // In flight; the zone change is handled on landing at 06:00Z (08:00 Berlin).
                harness.clock.advanceTo(z("2026-10-03T06:00:00Z"))
                harness.clock.setZone(F0.BERLIN)
                harness.engine.replan(ReplanReason.TIMEZONE).getOrThrow()
                harness.timer()

                val row = harness.row("v1|O7|D|2026-10-03|07:00")
                assertThat(row.state).isEqualTo(DecisionState.MISSED)
                assertThat(row.reason).isEqualTo(ReasonCode.TOO_LATE)
                assertThat(row.reasonDetail).isEqualTo("late=3600s")
                assertThat(harness.delivery.posts).isEmpty()
                assertThat(harness.store.timer(TimerKeys.slot("v1|O7|D|2026-10-04|07:00"))!!.dueAt).isEqualTo(z("2026-10-04T05:00:00Z"))
            },
            Vector("O7", "eastward with daily_at 07:45: 15 min late at the zone change, evaluated immediately") {
                val rule = Rules.rule("O7", Trigger.DailyAt(listOf("07:45")), createdAt = z("2026-10-02T20:00:00Z"))
                val harness = F0.harness(z("2026-10-02T22:00:00Z"), rule, zone = F0.NEW_YORK)
                harness.timer()

                harness.clock.advanceTo(z("2026-10-03T06:00:00Z"))
                harness.clock.setZone(F0.BERLIN)
                val plan = harness.engine.replan(ReplanReason.TIMEZONE).getOrThrow()
                harness.timer()

                assertThat(plan.nextDueAt).isEqualTo(z("2026-10-03T05:45:00Z"))
                val row = harness.row("v1|O7|D|2026-10-03|07:45")
                assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
                assertThat(row.decisionPointAt).isEqualTo(z("2026-10-03T06:00:00Z"))
                assertThat(row.zoneId).isEqualTo("Europe/Berlin")
            },
            Vector("O10b", "R1 slots 0-10 resolved; clock set back 1 h at real 22:40: no decision until wall 22:45 (real 23:45)") {
                val harness = F0.harness(F0.local("2026-10-01T20:00"), Rules.R1)
                harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T20:00")))
                harness.runTimerUntil(F0.local("2026-10-01T22:40"))
                val resolved = harness.store.rows().filter { it.jitaiId == "R1" && it.decisionKey.startsWith("v1|R1|I|2026-10-01|") }
                assertThat(resolved.map { it.decisionKey.substringAfterLast('|').toInt() }).containsExactlyElementsIn(0..10)

                harness.clock.setWallClock(F0.local("2026-10-01T21:40"))
                val plan = harness.engine.replan(ReplanReason.CLOCK).getOrThrow()
                val before = harness.store.rows().size
                val reports = harness.runTimerUntil(F0.local("2026-10-01T22:44:59"))

                assertThat(plan.rows.filter { it.kind == TimerKind.SLOT }.map { it.decisionKey }).containsExactly("v1|R1|I|2026-10-01|11")
                assertThat(harness.store.rows()).hasSize(before)
                assertThat(reports.mapNotNull { it.pass }.flatMap { it.written }).isEmpty()
                harness.timerAt(F0.local("2026-10-01T22:45"))
                assertThat(harness.row("v1|R1|I|2026-10-01|11").decisionPointAt).isEqualTo(F0.local("2026-10-01T22:45"))
                assertThat(harness.store.rows()).hasSize(before + 1)
            },
            Vector("O14", "R3 Berlin slots 0-1 at 20:00Z and 20:30Z; zone to London at 21:00Z: slots 0-1 skipped, slot 2 at 22:00Z runs") {
                val harness = F0.harness(z("2026-10-01T20:00:00Z"), Rules.R3)
                harness.features.set(Leaves.SCREEN, int(30, z("2026-10-01T20:00:00Z")))
                harness.runTimerUntil(z("2026-10-01T20:59:00Z"))
                assertThat(harness.row("v1|R3|I|2026-10-01|0").decisionPointAt).isEqualTo(z("2026-10-01T20:00:00Z"))
                assertThat(harness.row("v1|R3|I|2026-10-01|1").decisionPointAt).isEqualTo(z("2026-10-01T20:30:00Z"))

                harness.clock.advanceTo(z("2026-10-01T21:00:00Z"))
                harness.clock.setZone(F0.LONDON)
                harness.engine.replan(ReplanReason.TIMEZONE).getOrThrow()
                harness.features.set(Leaves.SCREEN, int(50, z("2026-10-01T21:00:00Z")))
                val before = harness.store.rows().size
                harness.runTimerUntil(z("2026-10-01T21:59:00Z"))
                assertThat(harness.store.rows()).hasSize(before)

                harness.runTimerUntil(z("2026-10-01T22:00:00Z"))

                val slot2 = harness.row("v1|R3|I|2026-10-01|2")
                assertThat(slot2.state).isEqualTo(DecisionState.DELIVERED)
                assertThat(slot2.decisionPointAt).isEqualTo(z("2026-10-01T22:00:00Z"))
                assertThat(slot2.zoneId).isEqualTo("Europe/London")
            },
            Vector(
                "O10",
                "after a reboot the timer rows are rebuilt and the backstop restarts; cooldowns use the wall clock across boots",
            ) {
                val harness = F0.harness(F0.local("2026-10-01T22:00"), Rules.R1)
                harness.features.set(Leaves.SCREEN, int(50, F0.local("2026-10-01T22:00")))
                harness.timer()
                assertThat(harness.row("v1|R1|I|2026-10-01|8").state).isEqualTo(DecisionState.DELIVERED)

                harness.clock.reboot(downtime = 30.minutes)
                harness.restart()
                val plan = harness.engine.replan(ReplanReason.BOOT).getOrThrow()
                assertThat(plan.rows.single { it.kind == TimerKind.BACKSTOP }.dueAt).isEqualTo(harness.clock.now() + 30.minutes)
                harness.timer()

                // 22:30 is 30 min after the 22:00 delivery by the wall clock (new boot): slot 10 is SUPPRESSED(COOLDOWN).
                assertThat(harness.row("v1|R1|I|2026-10-01|10").reason).isEqualTo(ReasonCode.COOLDOWN)
                harness.timerAt(F0.local("2026-10-01T23:00"))
                assertThat(harness.row("v1|R1|I|2026-10-01|12").state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.clock.now() - F0.local("2026-10-01T22:00")).isEqualTo(1.hours)
            },
        )
    }
}

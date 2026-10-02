package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.bool
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.DecisionTrace
import dev.agentle.jitai.engine.pipeline.TraceCodec
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.QuietHours
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.stampAt
import dev.agentle.jitai.engine.testing.EngineHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** R10 §12.M: R1 delivery-eligible at 22:30 with screen 50 unless stated; every suppression records its gate. */
class SafetyGatesScenarioTest {
    private fun eligible(
        at: String,
        vararg definitions: JitaiDefinition = arrayOf(Rules.R1),
        settings: EngineSettings = F0.SETTINGS,
    ): EngineHarness {
        val harness = F0.harness(F0.local(at), *definitions, settings = settings)
        harness.features.set(Leaves.SCREEN, int(50, F0.local(at)))
        return harness
    }

    private fun EngineHarness.r1(slot: Int, date: String = "2026-10-01"): DecisionRecord = store.row("v1|R1|I|$date|$slot")!!

    private fun DecisionRecord.trace(): DecisionTrace = TraceCodec.decode(content.traceJson!!)!!

    private fun DecisionRecord.failedGates(): List<ReasonCode> = trace().gates.orEmpty().filter { !it.passed }.map { it.gate }

    private fun assertSuppressed(row: DecisionRecord, reason: ReasonCode) {
        assertThat(row.state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(row.reason).isEqualTo(reason)
        assertThat(row.failedGates().first()).isEqualTo(reason)
        assertThat(row.nonce).isNull()
    }

    @Test
    fun `M1 R1 paused between evaluation and commit is SUPPRESSED(NOT_EFFECTIVE) by the in-transaction re-check`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.features.onResolve = { harness.repository.update("R1") { it.copy(status = JitaiStatus.PAUSED) } }

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.NOT_EFFECTIVE)
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `M2 expiresAt 20_30Z passes at 22_29_59`() = runTest {
        val r1 = Rules.R1.copy(expiresAt = kotlin.time.Instant.parse("2026-10-01T20:30:00Z"))
        val harness = eligible("2026-10-01T22:29:59", r1)

        harness.engine.runTick().getOrThrow()

        assertThat(harness.r1(9).state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
    }

    @Test
    fun `M2 expiresAt 20_30Z at 22_30 is SUPPRESSED(EXPIRED), status EXPIRED and its work cancelled`() = runTest {
        val r1 = Rules.R1.copy(expiresAt = kotlin.time.Instant.parse("2026-10-01T20:30:00Z"))
        val harness = eligible("2026-10-01T22:30", r1)

        val report = harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.EXPIRED)
        assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.EXPIRED)
        assertThat(report.pass.expired).containsExactly("R1")
        assertThat(report.pass.cancelTags).containsExactly(SchedulePlanner.jitaiTag("R1"))
        assertThat(report.nextTick).isNull()
    }

    @Test
    fun `M3 snoozedUntil 22_45 suppresses at 22_30 and passes at 22_45`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.store.seedRuntime(JitaiRuntimeState("R1", snoozedUntil = harness.stampAt(F0.local("2026-10-01T22:45"))))

        harness.engine.runTick().getOrThrow()
        assertSuppressed(harness.r1(10), ReasonCode.SNOOZED)

        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.r1(11).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M4 global pause until 23_00 is SUPPRESSED(GLOBAL_PAUSE)`() = runTest {
        val harness = eligible("2026-10-01T22:30", settings = F0.SETTINGS.copy(pauseUntil = F0.local("2026-10-01T23:00")))

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.GLOBAL_PAUSE)
    }

    @Test
    fun `M5 POST_NOTIFICATIONS denied is SUPPRESSED(NOTIFICATIONS_BLOCKED)`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.settings.notifications = NotificationSystemState(permissionGranted = false)

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.NOTIFICATIONS_BLOCKED)
    }

    @Test
    fun `M5 channel jitai_digital_wellbeing at importance NONE is SUPPRESSED(NOTIFICATIONS_BLOCKED)`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.settings.notifications = NotificationSystemState(blockedCategories = setOf(JitaiCategory.DIGITAL_WELLBEING))

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.NOTIFICATIONS_BLOCKED)
    }

    @Test
    fun `M6 quiet hours 22_00-07_00 with RESPECT is SUPPRESSED(QUIET_HOURS)`() = runTest {
        val harness = eligible("2026-10-01T22:30", settings = F0.SETTINGS.copy(quietHours = QuietHours()))

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.QUIET_HOURS)
    }

    @Test
    fun `M7 ALLOW_WHEN_INTERACTIVE passes while interactive`() = runTest {
        val r1 = Rules.R1.copy(delivery = Rules.R1.delivery.copy(quietHoursPolicy = QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE))
        val harness = eligible("2026-10-01T22:30", r1, settings = F0.SETTINGS.copy(quietHours = QuietHours()))

        harness.engine.runTick().getOrThrow()

        assertThat(harness.r1(10).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M7 ALLOW_WHEN_INTERACTIVE is SUPPRESSED(QUIET_HOURS) when not interactive`() = runTest {
        val r1 = Rules.R1.copy(delivery = Rules.R1.delivery.copy(quietHoursPolicy = QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE))
        val harness = eligible("2026-10-01T22:30", r1, settings = F0.SETTINGS.copy(quietHours = QuietHours()))
        harness.features.set("device_interactive", bool(false, F0.local("2026-10-01T22:30")))

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.QUIET_HOURS)
    }

    @ParameterizedTest(name = "M8 interruption filter {0}")
    @EnumSource(InterruptionFilter::class)
    fun `M8 only a definite ALL passes G07`(filter: InterruptionFilter) = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.settings.notifications = NotificationSystemState(interruptionFilter = filter)

        harness.engine.runTick().getOrThrow()

        if (filter == InterruptionFilter.ALL) {
            assertThat(harness.r1(10).state).isEqualTo(DecisionState.DELIVERED)
        } else {
            assertSuppressed(harness.r1(10), ReasonCode.DND)
        }
    }

    @Test
    fun `M8 an unreadable notification state fails closed`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.settings.failNext += "notifications"

        harness.engine.runTick().getOrThrow()

        assertThat(harness.r1(10).state).isEqualTo(DecisionState.SUPPRESSED)
    }

    @Test
    fun `M9 S1 blocks at 22_30 and no longer at 23_00 (window half-open)`() = runTest {
        val harness = eligible("2026-10-01T22:30", Rules.R1, Rules.S1)

        harness.engine.runTick().getOrThrow()
        assertSuppressed(harness.r1(10), ReasonCode.SUPPRESSED_BY_RULE)
        assertThat(harness.r1(10).trace().suppressedBy).containsExactly("S1")

        harness.clock.advanceTo(F0.local("2026-10-01T23:00"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.r1(12).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M10 R3 delivered at 22_30 is capped at 01_30 (same engine day) and passes in the next instance`() = runTest {
        val harness = eligible("2026-10-01T22:30", Rules.R3)
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row("v1|R3|I|2026-10-01|1")!!.state).isEqualTo(DecisionState.DELIVERED)

        harness.clock.advanceTo(F0.local("2026-10-02T01:30"))
        harness.engine.runTick().getOrThrow()
        val capped = harness.store.row("v1|R3|I|2026-10-01|7")!!
        assertSuppressed(capped, ReasonCode.DAILY_CAP)
        assertThat(capped.engineDay.toString()).isEqualTo("2026-10-01")

        harness.clock.advanceTo(F0.local("2026-10-02T22:00"))
        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row("v1|R3|I|2026-10-02|0")!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M11 weekly cap 2 counts engine days 09-25 to 10-01 on 2026-10-01`() = runTest {
        val r1 = Rules.R1.copy(maxPerDay = 1, maxPerWeek = 2)
        val harness = eligible("2026-10-01T22:30", r1)
        harness.seedCounted("R1", F0.local("2026-09-26T22:30"))
        harness.seedCounted("R1", F0.local("2026-09-30T22:30"))

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.WEEKLY_CAP)
    }

    @Test
    fun `M11 on 2026-10-03 only 09-30 is inside 09-27 to 10-03 and R1 passes`() = runTest {
        val r1 = Rules.R1.copy(maxPerDay = 1, maxPerWeek = 2)
        val harness = eligible("2026-10-03T22:30", r1)
        harness.seedCounted("R1", F0.local("2026-09-26T22:30"))
        harness.seedCounted("R1", F0.local("2026-09-30T22:30"))

        harness.engine.runTick().getOrThrow()

        assertThat(harness.r1(10, "2026-10-03").state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M12 another JITAI delivered 22_10 - R1 at 22_39_59 is SUPPRESSED(GLOBAL_MIN_GAP)`() = runTest {
        val harness = eligible("2026-10-01T22:39:59")
        harness.seedCounted("R9", F0.local("2026-10-01T22:10"))

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.GLOBAL_MIN_GAP)
    }

    @Test
    fun `M12 another JITAI delivered 22_10 - R1 at 22_40_00 passes`() = runTest {
        val harness = eligible("2026-10-01T22:40")
        harness.seedCounted("R9", F0.local("2026-10-01T22:10"))

        harness.engine.runTick().getOrThrow()

        assertThat(harness.r1(10).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M13 six deliveries already in the engine day is SUPPRESSED(GLOBAL_DAILY_CAP)`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        (10..15).forEach { hour -> harness.seedCounted("R$hour", F0.local("2026-10-01T$hour:00")) }

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.GLOBAL_DAILY_CAP)
    }

    @Test
    fun `M14 thirty deliveries in the last 7 engine days is SUPPRESSED(GLOBAL_WEEKLY_CAP)`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        (25..30).forEach { day -> (10..14).forEach { hour -> harness.seedCounted("R$hour", F0.local("2026-09-${day}T$hour:00")) } }

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.r1(10), ReasonCode.GLOBAL_WEEKLY_CAP)
    }

    @Test
    fun `M15 two VOICE deliveries today cap the VOICE rule while R1 passes`() = runTest {
        val voice = Rules.R1.copy(id = "RV", delivery = Rules.R1.delivery.copy(channel = DeliveryChannel.VOICE), priority = 90)
        val harness = eligible("2026-10-01T22:30", Rules.R1, voice)
        harness.seedCounted("V0", F0.local("2026-10-01T10:00"), channel = DeliveryChannel.VOICE)
        harness.seedCounted("V0", F0.local("2026-10-01T12:00"), channel = DeliveryChannel.VOICE)

        harness.engine.runTick().getOrThrow()

        assertSuppressed(harness.store.row("v1|RV|I|2026-10-01|10")!!, ReasonCode.CHANNEL_CAP)
        assertThat(harness.r1(10).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M16 the higher priority wins and the other loses arbitration`() = runTest {
        val r5 = Rules.R1.copy(id = "R5", priority = 60)
        val harness = eligible("2026-10-01T22:30", Rules.R1, r5)

        harness.engine.runTick().getOrThrow()

        assertThat(harness.store.row("v1|R5|I|2026-10-01|10")!!.state).isEqualTo(DecisionState.DELIVERED)
        assertSuppressed(harness.r1(10), ReasonCode.LOST_ARBITRATION)
        assertThat(harness.delivery.posts).hasSize(1)
    }

    @Test
    fun `M17 equal priority - never delivered R6 beats R1 last delivered 2026-09-30`() = runTest {
        val r6 = Rules.R1.copy(id = "R6")
        val harness = eligible("2026-10-01T22:30", Rules.R1, r6)
        harness.seedCounted("R1", F0.local("2026-09-30T22:30"))

        harness.engine.runTick().getOrThrow()

        assertThat(harness.store.row("v1|R6|I|2026-10-01|10")!!.state).isEqualTo(DecisionState.DELIVERED)
        assertSuppressed(harness.r1(10), ReasonCode.LOST_ARBITRATION)
    }

    @Test
    fun `M17 both never delivered - the earlier createdAt wins, then the lower id`() = runTest {
        val older = Rules.R1.copy(id = "R7", createdAt = F0.CREATED - 24.hours)
        val sameAge = Rules.R1.copy(id = "R8")
        val harness = eligible("2026-10-01T22:30", sameAge, Rules.R1, older)

        harness.engine.runTick().getOrThrow()
        assertThat(harness.store.row("v1|R7|I|2026-10-01|10")!!.state).isEqualTo(DecisionState.DELIVERED)

        val tie = eligible("2026-10-01T22:30", sameAge, Rules.R1)
        tie.engine.runTick().getOrThrow()
        assertThat(tie.r1(10).state).isEqualTo(DecisionState.DELIVERED)
        assertSuppressed(tie.store.row("v1|R8|I|2026-10-01|10")!!, ReasonCode.LOST_ARBITRATION)
    }

    @Test
    fun `M18 a DND suppression starts no cooldown - DND off at 22_45 delivers`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.settings.notifications = NotificationSystemState(interruptionFilter = InterruptionFilter.PRIORITY)
        harness.engine.runTick().getOrThrow()
        assertSuppressed(harness.r1(10), ReasonCode.DND)

        harness.settings.notifications = NotificationSystemState()
        harness.clock.advanceTo(F0.local("2026-10-01T22:45"))
        harness.engine.runTick().getOrThrow()

        assertThat(harness.r1(11).state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `M19 snoozed and DND at once - reason SNOOZED, the trace lists both`() = runTest {
        val harness = eligible("2026-10-01T22:30")
        harness.store.seedRuntime(JitaiRuntimeState("R1", snoozedUntil = harness.stampAt(F0.local("2026-10-01T22:30") + 600.seconds)))
        harness.settings.notifications = NotificationSystemState(interruptionFilter = InterruptionFilter.NONE)

        harness.engine.runTick().getOrThrow()

        val row = harness.r1(10)
        assertSuppressed(row, ReasonCode.SNOOZED)
        assertThat(row.failedGates()).containsAtLeast(ReasonCode.SNOOZED, ReasonCode.DND).inOrder()
        assertThat(row.trace().gates!!.map { it.gate }).containsExactlyElementsIn(ReasonCode.GATES.dropLast(1)).inOrder()
    }
}

package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.Vector
import dev.agentle.jitai.engine.bool
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.Deferral
import dev.agentle.jitai.engine.pipeline.TimerReport
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.QuietHours
import dev.agentle.jitai.engine.row
import dev.agentle.jitai.engine.seedCounted
import dev.agentle.jitai.engine.stampAt
import dev.agentle.jitai.engine.testing.EngineHarness
import dev.agentle.jitai.engine.timer
import dev.agentle.jitai.engine.timerAt
import dev.agentle.jitai.engine.trace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * R10 §12.M through `jitai-timer`: R1 delivery-eligible at 22:30 with screen 50 unless stated; every suppression records
 * its gate as the reason and in the trace. Scheduled points blocked only by Do Not Disturb or the global gap are deferred
 * within their lateness first (jitai-correctness-05/11); the slot-10 lateness of R1 ends at 22:44:59.999.
 */
class SafetyGatesScenarioTest {
    @ParameterizedTest(quoteTextArguments = false, name = "{0}")
    @MethodSource("vectors")
    fun `R10 12_M safety gates`(vector: Vector) = runTest { vector.body(this) }

    companion object {
        private const val TODAY = "2026-10-01"
        private val LATENESS_END: Instant = F0.local("2026-10-01T22:44:59.999")

        private fun key(slot: Int, id: String = "R1", date: String = TODAY) = "v1|$id|I|$date|$slot"

        private fun eligible(
            at: String,
            vararg definitions: JitaiDefinition = arrayOf(Rules.R1),
            settings: EngineSettings = F0.SETTINGS,
        ): EngineHarness {
            val harness = F0.harness(F0.local(at), *definitions, settings = settings)
            harness.features.set(Leaves.SCREEN, int(50, F0.local(at)))
            return harness
        }

        private fun DecisionRecord.failedGates(): List<ReasonCode> = gates().filter { !it.second }.map { it.first }

        private fun DecisionRecord.gates(): List<Pair<ReasonCode, Boolean>> =
            checkNotNull(content.traceJson?.let(dev.agentle.jitai.engine.pipeline.TraceCodec::decode)).gates.orEmpty()
                .map { it.gate to it.passed }

        private fun assertSuppressed(id: String, row: DecisionRecord, reason: ReasonCode) {
            assertWithMessage(id).that(row.state).isEqualTo(DecisionState.SUPPRESSED)
            assertWithMessage(id).that(row.reason).isEqualTo(reason)
            assertWithMessage(id).that(row.failedGates().first()).isEqualTo(reason)
            assertWithMessage(id).that(row.nonce).isNull()
        }

        private fun TimerReport.deferral(key: String): Deferral? = pass?.deferred?.singleOrNull { it.decisionKey == key }

        /** A scheduled point blocked only by DND: deferred at 22:30 and 22:40, SUPPRESSED(DND) at the lateness end. */
        private fun dnd(id: String, filter: InterruptionFilter) =
            Vector(id, "interruption filter $filter: deferred, then SUPPRESSED(DND) at 22:44:59.999") {
                val harness = eligible("2026-10-01T22:30")
                harness.settings.notifications = NotificationSystemState(interruptionFilter = filter)

                val first = harness.timer()
                assertThat(first.deferral(key(10))).isEqualTo(Deferral(key(10), "R1", F0.local("2026-10-01T22:40"), ReasonCode.DND))
                assertThat(harness.store.row(key(10))).isNull()
                harness.timerAt(F0.local("2026-10-01T22:40"))
                harness.timerAt(LATENESS_END)

                assertSuppressed(id, harness.row(key(10)), ReasonCode.DND)
                assertThat(harness.row(key(10)).decisionPointAt).isEqualTo(LATENESS_END)
                assertThat(harness.delivery.posts).isEmpty()
            }

        private fun blocked(id: String, title: String, prerequisite: DeliveryPrerequisite) = Vector(id, title) {
            val harness = eligible("2026-10-01T22:30")
            harness.delivery.setPrerequisite(JitaiCategory.DIGITAL_WELLBEING, prerequisite)

            harness.timer()

            assertSuppressed(id, harness.row(key(10)), ReasonCode.NOTIFICATIONS_BLOCKED)
            // Blocked rows do not count toward the global caps (jitai-correctness-13).
            assertThat(harness.row(key(10)).state.countsGlobally).isFalse()
        }

        private val allowWhenInteractive: JitaiDefinition =
            Rules.R1.copy(delivery = Rules.R1.delivery.copy(quietHoursPolicy = QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE))

        private val quiet: EngineSettings = F0.SETTINGS.copy(quietHours = QuietHours())

        @JvmStatic
        @Suppress("LongMethod") // One table: every row of R10 §12.M.
        fun vectors(): List<Vector> = listOf(
            Vector("M1", "R1 paused between evaluation and commit: SUPPRESSED(NOT_EFFECTIVE), G01 re-checked in the transaction") {
                val harness = eligible("2026-10-01T22:30")
                harness.features.onResolve = { harness.repository.update("R1") { it.copy(status = JitaiStatus.PAUSED) } }

                harness.timer()

                assertSuppressed("M1", harness.row(key(10)), ReasonCode.NOT_EFFECTIVE)
                assertThat(harness.delivery.posts).isEmpty()
                assertThat(harness.store.timerRows().filter { it.jitaiId == "R1" }).isEmpty()
            },
            Vector("M2", "expiresAt 2026-10-01T20:30:00Z, evaluated 22:29:59: passes (slot 9 late, inside its lateness)") {
                val r1 = Rules.R1.copy(expiresAt = Instant.parse("2026-10-01T20:30:00Z"))
                val harness = eligible("2026-10-01T22:29:59", r1)

                harness.timer()

                assertThat(harness.row(key(9)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.ACTIVE)
            },
            Vector("M2", "expiresAt 2026-10-01T20:30:00Z, evaluated 22:30:00: SUPPRESSED(EXPIRED), status EXPIRED, no timer rows left") {
                val r1 = Rules.R1.copy(expiresAt = Instant.parse("2026-10-01T20:30:00Z"))
                val harness = eligible("2026-10-01T22:30", r1)

                val report = harness.timer()

                assertSuppressed("M2", harness.row(key(10)), ReasonCode.EXPIRED)
                assertThat(harness.repository["R1"]!!.status).isEqualTo(JitaiStatus.EXPIRED)
                assertThat(report.pass!!.expired).containsExactly("R1")
                assertThat(harness.store.timerRows().filter { it.jitaiId == "R1" }).isEmpty()
                assertThat(report.nextDueAt).isNull()
            },
            Vector("M3", "snoozedUntil 22:45: SUPPRESSED(SNOOZED) at 22:30, DELIVERED at 22:45") {
                val harness = eligible("2026-10-01T22:30")
                harness.store.seedRuntime(JitaiRuntimeState("R1", snoozedUntil = harness.stampAt(F0.local("2026-10-01T22:45"))))

                harness.timer()
                assertSuppressed("M3", harness.row(key(10)), ReasonCode.SNOOZED)
                harness.timerAt(F0.local("2026-10-01T22:45"))

                assertThat(harness.row(key(11)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M4", "global pause until 23:00: SUPPRESSED(GLOBAL_PAUSE)") {
                val harness = eligible("2026-10-01T22:30", settings = F0.SETTINGS.copy(pauseUntil = F0.local("2026-10-01T23:00")))

                harness.timer()

                assertSuppressed("M4", harness.row(key(10)), ReasonCode.GLOBAL_PAUSE)
            },
            blocked(
                "M5",
                "POST_NOTIFICATIONS denied: SUPPRESSED(NOTIFICATIONS_BLOCKED)",
                DeliveryPrerequisite(notificationsEnabled = false, channelImportanceNone = false, notificationsPaused = false),
            ),
            blocked(
                "M5",
                "channel jitai_digital_wellbeing at importance NONE: SUPPRESSED(NOTIFICATIONS_BLOCKED)",
                DeliveryPrerequisite(notificationsEnabled = true, channelImportanceNone = true, notificationsPaused = false),
            ),
            blocked(
                "M5",
                "notifications paused (areNotificationsPaused): SUPPRESSED(NOTIFICATIONS_BLOCKED)",
                DeliveryPrerequisite(notificationsEnabled = true, channelImportanceNone = false, notificationsPaused = true),
            ),
            Vector("M6", "quiet hours 22:00-07:00 enabled, policy RESPECT: SUPPRESSED(QUIET_HOURS)") {
                val harness = eligible("2026-10-01T22:30", settings = quiet)

                harness.timer()

                assertSuppressed("M6", harness.row(key(10)), ReasonCode.QUIET_HOURS)
            },
            Vector("M7", "ALLOW_WHEN_INTERACTIVE and device_interactive true: passes") {
                val harness = eligible("2026-10-01T22:30", allowWhenInteractive, settings = quiet)

                harness.timer()

                assertThat(harness.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M7", "ALLOW_WHEN_INTERACTIVE and device_interactive false: SUPPRESSED(QUIET_HOURS)") {
                val harness = eligible("2026-10-01T22:30", allowWhenInteractive, settings = quiet)
                harness.features.set("device_interactive", bool(false, F0.local("2026-10-01T22:30")))

                harness.timer()

                assertSuppressed("M7", harness.row(key(10)), ReasonCode.QUIET_HOURS)
            },
            dnd("M8", InterruptionFilter.PRIORITY),
            dnd("M8", InterruptionFilter.ALARMS),
            dnd("M8", InterruptionFilter.NONE),
            dnd("M8", InterruptionFilter.UNKNOWN),
            Vector("M8", "interruption filter ALL: passes") {
                val harness = eligible("2026-10-01T22:30")
                harness.settings.notifications = NotificationSystemState(interruptionFilter = InterruptionFilter.ALL)

                harness.timer()

                assertThat(harness.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M8", "an unreadable notification state fails closed: deferred as DND, delivered once it reads ALL") {
                val harness = eligible("2026-10-01T22:30")
                harness.settings.failNext += "notifications"

                val first = harness.timer()
                assertThat(first.deferral(key(10))!!.reason).isEqualTo(ReasonCode.DND)
                assertThat(harness.delivery.posts).isEmpty()
                harness.timerAt(F0.local("2026-10-01T22:40"))

                assertThat(harness.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.row(key(10)).decisionPointAt).isEqualTo(F0.local("2026-10-01T22:40"))
            },
            Vector("M9", "S1 (22:00-23:00, DIGITAL_WELLBEING): SUPPRESSED(SUPPRESSED_BY_RULE) at 22:30, passes at 23:00 (half-open)") {
                val harness = eligible("2026-10-01T22:30", Rules.R1, Rules.S1)

                harness.timer()
                val blocked = harness.row(key(10))
                assertSuppressed("M9", blocked, ReasonCode.SUPPRESSED_BY_RULE)
                assertThat(harness.trace(key(10)).suppressedBy).containsExactly("S1")
                harness.runTimerUntil(F0.local("2026-10-01T23:00"))

                assertThat(harness.row(key(12)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector(
                "M10",
                "R3 delivered 22:30 (engine day 10-01); at 01:30 the same engine day: SUPPRESSED(DAILY_CAP); 10-02 22:00 passes",
            ) {
                val harness = eligible("2026-10-01T22:30", Rules.R3)
                harness.timer()
                assertThat(harness.row(key(1, "R3")).state).isEqualTo(DecisionState.DELIVERED)

                harness.timerAt(F0.local("2026-10-02T01:30"))
                val capped = harness.row(key(7, "R3"))
                assertSuppressed("M10", capped, ReasonCode.DAILY_CAP)
                assertThat(capped.engineDay.toString()).isEqualTo(TODAY)
                harness.timerAt(F0.local("2026-10-02T22:00"))

                assertThat(harness.row(key(0, "R3", "2026-10-02")).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M11", "maxPerWeek 2, deliveries 09-26 and 09-30: on 10-01 (window 09-25..10-01) SUPPRESSED(WEEKLY_CAP)") {
                val harness = eligible("2026-10-01T22:30", Rules.R1.copy(maxPerDay = 1, maxPerWeek = 2))
                harness.seedCounted("R1", F0.local("2026-09-26T22:30"))
                harness.seedCounted("R1", F0.local("2026-09-30T22:30"))

                harness.timer()

                assertSuppressed("M11", harness.row(key(10)), ReasonCode.WEEKLY_CAP)
            },
            Vector("M11", "maxPerWeek 2, deliveries 09-26 and 09-30: on 10-03 (window 09-27..10-03) passes") {
                val harness = eligible("2026-10-03T22:30", Rules.R1.copy(maxPerDay = 1, maxPerWeek = 2))
                harness.seedCounted("R1", F0.local("2026-09-26T22:30"))
                harness.seedCounted("R1", F0.local("2026-09-30T22:30"))

                harness.timer()

                assertThat(harness.row(key(10, date = "2026-10-03")).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M12", "another JITAI delivered 22:10; R1 at 22:39:59: gap not over, deferred to 22:40 and DELIVERED then") {
                val harness = eligible("2026-10-01T22:39:59")
                harness.seedCounted("R9", F0.local("2026-10-01T22:10"))

                val first = harness.timer()
                assertThat(
                    first.deferral(key(10)),
                ).isEqualTo(Deferral(key(10), "R1", F0.local("2026-10-01T22:40"), ReasonCode.GLOBAL_MIN_GAP))
                harness.timerAt(F0.local("2026-10-01T22:40"))

                assertThat(harness.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
                assertThat(harness.row(key(10)).decisionPointAt).isEqualTo(F0.local("2026-10-01T22:40"))
            },
            Vector("M12", "another JITAI delivered 22:10; R1 at 22:40:00 passes") {
                val harness = eligible("2026-10-01T22:40")
                harness.seedCounted("R9", F0.local("2026-10-01T22:10"))

                harness.timer()

                assertThat(harness.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M13", "six deliveries already in the engine day: SUPPRESSED(GLOBAL_DAILY_CAP)") {
                val harness = eligible("2026-10-01T22:30")
                (10..15).forEach { hour -> harness.seedCounted("R$hour", F0.local("2026-10-01T$hour:00")) }

                harness.timer()

                assertSuppressed("M13", harness.row(key(10)), ReasonCode.GLOBAL_DAILY_CAP)
            },
            Vector("M14", "thirty deliveries in the last 7 engine days: SUPPRESSED(GLOBAL_WEEKLY_CAP)") {
                val harness = eligible("2026-10-01T22:30")
                (25..30).forEach { day -> (10..14).forEach { hour -> harness.seedCounted("R$hour", F0.local("2026-09-${day}T$hour:00")) } }

                harness.timer()

                assertSuppressed("M14", harness.row(key(10)), ReasonCode.GLOBAL_WEEKLY_CAP)
            },
            Vector("M15", "two VOICE deliveries today: the VOICE rule is SUPPRESSED(CHANNEL_CAP), R1 (NOTIFICATION) passes") {
                val voice = Rules.R1.copy(id = "RV", delivery = Rules.R1.delivery.copy(channel = DeliveryChannel.VOICE), priority = 90)
                val harness = eligible("2026-10-01T22:30", Rules.R1, voice)
                harness.seedCounted("V0", F0.local("2026-10-01T10:00"), channel = DeliveryChannel.VOICE)
                harness.seedCounted("V0", F0.local("2026-10-01T12:00"), channel = DeliveryChannel.VOICE)

                harness.timer()

                assertSuppressed("M15", harness.row(key(10, "RV")), ReasonCode.CHANNEL_CAP)
                assertThat(harness.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M16", "R5 = R1 with priority 60 at the same point: R5 DELIVERED, R1 SUPPRESSED(LOST_ARBITRATION)") {
                val harness = eligible("2026-10-01T22:30", Rules.R1, Rules.R1.copy(id = "R5", priority = 60))

                harness.timer()

                assertThat(harness.row(key(10, "R5")).state).isEqualTo(DecisionState.DELIVERED)
                // The gap after the winner ends at 23:00, after the slot's lateness: no deferral.
                assertSuppressed("M16", harness.row(key(10)), ReasonCode.LOST_ARBITRATION)
                assertThat(harness.delivery.posts).hasSize(1)
            },
            Vector("M17", "equal priority: never-delivered R6 beats R1 (last delivered 2026-09-30)") {
                val harness = eligible("2026-10-01T22:30", Rules.R1, Rules.R1.copy(id = "R6"))
                harness.seedCounted("R1", F0.local("2026-09-30T22:30"))

                harness.timer()

                assertThat(harness.row(key(10, "R6")).state).isEqualTo(DecisionState.DELIVERED)
                assertSuppressed("M17", harness.row(key(10)), ReasonCode.LOST_ARBITRATION)
            },
            Vector("M17", "equal priority, both never delivered: the earlier createdAt wins, then the lower id") {
                val older = Rules.R1.copy(id = "R7", createdAt = F0.CREATED - 24.hours)
                val sameAge = Rules.R1.copy(id = "R8")
                val harness = eligible("2026-10-01T22:30", sameAge, Rules.R1, older)
                harness.timer()
                assertThat(harness.row(key(10, "R7")).state).isEqualTo(DecisionState.DELIVERED)

                val tie = eligible("2026-10-01T22:30", sameAge, Rules.R1)
                tie.timer()

                assertThat(tie.row(key(10)).state).isEqualTo(DecisionState.DELIVERED)
                assertSuppressed("M17", tie.row(key(10, "R8")), ReasonCode.LOST_ARBITRATION)
            },
            Vector("M18", "a DND suppression starts no cooldown: SUPPRESSED(DND) for slot 10, DND off at 22:45 delivers slot 11") {
                val harness = eligible("2026-10-01T22:30")
                harness.settings.notifications = NotificationSystemState(interruptionFilter = InterruptionFilter.PRIORITY)
                harness.runTimerUntil(LATENESS_END)
                assertSuppressed("M18", harness.row(key(10)), ReasonCode.DND)

                harness.settings.notifications = NotificationSystemState()
                harness.timerAt(F0.local("2026-10-01T22:45"))

                assertThat(harness.row(key(11)).state).isEqualTo(DecisionState.DELIVERED)
            },
            Vector("M19", "snoozed and DND at once: reason SNOOZED (the first failing gate), the trace lists both") {
                val harness = eligible("2026-10-01T22:30")
                harness.store.seedRuntime(JitaiRuntimeState("R1", snoozedUntil = harness.stampAt(F0.local("2026-10-01T22:40"))))
                harness.settings.notifications = NotificationSystemState(interruptionFilter = InterruptionFilter.NONE)

                harness.timer()

                val row = harness.row(key(10))
                assertSuppressed("M19", row, ReasonCode.SNOOZED)
                assertThat(row.failedGates()).containsAtLeast(ReasonCode.SNOOZED, ReasonCode.DND).inOrder()
                assertThat(row.gates().map { it.first }).containsExactlyElementsIn(ReasonCode.GATES.dropLast(1)).inOrder()
            },
        )
    }
}

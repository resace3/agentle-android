package dev.agentle.jitai.engine.gates

import com.google.common.truth.Truth.assertThat
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentMode
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.ports.DefinitionState
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.QuietHours
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** R10 §9 gate unit tests: G01-G15 on hand-built inputs, §12.O8-O10 monotonic cooldowns, limits and arbitration. */
class SafetyGatesTest {
    private val r1 = Rules.R1
    private val e0 = 3_600_000L

    private fun delivered(definition: JitaiDefinition, stamp: MonotonicStamp, channel: DeliveryChannel = DeliveryChannel.NOTIFICATION) =
        DecisionRecord(
            decisionKey = DecisionKeys.event(definition.id, stamp.wall),
            jitaiId = definition.id,
            jitaiVersion = 1,
            triggerKind = TriggerKind.EVENT,
            category = definition.category,
            channel = channel,
            state = DecisionState.DELIVERED,
            decided = stamp,
            zoneId = F0.BERLIN.id,
            localDateTime = stamp.wall.toLocalDateTime(F0.BERLIN),
            engineDay = EngineDays.of(stamp.wall, F0.BERLIN),
            delivered = stamp,
        )

    private fun input(
        now: MonotonicStamp,
        definition: JitaiDefinition = r1,
        recent: List<DecisionRecord> = emptyList(),
        latestOwn: DecisionRecord? = null,
        settings: EngineSettings = F0.SETTINGS,
        notifications: NotificationSystemState = NotificationSystemState(),
        runtime: JitaiRuntimeState = JitaiRuntimeState(definition.id),
        interactive: Tri = Tri.TRUE,
        suppressedBy: List<String> = emptyList(),
        state: DefinitionState? = DefinitionState(1, definition.enabled, definition.status, definition.expiresAt),
        snoozeFollowUp: Boolean = false,
    ) = GateInput(
        definition = definition,
        definitionState = state,
        channel = definition.delivery.channel,
        now = now,
        zone = F0.BERLIN,
        runtime = runtime,
        settings = settings,
        notifications = notifications,
        interactive = interactive,
        suppressedBy = suppressedBy,
        recent = recent,
        latestOwn = latestOwn,
        snoozeFollowUp = snoozeFollowUp,
    )

    private fun stamp(local: String, elapsed: Long, boot: Int? = 41) = MonotonicStamp(F0.local(local), elapsed, boot)

    private fun GateReport.check(gate: ReasonCode): GateCheck = checks.single { it.gate == gate }

    // -- §12.O8-O10 ------------------------------------------------------------------------------------------------------

    @Test
    fun `O8 clock set back 1 h - 65 min of elapsed realtime pass the 60 min cooldown`() {
        val last = delivered(r1, stamp("2026-10-01T22:00", e0))
        val now = stamp("2026-10-01T22:05", e0 + 65.minutes.inWholeMilliseconds)

        val report = GateEvaluator.evaluate(input(now, recent = listOf(last), latestOwn = last))

        assertThat(report.check(ReasonCode.COOLDOWN).passed).isTrue()
        assertThat(report.check(ReasonCode.COOLDOWN).detail).isEqualTo("elapsed=3900s cooldown=60m")
    }

    @Test
    fun `O9 clock set forward 1 h - 10 min of elapsed realtime are SUPPRESSED(COOLDOWN)`() {
        val last = delivered(r1, stamp("2026-10-01T22:00", e0))
        val now = stamp("2026-10-01T23:10", e0 + 10.minutes.inWholeMilliseconds)

        val report = GateEvaluator.evaluate(input(now, recent = listOf(last), latestOwn = last))

        assertThat(report.firstFailure!!.gate).isEqualTo(ReasonCode.COOLDOWN)
        assertThat(report.firstFailure!!.detail).isEqualTo("elapsed=600s cooldown=60m")
    }

    @Test
    fun `O10 after a reboot the wall-clock difference of 30 min is used - SUPPRESSED(COOLDOWN)`() {
        val last = delivered(r1, stamp("2026-10-01T22:00", e0))
        val now = stamp("2026-10-01T22:30", 30_000, boot = 42)

        val report = GateEvaluator.evaluate(input(now, recent = listOf(last), latestOwn = last))

        assertThat(report.firstFailure!!.gate).isEqualTo(ReasonCode.COOLDOWN)
        assertThat(report.firstFailure!!.detail).isEqualTo("elapsed=1800s cooldown=60m")
    }

    @Test
    fun `a wall clock set before the last delivery after a reboot clamps the elapsed time at zero`() {
        val last = delivered(r1, stamp("2026-10-01T22:00", e0))
        val now = stamp("2026-10-01T21:00", 30_000, boot = 42)

        val report = GateEvaluator.evaluate(input(now, recent = listOf(last), latestOwn = last))

        assertThat(report.check(ReasonCode.COOLDOWN).detail).isEqualTo("elapsed=0s cooldown=60m")
    }

    @Test
    fun `the cooldown also reads the latest own row beyond the recent window`() {
        val weekCooldown = r1.copy(cooldownMinutes = 10_080)
        val old = delivered(weekCooldown, stamp("2026-09-20T22:00", 0, boot = 40))
        val now = stamp("2026-10-01T22:00", e0)

        val report = GateEvaluator.evaluate(input(now, weekCooldown, latestOwn = old))

        assertThat(report.check(ReasonCode.COOLDOWN).passed).isTrue()
        val recentEnough = delivered(weekCooldown, stamp("2026-09-26T22:00", 0, boot = 40))
        assertThat(GateEvaluator.evaluate(input(now, weekCooldown, latestOwn = recentEnough)).check(ReasonCode.COOLDOWN).passed).isFalse()
    }

    // -- G01-G15 one by one --------------------------------------------------------------------------------------------

    private val now = stamp("2026-10-01T22:30", e0)

    @Test
    fun `with nothing in the way every gate passes`() {
        val report = GateEvaluator.evaluate(input(now))

        assertThat(report.passed).isTrue()
        assertThat(report.checks.map { it.gate }).isEqualTo(ReasonCode.GATES.dropLast(1))
        assertThat(report.check(ReasonCode.COOLDOWN).detail).isEqualTo("never")
        assertThat(report.check(ReasonCode.GLOBAL_MIN_GAP).detail).isEqualTo("never")
        assertThat(report.check(ReasonCode.QUIET_HOURS).detail).isEqualTo("off")
    }

    @Test
    fun `G01 a deleted, disabled or paused definition fails NOT_EFFECTIVE`() {
        assertThat(GateEvaluator.evaluate(input(now, state = null)).check(ReasonCode.NOT_EFFECTIVE).detail).isEqualTo("deleted")
        val paused = DefinitionState(2, true, JitaiStatus.PAUSED, null)
        assertThat(GateEvaluator.evaluate(input(now, state = paused)).firstFailure!!.gate).isEqualTo(ReasonCode.NOT_EFFECTIVE)
        val disabled = DefinitionState(2, false, JitaiStatus.ACTIVE, null)
        assertThat(GateEvaluator.evaluate(input(now, state = disabled)).firstFailure!!.detail).isEqualTo("enabled=false status=ACTIVE")
    }

    @Test
    fun `G02 reads expiresAt from the stored definition first`() {
        val stored = DefinitionState(2, true, JitaiStatus.ACTIVE, F0.local("2026-10-01T22:30"))

        assertThat(GateEvaluator.evaluate(input(now, state = stored)).firstFailure!!.gate).isEqualTo(ReasonCode.EXPIRED)
        assertThat(GateEvaluator.evaluate(input(now, r1.copy(expiresAt = F0.local("2026-10-01T22:31")))).passed).isTrue()
    }

    @Test
    fun `G03 snoozedUntil compares by elapsed time within the boot`() {
        val snoozed = JitaiRuntimeState("R1", snoozedUntil = now + 1.minutes)
        val wallOnlyLater = JitaiRuntimeState("R1", snoozedUntil = MonotonicStamp(now.wall + 10.minutes, now.elapsedMillis, now.bootCount))

        assertThat(GateEvaluator.evaluate(input(now, runtime = snoozed)).firstFailure!!.gate).isEqualTo(ReasonCode.SNOOZED)
        assertThat(GateEvaluator.evaluate(input(now, runtime = wallOnlyLater)).passed).isTrue()
    }

    @Test
    fun `G04 a global pause holds until pauseUntil`() {
        val paused = F0.SETTINGS.copy(pauseUntil = now.wall + 1.minutes)

        assertThat(GateEvaluator.evaluate(input(now, settings = paused)).firstFailure!!.gate).isEqualTo(ReasonCode.GLOBAL_PAUSE)
        assertThat(GateEvaluator.evaluate(input(now, settings = F0.SETTINGS.copy(pauseUntil = now.wall))).passed).isTrue()
    }

    @ParameterizedTest(name = "permission={0} app={1} blockedCategory={2}")
    @CsvSource("false, true, false", "true, false, false", "true, true, true")
    fun `G05 notifications blocked by permission, app setting or channel`(permission: Boolean, app: Boolean, blockCategory: Boolean) {
        val notifications = NotificationSystemState(
            permissionGranted = permission,
            appNotificationsEnabled = app,
            blockedCategories = if (blockCategory) setOf(JitaiCategory.DIGITAL_WELLBEING) else emptySet(),
        )

        assertThat(GateEvaluator.evaluate(input(now, notifications = notifications)).firstFailure!!.gate)
            .isEqualTo(ReasonCode.NOTIFICATIONS_BLOCKED)
    }

    @ParameterizedTest(name = "E10 quiet hours at {0} with {1}, interactive {2} -> passes {3}")
    @CsvSource(
        "2026-10-02T06:59, RESPECT, TRUE, false",
        "2026-10-02T07:00, RESPECT, TRUE, true",
        "2026-10-01T22:30, ALLOW_WHEN_INTERACTIVE, TRUE, true",
        "2026-10-01T22:30, ALLOW_WHEN_INTERACTIVE, UNKNOWN, false",
        "2026-10-01T22:30, ALLOW_WHEN_INTERACTIVE, FALSE, false",
    )
    fun `G06 quiet hours 22_00-07_00 (R10 9_3, 12_E10)`(local: String, policy: QuietHoursPolicy, interactive: Tri, passes: Boolean) {
        val settings = F0.SETTINGS.copy(quietHours = QuietHours("22:00", "07:00", enabled = true))
        val definition = r1.copy(delivery = r1.delivery.copy(quietHoursPolicy = policy))

        val report = GateEvaluator.evaluate(input(stamp(local, e0), definition, settings = settings, interactive = interactive))

        assertThat(report.check(ReasonCode.QUIET_HOURS).passed).isEqualTo(passes)
    }

    @Test
    fun `G06 an invalid quiet-hours setting fails closed`() {
        val settings = F0.SETTINGS.copy(quietHours = QuietHours("22:00", "22:00", enabled = true))

        assertThat(
            GateEvaluator.evaluate(input(now, settings = settings)).check(ReasonCode.QUIET_HOURS).detail,
        ).isEqualTo("invalid_setting")
    }

    @ParameterizedTest(name = "G07 {0}")
    @CsvSource("ALL, true", "PRIORITY, false", "ALARMS, false", "NONE, false", "UNKNOWN, false")
    fun `G07 only a definite interruption filter ALL passes`(filter: InterruptionFilter, passes: Boolean) {
        val report = GateEvaluator.evaluate(input(now, notifications = NotificationSystemState(interruptionFilter = filter)))

        assertThat(report.check(ReasonCode.DND).passed).isEqualTo(passes)
        assertThat(report.check(ReasonCode.DND).detail).isEqualTo(filter.name)
    }

    @Test
    fun `G08 blocking SUPPRESSION rules are counted in the detail`() {
        val report = GateEvaluator.evaluate(input(now, suppressedBy = listOf("S1", "S2")))

        assertThat(report.firstFailure).isEqualTo(GateCheck(ReasonCode.SUPPRESSED_BY_RULE, false, "rules=2"))
    }

    @Test
    fun `G10-G15 count counted rows of the engine day and week`() {
        val other = Rules.R3
        val rows = listOf(
            delivered(r1, stamp("2026-09-30T22:00", 0, boot = 40)),
            delivered(r1, stamp("2026-10-01T20:00", e0 - 150.minutes.inWholeMilliseconds)),
            delivered(other, stamp("2026-10-01T21:00", e0 - 90.minutes.inWholeMilliseconds), DeliveryChannel.VOICE),
        )

        val daily = GateEvaluator.evaluate(input(now, r1.copy(maxPerDay = 1), recent = rows))
        val weekly = GateEvaluator.evaluate(input(now, r1.copy(maxPerWeek = 2), recent = rows))
        val global = GateEvaluator.evaluate(input(now, recent = rows, settings = F0.SETTINGS.copy(globalMaxPerDay = 2)))
        val globalWeek = GateEvaluator.evaluate(input(now, recent = rows, settings = F0.SETTINGS.copy(globalMaxPerWeek = 3)))
        val voice = GateEvaluator.evaluate(
            input(
                now,
                r1.copy(delivery = r1.delivery.copy(channel = DeliveryChannel.VOICE)),
                recent = rows,
                settings = F0.SETTINGS.copy(channelCaps = mapOf(DeliveryChannel.VOICE to 1)),
            ),
        )

        assertThat(daily.check(ReasonCode.DAILY_CAP)).isEqualTo(GateCheck(ReasonCode.DAILY_CAP, false, "count=1 limit=1"))
        assertThat(weekly.check(ReasonCode.WEEKLY_CAP)).isEqualTo(GateCheck(ReasonCode.WEEKLY_CAP, false, "count=2 limit=2"))
        assertThat(global.check(ReasonCode.GLOBAL_DAILY_CAP).passed).isFalse()
        assertThat(
            globalWeek.check(ReasonCode.GLOBAL_WEEKLY_CAP),
        ).isEqualTo(GateCheck(ReasonCode.GLOBAL_WEEKLY_CAP, false, "count=3 limit=3"))
        assertThat(voice.check(ReasonCode.CHANNEL_CAP)).isEqualTo(GateCheck(ReasonCode.CHANNEL_CAP, false, "count=1 limit=1"))
        assertThat(voice.check(ReasonCode.GLOBAL_MIN_GAP).detail).isEqualTo("elapsed=5400s gap=30m")
    }

    @Test
    fun `a snooze follow-up skips G09-G11 but still meets G12 and the global caps`() {
        val last = delivered(r1, stamp("2026-10-01T22:20", e0 - 10.minutes.inWholeMilliseconds))

        val report = GateEvaluator.evaluate(input(now, recent = listOf(last), latestOwn = last, snoozeFollowUp = true))

        assertThat(report.check(ReasonCode.COOLDOWN).detail).isEqualTo("skipped:snooze_follow_up")
        assertThat(report.check(ReasonCode.DAILY_CAP).detail).isEqualTo("skipped:snooze_follow_up")
        assertThat(report.check(ReasonCode.WEEKLY_CAP).detail).isEqualTo("skipped:snooze_follow_up")
        assertThat(report.firstFailure!!.gate).isEqualTo(ReasonCode.GLOBAL_MIN_GAP)
    }

    // -- R10 §9.2 limits, settings ceilings -----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} cooldown={1} day={2} week={3} priority={4}")
    @CsvSource(
        "USER_MANUAL, 5, 20, 100, 120, 15, 12, 60, 100",
        "RULE_TEMPLATE, 30, 2, , 70, 30, 2, 2, 70",
        "AI_NATURAL_LANGUAGE, 5, 20, 100, 120, 60, 3, 14, 60",
        "AI_DISCOVERED, , , , 40, 60, 1, 1, 40",
        "USER_MANUAL, 20000, 0, 0, -5, 10080, 1, 1, 0",
    )
    fun `effective limits clamp to the origin's range, AI stricter`(
        createdBy: CreatedBy,
        cooldown: Int?,
        perDay: Int?,
        perWeek: Int?,
        priority: Int,
        expectedCooldown: Int,
        expectedDay: Int,
        expectedWeek: Int,
        expectedPriority: Int,
    ) {
        val definition = Rules.rule(
            "X",
            null,
            cooldown = cooldown,
            maxPerDay = perDay,
            maxPerWeek = perWeek,
            priority = priority,
            createdBy = createdBy,
        )

        assertThat(EffectiveLimits.of(definition)).isEqualTo(EffectiveLimits(expectedCooldown, expectedDay, expectedWeek, expectedPriority))
    }

    @Test
    fun `settings are clamped to the hard ceilings`() {
        val hostile = EngineSettings(
            globalMaxPerDay = 50,
            globalMaxPerWeek = 500,
            minGapMinutes = 1,
            channelCaps = mapOf(DeliveryChannel.VOICE to 99),
            rolloverMinute = 999,
            maxEventAgeMinutes = mapOf(JitaiEventType.POWER_CONNECTED to 0, JitaiEventType.USER_PRESENT to 10_000),
        ).effective()

        assertThat(hostile.globalMaxPerDay).isEqualTo(12)
        assertThat(hostile.globalMaxPerWeek).isEqualTo(60)
        assertThat(hostile.minGapMinutes).isEqualTo(15)
        assertThat(hostile.channelCap(DeliveryChannel.VOICE)).isEqualTo(12)
        assertThat(hostile.channelCap(DeliveryChannel.NOTIFICATION)).isEqualTo(12)
        assertThat(hostile.rolloverMinute).isEqualTo(360)
        assertThat(hostile.maxEventAge(JitaiEventType.POWER_CONNECTED)).isEqualTo(1.minutes)
        assertThat(hostile.maxEventAge(JitaiEventType.USER_PRESENT)).isEqualTo(120.minutes)
        assertThat(hostile.maxEventAge(JitaiEventType.ACTIVITY_STATE_CHANGED)).isEqualTo(10.minutes)
        assertThat(EngineSettings().channelCap(DeliveryChannel.VIDEO)).isEqualTo(1)
        assertThat(EngineSettings().channelCap(DeliveryChannel.VOICE)).isEqualTo(2)
    }

    @Test
    fun `backoff starts at 3 and caps at 7 days even for long runs`() {
        assertThat(Backoff.effectiveCooldown(60.minutes, 40)).isEqualTo(Backoff.CAP)
        assertThat(Backoff.effectiveCooldown(15.minutes, 3)).isEqualTo(30.minutes)
    }

    // -- §9.4 arbitration, §15.3 micro-randomization ---------------------------------------------------------------------

    @Test
    fun `arbitration ranks by priority, then oldest last delivery (never first), then createdAt, then id`() {
        fun contender(id: String, priority: Int, last: String?, created: String = "2026-09-01T08:00:00Z") = Arbitration.Contender(
            Rules.rule(id, null, createdAt = Instant.parse(created)),
            priority,
            last?.let(Instant::parse),
        )
        val ranked = Arbitration.rank(
            listOf(
                contender("B", 50, "2026-09-30T20:00:00Z"),
                contender("A", 50, "2026-09-30T20:00:00Z"),
                contender("C", 50, "2026-09-29T20:00:00Z"),
                contender("D", 50, null, "2026-09-02T08:00:00Z"),
                contender("E", 50, null),
                contender("F", 70, "2026-10-01T20:00:00Z"),
            ),
        )

        assertThat(ranked.map { it.definition.id }).containsExactly("F", "E", "D", "C", "A", "B").inOrder()
    }

    @Test
    fun `micro-randomization draws are reproducible, in range and decide by u less than p`() {
        val salt = ByteArray(16) { it.toByte() }
        val keys = (0 until 2_000).map { "v1|R1|I|2026-10-01|$it" }

        val draws = keys.map { MicroRandomization.draw(salt, it) }
        val delivered = keys.count { MicroRandomization.deliver(salt, it, 0.5) }

        assertThat(draws.all { it >= 0.0 && it < 1.0 }).isTrue()
        assertThat(keys.map { MicroRandomization.draw(salt, it) }).isEqualTo(draws)
        assertThat(delivered).isIn(900..1_100)
        keys.zip(draws).forEach { (key, u) ->
            if (u < 0.49 || u > 0.51) assertThat(MicroRandomization.deliver(salt, key, 0.5)).isEqualTo(u < 0.5)
        }
    }

    @Test
    fun `the delivery probability is clamped to 0_3-0_7 and absent when the experiment is off`() {
        assertThat(MicroRandomization.probability(ExperimentSpec())).isNull()
        assertThat(MicroRandomization.probability(ExperimentSpec(ExperimentMode.MICRO_RANDOMIZED))).isEqualTo(0.5)
        assertThat(MicroRandomization.probability(ExperimentSpec(ExperimentMode.MICRO_RANDOMIZED, 0.9))).isEqualTo(0.7)
        assertThat(MicroRandomization.probability(ExperimentSpec(ExperimentMode.MICRO_RANDOMIZED, 0.1))).isEqualTo(0.3)
    }

    @Test
    fun `sinceLatest ignores rows that are not counted`() {
        val failed = delivered(r1, stamp("2026-10-01T22:20", e0)).copy(state = DecisionState.FAILED)

        assertThat(GateEvaluator.sinceLatest(listOf(failed), now)).isNull()
        assertThat(GateEvaluator.recentDays(kotlinx.datetime.LocalDate.parse("2026-10-09")).start)
            .isEqualTo(kotlinx.datetime.LocalDate.parse("2026-10-01"))
    }
}

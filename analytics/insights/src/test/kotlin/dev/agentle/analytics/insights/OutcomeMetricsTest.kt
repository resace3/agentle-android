package dev.agentle.analytics.insights

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.analytics.features.daily.InMemoryDailyFeatureStore
import dev.agentle.analytics.features.daily.InMemoryDailyInputs
import dev.agentle.analytics.features.daily.MetricFamily
import dev.agentle.core.model.AppUsagePayload
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.EventType
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.ScreenPayload
import dev.agentle.core.model.SleepSessionPayload
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.StepsPayload
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The outcome metric catalog of docs/research/10 §15.1, computed from local data with latency allowances. */
class OutcomeMetricsTest {
    private val activity = Lineage(setOf(DataCategory.ACTIVITY), setOf(SourceFamily.GH_API))

    @Test
    fun `steps after a reminder come from the fused series, so watch and phone are not added`() = runTest {
        val inputs = inputs(stepMinutes(GH_STEPS, at(10, 0), 30, 100) + stepMinutes(PHONE_STEPS, at(10, 0), 30, 100))
        inputs.setSourceCoverage(MetricFamily.STEPS, GH_STEPS, listOf(ClosedOpenRange(at(9, 0), at(11, 0))))
        val calculator = OutcomeCalculator(inputs, InMemoryDailyFeatureStore())
        val decision = OutcomeDecision("d1", at(10, 0), TimeZone.UTC)

        assertThat(calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(11, 29), 30).state).isEqualTo(OutcomeState.PENDING)
        val value = calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(11, 30), 30)

        assertThat(
            value,
        ).isEqualTo(OutcomeValue("d1", OutcomeMetric.STEPS_AFTER, OutcomeState.AVAILABLE, number = 3_000, lineage = activity))
        assertThat(calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(11, 30), 30)).isEqualTo(value)
    }

    @Test
    fun `minutes the canonical source lacks come from the next source`() = runTest {
        val inputs = inputs(stepMinutes(GH_STEPS, at(10, 0), 20, 100) + stepMinutes(PHONE_STEPS, at(10, 0), 30, 100))
        inputs.setSourceCoverage(MetricFamily.STEPS, GH_STEPS, listOf(ClosedOpenRange(at(9, 0), at(11, 0))))

        val value = OutcomeCalculator(inputs, InMemoryDailyFeatureStore())
            .compute(OutcomeMetric.STEPS_AFTER, OutcomeDecision("d1", at(10, 0), TimeZone.UTC), at(12, 0), 30)

        assertThat(value.number).isEqualTo(3_000)
        assertThat(value.lineage.sourceFamilies).containsExactly(SourceFamily.GH_API, SourceFamily.ON_DEVICE)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        // row, record steps, coverage claimed, minutes after the window end, expected state, expected steps
        "ST1 prorated record, 200, true, 60, AVAILABLE, 100",
        "ST2 no claim yet, 200, false, 60, PENDING, ",
        "ST3 no claim after 48 h, 200, false, 2880, AVAILABLE, 100",
        "ST4 claim without records is a true zero, 0, true, 60, AVAILABLE, 0",
        "ST5 nothing after 48 h, 0, false, 2880, UNAVAILABLE, ",
        "ST6 nothing before 48 h, 0, false, 2879, PENDING, ",
    )
    fun `steps after a reminder are pending until the source covers the window`(
        row: String,
        steps: Long,
        claimed: Boolean,
        minutesAfterEnd: Long,
        state: OutcomeState,
        expected: Long?,
    ) = runTest {
        // One record of [10:20, 10:40) against the window [10:00, 10:30): half of it counts.
        val inputs = inputs(
            if (steps >
                0
            ) {
                listOf(event(EventType.STEP_SAMPLE, GH_STEPS, at(10, 20), at(10, 40), StepsPayload(steps)))
            } else {
                emptyList()
            },
        )
        if (claimed) inputs.setSourceCoverage(MetricFamily.STEPS, GH_STEPS, listOf(ClosedOpenRange(at(9, 0), at(11, 0))))

        val value = OutcomeCalculator(inputs, InMemoryDailyFeatureStore())
            .compute(OutcomeMetric.STEPS_AFTER, OutcomeDecision("d1", at(10, 0), TimeZone.UTC), at(10, 30) + minutesAfterEnd.minutes, 30)

        assertWithMessage(row).that(value.state).isEqualTo(state)
        assertWithMessage(row).that(value.number).isEqualTo(expected)
    }

    @Test
    fun `a source outside the canonical policy neither covers nor counts`() = runTest {
        val inputs = inputs(stepMinutes(OTHER_STEPS, at(10, 0), 30, 100))
        inputs.setSourceCoverage(MetricFamily.STEPS, OTHER_STEPS, listOf(ClosedOpenRange(at(9, 0), at(11, 0))))
        val calculator = OutcomeCalculator(inputs, InMemoryDailyFeatureStore())
        val decision = OutcomeDecision("d1", at(10, 0), TimeZone.UTC)

        assertThat(calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(11, 30), 30).state).isEqualTo(OutcomeState.PENDING)
        assertThat(
            calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(10, 30) + 48.hours, 30).state,
        ).isEqualTo(OutcomeState.UNAVAILABLE)
    }

    @Test
    fun `screen minutes after a reminder are the union of screen-on time once the collector covered the window`() = runTest {
        val events = listOf(screen(at(20, 0), at(20, 10)), screen(at(20, 5), at(20, 12) + 30.seconds), screen(at(20, 40), at(20, 50)))
        val covered = OutcomeCalculator(inputs(events, coverage = ClosedOpenRange(at(19, 0), at(21, 0))), InMemoryDailyFeatureStore())
        val gap = OutcomeCalculator(inputs(events, coverage = ClosedOpenRange(at(19, 0), at(20, 20))), InMemoryDailyFeatureStore())
        val decision = OutcomeDecision("d1", at(20, 0), TimeZone.UTC)

        assertThat(covered.compute(OutcomeMetric.SCREEN_MINUTES_AFTER, decision, at(20, 34), 30).state).isEqualTo(OutcomeState.PENDING)
        assertThat(covered.compute(OutcomeMetric.SCREEN_MINUTES_AFTER, decision, at(20, 35), 30)).isEqualTo(
            OutcomeValue(
                "d1",
                OutcomeMetric.SCREEN_MINUTES_AFTER,
                OutcomeState.AVAILABLE,
                number = 12,
                lineage = Lineage(setOf(DataCategory.SCREEN), setOf(SourceFamily.ON_DEVICE)),
            ),
        )
        assertThat(gap.compute(OutcomeMetric.SCREEN_MINUTES_AFTER, decision, at(20, 35), 30).state).isEqualTo(OutcomeState.PENDING)
        assertThat(
            gap.compute(OutcomeMetric.SCREEN_MINUTES_AFTER, decision, at(20, 30) + 48.hours, 30).state,
        ).isEqualTo(OutcomeState.UNAVAILABLE)
    }

    @Test
    fun `app minutes count the package while the screen is on`() = runTest {
        val events = listOf(
            screen(at(20, 0), at(20, 12)),
            app("com.instagram.android", at(20, 5), at(20, 15)),
            app("com.other", at(20, 0), at(20, 30)),
        )
        val calculator = OutcomeCalculator(inputs(events, coverage = ClosedOpenRange(at(19, 0), at(21, 0))), InMemoryDailyFeatureStore())

        val value = calculator.compute(
            OutcomeMetric.APP_MINUTES_AFTER,
            OutcomeDecision("d1", at(20, 0), TimeZone.UTC),
            at(21, 0),
            30,
            mapOf(OutcomeCalculator.ARG_PACKAGE to "com.instagram.android"),
        )

        assertThat(value.number).isEqualTo(7)
        assertThat(value.lineage).isEqualTo(Lineage(setOf(DataCategory.APP_USAGE), setOf(SourceFamily.ON_DEVICE)))
    }

    @Test
    fun `category minutes are the union over the category, with user overrides`() = runTest {
        val events = listOf(
            screen(at(20, 0), at(20, 30)),
            app("com.chat.a", at(20, 0), at(20, 10), category = "social"),
            app("com.chat.b", at(20, 5), at(20, 15)),
            app("com.game", at(20, 0), at(20, 30), category = "game"),
        )
        val calculator = OutcomeCalculator(
            inputs(events, coverage = ClosedOpenRange(at(19, 0), at(21, 0))),
            InMemoryDailyFeatureStore(),
            appCategoryOverrides = mapOf("com.chat.b" to "Social"),
        )

        val value = calculator.compute(
            OutcomeMetric.APP_CATEGORY_MINUTES_AFTER,
            OutcomeDecision("d1", at(20, 0), TimeZone.UTC),
            at(21, 0),
            30,
            mapOf(OutcomeCalculator.ARG_CATEGORY to "SOCIAL"),
        )

        assertThat(value.number).isEqualTo(15)
    }

    @Test
    fun `a reminder counts as opened only within the window after delivery`() = runTest {
        val calculator = OutcomeCalculator(inputs(emptyList()), InMemoryDailyFeatureStore())
        fun decision(vararg responses: DecisionResponse) =
            OutcomeDecision("d1", at(20, 0), TimeZone.UTC, deliveredAt = at(20, 1), responses = responses.toList())
        val metric = OutcomeMetric.NOTIFICATION_OPENED

        assertThat(calculator.compute(metric, decision(DecisionResponse(ResponseKind.OPENED, at(20, 30))), at(20, 31), 60).flag).isTrue()
        assertThat(calculator.compute(metric, decision(), at(20, 31), 60).state).isEqualTo(OutcomeState.PENDING)
        assertThat(calculator.compute(metric, decision(DecisionResponse(ResponseKind.OPENED, at(21, 1))), at(21, 2), 60).flag).isFalse()
        assertThat(calculator.compute(metric, decision(DecisionResponse(ResponseKind.SNOOZED, at(20, 2))), at(21, 1), 60).flag).isFalse()
        val undelivered = calculator.compute(metric, OutcomeDecision("d2", at(20, 0), TimeZone.UTC), at(20, 1), 60)
        assertThat(undelivered.state).isEqualTo(OutcomeState.AVAILABLE)
        assertThat(undelivered.flag).isNull()
        assertThat(undelivered.lineage.categories).containsExactly(DataCategory.INTERVENTIONS)
    }

    @Test
    fun `self reports count until the notification times out`() = runTest {
        val calculator = OutcomeCalculator(inputs(emptyList()), InMemoryDailyFeatureStore())
        fun decision(vararg responses: DecisionResponse, timeout: Instant? = at(21, 0)) =
            OutcomeDecision("d1", at(20, 0), TimeZone.UTC, deliveredAt = at(20, 0), timeoutAt = timeout, responses = responses.toList())
        val metric = OutcomeMetric.SELF_REPORT_HELPFUL

        assertThat(
            calculator.compute(metric, decision(DecisionResponse(ResponseKind.HELPFUL, at(20, 30))), at(20, 31)).code,
        ).isEqualTo("HELPFUL")
        assertThat(
            calculator.compute(metric, decision(DecisionResponse(ResponseKind.NOT_HELPFUL, at(20, 30))), at(20, 31)).code,
        ).isEqualTo("NOT_HELPFUL")
        assertThat(calculator.compute(metric, decision(), at(20, 59)).state).isEqualTo(OutcomeState.PENDING)
        assertThat(calculator.compute(metric, decision(), at(21, 0)).code).isEqualTo(OutcomeCalculator.NO_ANSWER)
        assertThat(
            calculator.compute(metric, decision(DecisionResponse(ResponseKind.HELPFUL, at(21, 5))), at(21, 6)).code,
        ).isEqualTo("NONE")
        assertThat(calculator.compute(metric, decision(timeout = null), at(23, 0)).state).isEqualTo(OutcomeState.PENDING)
        assertThat(calculator.compute(metric, OutcomeDecision("d2", at(20, 0), TimeZone.UTC), at(20, 1)).code).isNull()
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        // row, sessions (source:DDThh:mm-DDThh:mm:asleep[:flag], days of October 2026 UTC), now, state, bedtime (night clock),
        // asleep minutes; the reminder is at 2026-10-05T20:00Z.
        "S1 main session, gh:05T23:10-06T07:00:420, 06T14:00, AVAILABLE, 670, 420",
        "S2 before next day 14:00, gh:05T23:10-06T07:00:420, 06T13:59, PENDING, , ",
        "S3 canonical source first, gh:05T23:10-06T07:00:420;hc:05T22:00-06T06:00:400, 06T14:00, AVAILABLE, 670, 420",
        "S4 next source, hc:05T22:00-06T06:00:400, 06T14:00, AVAILABLE, 600, 400",
        "S5 naps are skipped, gh:06T12:00-06T13:00:60:nap, 06T14:00, PENDING, , ",
        "S6 no session after 18 h and 48 h, gh:06T12:00-06T13:00:60:nap, 08T14:00, UNAVAILABLE, , ",
        "S7 starts 18 h after t0, gh:06T14:00-06T22:00:420, 08T14:00, UNAVAILABLE, , ",
        "S8 unprocessed, gh:05T23:10-06T07:00:420:unprocessed, 06T14:00, PENDING, , ",
        "S9 unprocessed after 48 h, gh:05T23:10-06T07:00:420:unprocessed, 08T14:00, AVAILABLE, 670, 420",
        "S10 main before other, gh:05T21:00-05T22:00:60:other;gh:05T23:10-06T07:00:420, 06T14:00, AVAILABLE, 670, 420",
        "S11 started before t0, gh:05T19:00-05T20:30:90;gh:05T23:10-06T07:00:420, 06T14:00, AVAILABLE, 670, 420",
        "S12 short Health Connect session is a nap, hc:05T23:00-06T01:00:120, 08T14:00, UNAVAILABLE, , ",
    )
    fun `the night after a reminder is the first main sleep that starts within 18 hours`(
        row: String,
        sessions: String,
        now: String,
        state: OutcomeState,
        bedtime: Long?,
        asleep: Long?,
    ) = runTest {
        val calculator = OutcomeCalculator(inputs(sessions.split(';').map { sleepOf(it) }), InMemoryDailyFeatureStore())
        val decision = OutcomeDecision("d1", at(20, 0), TimeZone.UTC)
        val nowAt = october(now)

        val bed = calculator.compute(OutcomeMetric.BEDTIME_NEXT, decision, nowAt)
        val minutes = calculator.compute(OutcomeMetric.SLEEP_MINUTES_NEXT, decision, nowAt)

        assertWithMessage(row).that(bed.state).isEqualTo(state)
        assertWithMessage(row).that(bed.number).isEqualTo(bedtime)
        assertWithMessage(row).that(minutes.number).isEqualTo(asleep)
        if (state == OutcomeState.AVAILABLE) assertWithMessage(row).that(bed.lineage.categories).containsExactly(DataCategory.SLEEP)
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        // row, row status (or NONE), row value, now, expected state, expected steps
        "D1 final row, FINAL, 8000, 2026-10-06T05:00, AVAILABLE, 8000",
        "D2 before next day 04:00 plus 60 min, FINAL, 8000, 2026-10-06T04:59, PENDING, ",
        "D3 provisional row, PROVISIONAL, 6000, 2026-10-06T05:00, PENDING, ",
        "D4 provisional row after 48 h, PROVISIONAL, 6000, 2026-10-08T00:00, AVAILABLE, 6000",
        "D5 missing row after 48 h, MISSING, , 2026-10-08T00:00, UNAVAILABLE, ",
        "D6 no row after 48 h, NONE, , 2026-10-08T00:00, UNAVAILABLE, ",
    )
    fun `the day total is the steps daily row of the reminder's local date`(
        row: String,
        status: String,
        rowValue: Double?,
        now: String,
        state: OutcomeState,
        expected: Long?,
    ) = runTest {
        val daily = InMemoryDailyFeatureStore()
        if (status != "NONE") {
            daily.replaceDays(
                setOf(date("2026-10-05")),
                listOf(dailyRow(date("2026-10-05"), "steps", rowValue, DailyRowStatus.valueOf(status), activity)),
            )
        }
        val value = OutcomeCalculator(inputs(emptyList()), daily)
            .compute(OutcomeMetric.STEPS_DAY_TOTAL, OutcomeDecision("d1", at(10, 0), TimeZone.UTC), Instant.parse("$now:00Z"))

        assertWithMessage(row).that(value.state).isEqualTo(state)
        assertWithMessage(row).that(value.number).isEqualTo(expected)
        if (state == OutcomeState.AVAILABLE) assertWithMessage(row).that(value.lineage).isEqualTo(activity)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        // Next-day allowances are read in the decision's zone, across DST transitions (integrator correction 3b).
        "R1, America/Los_Angeles, 2026-10-31T20:00, STEPS_DAY_TOTAL, 2026-11-01T13:00:00Z",
        "R2, Pacific/Chatham, 2026-09-26T20:00, STEPS_DAY_TOTAL, 2026-09-26T15:15:00Z",
        "R3, America/St_Johns, 2026-10-31T20:00, STEPS_DAY_TOTAL, 2026-11-01T08:30:00Z",
        "R4, Australia/Adelaide, 2026-10-03T21:00, BEDTIME_NEXT, 2026-10-04T03:30:00Z",
        "R5, Asia/Kolkata, 2026-10-05T21:00, BEDTIME_NEXT, 2026-10-06T08:30:00Z",
        "R6, Pacific/Chatham, 2026-04-04T21:00, BEDTIME_NEXT, 2026-04-05T01:15:00Z",
        "R7, UTC, 2026-10-05T21:00, STEPS_DAY_TOTAL, 2026-10-06T05:00:00Z",
    )
    fun `latency allowances end at the next local day in the user's zone`(
        row: String,
        zoneId: String,
        localT0: String,
        metric: OutcomeMetric,
        ready: String,
    ) = runTest {
        val zone = TimeZone.of(zoneId)
        val t0 = LocalDateTime.parse(localT0).toInstant(zone)
        val localDate = LocalDateTime.parse(localT0).date
        val daily = InMemoryDailyFeatureStore()
        daily.replaceDays(setOf(localDate), listOf(dailyRow(localDate, "steps", 4_321.0, lineage = activity)))
        val sleep = event(
            EventType.SLEEP_SESSION,
            GH_SLEEP,
            t0 + 2.hours,
            t0 + 10.hours,
            SleepSessionPayload(minutesAsleep = 450, processed = true),
            zone,
        )
        val calculator = OutcomeCalculator(inputs(listOf(sleep)), daily)
        val decision = OutcomeDecision("d1", t0, zone)

        for (jvmZone in listOf("America/St_Johns", "Pacific/Kiritimati")) {
            val before = withJvmDefaultZone(jvmZone) { calculator.compute(metric, decision, Instant.parse(ready) - 1.minutes) }
            val after = withJvmDefaultZone(jvmZone) { calculator.compute(metric, decision, Instant.parse(ready)) }

            assertWithMessage("$row under $jvmZone").that(before.state).isEqualTo(OutcomeState.PENDING)
            assertWithMessage("$row under $jvmZone").that(after.state).isEqualTo(OutcomeState.AVAILABLE)
        }
    }

    @Test
    fun `window lengths outside the catalog are rejected`() = runTest {
        val calculator = OutcomeCalculator(inputs(emptyList()), InMemoryDailyFeatureStore())
        val decision = OutcomeDecision("d1", at(10, 0), TimeZone.UTC)

        assertThrows<IllegalArgumentException> { calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(12, 0)) }
        assertThrows<IllegalArgumentException> { calculator.compute(OutcomeMetric.STEPS_AFTER, decision, at(12, 0), 9) }
        assertThrows<IllegalArgumentException> { calculator.compute(OutcomeMetric.SCREEN_MINUTES_AFTER, decision, at(12, 0), 121) }
        assertThrows<IllegalArgumentException> { calculator.compute(OutcomeMetric.NOTIFICATION_OPENED, decision, at(12, 0), 241) }
        assertThrows<IllegalArgumentException> { calculator.compute(OutcomeMetric.APP_MINUTES_AFTER, decision, at(12, 0), 30) }
        assertThrows<IllegalArgumentException> { calculator.compute(OutcomeMetric.APP_CATEGORY_MINUTES_AFTER, decision, at(12, 0), 30) }
        assertThat(OutcomeMetric.entries.filter { it.role == OutcomeRole.DISTAL })
            .containsExactly(OutcomeMetric.BEDTIME_NEXT, OutcomeMetric.SLEEP_MINUTES_NEXT, OutcomeMetric.STEPS_DAY_TOTAL)
    }

    private fun inputs(events: List<PersonalEvent>, coverage: ClosedOpenRange? = null): InMemoryDailyInputs = InMemoryDailyInputs(
        events = events,
        collectorCoverage = coverage?.let { mapOf(OutcomeCalculator.USAGE_COLLECTOR to listOf(it)) }.orEmpty(),
    )

    /** `gh:05T23:10-06T07:00:420[:nap|:unprocessed|:other]`: days of October 2026, UTC. */
    private fun sleepOf(spec: String): PersonalEvent {
        val source = if (spec.startsWith("gh:")) GH_SLEEP else HC_SLEEP
        val groups = requireNotNull(SLEEP_SPEC.matchEntire(spec.substringAfter(':'))) { spec }.groupValues
        val flag = groups[4]
        val payload = SleepSessionPayload(
            minutesAsleep = groups[3].toLong(),
            isMainSleep = flag != "other",
            isNap = flag == "nap",
            processed = flag != "unprocessed",
        )
        return event(EventType.SLEEP_SESSION, source, october(groups[1]), october(groups[2]), payload)
    }

    /** `06T14:00` as 2026-10-06T14:00Z. */
    private fun october(dayAndTime: String): Instant = Instant.parse("2026-10-$dayAndTime:00Z")

    private companion object {
        val GH_STEPS = DataSourceId("googlehealth.steps")
        val PHONE_STEPS = DataSourceId("android.steps")
        val OTHER_STEPS = DataSourceId("otherapp.steps")
        val GH_SLEEP = DataSourceId("googlehealth.sleep")
        val HC_SLEEP = DataSourceId("healthconnect.sleep")
        val USAGE = DataSourceId("android.usage")
        val META = EventMetadata(ingestedAt = Instant.parse("2026-01-01T00:00:00Z"))
        val SLEEP_SPEC = Regex("(\\d\\dT\\d\\d:\\d\\d)-(\\d\\dT\\d\\d:\\d\\d):(\\d+)(?::(\\w+))?")

        fun at(hour: Int, minute: Int): Instant =
            Instant.parse("2026-10-05T" + hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0') + ":00Z")

        fun event(
            type: EventType,
            source: DataSourceId,
            start: Instant,
            end: Instant?,
            payload: EventPayload,
            zone: TimeZone = TimeZone.UTC,
        ): PersonalEvent {
            val key = listOf(
                source.value,
                type.name,
                start.toEpochMilliseconds(),
                end?.toEpochMilliseconds(),
                payload.hashCode(),
            ).joinToString("|")
            return PersonalEvent(EventId(key), type, source, start, end, zone.id, payload, dedupKey = key, metadata = META)
        }

        fun stepMinutes(source: DataSourceId, start: Instant, minutes: Int, perMinute: Long): List<PersonalEvent> =
            (0 until minutes).map { i ->
                event(EventType.STEP_SAMPLE, source, start + i.minutes, start + (i + 1).minutes, StepsPayload(perMinute))
            }

        fun screen(start: Instant, end: Instant): PersonalEvent =
            event(EventType.SCREEN_SESSION, USAGE, start, end, ScreenPayload(true, (end - start).inWholeMilliseconds))

        fun app(pkg: String, start: Instant, end: Instant, category: String? = null): PersonalEvent =
            event(EventType.APP_SESSION, USAGE, start, end, AppUsagePayload(pkg, (end - start).inWholeMilliseconds, category))
    }
}

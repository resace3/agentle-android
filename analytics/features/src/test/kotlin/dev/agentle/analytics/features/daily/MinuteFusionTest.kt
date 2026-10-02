package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MinuteFusionTest {
    private val day = date("2026-09-14")
    private val calendar = range(at(day, 0), at(day.plusDays(1), 0))
    private val now = at(day.plusDays(4), 12)

    @Test
    fun `a watch and a phone counting the same walk never double the steps`() = runTest {
        val walk = at(day, 10)
        val inputs = InMemoryDailyInputs(
            Ev.stepMinutes(Src.GH_STEPS, walk, 30, 100) + Ev.stepMinutes(Src.PHONE_STEPS, walk, 30, 95),
        )

        val rows = DailyFeatureCalculator(inputs).compute(day, now)

        assertThat(rows.row("steps").value).isEqualTo(3_000.0)
        assertThat(rows.row("steps").lineage.sourceFamilies).containsExactly(SourceFamily.GH_API)
        assertThat(rows.row("active_minutes").value).isEqualTo(30.0)
    }

    @Test
    fun `the phone fills the minutes the watch was off the wrist`() = runTest {
        val walk = at(day, 10)
        val inputs = InMemoryDailyInputs(
            Ev.stepMinutes(Src.GH_STEPS, walk, 30, 100) + Ev.stepMinutes(Src.PHONE_STEPS, walk, 40, 90),
        )

        val rows = DailyFeatureCalculator(inputs).compute(day, now)

        assertThat(rows.row("steps").value).isEqualTo(30 * 100.0 + 10 * 90.0)
        assertThat(rows.row("steps").lineage.sourceFamilies).containsExactly(SourceFamily.GH_API, SourceFamily.ON_DEVICE)
        assertThat(rows.row("steps").source).isNull()
    }

    @Test
    fun `a record that touches a minute the watch owns is not used for that minute`() {
        val t = at(day, 10)
        val watch = Ev.steps(Src.GH_STEPS, t + 30.seconds, t + 90.seconds, 60)
        val phone = Ev.steps(Src.PHONE_STEPS, t, t + 60.seconds, 50)
        val later = Ev.steps(Src.PHONE_STEPS, t + 2.minutes, t + 3.minutes, 40)

        val pieces = MinuteFusion.fuse(listOf(watch, phone, later), MetricFamily.STEPS, CanonicalSourcePolicy.DEFAULT, calendar)

        assertThat(pieces.single { it.event == watch }.selected).containsExactly(range(t + 30.seconds, t + 90.seconds))
        assertThat(pieces.single { it.event == phone }.isSelected).isFalse()
        assertThat(pieces.single { it.event == later }.selected).containsExactly(range(t + 2.minutes, t + 3.minutes))
    }

    @Test
    fun `sources the policy does not list are never used`() {
        val t = at(day, 10)
        val pieces = MinuteFusion.fuse(
            listOf(Ev.steps(Src.UNLISTED, t, t + 1.minutes, 500), Ev.heartRate(Src.GH_HR, t, 70.0)),
            MetricFamily.STEPS,
            CanonicalSourcePolicy.DEFAULT,
            calendar,
        )

        assertThat(pieces).isEmpty()
    }

    @Test
    fun `heart-rate samples belong to the source that owns their minute`() {
        val t = at(day, 10)
        val gh = Ev.heartRate(Src.GH_HR, t + 10.seconds, 60.0)
        val hcSameMinute = Ev.heartRate(Src.HC_HR, t + 20.seconds, 90.0)
        val hcOtherMinute = Ev.heartRate(Src.HC_HR, t + 2.minutes, 80.0)
        val outside = Ev.heartRate(Src.HC_HR, at(day.plusDays(1), 0) + 1.minutes, 80.0)

        val pieces = MinuteFusion.fuse(
            listOf(gh, hcSameMinute, hcOtherMinute, outside),
            MetricFamily.HEART_RATE,
            CanonicalSourcePolicy.DEFAULT,
            calendar,
        )

        assertThat(pieces.filter { it.isSelected }.map { it.event }).containsExactly(gh, hcOtherMinute)
    }

    @Test
    fun `range helpers intersect and subtract sorted lists`() {
        val t = at(day, 0)
        val a = listOf(range(t, t + 4.hours))
        val b = listOf(range(t + 1.hours, t + 2.hours), range(t + 3.hours, t + 5.hours))

        assertThat(MinuteFusion.intersect(a, b)).containsExactly(range(t + 1.hours, t + 2.hours), range(t + 3.hours, t + 4.hours)).inOrder()
        assertThat(MinuteFusion.subtract(a, b)).containsExactly(range(t, t + 1.hours), range(t + 2.hours, t + 3.hours)).inOrder()
        assertThat(MinuteFusion.subtract(a, emptyList())).isEqualTo(a)
        assertThat(MinuteFusion.subtract(a, listOf(range(t, t + 6.hours)))).isEmpty()
    }

    @Test
    fun `canonical source policy ranks by connector or full source id`() {
        val policy = CanonicalSourcePolicy(mapOf(MetricFamily.STEPS to listOf("android.steps", "googlehealth")))

        assertThat(policy.rank(MetricFamily.STEPS, Src.PHONE_STEPS)).isEqualTo(0)
        assertThat(policy.rank(MetricFamily.STEPS, Src.GH_STEPS)).isEqualTo(1)
        assertThat(policy.isEligible(MetricFamily.STEPS, Src.HC_STEPS)).isFalse()
        assertThat(policy.isEligible(MetricFamily.SLEEP, Src.GH_SLEEP)).isFalse()
        assertThat(policy.choose(MetricFamily.STEPS, listOf(Src.GH_STEPS, Src.PHONE_STEPS, Src.HC_STEPS))).isEqualTo(Src.PHONE_STEPS)
        assertThat(policy.choose(MetricFamily.STEPS, listOf(Src.HC_STEPS))).isNull()
        assertThat(CanonicalSourcePolicy.DEFAULT.choose(MetricFamily.SLEEP, listOf(Src.HC_SLEEP, Src.GH_SLEEP))).isEqualTo(Src.GH_SLEEP)
    }

    @Test
    fun `overlap queries find intervals that span the window start`() = runTest {
        val start = at(day, 4)
        val spanning = Ev.screen(start - 2.hours, start + 1.hours)
        val before = Ev.screen(start - 3.hours, start - 2.hours)
        val point = Ev.unlock(start)
        val inputs = InMemoryDailyInputs(listOf(spanning, before, point, Ev.app("a.b", start, start + 1.minutes)))

        val found = inputs.events(EventQuery(setOf(EventType.SCREEN_SESSION, EventType.DEVICE_UNLOCK), range(start, start + 1.hours)))

        assertThat(found).containsExactly(spanning, point).inOrder()
        assertThat(inputs.events(EventQuery(setOf(EventType.APP_SESSION), range(start, start + 1.hours), subject = "a.b"))).hasSize(1)
        assertThat(inputs.events(EventQuery(setOf(EventType.APP_SESSION), range(start, start + 1.hours), subject = "c.d"))).isEmpty()
        assertThat(
            inputs.events(EventQuery(setOf(EventType.APP_SESSION), range(start, start + 1.hours), sources = setOf(Src.GH_STEPS))),
        ).isEmpty()
        assertThat(inputs.queryCount).isEqualTo(4)
    }

    @Test
    fun `civil-date values are read by the reported date, never by the instants`() = runTest {
        // Tokyo's 2026-09-15 starts at 2026-09-14T15:00Z: a UTC reading of the instants would say 09-14.
        val inputs = InMemoryDailyInputs(
            listOf(
                Ev.restingHr(Src.GH_RHR, date("2026-09-15"), 55.0, TOKYO),
                Ev.dailySteps(Src.GH_DAILY, date("2026-09-15"), 10_412.0, TOKYO),
            ),
        )

        assertThat(inputs.civilDateValues(MetricFamily.RESTING_HEART_RATE, date("2026-09-14"))).isEmpty()
        assertThat(inputs.civilDateValues(MetricFamily.RESTING_HEART_RATE, date("2026-09-15")).single().value).isEqualTo(55.0)
        assertThat(inputs.civilDateValues(MetricFamily.STEPS, date("2026-09-15")).single().value).isEqualTo(10_412.0)
        assertThat(inputs.civilDateValues(MetricFamily.SLEEP, date("2026-09-15"))).isEmpty()
    }

    @Test
    fun `legacy resting heart rate rows are dated in their own zone`() {
        val legacy = Ev.of(
            EventType.RESTING_HEART_RATE,
            Src.HC_RHR,
            Instant.parse("2026-09-14T16:00:00Z"),
            null,
            dev.agentle.core.model.HeartRatePayload(58.0),
            TOKYO,
        )

        assertThat(DailyInputs.civilValueOf(legacy)?.date).isEqualTo(date("2026-09-15"))
        assertThat(DailyInputs.civilValueOf(Ev.heartRate(Src.GH_HR, at(day, 10), 70.0))).isNull()
        assertThat(DailyInputs.civilValueOf(Ev.unlock(at(day, 10)))).isNull()
        assertThat(DailyInputs.subjectOf(Ev.unlock(at(day, 10)))).isNull()
    }

    @Test
    fun `removing data removes its coverage too`() = runTest {
        val inputs = InMemoryDailyInputs(listOf(Ev.unlock(at(day, 10))), collectorCoverage = mapOf(Col.UNLOCK to listOf(calendar)))
        inputs.setSourceCoverage(MetricFamily.STEPS, Src.GH_STEPS, listOf(calendar))

        inputs.remove { it.type == EventType.DEVICE_UNLOCK }
        inputs.removeCoverage(range(at(day, 6), at(day, 18)))

        assertThat(inputs.allEvents).isEmpty()
        assertThat(
            inputs.collectorCoverage(Col.UNLOCK, calendar),
        ).containsExactly(range(at(day, 0), at(day, 6)), range(at(day, 18), at(day, 24)))
        assertThat(inputs.sourceCoverage(MetricFamily.STEPS, calendar).getValue(Src.GH_STEPS)).hasSize(2)
        assertThat(InMemoryDailyInputs.stepCount(Ev.unlock(at(day, 10)))).isEqualTo(0)
    }
}

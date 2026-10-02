package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.MissingReason
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * True zero versus no data, per null semantics (docs/research/10 §5.3: absence is never zero). Each row names the
 * scenario; `now` is far past the grace period unless the row is about a running or recent window.
 */
class NullSemanticsTest {
    data class Case(
        val metric: String,
        val events: List<PersonalEvent> = emptyList(),
        val collectors: Map<String, List<ClosedOpenRange>> = emptyMap(),
        val claims: Map<Pair<MetricFamily, DataSourceId>, ClosedOpenRange> = emptyMap(),
        val now: Instant = LATER,
        val status: DailyRowStatus,
        val value: Double?,
        val reason: MissingReason? = null,
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `absence is never zero`(row: String, case: Case) = runTest {
        val inputs = InMemoryDailyInputs(case.events, collectorCoverage = case.collectors)
        case.claims.forEach { (key, claim) -> inputs.setSourceCoverage(key.first, key.second, listOf(claim)) }

        val result = DailyFeatureCalculator(inputs).compute(D, case.now).row(case.metric)

        assertWithMessage(row).that(result.status).isEqualTo(case.status)
        assertThat(result.value).isEqualTo(case.value)
        assertThat(result.missingReason).isEqualTo(case.reason)
        assertThat(result.catalogVersion).isEqualTo(DailyFeatureCatalog.VERSION)
    }

    companion object {
        private val D = date("2026-09-14")
        private val LATER = at(D.plusDays(4), 12)
        private val FULL = listOf(around(D))
        private val HALF = listOf(range(at(D, 4), at(D, 16)))

        @JvmStatic
        fun cases(): List<Arguments> = collectorCases() + sourcedCases()

        private fun collectorCases(): List<Arguments> = listOf(
            "N1 usage covered without events is a true 0" to Case(
                "screen_minutes",
                collectors = mapOf(Col.USAGE to FULL),
                status = DailyRowStatus.FINAL,
                value = 0.0,
            ),
            "N2 usage without coverage is unknown" to Case(
                "screen_minutes",
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.COLLECTOR_INACTIVE,
            ),
            "N3 half-covered usage is partial" to Case(
                "screen_minutes",
                events = listOf(Ev.screen(at(D, 10), at(D, 10, 45))),
                collectors = mapOf(Col.USAGE to HALF),
                status = DailyRowStatus.PARTIAL,
                value = 45.0,
            ),
            "N4 a running engine day is provisional" to Case(
                "screen_minutes",
                events = listOf(Ev.screen(at(D, 10), at(D, 10, 45))),
                collectors = mapOf(Col.USAGE to FULL),
                now = at(D, 12),
                status = DailyRowStatus.PROVISIONAL,
                value = 45.0,
            ),
            "N5 an engine day that has not started is not yet available" to Case(
                "screen_minutes",
                collectors = mapOf(Col.USAGE to FULL),
                now = at(D, 2),
                status = DailyRowStatus.PROVISIONAL,
                value = null,
                reason = MissingReason.NOT_YET_AVAILABLE,
            ),
            "N6 notifications covered without posts are a true 0" to Case(
                "notifications",
                collectors = mapOf(Col.NOTIFICATIONS to FULL),
                status = DailyRowStatus.FINAL,
                value = 0.0,
            ),
            "N7 unlocks without coverage are unknown" to Case(
                "unlocks",
                collectors = mapOf(Col.USAGE to FULL),
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.COLLECTOR_INACTIVE,
            ),
            "N8 steps without any record are no data" to Case(
                "steps",
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.NO_DATA,
            ),
            "N9 a true-zero step record is 0" to Case(
                "steps",
                events = listOf(Ev.steps(Src.GH_STEPS, at(D, 10), at(D, 11), 0)),
                status = DailyRowStatus.FINAL,
                value = 0.0,
            ),
            "N10 steps wait for the source to assert the whole day" to Case(
                "steps",
                events = listOf(Ev.steps(Src.GH_STEPS, at(D, 10), at(D, 11), 500)),
                claims = mapOf((MetricFamily.STEPS to Src.GH_STEPS) to range(at(D, 0), at(D, 18))),
                now = at(D.plusDays(1), 10),
                status = DailyRowStatus.PROVISIONAL,
                value = 500.0,
            ),
            "N11 steps are final once the source asserts the whole day" to Case(
                "steps",
                events = listOf(Ev.steps(Src.GH_STEPS, at(D, 10), at(D, 11), 500)),
                claims = mapOf((MetricFamily.STEPS to Src.GH_STEPS) to FULL.single()),
                now = at(D.plusDays(1), 10),
                status = DailyRowStatus.FINAL,
                value = 500.0,
            ),
        ).map { (id, case) -> Arguments.of(id, case) }

        private fun sourcedCases(): List<Arguments> = listOf(
            "N12 exercise inside source coverage without sessions is a true 0" to Case(
                "exercise_minutes",
                claims = mapOf((MetricFamily.EXERCISE to Src.GH_EXERCISE) to FULL.single()),
                status = DailyRowStatus.FINAL,
                value = 0.0,
            ),
            "N13 exercise without a connected source is unknown" to Case(
                "exercise_day",
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.SOURCE_DISCONNECTED,
            ),
            "N14 exercise not synced yet waits" to Case(
                "exercise_minutes",
                claims = mapOf((MetricFamily.EXERCISE to Src.GH_EXERCISE) to range(at(D, 0), at(D, 12))),
                now = at(D.plusDays(1), 10),
                status = DailyRowStatus.PROVISIONAL,
                value = null,
                reason = MissingReason.NOT_SYNCED,
            ),
            "N15 exercise never synced is unknown after the grace period" to Case(
                "exercise_minutes",
                claims = mapOf((MetricFamily.EXERCISE to Src.GH_EXERCISE) to range(at(D, 0), at(D, 12))),
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.NOT_SYNCED,
            ),
            "N16 a night without a main sleep is no data" to Case(
                "sleep_minutes",
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.NO_DATA,
            ),
            "N17 sleep still being processed is provisional" to Case(
                "sleep_minutes",
                events = listOf(Ev.sleep(Src.GH_SLEEP, at(D, 23), at(D.plusDays(1), 7), processed = false)),
                claims = mapOf((MetricFamily.SLEEP to Src.GH_SLEEP) to FULL.single()),
                now = at(D.plusDays(1), 15),
                status = DailyRowStatus.PROVISIONAL,
                value = 480.0,
            ),
            "N18 an implausible resting heart rate is invalid" to Case(
                "resting_hr",
                events = listOf(Ev.restingHr(Src.GH_RHR, D, 15.0)),
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.INVALID_VALUE,
            ),
            "N19 no intervention is a true 0" to Case("interventions_delivered", status = DailyRowStatus.FINAL, value = 0.0),
            "N20 interventions of a running engine day are provisional" to Case(
                "interventions_delivered",
                events = listOf(Ev.jitai(EventType.JITAI_DELIVERED, at(D, 9))),
                now = at(D, 12),
                status = DailyRowStatus.PROVISIONAL,
                value = 1.0,
            ),
            "N21 interventions before the engine day starts are not yet available" to Case(
                "interventions_opened",
                now = at(D, 3),
                status = DailyRowStatus.PROVISIONAL,
                value = null,
                reason = MissingReason.NOT_YET_AVAILABLE,
            ),
            "N22 a heart-rate day without samples is no data" to Case(
                "hr_mean",
                status = DailyRowStatus.MISSING,
                value = null,
                reason = MissingReason.NO_DATA,
            ),
            "N23 a day that has not started is not yet available for sourced features" to Case(
                "steps",
                now = at(D, 0) - 1.hours,
                status = DailyRowStatus.PROVISIONAL,
                value = null,
                reason = MissingReason.NOT_YET_AVAILABLE,
            ),
        ).map { (id, case) -> Arguments.of(id, case) }
    }
}

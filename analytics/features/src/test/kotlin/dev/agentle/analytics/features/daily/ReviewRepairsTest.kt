package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.ActivityKind
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TransitionKind
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Regression tests for the integrator's review findings R1-1 to R1-8 on the daily features. */
class ReviewRepairsTest {
    private suspend fun compute(inputs: InMemoryDailyInputs): List<DailySummaryRow> = DailyFeatureCalculator(inputs).compute(D, LATER)

    @Test
    fun `R1-1 sedentary minutes count only inside the source's covered time and the row is PARTIAL`() = runTest {
        val inputs = InMemoryDailyInputs(listOf(Ev.steps(Src.GH_STEPS, at(D, 9), at(D, 9, 1), 10)))
        inputs.setSourceCoverage(MetricFamily.STEPS, Src.GH_STEPS, listOf(range(at(D, 8), at(D, 10))))

        val rows = compute(inputs)

        assertThat(rows.row("sedentary_minutes").value).isEqualTo(119.0)
        assertThat(rows.row("sedentary_minutes").status).isEqualTo(DailyRowStatus.PARTIAL)
        assertThat(rows.row("steps").status).isEqualTo(DailyRowStatus.PARTIAL)
    }

    @Test
    fun `R1-2 overlapping records of one source take the maximum, never the sum`() = runTest {
        val inputs = InMemoryDailyInputs(
            listOf(Ev.steps(Src.HC_STEPS, at(D, 10), at(D, 10, 30), 3_000), Ev.steps(Src.HC_STEPS, at(D, 10), at(D, 10, 30), 2_900)),
        )
        inputs.setSourceCoverage(MetricFamily.STEPS, Src.HC_STEPS, listOf(around(D)))

        val rows = compute(inputs)

        assertThat(rows.row("steps").value).isEqualTo(3_000.0)
        assertThat(rows.row("steps").status).isEqualTo(DailyRowStatus.FINAL)
    }

    @Test
    fun `R1-3 concurrent recomputes are serialized`() = runTest {
        var active = 0
        var maxActive = 0
        val base = InMemoryDailyInputs()
        val slow = object : DailyInputs by base {
            override suspend fun events(query: EventQuery): List<PersonalEvent> {
                active++
                maxActive = maxOf(maxActive, active)
                delay(1)
                active--
                return base.events(query)
            }
        }
        val engine = DailyFeatureEngine(slow, InMemoryDailyFeatureStore(), TestAgentleClock(LATER, UTC))

        listOf(
            async { engine.recompute(setOf(D)).getOrThrow() },
            async { engine.recomputeRecent().getOrThrow() },
            async { engine.refresh().getOrThrow() },
        ).awaitAll()

        assertThat(maxActive).isEqualTo(1)
    }

    @Test
    fun `R1-5 charging and activity states are clipped to collector coverage`() = runTest {
        val inputs = InMemoryDailyInputs(
            listOf(
                Ev.charging(true, at(D, 10)),
                Ev.activity(ActivityKind.WALKING, TransitionKind.ENTER, at(D, 10)),
            ),
            collectorCoverage = mapOf(
                Col.BATTERY to listOf(range(at(D, 4), at(D, 12))),
                Col.ACTIVITY to listOf(range(at(D, 4), at(D, 11))),
            ),
        )

        val rows = compute(inputs)

        assertThat(rows.row("charging_minutes").value).isEqualTo(120.0)
        assertThat(rows.single { it.featureId == "activity_minutes" }.value).isEqualTo(60.0)
    }

    @Test
    fun `R1-7 a row of another catalog version is not final in a rolling window`() {
        val def = requireNotNull(DailyFeatureCatalog.rollingFeature("screen_minutes"))
        val daily = (0 until 3).associate { i ->
            val d = D.plusDays(-i)
            d to DailySummaryRow(
                date = d, metric = "screen_minutes", featureId = "screen_minutes", value = 10.0 * (i + 1), coverage = 1.0,
                status = DailyRowStatus.FINAL, lineage = SCREEN, computedAt = LATER,
                catalogVersion = if (i == 0) DailyFeatureCatalog.VERSION - 1 else DailyFeatureCatalog.VERSION,
            )
        }

        val result = RollingWindows.compute(def, 3, D, daily, LATER)

        assertThat(result.status).isEqualTo(DerivedStatus.STALE)
        assertThat(result.coveredDays).isEqualTo(2)
        assertThat(result.value).isEqualTo(25.0)
    }

    @Test
    fun `R1-8 a canonical-source choice applies only to the minutes of its validity`() = runTest {
        val phoneFirst = mapOf(MetricFamily.STEPS to listOf("android", "googlehealth"))
        val policy = CanonicalSourcePolicy(CanonicalSourcePolicy.DEFAULT.preferences, listOf(PolicyPeriod(null, at(D, 12), phoneFirst)))
        val inputs = InMemoryDailyInputs(
            listOf(
                Ev.steps(Src.PHONE_STEPS, at(D, 10), at(D, 10, 10), 100),
                Ev.steps(Src.GH_STEPS, at(D, 10), at(D, 10, 10), 200),
                Ev.steps(Src.PHONE_STEPS, at(D, 14), at(D, 14, 10), 100),
                Ev.steps(Src.GH_STEPS, at(D, 14), at(D, 14, 10), 200),
            ),
            policy = policy,
        )
        inputs.setSourceCoverage(MetricFamily.STEPS, Src.PHONE_STEPS, listOf(around(D)))
        inputs.setSourceCoverage(MetricFamily.STEPS, Src.GH_STEPS, listOf(around(D)))

        assertThat(compute(inputs).row("steps").value).isEqualTo(300.0)
        assertThat(policy.at(at(D, 11)).preferences).isEqualTo(phoneFirst)
        assertThat(policy.at(at(D, 13)).preferences).isEqualTo(CanonicalSourcePolicy.DEFAULT.preferences)
    }

    private companion object {
        val D = date("2026-09-14")
        val LATER: Instant = at(D.plusDays(4), 12)
        val SCREEN = Lineage(setOf(DataCategory.SCREEN), setOf(SourceFamily.ON_DEVICE))
    }
}

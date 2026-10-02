package dev.agentle.analytics.features.daily

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureAvailability
import dev.agentle.connectors.api.CapabilityRegistry
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EventType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DailyFeatureCatalogTest {
    @Test
    fun `the catalog covers every daily feature group of the brief`() {
        val ids = DailyFeatureCatalog.ids

        assertThat(ids).containsAtLeast(
            "screen_minutes", "screen_minutes_late_night", "app_minutes", "unlocks", "steps", "steps_by_hour", "sedentary_minutes",
            "sleep_minutes", "sleep_midpoint", "bedtime", "wake_time", "resting_hr", "hr_mean", "hr_max", "notifications",
            "app_notifications", "notifications_late_night", "charging_last_start", "charging_first_end", "place_minutes",
            "activity_minutes", "exercise_day", "is_weekend", "interventions_delivered",
        )
        assertThat(DailyFeatureCatalog.rollingFeature("sleep_regularity")?.dailyFeatureId).isEqualTo("sleep_midpoint")
        assertThat(DailyFeatureCatalog.WINDOWS).containsExactly(1, 3, 7, 14, 30, 90).inOrder()
        assertThat(DailyFeatureCatalog.VERSION).isEqualTo(1)
    }

    @Test
    fun `every source is a capability id, a connector id or agentle itself`() {
        val known = CapabilityRegistry.load().all.map { it.id }.toSet() + setOf("googlehealth", "agentle")
        val unknown = DailyFeatureCatalog.all.flatMap { it.sources + listOfNotNull(it.coverageCollector) }.filterNot { it in known }

        assertThat(unknown).isEmpty()
    }

    @Test
    fun `only the calendar feature has no data category and place minutes is the only unavailable feature`() {
        assertThat(DailyFeatureCatalog.all.filter { it.category == null }.map { it.id }).containsExactly("is_weekend")
        val unavailable = DailyFeatureCatalog.all.filterNot { it.isAvailable }
        assertThat(unavailable.map { it.id }).containsExactly("place_minutes")
        assertThat((unavailable.single().availability as FeatureAvailability.Unavailable).capabilityId).isEqualTo("location_background")
        assertThat(DailyFeatureCatalog["no_such_feature"]).isNull()
        assertThat(DailyFeatureCatalog["steps"]?.category).isEqualTo(DataCategory.ACTIVITY)
    }

    @Test
    fun `definitions reject inconsistent semantics`() {
        assertThrows<IllegalArgumentException> {
            DailyFeatureDefinition(
                "Bad-Id",
                DailyGroup.TIME,
                "count",
                "x",
                DailyWindow.CALENDAR_DAY,
                NullSemantics.ALWAYS_KNOWN,
                emptySet(),
                null,
            )
        }
        assertThrows<IllegalArgumentException> {
            DailyFeatureDefinition(
                "no_collector",
                DailyGroup.USAGE,
                "min",
                "x",
                DailyWindow.ENGINE_DAY,
                NullSemantics.COLLECTOR_COVERAGE,
                emptySet(),
                null,
            )
        }
        assertThrows<IllegalArgumentException> {
            DailyFeatureDefinition(
                "no_family",
                DailyGroup.HEART,
                "bpm",
                "x",
                DailyWindow.CALENDAR_DAY,
                NullSemantics.SOURCE_RECORDS,
                emptySet(),
                null,
            )
        }
        assertThrows<IllegalArgumentException> {
            DailyFeatureDefinition(
                "subject_rolled", DailyGroup.USAGE, "min", "x", DailyWindow.ENGINE_DAY, NullSemantics.ALWAYS_KNOWN,
                setOf(EventType.APP_SESSION), null, subject = SubjectKind.PACKAGE, rolling = RollingAggregator.MEAN,
            )
        }
    }

    @Test
    fun `rows enforce their status invariants`() {
        val base = DailySummaryRow(
            date("2026-09-14"),
            "steps",
            "steps",
            1.0,
            1.0,
            DailyRowStatus.FINAL,
            lineage = dev.agentle.core.model.Lineage.NONE,
            computedAt = kotlin.time.Instant.parse("2026-09-20T00:00:00Z"),
        )

        assertThrows<IllegalArgumentException> { base.copy(value = null) }
        assertThrows<IllegalArgumentException> { base.copy(status = DailyRowStatus.MISSING) }
        assertThrows<IllegalArgumentException> { base.copy(coverage = 1.5) }
        assertThat(base.copy(status = DailyRowStatus.PROVISIONAL, value = null).finalValue).isNull()
        assertThat(DailySummaryRow.metricKey("app_minutes", SubjectKind.PACKAGE, "a.b")).isEqualTo("app_minutes{package=a.b}")
        assertThat(DailySummaryRow.metricKey("steps", null, null)).isEqualTo("steps")
        assertThrows<IllegalArgumentException> {
            DerivedFeatureRow(
                "steps",
                7,
                date("2026-09-14"),
                null,
                DerivedStatus.OK,
                0,
                dev.agentle.core.model.Lineage.NONE,
                base.computedAt,
            )
        }
        assertThrows<IllegalArgumentException> { DailyFeatureConfig(minCollectorCoverage = 2.0) }
        assertThrows<IllegalArgumentException> { DailyFeatureConfig(recomputeDays = 0) }
        assertThrows<IllegalArgumentException> { DailyFeatureConfig(sedentaryBoutMinutes = 0) }
    }
}

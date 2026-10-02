package dev.agentle.jitai.dsl.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.analysis.RuleAnalysis
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.RuleValidator
import org.junit.jupiter.api.Test

/** Integrator correction testing-build: renaming a catalog id needs an alias table, and this is its test. */
class FeatureAliasesTest {
    @Test
    fun `the shipped alias table is consistent with the catalog`() {
        assertThat(FeatureAliases.problems()).isEmpty()
    }

    @Test
    fun `every feature id stored by catalog version 1 still resolves to a catalog id`() {
        // Renaming or removing one of these ids without an entry in FeatureAliases.ALIASES fails here.
        for (id in V1_IDS) {
            assertWithMessage(id).that(RealtimeFeatureCatalog.ids).contains(FeatureAliases.canonical(id))
        }
    }

    @Test
    fun `a stored definition with a renamed id decodes with the current id`() {
        val current = Fixtures.resource("r10/definition-13-6-2.json")
        val old = current.replace("steps_today", OLD_ID)

        val unmigrated = RuleCodec.decodeDefinition(old).getOrThrow()
        val migrated = RuleCodec.decodeDefinition(old, mapOf(OLD_ID to "steps_today")).getOrThrow()

        assertThat(RuleValidator.revalidate(unmigrated).codes).containsAtLeast(IssueCode.E010, IssueCode.E063)
        assertThat(RuleCodec.encodeDefinition(migrated)).isEqualTo(json(current).toString())
        assertThat(RuleValidator.revalidate(migrated).errors).isEmpty()
    }

    @Test
    fun `migration rewrites nested leaves and placeholders and nothing else`() {
        val tree = """{"type": "all", "of": [{"type": "not", "of": {"type": "lt", "feature": "$OLD_ID", "args": {},
            "value": 0.250, "onUnknown": null}}, {"type": "gte", "feature": "battery_pct", "args": {}, "value": 10,
            "onUnknown": null}], "name": "$OLD_ID", "body": "{{$OLD_ID}} of {{steps_last_60m}}, {$OLD_ID}"}"""

        val migrated = FeatureAliases.migrate(json(tree), mapOf(OLD_ID to "steps_today")).toString()

        assertThat(migrated).isEqualTo(
            """{"type":"all","of":[{"type":"not","of":{"type":"lt","feature":"steps_today","args":{},"value":0.250,""" +
                """"onUnknown":null}},{"type":"gte","feature":"battery_pct","args":{},"value":10,"onUnknown":null}],""" +
                """"name":"$OLD_ID","body":"{{steps_today}} of {{steps_last_60m}}, {$OLD_ID}"}""",
        )
        assertThat(FeatureAliases.migrate(json(tree))).isEqualTo(json(tree))
    }

    @Test
    fun `a stored condition tree with old ids reads with the current ids`() {
        val old = Fixtures.resource("r10/condition-4-7-2.json").replace("\"day_type\"", "\"weekday_type\"")
        val migrated = FeatureAliases.migrate(json(old), mapOf("weekday_type" to "day_type"))

        val condition = RuleCodec.decodeCondition(migrated.toString()).getOrThrow()

        assertThat(RuleAnalysis.dependencies(condition).map(FeatureRef::featureId))
            .containsExactly("day_type", "location_class", "minutes_since_last_delivery")
    }

    @Test
    fun `alias table problems name the broken entries`() {
        assertThat(FeatureAliases.problems(mapOf(OLD_ID to "steps_today"))).isEmpty()
        assertThat(FeatureAliases.problems(mapOf("steps_today" to "steps_last_60m")))
            .containsExactly("\"steps_today\" is still a catalog id")
        assertThat(FeatureAliases.problems(mapOf(OLD_ID to "steps_tomorrow"))).containsExactly("\"steps_tomorrow\" is not a catalog id")
        assertThat(FeatureAliases.problems(mapOf("a_old" to "b_old", "b_old" to "steps_today")))
            .containsExactly("\"b_old\" is not a catalog id", "\"b_old\" is itself renamed")
        assertThat(FeatureAliases.canonical(OLD_ID, mapOf(OLD_ID to "steps_today"))).isEqualTo("steps_today")
        assertThat(FeatureAliases.canonical("battery_pct")).isEqualTo("battery_pct")
    }

    private companion object {
        const val OLD_ID = "steps_count_today"

        /** The 35 ids of [RealtimeFeatureCatalog] version 1 (R10 §5.4), pinned. */
        val V1_IDS = listOf(
            "local_time", "day_of_week", "day_type", "engine_day_of_week", "charging", "battery_pct", "device_interactive",
            "dnd_active", "in_call", "headphones_connected", "screen_minutes_last_60m", "screen_minutes_since",
            "app_minutes_last_60m", "app_minutes_since", "app_category_minutes_last_60m", "app_category_minutes_since",
            "app_opens_last_60m", "foreground_app", "notifications_last_60m", "location_class", "activity_state",
            "activity_level_last_30m", "steps_today", "steps_last_60m", "steps_last_30m", "sleep_minutes_last_night",
            "bedtime_last_night", "wake_time_today", "resting_hr_today", "resting_hr_delta_vs_28d",
            "minutes_since_last_delivery", "deliveries_today", "deliveries_last_7d", "last_response", "consecutive_ignored",
        )
    }
}

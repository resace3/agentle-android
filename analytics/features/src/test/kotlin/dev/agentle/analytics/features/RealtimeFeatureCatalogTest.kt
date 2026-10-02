package dev.agentle.analytics.features

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.api.CapabilityRegistry
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class RealtimeFeatureCatalogTest {
    @Test
    fun `catalog has the 35 features of R10 section 5_4`() {
        assertThat(RealtimeFeatureCatalog.all).hasSize(35)
    }

    @Test
    fun `location_class is the only feature unavailable in v1 because background location is deferred`() {
        val unavailable = RealtimeFeatureCatalog.all.filterNot { it.isAvailable }

        assertThat(unavailable.map { it.id }).containsExactly("location_class")
        val availability = unavailable.single().availability as FeatureAvailability.Unavailable
        assertThat(availability.capabilityId).isEqualTo("location_background")
        assertThat(RealtimeFeatureCatalog.ids).containsAtLeast("screen_minutes_last_60m", "local_time", "steps_today", "last_response")
    }

    @Test
    fun `every source is a known capability id or a connector id`() {
        val known = CapabilityRegistry.load().all.map { it.id }.toSet() + "googlehealth"
        val unknown = RealtimeFeatureCatalog.all.flatMap { it.sources }.filterNot { it in known }
        assertThat(unknown).isEmpty()
    }

    @Test
    fun `only clock and calendar features have no data category`() {
        val uncategorized = RealtimeFeatureCatalog.all.filter { it.category == null }.map { it.group }.toSet()
        assertThat(uncategorized).containsExactly(FeatureGroup.TIME)
    }

    @Test
    fun `steps_today is the only monotone feature`() {
        assertThat(RealtimeFeatureCatalog.all.filter { it.monotoneNonDecreasing }.map { it.id }).containsExactly("steps_today")
    }

    @Test
    fun `intervention history features all take the jitai arg`() {
        val history = RealtimeFeatureCatalog.all.filter { it.group == FeatureGroup.HISTORY }
        assertThat(history.map { it.arg("jitai")?.kind }.toSet()).containsExactly(FeatureArgKind.JITAI_REF)
    }

    @Test
    fun `feature refs are canonical regardless of arg order`() {
        val a = FeatureRef("app_minutes_since", mapOf("package" to "com.example.app", "since" to "22:00"))
        val b = FeatureRef("app_minutes_since", mapOf("since" to "22:00", "package" to "com.example.app"))
        assertThat(a.key).isEqualTo(b.key)
        assertThat(a.key).isEqualTo("app_minutes_since{package=com.example.app,since=22:00}")
        assertThat(FeatureRef("local_time").key).isEqualTo("local_time")
    }

    @Test
    fun `night clock orders noon first and late morning last`() {
        val night = { h: Int, m: Int -> FeatureScalar.NightTimeValue(h * 60 + m).nightOrder }
        assertThat(night(12, 0)).isEqualTo(0)
        assertThat(night(23, 59)).isLessThan(night(0, 0))
        assertThat(night(0, 30)).isLessThan(night(11, 59))
    }

    @Test
    fun `feature values round trip through json for decision snapshots`() {
        val json = Json
        val values = listOf<FeatureValue>(
            FeatureValue.Known(FeatureScalar.IntValue(52), Instant.parse("2026-10-02T22:30:00Z")),
            FeatureValue.Known(FeatureScalar.DayOfWeekValue(DayOfWeek.FRIDAY), Instant.parse("2026-10-02T22:30:00Z"), Quality.PROVISIONAL),
            FeatureValue.Stale(FeatureScalar.IntValue(2100), Instant.parse("2026-10-02T21:00:00Z"), MissingReason.NOT_SYNCED),
            FeatureValue.Missing(MissingReason.NO_PERMISSION),
            FeatureValue.Known(FeatureScalar.Never, Instant.parse("2026-10-02T22:30:00Z")),
        )
        values.forEach { v ->
            assertThat(json.decodeFromString(FeatureValue.serializer(), json.encodeToString(FeatureValue.serializer(), v))).isEqualTo(v)
        }
    }
}

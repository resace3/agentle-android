package dev.agentle.analytics.features

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class FeatureSnapshotTest {
    private val at = Instant.parse("2026-10-02T21:30:00Z")
    private val screen = FeatureRef("screen_minutes_last_60m")
    private val app = FeatureRef("app_minutes_since", mapOf("package" to "com.example.social", "since" to "22:00"))

    @Test
    fun `values are looked up by canonical ref key`() {
        val snapshot = FeatureSnapshot.of(
            at,
            "Europe/Berlin",
            mapOf(
                screen to FeatureValue.Known(FeatureScalar.IntValue(50), at),
                app to FeatureValue.Missing(MissingReason.NO_PERMISSION),
            ),
        )

        assertThat(snapshot[screen]).isEqualTo(FeatureValue.Known(FeatureScalar.IntValue(50), at))
        assertThat(snapshot[FeatureRef("app_minutes_since", mapOf("since" to "22:00", "package" to "com.example.social"))])
            .isEqualTo(FeatureValue.Missing(MissingReason.NO_PERMISSION))
        assertThat(snapshot[FeatureRef("steps_today")]).isNull()
        assertThat(snapshot.catalogVersion).isEqualTo(RealtimeFeatureCatalog.VERSION)
    }

    @Test
    fun `snapshot survives a JSON round trip for decision traces`() {
        val snapshot = FeatureSnapshot.of(
            at,
            "UTC",
            mapOf(
                screen to FeatureValue.Stale(FeatureScalar.IntValue(12), at, MissingReason.COVERAGE_GAP),
                FeatureRef("local_time") to FeatureValue.Known(FeatureScalar.LocalTimeValue(1290), at),
            ),
        )
        val json = Json.encodeToString(FeatureSnapshot.serializer(), snapshot)

        assertThat(Json.decodeFromString(FeatureSnapshot.serializer(), json)).isEqualTo(snapshot)
    }
}

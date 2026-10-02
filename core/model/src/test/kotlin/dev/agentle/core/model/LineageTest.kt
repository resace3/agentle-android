package dev.agentle.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class LineageTest {
    private fun event(source: String, type: EventType) = PersonalEvent(
        id = EventId("e-$source-$type"),
        type = type,
        source = DataSourceId(source),
        startTime = Instant.parse("2026-10-01T10:00:00Z"),
        zoneId = "UTC",
        payload = NoPayload,
        dedupKey = "k-$source-$type",
        metadata = EventMetadata(ingestedAt = Instant.parse("2026-10-01T10:00:00Z")),
    )

    @Test
    fun `connectors map to their source family`() {
        assertThat(SourceFamily.of("googlehealth")).isEqualTo(SourceFamily.WEARABLE)
        assertThat(SourceFamily.of("healthconnect")).isEqualTo(SourceFamily.WEARABLE)
        assertThat(SourceFamily.of("android")).isEqualTo(SourceFamily.ANDROID)
        assertThat(SourceFamily.of("user")).isEqualTo(SourceFamily.USER)
        assertThat(SourceFamily.of("agentle")).isEqualTo(SourceFamily.AGENTLE)
        assertThat(SourceFamily.of("somethingnew")).isEqualTo(SourceFamily.OTHER)
    }

    @Test
    fun `lineage of events unions families and categories`() {
        val lineage = Lineage.of(
            listOf(event("googlehealth.sleep", EventType.SLEEP_SESSION), event("android.usage", EventType.APP_SESSION)),
        )
        assertThat(lineage.families).containsExactly(SourceFamily.WEARABLE, SourceFamily.ANDROID)
        assertThat(lineage.categories).containsExactly(DataCategory.SLEEP, DataCategory.APP_USAGE)
        assertThat(lineage.includes(SourceFamily.WEARABLE)).isTrue()
        assertThat(lineage.includes(SourceFamily.USER)).isFalse()
        assertThat(Lineage.NONE.isEmpty).isTrue()
    }
}

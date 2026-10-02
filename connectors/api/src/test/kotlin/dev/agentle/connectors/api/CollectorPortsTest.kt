package dev.agentle.connectors.api

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.NoPayload
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.PlaceClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Instant

class CollectorPortsTest {
    private val at = Instant.parse("2026-10-01T12:00:00Z")

    private fun event(i: Int) = PersonalEvent(
        id = EventId("e$i"),
        type = EventType.SCREEN_ON,
        source = DataSourceId("android.screen"),
        startTime = at,
        zoneId = "UTC",
        payload = NoPayload,
        dedupKey = "k$i",
        metadata = EventMetadata(ingestedAt = at),
    )

    @Test
    fun `a write batch holds at most 500 rows counting deletions`() {
        val full = WriteBatch(epoch = 1, events = (0 until 400).map(::event), deleteDedupKeys = (0 until 100).map { "d$it" }.toSet())

        assertThat(full.rows).isEqualTo(WriteBatch.MAX_ROWS)
        assertThrows<IllegalArgumentException> {
            WriteBatch(epoch = 1, events = (0 until 401).map(::event), deleteDedupKeys = (0 until 100).map { "d$it" }.toSet())
        }
    }

    @Test
    fun `known places validate coordinates and radius`() {
        assertThat(KnownPlace(PlaceClass.HOME, 52.5, 13.4).radiusMeters).isEqualTo(KnownPlace.DEFAULT_RADIUS_METERS)
        assertThrows<IllegalArgumentException> { KnownPlace(PlaceClass.WORK, 91.0, 0.0) }
        assertThrows<IllegalArgumentException> { KnownPlace(PlaceClass.GYM, 0.0, 0.0, radiusMeters = 0.0) }
    }

    @Test
    fun `collection settings default to the privacy preserving choices`() {
        val defaults = CollectionSettings()

        assertThat(defaults.notificationContentPackages).isEmpty()
        assertThat(defaults.notificationContentFromSmsAndDialer).isFalse()
        assertThat(defaults.preciseLocation).isFalse()
        assertThat(defaults.disabledConnectors).isEmpty()
    }
}

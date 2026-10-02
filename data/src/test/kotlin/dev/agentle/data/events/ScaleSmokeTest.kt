package dev.agentle.data.events

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.StepsPayload
import dev.agentle.data.TestDataAccess
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Scale smoke test (brief item 5): 100,000 events through the ingest path in 500-row transactions, then indexed range
 * queries and a keyset timeline page. Timings are written to `build/scale-smoke.txt` as evidence, never asserted
 * tightly (CI hosts vary); the bounds only catch a missing index (a full scan per query).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ScaleSmokeTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val data = TestDataAccess()
    private val repository = RoomEventRepository(data.access)

    @After
    fun tearDown() = data.close()

    @Suppress("ForbiddenMethodCall") // Wall-clock timing of the test itself, not app time: the injected clock is virtual here.
    private fun nanos(): Long = System.nanoTime()

    @Test
    fun `100k events insert and range queries stay indexed`() = runTest(timeout = 10.minutes) {
        val total = 100_000
        val t0 = nanos()
        (0 until total).chunked(CHUNK).forEach { chunk ->
            data.insert(
                chunk.map { i ->
                    PersonalEvent(
                        id = EventId("e$i"), type = EventType.STEP_SAMPLE, source = DataSourceId.of("android", "steps"),
                        startTime = start + i.minutes, endTime = start + (i + 1).minutes, zoneId = "America/St_Johns",
                        payload = StepsPayload(i.toLong()), dedupKey = "e$i", metadata = EventMetadata(ingestedAt = start),
                    )
                },
                start.toEpochMilliseconds(),
            )
        }
        val inserted = nanos() - t0

        val t1 = nanos()
        val day = repository.range(EventType.STEP_SAMPLE, start + 50_000.minutes, start + (50_000 + 1_440).minutes)
        val rangeNanos = nanos() - t1
        val t2 = nanos()
        val page = repository.timeline(start + total.minutes, 50)
        val pageNanos = nanos() - t2

        assertThat(data.access.read { sql.queryLong("SELECT COUNT(*) FROM event") }).isEqualTo(total.toLong())
        assertThat(day.size).isIn(1_440..1_441)
        assertThat(page.events).hasSize(50)
        File("build").mkdirs()
        File("build/scale-smoke.txt").writeText(
            "events=$total insertMs=${inserted / NANOS_PER_MS} " +
                "dayRangeMs=${rangeNanos / NANOS_PER_MS} pageMs=${pageNanos / NANOS_PER_MS}\n",
        )
        assertThat(rangeNanos / NANOS_PER_MS).isLessThan(QUERY_BOUND_MS)
        assertThat(pageNanos / NANOS_PER_MS).isLessThan(QUERY_BOUND_MS)
    }

    private companion object {
        const val CHUNK = 500
        const val NANOS_PER_MS = 1_000_000L
        const val QUERY_BOUND_MS = 2_000L
    }
}

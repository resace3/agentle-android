package dev.agentle.data.analytics

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.MissingReason
import dev.agentle.analytics.features.daily.DailyRowStatus
import dev.agentle.analytics.features.daily.DailySummaryRow
import dev.agentle.analytics.features.daily.DerivedFeatureRow
import dev.agentle.analytics.features.daily.DerivedStatus
import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.DataSourceId
import dev.agentle.core.model.EventId
import dev.agentle.core.model.EventMetadata
import dev.agentle.core.model.EventType
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.StepsPayload
import dev.agentle.data.TestDataAccess
import dev.agentle.data.ingest.FloorSnapshot
import dev.agentle.data.ingest.IngestSession
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The analytics stores (round 2 correction 3): day replacement, compare-and-clear dirty marks, and the change feed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AnalyticsStoresTest {
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val data = TestDataAccess()
    private val store = RoomDailyFeatureStore(data.access)
    private val feed = RoomEventChangeFeed(data.access)
    private val d1 = LocalDate(2026, 9, 29)
    private val d2 = LocalDate(2026, 9, 30)
    private val lineage = Lineage(categories = setOf(DataCategory.ACTIVITY))

    @After
    fun tearDown() = data.close()

    private fun row(date: LocalDate, metric: String, value: Double?, status: DailyRowStatus) = DailySummaryRow(
        date = date, metric = metric, featureId = metric, value = value, coverage = if (value == null) 0.0 else 1.0, status = status,
        missingReason = if (status == DailyRowStatus.MISSING) MissingReason.entries.first() else null,
        source = DataSourceId.of("android", "steps"), lineage = lineage, computedAt = now,
    )

    @Test
    fun `replaceDays replaces exactly the given dates and rows round-trip`() = runTest {
        store.replaceDays(
            setOf(d1, d2),
            listOf(row(d1, "steps", 10.0, DailyRowStatus.FINAL), row(d2, "steps", 5.0, DailyRowStatus.PROVISIONAL)),
        )
        store.replaceDays(setOf(d1), listOf(row(d1, "sleep", null, DailyRowStatus.MISSING)))

        val rows = store.dailyRows(d1, d2)
        assertThat(rows.map { it.date to it.metric }).containsExactly(d1 to "sleep", d2 to "steps")
        assertThat(rows.single { it.date == d1 }.missingReason).isEqualTo(MissingReason.entries.first())
        assertThat(rows.single { it.date == d2 }.lineage).isEqualTo(lineage)
        assertThat(store.provisionalDates()).containsExactly(d2)
    }

    @Test
    fun `derived rows upsert by feature, window and anchor`() = runTest {
        val first = DerivedFeatureRow("steps_mean", 7, d2, 10.0, DerivedStatus.OK, 7, lineage, now)
        store.upsertDerived(listOf(first, first.copy(windowDays = 28, value = null, status = DerivedStatus.UNKNOWN, coveredDays = 3)))
        store.upsertDerived(listOf(first.copy(value = 12.0)))

        val rows = store.derivedRows(d1, d2)
        assertThat(rows).hasSize(2)
        assertThat(rows.single { it.windowDays == 7 }.value).isEqualTo(12.0)
        assertThat(store.derivedRows(d1, d2, featureId = "other")).isEmpty()
    }

    @Test
    fun `clearDirty keeps a date that was marked again after it was read`() = runTest {
        store.markDirty(setOf(d1, d2))
        val read = store.dirtyMarks()
        store.markDirty(setOf(d2))

        store.clearDirty(read)

        assertThat(store.dirtyMarks().map { it.date }).containsExactly(d2)
    }

    @Test
    fun `the change feed lists inserts, updates and tombstones in order`() = runTest {
        fun steps(count: Long) = PersonalEvent(
            id = EventId("s"), type = EventType.STEP_SAMPLE, source = DataSourceId.of("android", "steps"), startTime = now - 5.minutes,
            endTime = now, zoneId = "America/St_Johns",
            payload = StepsPayload(
                count,
            ),
            dedupKey = "s", metadata = EventMetadata(ingestedAt = now),
        )
        data.insert(listOf(steps(1)), now.toEpochMilliseconds())
        data.insert(listOf(steps(2)), now.toEpochMilliseconds())
        data.access.write {
            val session = IngestSession(this, data.access.terms, FloorSnapshot.NONE, now.toEpochMilliseconds())
            session.deleteKeys(listOf("s"), session.accountTerm(null))
            session.finish()
        }

        val changes = feed.changesAfter(0, 10)

        assertThat(changes.last().transition).isEqualTo(EventTransition.TOMBSTONED)
        assertThat(changes.last().type).isEqualTo(EventType.STEP_SAMPLE)
        assertThat(changes.map { it.changeSeq }).isInOrder()
        assertThat(feed.changesAfter(changes.last().changeSeq, 10)).isEmpty()
    }
}

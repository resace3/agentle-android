package dev.agentle.fakes.ai

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.context.AggregateFact
import dev.agentle.ai.context.AggregateValue
import dev.agentle.ai.context.AiDataQuery
import dev.agentle.ai.context.AppUsageFact
import dev.agentle.ai.context.ItemKind
import dev.agentle.ai.context.UserTextFact
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.AiDataCategory.HEART
import dev.agentle.core.model.AiDataCategory.SLEEP
import dev.agentle.core.model.AiDataCategory.STEPS
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.SourceFamily.GH_API
import dev.agentle.core.model.SourceFamily.HEALTH_CONNECT
import dev.agentle.core.model.SourceFamily.ON_DEVICE
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.time.ClosedOpenRange
import dev.agentle.core.time.EngineDay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Test

/** The in-memory feature layer follows the port's rules (database-sync-15 and database-sync-02, round 2 correction 1). */
class InMemoryAiContextDataSourceTest {
    private val zone = TimeZone.of("Asia/Kathmandu")
    private val days = listOf(LocalDate(2026, 9, 28), LocalDate(2026, 9, 29), LocalDate(2026, 9, 30))
    private val range = ClosedOpenRange(EngineDay.bounds(days.first(), zone).start, EngineDay.bounds(LocalDate(2026, 10, 1), zone).start)

    private fun query(
        categories: Set<AiDataCategory> = setOf(STEPS, SLEEP, HEART, AiDataCategory.SCREEN_TIME_TOTALS),
        families: Set<SourceFamily> = SourceFamily.entries.toSet(),
        kinds: Set<ItemKind> = ItemKind.entries.toSet(),
        fields: Set<String>? = null,
        purpose: AiPurpose = AiPurpose.GENERAL_QUESTION,
    ): AiDataQuery = AiDataQuery(purpose, AiRequestMode.USER_INITIATED, categories, families, kinds, range, zone, fields, null)

    private fun steps(day: LocalDate, value: Double, connector: String, account: String? = null): FakeDailyRow =
        FakeDailyRow(FakeDailyMetric.STEPS, day, value, connector, account)

    private suspend fun InMemoryAiContextDataSource.facts(query: AiDataQuery = query()): Map<String, AggregateFact> =
        aggregates(query).getOrThrow().associateBy { it.field }

    private fun AggregateFact.number(): Double = (value as AggregateValue.Quantity).value

    @Test
    fun `aggregates use only rows of the active Google Health account`() = runTest {
        val data = InMemoryAiContextDataSource(activeGoogleHealthAccount = "gh-new")
        days.forEach { day ->
            data.put(steps(day, 12_000.0, ConnectorIds.GOOGLE_HEALTH, "gh-old"), steps(day, 8_000.0, ConnectorIds.GOOGLE_HEALTH, "gh-new"))
        }

        val current = data.facts().getValue("steps.daily_avg")
        assertThat(current.number()).isEqualTo(8_000.0)
        assertThat(current.lineage).isEqualTo(DataLineage(setOf(STEPS), setOf(GH_API)))

        data.activeGoogleHealthAccount = "gh-old"
        assertThat(data.facts().getValue("steps.daily_avg").number()).isEqualTo(12_000.0)

        data.activeGoogleHealthAccount = null
        assertThat(data.facts()).isEmpty()
        days.forEach { data.put(steps(it, 5_000.0, ConnectorIds.ANDROID)) }
        assertThat(data.facts().getValue("steps.daily_avg").lineage).isEqualTo(DataLineage(setOf(STEPS), setOf(ON_DEVICE)))

        data.activeGoogleHealthAccount = "gh-old"
        data.deleteAccount("gh-old")
        assertThat(data.facts().getValue("steps.daily_avg").number()).isEqualTo(5_000.0)
    }

    @Test
    fun `one source supplies each day, so a metric is never summed across sources`() = runTest {
        val data = InMemoryAiContextDataSource(activeGoogleHealthAccount = "gh")
        data.put(
            steps(days[0], 8_000.0, ConnectorIds.GOOGLE_HEALTH, "gh"),
            steps(days[0], 7_000.0, ConnectorIds.HEALTH_CONNECT),
            steps(days[0], 6_000.0, ConnectorIds.ANDROID),
            steps(days[1], 5_000.0, ConnectorIds.HEALTH_CONNECT),
            steps(days[1], 4_000.0, ConnectorIds.ANDROID),
            steps(days[2], 3_000.0, ConnectorIds.ANDROID),
            FakeDailyRow(FakeDailyMetric.SLEEP_MINUTES, days[0], 420.0, ConnectorIds.ANDROID),
        )

        val all = data.facts().getValue("steps.daily_avg")
        assertThat(all.number()).isEqualTo(5_333.0)
        assertThat(all.lineage.sources).containsExactly(GH_API, HEALTH_CONNECT, ON_DEVICE)

        val withoutWearableApi = data.facts(query(families = setOf(HEALTH_CONNECT, ON_DEVICE))).getValue("steps.daily_avg")
        assertThat(withoutWearableApi.number()).isEqualTo(5_000.0)
        assertThat(withoutWearableApi.lineage.sources).containsExactly(HEALTH_CONNECT, ON_DEVICE)

        val phoneOnly = data.facts(query(families = setOf(ON_DEVICE))).getValue("steps.daily_avg")
        assertThat(phoneOnly.number()).isEqualTo(4_333.0)
        assertThat(phoneOnly.lineage.sources).containsExactly(ON_DEVICE)

        // The phone cannot measure sleep, so its row is never used for the sleep average.
        assertThat(data.facts()).doesNotContainKey("sleep.minutes_avg")
    }

    @Test
    fun `facts stay inside the query`() = runTest {
        val data = InMemoryAiContextDataSource()
        data.put(
            steps(LocalDate(2026, 9, 27), 90_000.0, ConnectorIds.ANDROID),
            steps(days[1], 6_000.0, ConnectorIds.ANDROID),
            steps(LocalDate(2026, 10, 1), 90_000.0, ConnectorIds.ANDROID),
            FakeDailyRow(FakeDailyMetric.SLEEP_MINUTES, days[1], 410.0, ConnectorIds.HEALTH_CONNECT),
            FakeDailyRow(FakeDailyMetric.RESTING_HEART_RATE, days[1], 58.0, UNLISTED_CONNECTOR),
        )
        val apps = AppUsageFact(
            UntrustedText("Maps", TextOrigin.APP_LABEL),
            30,
            null,
            DataLineage(setOf(AiDataCategory.APP_IDENTITY, AiDataCategory.SCREEN_TIME_TOTALS), setOf(ON_DEVICE)),
        )
        val note =
            UserTextFact(
                "user.note",
                UntrustedText("Slept well", TextOrigin.USER_NOTE),
                DataLineage(setOf(AiDataCategory.USER_TEXT), setOf(ON_DEVICE)),
            )
        data.putApps(apps)
        data.putTexts(note)

        assertThat(data.facts().getValue("steps.daily_avg").number()).isEqualTo(6_000.0)
        assertThat(data.facts().keys).containsExactly("steps.daily_avg", "sleep.minutes_avg")
        assertThat(data.facts(query(categories = setOf(SLEEP))).keys).containsExactly("sleep.minutes_avg")
        assertThat(data.facts(query(fields = setOf("sleep.minutes_avg"))).keys).containsExactly("sleep.minutes_avg")
        assertThat(data.facts(query(kinds = setOf(ItemKind.CODE)))).isEmpty()
        assertThat(data.facts(query(families = setOf(GH_API)))).isEmpty()

        val everything = query(categories = AiDataCategory.entries.toSet())
        assertThat(data.appUsage(everything).getOrThrow()).containsExactly(apps)
        assertThat(data.userTexts(everything).getOrThrow()).containsExactly(note)
        assertThat(data.appUsage(query(categories = setOf(AiDataCategory.SCREEN_TIME_TOTALS))).getOrThrow()).isEmpty()
        assertThat(data.userTexts(query(categories = AiDataCategory.entries.toSet(), kinds = setOf(ItemKind.QUANTITY))).getOrThrow())
            .isEmpty()
        assertThat(data.rawEvents(everything).getOrThrow()).isEmpty()
    }

    private companion object {
        /** A connector no metric lists as a source: its rows are never used. */
        const val UNLISTED_CONNECTOR = "someconnector"
    }
}

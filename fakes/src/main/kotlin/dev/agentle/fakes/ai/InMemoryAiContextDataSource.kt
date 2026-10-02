package dev.agentle.fakes.ai

import dev.agentle.ai.context.AggregateFact
import dev.agentle.ai.context.AggregateValue
import dev.agentle.ai.context.AiContextDataSource
import dev.agentle.ai.context.AiDataQuery
import dev.agentle.ai.context.AiFieldRegistry
import dev.agentle.ai.context.AppUsageFact
import dev.agentle.ai.context.ItemKind
import dev.agentle.ai.context.RawEventFact
import dev.agentle.ai.context.UserTextFact
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.ConnectorIds
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.sourceFamilyOfConnector
import dev.agentle.core.time.EngineDay
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong

/**
 * A metric the [InMemoryAiContextDataSource] stores per day and source: the daily-average [field] it answers
 * ([AiFieldRegistry]), its [unit] and [category], and [preference], the order in which sources supply a day when several
 * have one. A source that is not in [preference] never supplies the metric.
 */
public enum class FakeDailyMetric(
    public val field: String,
    public val unit: String,
    public val category: AiDataCategory,
    public val preference: List<String>,
) {
    STEPS("steps.daily_avg", "steps", AiDataCategory.STEPS, WEARABLE_FIRST + ConnectorIds.ANDROID),
    SLEEP_MINUTES("sleep.minutes_avg", "min", AiDataCategory.SLEEP, WEARABLE_FIRST),
    RESTING_HEART_RATE("heart.resting_bpm_avg", "bpm", AiDataCategory.HEART, WEARABLE_FIRST + ConnectorIds.ANDROID),
    SCREEN_MINUTES("screen.minutes_daily_avg", "min", AiDataCategory.SCREEN_TIME_TOTALS, listOf(ConnectorIds.ANDROID)),
}

/** The wearable's own account first, then Health Connect (UNVERIFIED product choice: no source order is documented). */
private val WEARABLE_FIRST: List<String> = listOf(ConnectorIds.GOOGLE_HEALTH, ConnectorIds.HEALTH_CONNECT)

/**
 * One stored daily value: [connectorId] wrote [value] of [metric] for the local [day]. [accountId] names the Google
 * Health account of a `googlehealth` row.
 */
public data class FakeDailyRow(
    val metric: FakeDailyMetric,
    val day: LocalDate,
    val value: Double,
    val connectorId: String,
    val accountId: String? = null,
)

/**
 * The in-memory feature layer of the fake flavor and of tests. It follows every rule of [AiContextDataSource]:
 * 1. Aggregates use only rows of the active Google Health account ([activeGoogleHealthAccount]). Rows of any other
 *    account are never used, even before they are deleted; with no active account no `googlehealth` row is used.
 * 2. A metric is never summed across sources. For each metric and local day, the first source of the metric's
 *    [FakeDailyMetric.preference] that has a row, and whose family the query allows, supplies the day. The average is
 *    taken over days, never over sources.
 * 3. A fact's lineage is the metric's category plus the family of every source that supplied a day.
 * 4. Facts stay inside the query: categories, source families, kinds, fields, purpose and range (engine days of the
 *    query's zone, never the JVM default). Stored app usage and user texts are returned when the query allows them.
 *    It holds no individual events.
 */
public class InMemoryAiContextDataSource(activeGoogleHealthAccount: String? = null) : AiContextDataSource {
    /** The Google Health account currently connected; null when none is. */
    @Volatile
    public var activeGoogleHealthAccount: String? = activeGoogleHealthAccount

    private val lock = Any()
    private val rows = mutableListOf<FakeDailyRow>()
    private val apps = mutableListOf<AppUsageFact>()
    private val texts = mutableListOf<UserTextFact>()

    public fun put(vararg rows: FakeDailyRow) {
        synchronized(lock) { this.rows += rows }
    }

    public fun putApps(vararg apps: AppUsageFact) {
        synchronized(lock) { this.apps += apps }
    }

    public fun putTexts(vararg texts: UserTextFact) {
        synchronized(lock) { this.texts += texts }
    }

    /** Removes every row of the Google Health account [accountId], as the deletion after a disconnect eventually does. */
    public fun deleteAccount(accountId: String) {
        synchronized(lock) { rows.removeAll { it.accountId == accountId } }
    }

    override suspend fun aggregates(query: AiDataQuery): Outcome<List<AggregateFact>> {
        val account = activeGoogleHealthAccount
        val stored = synchronized(lock) { rows.toList() }
        return Outcome.Success(FakeDailyMetric.entries.mapNotNull { metric -> aggregate(metric, stored, query, account) })
    }

    override suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>> {
        val stored = synchronized(lock) { apps.toList() }
        return Outcome.Success(stored.filter { inside(query, it.field, ItemKind.APP_USAGE, it.lineage) })
    }

    override suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>> {
        val stored = synchronized(lock) { texts.toList() }
        return Outcome.Success(stored.filter { inside(query, it.field, ItemKind.TEXT, it.lineage) })
    }

    override suspend fun rawEvents(query: AiDataQuery): Outcome<List<RawEventFact>> = Outcome.Success(emptyList())

    private fun aggregate(metric: FakeDailyMetric, stored: List<FakeDailyRow>, query: AiDataQuery, account: String?): AggregateFact? {
        if (!usable(query, metric.field, ItemKind.QUANTITY) || metric.category !in query.categories) return null
        val supplied = stored
            .filter { row -> row.metric == metric && row.connectorId in metric.preference && fromActiveAccount(row, account) }
            .filter { row -> familyOf(row) in query.sourceFamilies }
            .filter { row -> query.range?.let { EngineDay.bounds(row.day, query.zone).start in it } ?: true }
            .groupBy { it.day }
            .values
            .map { sameDay -> metric.preference.firstNotNullOf { connector -> sameDay.firstOrNull { it.connectorId == connector } } }
        if (supplied.isEmpty()) return null
        val average = supplied.sumOf { it.value } / supplied.size
        val lineage = DataLineage(setOf(metric.category), supplied.mapNotNullTo(sortedSetOf()) { familyOf(it) })
        return AggregateFact(metric.field, AggregateValue.Quantity(average.roundToLong().toDouble(), metric.unit), lineage)
    }

    private fun fromActiveAccount(row: FakeDailyRow, account: String?): Boolean =
        row.connectorId != ConnectorIds.GOOGLE_HEALTH || (account != null && row.accountId == account)

    private fun familyOf(row: FakeDailyRow): SourceFamily? = sourceFamilyOfConnector(row.connectorId)

    private fun inside(query: AiDataQuery, field: String, kind: ItemKind, lineage: DataLineage): Boolean =
        usable(query, field, kind) && query.categories.containsAll(lineage.categories) && query.sourceFamilies.containsAll(lineage.sources)

    /** A registered field of [kind] the query asks for and its purpose may use. */
    private fun usable(query: AiDataQuery, field: String, kind: ItemKind): Boolean {
        val registered = AiFieldRegistry[field] ?: return false
        return registered.kind == kind &&
            kind in query.kinds &&
            registered.purposes?.contains(query.purpose) != false &&
            query.fields?.contains(field) != false
    }
}

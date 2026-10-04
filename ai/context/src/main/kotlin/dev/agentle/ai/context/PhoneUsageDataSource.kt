package dev.agentle.ai.context

import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A screen-on period, an unlock and a foreground app session as the event store holds them. */
public data class ScreenSession(val start: Instant, val durationMs: Long)

/** One foreground session of an app; [label] is the app's launcher label. */
public data class AppSession(val label: String, val durationMs: Long)

/** Reads the on-device phone-usage events in a range (the app implements it over the event store). */
public interface PhoneUsageLoader {
    public suspend fun screenSessions(range: ClosedOpenRange): List<ScreenSession>

    public suspend fun unlocks(range: ClosedOpenRange): List<Instant>

    public suspend fun appSessions(range: ClosedOpenRange): List<AppSession>
}

/**
 * The [AiContextDataSource] for phone usage: daily screen time, today's screen time and daily unlocks, plus the most
 * used apps. Everything is on-device data, so each fact carries [SourceFamily.ON_DEVICE]; values stay within the query's
 * categories, fields and range, and averages are over the local days that have data (never a guessed zero).
 */
public class PhoneUsageDataSource(private val loader: PhoneUsageLoader, private val now: () -> Instant) : AiContextDataSource {
    override suspend fun aggregates(query: AiDataQuery): Outcome<List<AggregateFact>> {
        val range = query.range
        if (range == null || !onDevice(query) || AiDataCategory.SCREEN_TIME_TOTALS !in query.categories) return Outcome.Success(emptyList())
        val sessions = loader.screenSessions(range)
        val unlocks = loader.unlocks(range)
        val today = now().toLocalDateTime(query.zone).date
        val facts = buildList {
            if (sessions.isNotEmpty()) {
                val byDay = sessions.groupBy { day(it.start, query.zone) }.mapValues { (_, list) ->
                    list.sumOf { it.durationMs } /
                        MS_PER_MINUTE
                }
                add(quantity("screen.minutes_daily_avg", byDay.values.average(), MINUTES))
                add(quantity("screen.minutes_today", (byDay[today] ?: 0.0), MINUTES))
            }
            if (unlocks.isNotEmpty()) {
                val byDay = unlocks.groupBy { day(it, query.zone) }.mapValues { it.value.size.toDouble() }
                add(quantity("screen.unlocks_daily_avg", byDay.values.average(), COUNT))
            }
        }.filter { query.fields == null || it.field in query.fields }
        return Outcome.Success(facts)
    }

    override suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>> {
        val range = query.range
        val allowed = range != null && onDevice(query) && APPS.categories.all { it in query.categories } &&
            ItemKind.APP_USAGE in query.kinds && (query.fields == null || AppUsageFact.APP_USAGE_FIELD in query.fields)
        if (!allowed) return Outcome.Success(emptyList())
        val facts = loader.appSessions(range)
            .filter { it.label.isNotBlank() }
            .groupBy { it.label }
            .map { (label, list) -> label to list.sumOf { it.durationMs } / MS_PER_MINUTE.toLong() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_APPS)
            .map { (label, minutes) -> AppUsageFact(UntrustedText(label, TextOrigin.APP_LABEL), minutes, null, APPS) }
        return Outcome.Success(facts)
    }

    override suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>> = Outcome.Success(emptyList())

    override suspend fun rawEvents(query: AiDataQuery): Outcome<List<RawEventFact>> = Outcome.Success(emptyList())

    private fun onDevice(query: AiDataQuery) = SourceFamily.ON_DEVICE in query.sourceFamilies

    private fun day(at: Instant, zone: TimeZone) = at.toLocalDateTime(zone).date

    private fun quantity(field: String, value: Double, unit: String) =
        AggregateFact(field, AggregateValue.Quantity(Math.round(value * 10) / 10.0, unit), SCREEN)

    private companion object {
        const val MS_PER_MINUTE = 60_000.0
        const val MINUTES = "min"
        const val COUNT = "count"
        const val MAX_APPS = 10
        val ON_DEVICE = setOf(SourceFamily.ON_DEVICE)
        val SCREEN = DataLineage(setOf(AiDataCategory.SCREEN_TIME_TOTALS), ON_DEVICE)
        val APPS = DataLineage(setOf(AiDataCategory.APP_IDENTITY, AiDataCategory.SCREEN_TIME_TOTALS), ON_DEVICE)
    }
}

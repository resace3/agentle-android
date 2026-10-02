package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.EventType
import dev.agentle.core.model.SourceFamily
import dev.agentle.core.model.UntrustedText
import dev.agentle.core.time.ClosedOpenRange
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * What the [ContextSelectionEngine] asks the feature layer for. Every value it gets back must be a field usable for
 * [purpose] ([AiFieldRegistry.fieldsFor]) and stay within [categories], [sourceFamilies], [kinds] and [range], and within
 * [fields] when that is not null (background requests). Anything outside makes the whole request fail closed. Days are
 * local days of [zone], the clock's zone, never the JVM default.
 * [subject] is an optional app code chosen by the caller (for example the JITAI category of pooled reminder text). It
 * selects data and is never sent.
 */
public data class AiDataQuery(
    val purpose: AiPurpose,
    val mode: AiRequestMode,
    val categories: Set<AiDataCategory>,
    val sourceFamilies: Set<SourceFamily>,
    val kinds: Set<ItemKind>,
    val range: ClosedOpenRange?,
    val zone: TimeZone,
    val fields: Set<String>?,
    val subject: String?,
)

/** An aggregate value. Aggregates hold numbers, times and codes, never free text. */
public sealed interface AggregateValue {
    /** A number with its unit code; for fields with a fixed unit (see [AiFieldRegistry]) it must be that unit. */
    public data class Quantity(val value: Double, val unit: String) : AggregateValue

    /** A local time of day (hours and minutes are sent). */
    public data class TimeOfDay(val time: LocalTime) : AggregateValue

    /** An upper-case code from a closed set (`STABLE`, `WEEKEND`, `PHYSICAL_ACTIVITY`). */
    public data class Code(val code: String) : AggregateValue
}

/** One aggregate for [field] (an [AiFieldRegistry] code) and the [lineage] of everything it was computed from. */
public data class AggregateFact(val field: String, val value: AggregateValue, val lineage: DataLineage)

/** Use of one app: its launcher label as an [UntrustedText] of origin `APP_LABEL`, minutes and opens over the range. */
public data class AppUsageFact(val label: UntrustedText, val minutes: Long, val opens: Long?, val lineage: DataLineage) {
    public val field: String get() = APP_USAGE_FIELD

    public companion object {
        public const val APP_USAGE_FIELD: String = "apps.usage"
    }
}

/** The user's own text for [field] (`user.note`, `goal.text`): origin `USER_NOTE` or `USER_GOAL`. */
public data class UserTextFact(val field: String, val text: UntrustedText, val lineage: DataLineage)

/**
 * One stored event. It has no text at all: only its type, local start and end, and named numbers ([values] keys are
 * app-defined lower camel or snake case codes). The engine computes its lineage from [type] and [connectorId]
 * ([AiLineageTables.forEvent]) and adds [lineage].
 */
public data class RawEventFact(
    val type: EventType,
    val start: Instant,
    val end: Instant?,
    val values: Map<String, Double>,
    val connectorId: String,
    val lineage: DataLineage = DataLineage.NONE,
)

/**
 * The port from the feature layer to the [ContextSelectionEngine] (aggregates by default; raw events only when the
 * engine asks after a user confirmation).
 *
 * Rules every implementation follows (database-sync-15 and database-sync-02):
 * 1. Aggregates are computed only from the rows of the active Google Health account. Rows of an account that was
 *    disconnected or replaced are never used, even before they are deleted.
 * 2. A metric is never summed across sources. For each metric and local day, one source supplies the value (the
 *    preferred source that has data), and a daily total from a source is never added to interval samples of the same
 *    metric.
 * 3. Each fact's lineage names every AI category and source family it was derived from ([AiLineageTables]). A fact
 *    outside the query fails the whole request rather than being filtered.
 * 4. Facts come in the order of their relevance, because the engine keeps the first 20 per block.
 *
 * Expected failures are returned as [Outcome.Failure]. A throwing implementation also fails the request.
 */
public interface AiContextDataSource {
    public suspend fun aggregates(query: AiDataQuery): Outcome<List<AggregateFact>>

    /** Per-app usage. Asked only when the query allows [AiDataCategory.APP_IDENTITY] and the request is user-initiated. */
    public suspend fun appUsage(query: AiDataQuery): Outcome<List<AppUsageFact>>

    /** The user's notes and goals. Asked only when the query allows USER_TEXT or GOALS and the request is user-initiated. */
    public suspend fun userTexts(query: AiDataQuery): Outcome<List<UserTextFact>>

    /** Individual events. Asked only after the user confirmed raw events for this one request. */
    public suspend fun rawEvents(query: AiDataQuery): Outcome<List<RawEventFact>>
}

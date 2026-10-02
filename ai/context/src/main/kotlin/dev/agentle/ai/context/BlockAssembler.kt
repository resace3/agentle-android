@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.context

import dev.agentle.ai.api.AiEnvelopeConstruction
import dev.agentle.ai.api.BlockKind
import dev.agentle.ai.api.ContextBlock
import dev.agentle.ai.api.ContextItem
import dev.agentle.ai.api.DataItem
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.DataLineage
import dev.agentle.core.model.TextOrigin
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The lineage the gate checks for a value of [field] whose producer claimed [claimed]. It is the claim plus the
 * field's floor. It is UNKNOWN when the field is not registered, when the claim names no category, or when the claim
 * names a source family the field cannot come from.
 */
internal fun effectiveLineage(field: AiField?, claimed: DataLineage): DataLineage {
    val normalized = claimed.normalized()
    return if (field == null || normalized.categories.isEmpty() || !field.sources.containsAll(normalized.sources)) {
        DataLineage.UNKNOWN
    } else {
        DataLineage(normalized.categories + field.categories, normalized.sources)
    }
}

/**
 * Turns collected facts into quoted blocks. It refuses (fails closed) text of a third-party origin, drops AI-written
 * text unless the purpose accepts it, drops malformed values and keeps the first [EnvelopeGate.MAX_ITEMS] items of a
 * block and the first [EnvelopeGate.MAX_BLOCKS] blocks. Local times use [zone], the clock's zone.
 */
internal class BlockAssembler(private val spec: PurposeSpec, private val zone: TimeZone) {
    private class Group(val label: String, val category: AiDataCategory?, val kind: BlockKind) {
        val items = mutableListOf<ContextItem>()
    }

    private val groups = LinkedHashMap<Pair<String, BlockKind>, Group>()

    /** Values left out (malformed, empty after reduction, AI-written or over a limit). */
    var dropped: Int = 0
        private set

    fun blocks(facts: CollectedFacts): Outcome<List<ContextBlock>> {
        facts.aggregates.forEach(::addAggregate)
        val refused = sortedSetOf<AiDataCategory>()
        var thirdParty = false
        (facts.apps.map(::addApp) + facts.texts.map(::addText)).filterNotNull().forEach {
            thirdParty = true
            refused += it
        }
        facts.raw.forEach(::addEvent)
        if (thirdParty) return Outcome.Failure(AppError.ConsentViolation(refused.namesSorted(), GateCodes.THIRD_PARTY))
        val all = groups.values.toList()
        dropped += all.drop(EnvelopeGate.MAX_BLOCKS).sumOf { it.items.size }
        return Outcome.Success(
            all.take(EnvelopeGate.MAX_BLOCKS).map { group ->
                dropped += (group.items.size - EnvelopeGate.MAX_ITEMS).coerceAtLeast(0)
                val items = group.items.take(EnvelopeGate.MAX_ITEMS)
                val category = group.category ?: items.flatMap { it.lineage.categories }.minOrNull() ?: AiDataCategory.SETTINGS
                ContextBlock(group.label, category, group.kind, items)
            },
        )
    }

    private fun add(label: String, category: AiDataCategory?, kind: BlockKind, item: ContextItem) {
        groups.getOrPut(label to kind) { Group(label, category, kind) }.items += item
    }

    private fun addAggregate(fact: AggregateFact) {
        val field = AiFieldRegistry[fact.field]
        val item = aggregateItem(fact, field)
        if (item == null) {
            dropped++
        } else {
            add(
                field?.group ?: UNKNOWN_GROUP,
                field?.primary,
                BlockKind.AGGREGATES,
                ContextItem(item, effectiveLineage(field, fact.lineage)),
            )
        }
    }

    private fun aggregateItem(fact: AggregateFact, field: AiField?): DataItem? = when (val value = fact.value) {
        is AggregateValue.Quantity -> DataItem.Quantity(fact.field, value.value, value.unit).takeIf {
            value.value.isFinite() && EnvelopeGate.UNIT.matches(value.unit) && (field?.unit == null || field.unit == value.unit)
        }

        is AggregateValue.TimeOfDay -> DataItem.TimeOfDay(fact.field, LocalTime(value.time.hour, value.time.minute).toString())

        is AggregateValue.Code -> DataItem.Code(fact.field, value.code).takeIf {
            EnvelopeGate.CODE.matches(value.code) && field?.codes?.contains(value.code) != false
        }
    }

    /** Adds one app; returns the categories to refuse when the label is not an app label. */
    private fun addApp(fact: AppUsageFact): Set<AiDataCategory>? {
        if (fact.label.origin != TextOrigin.APP_LABEL) return refusedFor(fact.label.origin)
        val label = SafeText.appLabel(fact.label.raw)
        val usable =
            !(fact.label.aiGenerated && !spec.acceptsAiGenerated) && label.isNotEmpty() && fact.minutes >= 0 && (fact.opens ?: 0) >= 0
        if (usable) {
            val field = AiFieldRegistry[AppUsageFact.APP_USAGE_FIELD]
            val item = DataItem.AppUsage(AppUsageFact.APP_USAGE_FIELD, label, fact.minutes, fact.opens)
            add(APP_USAGE_GROUP, AiDataCategory.APP_IDENTITY, BlockKind.APP_USAGE, ContextItem(item, effectiveLineage(field, fact.lineage)))
        } else {
            dropped++
        }
        return null
    }

    /** Adds one text of the user; returns the categories to refuse when its origin is a third party. */
    private fun addText(fact: UserTextFact): Set<AiDataCategory>? {
        val origin = fact.text.origin
        if (origin.thirdParty) return refusedFor(origin)
        val text = SafeText.reduce(fact.text.raw, SafeText.ITEM_MAX)
        if ((fact.text.aiGenerated && !spec.acceptsAiGenerated) || text.isEmpty()) {
            dropped++
        } else {
            val field = AiFieldRegistry[fact.field]
            val label = fact.field.replace('.', '_').takeIf { field != null } ?: UNKNOWN_GROUP
            add(
                label,
                field?.primary,
                BlockKind.USER_TEXT,
                ContextItem(DataItem.Text(fact.field, text), effectiveLineage(field, fact.lineage)),
            )
        }
        return null
    }

    private fun addEvent(fact: RawEventFact) {
        val values = fact.values.toSortedMap()
        val wellFormed = values.size <= MAX_EVENT_VALUES &&
            values.all { (key, value) -> EnvelopeGate.EVENT_KEY.matches(key) && value.isFinite() } &&
            (fact.end == null || fact.end >= fact.start)
        if (!wellFormed) {
            dropped++
            return
        }
        val lineage = AiLineageTables.forEvent(fact.type, fact.connectorId) + fact.lineage.normalized()
        val category = lineage.categories.min()
        val item = DataItem.Event(AiFieldRegistry.EVENT_FIELD, fact.type.name, local(fact.start), fact.end?.let(::local), values)
        add("events_" + category.name.lowercase(), category, BlockKind.RAW_EVENTS, ContextItem(item, lineage))
    }

    private fun local(instant: Instant): String {
        val time = instant.toLocalDateTime(zone)
        return LocalDateTime(time.date, LocalTime(time.hour, time.minute)).toString()
    }

    private fun refusedFor(origin: TextOrigin): Set<AiDataCategory> = when (origin) {
        TextOrigin.NOTIFICATION -> setOf(AiDataCategory.NOTIFICATION_TEXT)
        TextOrigin.CALENDAR -> setOf(AiDataCategory.CALENDAR_TEXT)
        else -> emptySet()
    }

    private companion object {
        const val UNKNOWN_GROUP = "unknown"
        const val APP_USAGE_GROUP = "app_usage"
        const val MAX_EVENT_VALUES = 20
    }
}

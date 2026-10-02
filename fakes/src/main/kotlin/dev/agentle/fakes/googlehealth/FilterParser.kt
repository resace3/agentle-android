package dev.agentle.fakes.googlehealth

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

/** One parsed `<type>.<member> <op> "<literal>"` restriction. */
internal data class FilterRestriction(
    val typeName: String,
    val member: String,
    val op: String,
    val literal: String,
    val kind: LiteralKind?,
) {
    private val instant: Instant? by lazy { if (kind == LiteralKind.PHYSICAL) FilterLiterals.instant(literal) else null }
    private val civil: LocalDateTime? by lazy { if (kind == LiteralKind.CIVIL) FilterLiterals.civil(literal) else null }
    private val date: LocalDate? by lazy { if (kind == LiteralKind.DATE) FilterLiterals.date(literal) else null }

    @Suppress("ReturnCount")
    fun matches(point: FakePoint): Boolean {
        val endMember = member.endsWith("end_time")
        val cmp: Int = when (kind) {
            LiteralKind.PHYSICAL -> (if (endMember) point.end else point.start).compareTo(instant ?: return false)

            LiteralKind.CIVIL -> {
                val local = if (endMember) {
                    PointJson.localTime(point.end, point.endOffsetSeconds)
                } else {
                    PointJson.localTime(point.start, point.startOffsetSeconds)
                }
                local.compareTo(civil ?: return false)
            }

            LiteralKind.DATE -> (point.date ?: return false).compareTo(date ?: return false)

            null -> return false
        }
        return if (op == ">=") cmp >= 0 else cmp < 0
    }

    fun isLower(): Boolean = op == ">="

    /** Compares two literals of the same kind; null when they cannot be compared. */
    fun compareLiteral(other: FilterRestriction): Int? = when {
        kind != other.kind -> null
        kind == LiteralKind.PHYSICAL -> instant?.let { a -> other.instant?.let { a.compareTo(it) } }
        kind == LiteralKind.CIVIL -> civil?.let { a -> other.civil?.let { a.compareTo(it) } }
        kind == LiteralKind.DATE -> date?.let { a -> other.date?.let { a.compareTo(it) } }
        else -> null
    }
}

/** A filter in disjunctive normal form; an empty filter matches everything. */
internal class ParsedFilter(val disjuncts: List<List<FilterRestriction>>) {
    fun matches(point: FakePoint): Boolean = disjuncts.isEmpty() || disjuncts.any { all -> all.all { it.matches(point) } }

    companion object {
        val NONE = ParsedFilter(emptyList())
    }
}

internal sealed interface FilterResult {
    data class Ok(val filter: ParsedFilter) : FilterResult

    /** E400-FILTER-A/-B with this `detailedReasons` value (docs/research/05 §8.3 V2). */
    data class Rejected(val reason: String) : FilterResult
}

internal object FilterLiterals {
    private val CIVIL = Regex("""^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2})?$""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val PHYSICAL = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$""")

    /** Every member the API documents, with its literal kind (§5.1). */
    val KNOWN_MEMBERS: Map<String, LiteralKind> = mapOf(
        "interval.start_time" to LiteralKind.PHYSICAL,
        "interval.end_time" to LiteralKind.PHYSICAL,
        "interval.civil_start_time" to LiteralKind.CIVIL,
        "interval.civil_end_time" to LiteralKind.CIVIL,
        "sample_time.physical_time" to LiteralKind.PHYSICAL,
        "sample_time.civil_time" to LiteralKind.CIVIL,
        "date" to LiteralKind.DATE,
    )

    fun instant(s: String): Instant? = if (PHYSICAL.matches(s)) runCatching { Instant.parse(s) }.getOrNull() else null

    fun civil(s: String): LocalDateTime? {
        if (!CIVIL.matches(s)) return null
        return runCatching { if (s.length == DATE_LENGTH) LocalDateTime.parse(s + "T00:00:00") else LocalDateTime.parse(s) }.getOrNull()
    }

    fun date(s: String): LocalDate? = if (DATE.matches(s)) runCatching { LocalDate.parse(s) }.getOrNull() else null

    fun valid(kind: LiteralKind?, literal: String): Boolean = when (kind) {
        LiteralKind.PHYSICAL -> instant(literal) != null
        LiteralKind.CIVIL -> civil(literal) != null
        LiteralKind.DATE -> date(literal) != null
        null -> instant(literal) != null || civil(literal) != null
    }

    private const val DATE_LENGTH = 10
}

/** The V2 filter rules of docs/research/05 §8.3, checked in the documented order. */
internal object FilterParser {
    const val STRUCTURE = "INVALID_DATA_POINT_FILTER_EXPRESSION_STRUCTURE"
    const val INVALID = "INVALID_DATA_POINT_FILTER"
    const val COMPARATOR = "INVALID_DATA_POINT_FILTER_RESTRICTION_COMPARATOR"
    const val MISMATCH = "INVALID_DATA_POINT_FILTER_COLLECTION_MISMATCH"
    const val MEMBER = "INVALID_DATA_POINT_FILTER_DATA_TYPE_MEMBER"
    const val MIXED = "INVALID_DATA_POINT_FILTER_MIXED_TIME_RESTRICTIONS"
    const val TIME_RANGE = "INVALID_TIME_RANGE"

    private val RESTRICTION = Regex("""^([A-Za-z0-9_\-]+)\.([A-Za-z0-9_.]+)\s*(>=|<=|!=|=|>|<|:)\s*"([^"]*)"$""")

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun parse(filter: String?, type: GhDataType, config: FakeGoogleHealthConfig): FilterResult {
        if (filter.isNullOrBlank()) return FilterResult.Ok(ParsedFilter.NONE)
        val sleepOr = type == GhDataTypes.SLEEP && config.sleepFilter == SleepFilterMode.DOCUMENTED
        // 1. Disjunctions are only documented for the sleep end-time members.
        if (" OR " in filter && !sleepOr) return FilterResult.Rejected(STRUCTURE)
        val disjuncts = filter.split(" OR ").map { part -> part.split(" AND ").map { it.trim() } }
        // 2. Every restriction parses and its literal is well formed.
        val parsed = disjuncts.map { conj ->
            conj.map { text ->
                val m = RESTRICTION.matchEntire(text) ?: return FilterResult.Rejected(INVALID)
                val typeName = m.groupValues[1]
                val member = m.groupValues[2]
                val op = m.groupValues[3]
                val literal = m.groupValues[4]
                val kind = type.members[member] ?: FilterLiterals.KNOWN_MEMBERS[member]
                if (!FilterLiterals.valid(kind, literal)) return FilterResult.Rejected(INVALID)
                FilterRestriction(typeName, member, op, literal, kind)
            }
        }
        val all = parsed.flatten()
        // 3. Only >= and <.
        if (all.any { it.op != ">=" && it.op != "<" }) return FilterResult.Rejected(COMPARATOR)
        // 4. Hyphenated type names.
        if (all.any { '-' in it.typeName }) return FilterResult.Rejected(INVALID)
        // 5. The type must be the path's type.
        if (all.any { it.typeName != type.snake }) return FilterResult.Rejected(MISMATCH)
        // 6. The member must exist for the type and not be rejected by a knob.
        val sleepRejected = type == GhDataTypes.SLEEP && config.sleepFilter == SleepFilterMode.REJECT_ALL
        if (sleepRejected || all.any { it.member !in type.members || it.member in config.rejectFilterMembers }) {
            return FilterResult.Rejected(MEMBER)
        }
        // 7. No mix of physical and civil bounds.
        val kinds = all.mapNotNull { it.kind }.toSet()
        if (LiteralKind.PHYSICAL in kinds && LiteralKind.CIVIL in kinds) return FilterResult.Rejected(MIXED)
        // 8. Upper bound strictly after the lower bound.
        for (conj in parsed) {
            for (lower in conj.filter { it.isLower() }) {
                for (upper in conj.filterNot { it.isLower() }) {
                    val cmp = upper.compareLiteral(lower)
                    if (cmp != null && cmp <= 0) return FilterResult.Rejected(TIME_RANGE)
                }
            }
        }
        return FilterResult.Ok(ParsedFilter(parsed))
    }
}

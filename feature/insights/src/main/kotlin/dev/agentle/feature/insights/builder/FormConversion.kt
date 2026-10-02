package dev.agentle.feature.insights.builder

import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.Delivery
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiLifecycle
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetricRef
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.SuppressionTarget
import dev.agentle.jitai.dsl.model.TextPair
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The definition built from a form, where each condition row went in the tree ([layout], for mapping validator issues
 * back to rows) and the form's own input problems ([localIssues], field id -> problem) that no validator code covers
 * (text that is not a whole number).
 */
internal data class BuiltRule(val definition: JitaiDefinition, val layout: TreeLayout, val localIssues: Map<String, LocalIssue>)

/** Input problems the form detects before validation. */
internal enum class LocalIssue {
    /** The text is not a whole number. */
    NOT_A_NUMBER,

    /** The number of days is outside 1-365. */
    DAYS_OUT_OF_RANGE,
}

/** JSON-pointer paths of the condition rows of both trees: row path -> row key, plus whether the row is negated. */
internal data class TreeLayout(val conditions: TreeRows, val requirements: TreeRows)

internal data class TreeRows(val rows: List<RowPlacement>, val preserved: Boolean)

internal data class RowPlacement(val path: String, val key: Int, val negated: Boolean)

/** Text of numeric fields, parsed; null text means "not a whole number". */
private class NumberReader {
    val problems = LinkedHashMap<String, LocalIssue>()

    fun required(field: String, text: String, fallback: Int): Int = text.trim().toIntOrNull() ?: fallback.also {
        problems[field] = LocalIssue.NOT_A_NUMBER
    }

    fun optional(field: String, text: String): Int? {
        if (text.isBlank()) return null
        return text.trim().toIntOrNull() ?: null.also { problems[field] = LocalIssue.NOT_A_NUMBER }
    }
}

/** Builds the definition of the form at [now] in [zone] (`modifiedAt`, a new `createdAt`, an expiry in days). */
internal fun BuilderForm.toDefinition(now: Instant, zone: TimeZone): BuiltRule {
    val numbers = NumberReader()
    val at = Instant.fromEpochSeconds(now.epochSeconds)
    val intervention = isIntervention
    val conditionsTree = conditions.toTree()
    val requirementsTree = if (intervention) requirements.toTree() else null
    val definition = JitaiDefinition(
        id = base.id,
        version = base.version,
        name = name,
        description = description,
        kind = kind,
        category = category,
        status = base.status,
        enabled = base.status == JitaiStatus.ACTIVE,
        trigger = if (intervention) trigger.toTrigger(numbers) else null,
        activeWindow = window.toActiveWindow(),
        conditions = conditionsTree?.first,
        contextRequirements = requirementsTree?.first,
        delivery = delivery(numbers),
        content = if (intervention) content.toStrategy() else null,
        cooldownMinutes = if (intervention) numbers.optional(Fields.COOLDOWN, frequency.cooldownMinutes) else null,
        maxPerDay = if (intervention) numbers.optional(Fields.MAX_PER_DAY, frequency.maxPerDay) else null,
        maxPerWeek = if (intervention) numbers.optional(Fields.MAX_PER_WEEK, frequency.maxPerWeek) else null,
        priority = numbers.required(Fields.PRIORITY, frequency.priority, INVALID_NUMBER),
        snooze = if (intervention) SnoozePolicy(snooze.mode, snooze.options.sortedBy { it.ordinal }) else null,
        expiresAt = expiry.toInstant(at, zone, numbers),
        createdBy = base.createdBy,
        createdAt = base.createdAt ?: at,
        modifiedAt = at,
        outcome = if (intervention) outcome.toSpec(numbers) else null,
        suppression = if (intervention) {
            null
        } else {
            SuppressionTarget(
                suppressionCategories.sortedBy { it.ordinal },
                base.suppressionJitaiIds,
            )
        },
        experiment = base.experiment,
        userConfirmedUnknownOverrides = confirmUnknownOverrides,
        provenance = base.provenance,
    )
    val layout = TreeLayout(
        conditions = TreeRows(conditionsTree?.second.orEmpty(), conditions.preserved != null),
        requirements = TreeRows(requirementsTree?.second.orEmpty(), requirements.preserved != null),
    )
    return BuiltRule(definition, layout, numbers.problems)
}

private fun BuilderForm.delivery(numbers: NumberReader): Delivery = if (isIntervention) {
    Delivery(
        channel = delivery.channel,
        quietHoursPolicy = delivery.quietHoursPolicy,
        notificationTimeoutMinutes = numbers.optional(Fields.TIMEOUT, delivery.notificationTimeoutMinutes),
        deliveryDeadlineMinutes = base.deliveryDeadlineMinutes,
    )
} else {
    Delivery(
        channel = DeliveryChannel.NONE,
        quietHoursPolicy = QuietHoursPolicy.RESPECT,
        deliveryDeadlineMinutes = base.deliveryDeadlineMinutes,
    )
}

private fun TriggerForm.toTrigger(numbers: NumberReader): Trigger = when (type) {
    TriggerType.DAILY_AT -> Trigger.DailyAt(
        times = dailyTimes.map { it.trim() },
        maxLatenessMinutes = numbers.required(Fields.LATENESS, lateness, INVALID_NUMBER),
    )

    TriggerType.INTERVAL -> Trigger.Interval(numbers.required(Fields.INTERVAL, intervalMinutes, INVALID_NUMBER))

    TriggerType.EVENT -> Trigger.Event(
        events = events,
        debounceSeconds = numbers.required(Fields.DEBOUNCE, debounceSeconds, INVALID_NUMBER),
    )
}

private fun WindowForm.toActiveWindow(): ActiveWindow? = if (enabled) {
    ActiveWindow(start.trim(), end.trim(), days.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal })
} else {
    null
}

private fun ContentForm.toStrategy(): ContentStrategy = when (type) {
    ContentType.TEXT -> if (hasPlaceholderSyntax(title) || hasPlaceholderSyntax(body)) {
        ContentStrategy.Template(title, body)
    } else {
        ContentStrategy.Static(title, body)
    }

    ContentType.VARIANTS -> ContentStrategy.Variants(variants.map { TextPair(it.title, it.body) })

    ContentType.AI_TEXT -> ContentStrategy.AiText(goal, tone, ContentStrategy.Template(title, body))

    ContentType.MEDIA -> ContentStrategy.LocalMedia(assetId, ContentStrategy.Template(title, body))
}

private fun hasPlaceholderSyntax(text: String): Boolean = "{{" in text || "}}" in text

private fun ExpiryForm.toInstant(now: Instant, zone: TimeZone, numbers: NumberReader): Instant? = when (mode) {
    ExpiryMode.NEVER -> null

    ExpiryMode.KEEP -> keep

    ExpiryMode.AFTER_DAYS -> {
        val n = numbers.optional(Fields.EXPIRY, days)
        when {
            n == null -> {
                numbers.problems.putIfAbsent(Fields.EXPIRY, LocalIssue.NOT_A_NUMBER)
                null
            }

            n !in EXPIRY_DAYS -> {
                numbers.problems[Fields.EXPIRY] = LocalIssue.DAYS_OUT_OF_RANGE
                null
            }

            else -> JitaiLifecycle.expiresAt(now, zone, n)
        }
    }
}

private fun OutcomeForm.toSpec(numbers: NumberReader): OutcomeSpec {
    val argNames = setOfNotNull(
        when (proximal.argKind) {
            FeatureArgKind.PACKAGE -> "package"
            FeatureArgKind.APP_CATEGORY -> "category"
            else -> null
        },
    )
    val window = if (proximal.windowMinutes != null) numbers.optional(Fields.OUTCOME_WINDOW, windowMinutes) else null
    return OutcomeSpec(
        proximal = OutcomeMetricRef(proximal, args.filter { it.key in argNames && it.value.isNotBlank() }, window),
        distal = distal?.let { OutcomeMetricRef(it) },
    )
}

/** The tree of the rows and where each row sits; null when there is nothing to evaluate. */
private fun ConditionsForm.toTree(): Pair<Condition, List<RowPlacement>>? {
    preserved?.let { return it to emptyList() }
    if (rows.isEmpty()) return null
    if (rows.size == 1) {
        val row = rows.single()
        return row.toCondition() to listOf(RowPlacement("", row.key, row.negate))
    }
    val children = rows.map { it.toCondition() }
    val tree = if (mode == GroupMode.ALL) Condition.AllOf(children) else Condition.AnyOf(children)
    return tree to rows.mapIndexed { index, row -> RowPlacement("/of/$index", row.key, row.negate) }
}

internal fun ConditionRow.toCondition(): Condition {
    val node = when (kind) {
        RowKind.TIME_WINDOW -> Condition.LocalTimeIn(start.trim(), end.trim())
        RowKind.FEATURE -> toLeaf()
    }
    return if (negate) Condition.Not(node) else node
}

private fun ConditionRow.toLeaf(): Condition.FeatureLeaf {
    val definition = RealtimeFeatureCatalog[featureId]
    val type = definition?.type
    val argNames = definition?.args.orEmpty().map { it.name }.toSet()
    val leafArgs = args.filter { (name, value) -> name in argNames && value.isNotBlank() }.mapValues { it.value.trim() }
    return when (operator) {
        Operator.BETWEEN -> Condition.Between(featureId, leafArgs, literal(type, value), literal(type, secondValue), onUnknown)
        Operator.IN -> Condition.In(featureId, leafArgs, values.map { literal(type, it) }, onUnknown)
        else -> Condition.compare(operator, featureId, literal(type, value), leafArgs, onUnknown)
    }
}

/**
 * The literal for [text] by the feature's type. A whole number becomes a JSON number token; anything else stays a
 * string, so the validator reports it on its field (E015) instead of the form guessing.
 */
internal fun literal(type: FeatureType?, text: String): RuleLiteral {
    val trimmed = text.trim()
    return when (type) {
        FeatureType.INT -> trimmed.toLongOrNull()?.let { RuleLiteral.NumberToken(it.toString()) } ?: RuleLiteral.Text(trimmed)

        FeatureType.BOOL -> when (trimmed) {
            "true" -> RuleLiteral.Bool(true)
            "false" -> RuleLiteral.Bool(false)
            else -> RuleLiteral.Text(trimmed)
        }

        else -> RuleLiteral.Text(trimmed)
    }
}

/** Days accepted for "end after N days". */
internal val EXPIRY_DAYS: IntRange = 1..365

/** Stands in for a number field that is not a whole number; the validator then also rejects it. */
private const val INVALID_NUMBER = -1

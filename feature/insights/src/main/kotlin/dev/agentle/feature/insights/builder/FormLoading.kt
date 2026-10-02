package dev.agentle.feature.insights.builder

import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.RuleLiteral

/**
 * The form of a stored rule (edit mode). Condition trees the rows cannot express (nested groups, a `not` around a
 * group) are kept as they are ([ConditionsForm.preserved]) and shown read-only.
 */
internal fun formOf(definition: JitaiDefinition): BuilderForm {
    var nextKey = 0
    val keys = { nextKey++ }
    val intervention = definition.kind == JitaiKind.INTERVENTION
    return BuilderForm(
        base = RuleBase(
            id = definition.id,
            version = definition.version,
            status = definition.status,
            isNew = false,
            createdBy = definition.createdBy,
            createdAt = definition.createdAt,
            provenance = definition.provenance,
            experiment = definition.experiment,
            deliveryDeadlineMinutes = definition.delivery.deliveryDeadlineMinutes,
            suppressionJitaiIds = definition.suppression?.jitaiIds.orEmpty(),
        ),
        name = definition.name,
        description = definition.description,
        kind = definition.kind,
        category = definition.category,
        trigger = triggerForm(definition.trigger),
        window = definition.activeWindow?.let { WindowForm(true, it.start, it.end, it.days.orEmpty().toSet()) } ?: WindowForm(),
        conditions = conditionsForm(definition.conditions, keys),
        requirements = conditionsForm(definition.contextRequirements, keys),
        frequency = FrequencyForm(
            cooldownMinutes = definition.cooldownMinutes?.toString().orEmpty(),
            maxPerDay = definition.maxPerDay?.toString().orEmpty(),
            maxPerWeek = definition.maxPerWeek?.toString().orEmpty(),
            priority = definition.priority.toString(),
        ),
        delivery = DeliveryForm(
            channel = if (intervention) definition.delivery.channel else DeliveryForm().channel,
            allowDuringQuietHours = definition.delivery.quietHoursPolicy == QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE,
            notificationTimeoutMinutes = definition.delivery.notificationTimeoutMinutes?.toString().orEmpty(),
        ),
        content = definition.content?.let(::contentForm) ?: ContentForm(),
        snooze = (definition.snooze ?: SnoozePolicy.defaultFor(definition.trigger)).let { SnoozeForm(it.mode, it.options.toSet()) },
        expiry = expiryForm(definition),
        outcome = definition.outcome?.let { spec ->
            OutcomeForm(
                proximal = spec.proximal.metric,
                windowMinutes = spec.proximal.windowMinutes?.toString() ?: OutcomeForm().windowMinutes,
                args = spec.proximal.args,
                distal = spec.distal?.metric,
            )
        } ?: OutcomeForm(),
        suppressionCategories = definition.suppression?.categories.orEmpty().toSet(),
        confirmUnknownOverrides = definition.userConfirmedUnknownOverrides,
    )
}

private fun triggerForm(trigger: Trigger?): TriggerForm = when (trigger) {
    is Trigger.DailyAt -> TriggerForm(TriggerType.DAILY_AT, dailyTimes = trigger.times, lateness = trigger.maxLatenessMinutes.toString())
    is Trigger.Interval -> TriggerForm(TriggerType.INTERVAL, intervalMinutes = trigger.everyMinutes.toString())
    is Trigger.Event -> TriggerForm(TriggerType.EVENT, events = trigger.events, debounceSeconds = trigger.debounceSeconds.toString())
    null -> TriggerForm()
}

private fun contentForm(content: ContentStrategy): ContentForm = when (content) {
    is ContentStrategy.Static -> ContentForm(ContentType.TEXT, title = content.title, body = content.body)

    is ContentStrategy.Template -> ContentForm(ContentType.TEXT, title = content.title, body = content.body)

    is ContentStrategy.Variants -> ContentForm(ContentType.VARIANTS, variants = content.items.map { TextPairForm(it.title, it.body) })

    is ContentStrategy.AiText -> ContentForm(
        ContentType.AI_TEXT,
        title = content.fallback.title,
        body = content.fallback.body,
        goal = content.goal,
        tone = content.tone,
    )

    is ContentStrategy.LocalMedia -> ContentForm(
        ContentType.MEDIA,
        title = content.caption.title,
        body = content.caption.body,
        assetId = content.assetId,
    )
}

/** A stored end is kept; a proposal's trial length becomes "end after N days" counted from saving. */
private fun expiryForm(definition: JitaiDefinition): ExpiryForm {
    val expiresAt = definition.expiresAt
    val trialDays = definition.provenance?.expiresInDays
    return when {
        expiresAt != null -> ExpiryForm(ExpiryMode.KEEP, keep = expiresAt)
        trialDays != null && definition.status == JitaiStatus.PROPOSED -> ExpiryForm(ExpiryMode.AFTER_DAYS, days = trialDays.toString())
        else -> ExpiryForm()
    }
}

private fun conditionsForm(tree: Condition?, keys: () -> Int): ConditionsForm {
    val (mode, children) = when (tree) {
        null -> return ConditionsForm()
        is Condition.AllOf -> GroupMode.ALL to tree.of
        is Condition.AnyOf -> GroupMode.ANY to tree.of
        else -> GroupMode.ALL to listOf(tree)
    }
    val rows = children.map { child -> rowOf(keys(), child) ?: return ConditionsForm(preserved = tree) }
    return ConditionsForm(mode, rows)
}

/** The row of a leaf, a time window or a `not` around one of them; null for anything else. */
private fun rowOf(key: Int, node: Condition): ConditionRow? = when (node) {
    is Condition.Not -> when (val inner = node.of) {
        is Condition.FeatureLeaf, is Condition.LocalTimeIn -> rowOf(key, inner)?.copy(negate = true)
        else -> null
    }

    is Condition.LocalTimeIn -> ConditionRow(key, kind = RowKind.TIME_WINDOW, start = node.start, end = node.end)

    is Condition.FeatureLeaf -> leafRow(key, node)

    is Condition.AllOf, is Condition.AnyOf -> null
}

private fun leafRow(key: Int, leaf: Condition.FeatureLeaf): ConditionRow {
    val texts = leaf.literals.map(::literalText)
    return ConditionRow(
        key = key,
        featureId = leaf.feature,
        operator = leaf.operator,
        value = if (leaf.operator == Operator.IN) "" else texts.firstOrNull().orEmpty(),
        secondValue = if (leaf.operator == Operator.BETWEEN) texts.getOrNull(1).orEmpty() else "",
        values = if (leaf.operator == Operator.IN) texts else emptyList(),
        args = leaf.args,
        onUnknown = leaf.onUnknown,
    )
}

private fun literalText(literal: RuleLiteral): String = when (literal) {
    is RuleLiteral.Text -> literal.value
    is RuleLiteral.NumberToken -> literal.token
    is RuleLiteral.Bool -> literal.value.toString()
}

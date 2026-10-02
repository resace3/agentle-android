package dev.agentle.jitai.dsl.validation

import dev.agentle.jitai.dsl.codec.JsonPointer
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.OutcomeSpec
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.nl.JitaiDraft
import dev.agentle.jitai.dsl.rule.Condition

/**
 * One rule as the semantic checks (S6) see it: a proposal draft or a stored definition, with [base] the JSON pointer of
 * the rule inside the validated document (`/jitai` for proposals, `""` for definitions), so every issue path points
 * into the document the caller sent.
 */
internal data class RuleView(
    val base: String,
    val isDraft: Boolean,
    val createdBy: CreatedBy,
    val id: String?,
    val name: String,
    val description: String,
    val kind: JitaiKind,
    val category: JitaiCategory,
    val trigger: Trigger?,
    val activeWindow: ActiveWindow?,
    val conditions: Condition?,
    val contextRequirements: Condition?,
    val channel: DeliveryChannel,
    val quietHoursPolicy: QuietHoursPolicy,
    val notificationTimeoutMinutes: Int?,
    val deliveryDeadlineMinutes: Int?,
    val content: ContentStrategy?,
    val cooldownMinutes: Int?,
    val maxPerDay: Int?,
    val maxPerWeek: Int?,
    val priority: Int,
    val snooze: SnoozePolicy?,
    val expiresInDays: Int?,
    val expiresInDaysPath: String,
    val hasExpiresAt: Boolean,
    val outcome: OutcomeSpec?,
    val hasSuppression: Boolean,
    val suppressionCategories: List<JitaiCategory>,
    val suppressionJitaiIds: List<String>,
    val experiment: ExperimentSpec?,
    val userConfirmedUnknownOverrides: Boolean,
) {
    fun path(vararg segments: Any): String = segments.fold(base) { acc, segment ->
        if (segment is Int) JsonPointer.child(acc, segment) else JsonPointer.child(acc, segment.toString())
    }

    companion object {
        fun of(draft: JitaiDraft, createdBy: CreatedBy, base: String): RuleView = RuleView(
            base = base,
            isDraft = true,
            createdBy = createdBy,
            id = null,
            name = draft.name,
            description = draft.description,
            kind = draft.kind,
            category = draft.category,
            trigger = draft.trigger,
            activeWindow = draft.activeWindow,
            conditions = draft.conditions,
            contextRequirements = draft.contextRequirements,
            channel = draft.delivery.channel,
            quietHoursPolicy = draft.delivery.quietHoursPolicy,
            notificationTimeoutMinutes = draft.delivery.notificationTimeoutMinutes,
            deliveryDeadlineMinutes = null,
            content = draft.content,
            cooldownMinutes = draft.cooldownMinutes,
            maxPerDay = draft.maxPerDay,
            maxPerWeek = draft.maxPerWeek,
            priority = draft.priority,
            snooze = draft.snooze,
            expiresInDays = draft.expiresInDays,
            expiresInDaysPath = "$base/expiresInDays",
            hasExpiresAt = false,
            outcome = draft.outcome,
            hasSuppression = draft.suppression != null,
            suppressionCategories = draft.suppression?.categories.orEmpty(),
            suppressionJitaiIds = emptyList(),
            experiment = null,
            userConfirmedUnknownOverrides = false,
        )

        fun of(definition: JitaiDefinition): RuleView = RuleView(
            base = "",
            isDraft = false,
            createdBy = definition.createdBy,
            id = definition.id,
            name = definition.name,
            description = definition.description,
            kind = definition.kind,
            category = definition.category,
            trigger = definition.trigger,
            activeWindow = definition.activeWindow,
            conditions = definition.conditions,
            contextRequirements = definition.contextRequirements,
            channel = definition.delivery.channel,
            quietHoursPolicy = definition.delivery.quietHoursPolicy,
            notificationTimeoutMinutes = definition.delivery.notificationTimeoutMinutes,
            deliveryDeadlineMinutes = definition.delivery.deliveryDeadlineMinutes,
            content = definition.content,
            cooldownMinutes = definition.cooldownMinutes,
            maxPerDay = definition.maxPerDay,
            maxPerWeek = definition.maxPerWeek,
            priority = definition.priority,
            snooze = definition.snooze,
            expiresInDays = definition.provenance?.expiresInDays,
            expiresInDaysPath = "/provenance/expiresInDays",
            hasExpiresAt = definition.expiresAt != null,
            outcome = definition.outcome,
            hasSuppression = definition.suppression != null,
            suppressionCategories = definition.suppression?.categories.orEmpty(),
            suppressionJitaiIds = definition.suppression?.jitaiIds.orEmpty(),
            experiment = definition.experiment,
            userConfirmedUnknownOverrides = definition.userConfirmedUnknownOverrides,
        )
    }
}

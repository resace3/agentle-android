package dev.agentle.feature.insights.builder

import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureType
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.Delivery
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.ExperimentSpec
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.JitaiKind
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeMetric
import dev.agentle.jitai.dsl.model.Provenance
import dev.agentle.jitai.dsl.model.QuietHoursPolicy
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.Tone
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.model.WeekDay
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.OnUnknown
import dev.agentle.jitai.dsl.rule.Operator
import dev.agentle.jitai.dsl.rule.TypedLiterals
import kotlin.time.Instant

/**
 * The manual builder's form (spec §18 manual mode, R10 §3): plain values the screen edits, converted into a
 * [JitaiDefinition] by [toDefinition] and validated with `RuleValidator` on every change. Numbers are kept as the
 * text the user typed so a typo is shown on its field instead of being dropped.
 */
internal data class BuilderForm(
    val base: RuleBase,
    val name: String = "",
    val description: String = "",
    val kind: JitaiKind = JitaiKind.INTERVENTION,
    val category: JitaiCategory = JitaiCategory.GENERAL,
    val trigger: TriggerForm = TriggerForm(),
    val window: WindowForm = WindowForm(),
    val conditions: ConditionsForm = ConditionsForm(),
    val requirements: ConditionsForm = ConditionsForm(),
    val frequency: FrequencyForm = FrequencyForm(),
    val delivery: DeliveryForm = DeliveryForm(),
    val content: ContentForm = ContentForm(),
    val snooze: SnoozeForm = SnoozeForm(),
    val expiry: ExpiryForm = ExpiryForm(),
    val outcome: OutcomeForm = OutcomeForm(),
    val suppressionCategories: Set<JitaiCategory> = emptySet(),
    val confirmUnknownOverrides: Boolean = false,
) {
    val isIntervention: Boolean get() = kind == JitaiKind.INTERVENTION

    companion object {
        /** A new rule: a daily reminder with the defaults of R10 §9.2 (caps 2 per day, 10 per week, 2 h apart). */
        fun new(id: String): BuilderForm = BuilderForm(base = RuleBase(id = id))
    }
}

/**
 * The fields of the rule the form does not edit, kept as loaded: identity, origin and provenance (an edited AI rule
 * keeps `createdBy`, so the AI limits still apply), and stored fields without a form control.
 *
 * @property status the stored status (DRAFT for a new rule); the lifecycle transition is applied when saving.
 */
internal data class RuleBase(
    val id: String,
    val version: Int = 1,
    val status: JitaiStatus = JitaiStatus.DRAFT,
    val isNew: Boolean = true,
    val createdBy: CreatedBy = CreatedBy.USER_MANUAL,
    val createdAt: Instant? = null,
    val provenance: Provenance? = null,
    val experiment: ExperimentSpec = ExperimentSpec(),
    val deliveryDeadlineMinutes: Int = Delivery.DEFAULT_DELIVERY_DEADLINE_MINUTES,
    val suppressionJitaiIds: List<String> = emptyList(),
)

internal enum class TriggerType { DAILY_AT, INTERVAL, EVENT }

internal data class TriggerForm(
    val type: TriggerType = TriggerType.DAILY_AT,
    val dailyTimes: List<String> = listOf("09:00"),
    val lateness: String = Trigger.DEFAULT_MAX_LATENESS_MINUTES.toString(),
    val intervalMinutes: String = "60",
    val events: List<JitaiEventType> = emptyList(),
    val debounceSeconds: String = Trigger.DEFAULT_DEBOUNCE_SECONDS.toString(),
)

/** The active window; [days] empty means every day. */
internal data class WindowForm(
    val enabled: Boolean = false,
    val start: String = "09:00",
    val end: String = "21:00",
    val days: Set<WeekDay> = emptySet(),
)

/** How the condition rows combine: all must hold, or any one. */
internal enum class GroupMode { ALL, ANY }

/**
 * A list of condition rows combined by [mode]. [preserved] holds a stored tree the rows cannot express (nested
 * groups): it is kept unchanged and shown read-only until the user replaces it.
 */
internal data class ConditionsForm(
    val mode: GroupMode = GroupMode.ALL,
    val rows: List<ConditionRow> = emptyList(),
    val preserved: Condition? = null,
)

internal enum class RowKind { FEATURE, TIME_WINDOW }

/**
 * One condition: a feature comparison or a local time window. Values are text as typed; [values] holds the list of
 * an `in` comparison.
 *
 * @property key stable identity of the row for the screen and for mapping validator issues to it.
 */
internal data class ConditionRow(
    val key: Int,
    val kind: RowKind = RowKind.FEATURE,
    val featureId: String = DEFAULT_FEATURE,
    val operator: Operator = Operator.GTE,
    val value: String = "",
    val secondValue: String = "",
    val values: List<String> = emptyList(),
    val args: Map<String, String> = emptyMap(),
    val negate: Boolean = false,
    val onUnknown: OnUnknown? = null,
    val start: String = "21:00",
    val end: String = "23:00",
) {
    companion object {
        const val DEFAULT_FEATURE: String = "screen_minutes_last_60m"

        /** A new feature row for [featureId] with the first allowed operator and default args. */
        fun forFeature(key: Int, featureId: String): ConditionRow {
            val definition = RealtimeFeatureCatalog[featureId]
            val operators = definition?.let { TypedLiterals.allowedOperators(it.type) }.orEmpty()
            val operator = PREFERRED_OPERATORS.firstOrNull { it in operators } ?: operators.firstOrNull() ?: Operator.EQ
            val args = definition?.args.orEmpty().associate { arg -> arg.name to defaultArg(arg.kind) }
            val value = when (definition?.type) {
                FeatureType.BOOL -> "true"
                FeatureType.ENUM -> definition.enumValues.first()
                FeatureType.DAY_OF_WEEK -> WeekDay.MON.name
                else -> ""
            }
            return ConditionRow(key = key, featureId = featureId, operator = operator, value = value, args = args)
        }

        private val PREFERRED_OPERATORS = listOf(Operator.GTE, Operator.EQ)

        private fun defaultArg(kind: FeatureArgKind): String = when (kind) {
            FeatureArgKind.SINCE -> "18:00"
            FeatureArgKind.APP_CATEGORY -> RealtimeFeatureCatalog.APP_CATEGORIES.first()
            FeatureArgKind.JITAI_REF -> "self"
            FeatureArgKind.PACKAGE -> ""
        }
    }
}

internal data class FrequencyForm(
    val cooldownMinutes: String = "120",
    val maxPerDay: String = "2",
    val maxPerWeek: String = "10",
    val priority: String = JitaiDefinition.DEFAULT_PRIORITY.toString(),
)

internal data class DeliveryForm(
    val channel: DeliveryChannel = DeliveryChannel.NOTIFICATION,
    val allowDuringQuietHours: Boolean = false,
    val notificationTimeoutMinutes: String = "",
) {
    val quietHoursPolicy: QuietHoursPolicy
        get() = if (allowDuringQuietHours) QuietHoursPolicy.ALLOW_WHEN_INTERACTIVE else QuietHoursPolicy.RESPECT
}

/** Content strategies the form offers (R10 §3.3). TEXT is `static`, or `template` when it has a placeholder. */
internal enum class ContentType { TEXT, VARIANTS, AI_TEXT, MEDIA }

internal data class ContentForm(
    val type: ContentType = ContentType.TEXT,
    val title: String = "",
    val body: String = "",
    val variants: List<TextPairForm> = listOf(TextPairForm(), TextPairForm()),
    val goal: String = "",
    val tone: Tone = Tone.WARM,
    val assetId: String = "",
)

internal data class TextPairForm(val title: String = "", val body: String = "")

internal data class SnoozeForm(
    val mode: SnoozeMode = SnoozeMode.SUPPRESS_ONLY,
    val options: Set<SnoozeOption> = setOf(SnoozeOption.MINUTES_60, SnoozeOption.UNTIL_TOMORROW),
)

/**
 * Expiry: none, a number of days from saving, or (when editing) the stored end kept as it is.
 *
 * @property keep the stored `expiresAt` of an edited rule, used while [mode] is [ExpiryMode.KEEP].
 */
internal data class ExpiryForm(val mode: ExpiryMode = ExpiryMode.NEVER, val days: String = "28", val keep: Instant? = null)

internal enum class ExpiryMode { NEVER, AFTER_DAYS, KEEP }

/** The outcome the rule is measured by (R10 §15.1); [args] holds `package` or `category` for app metrics. */
internal data class OutcomeForm(
    val proximal: OutcomeMetric = OutcomeMetric.NOTIFICATION_OPENED,
    val windowMinutes: String = "60",
    val args: Map<String, String> = emptyMap(),
    val distal: OutcomeMetric? = null,
)

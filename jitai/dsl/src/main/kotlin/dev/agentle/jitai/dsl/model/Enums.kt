package dev.agentle.jitai.dsl.model

import dev.agentle.analytics.features.FeatureArgKind
import dev.agentle.analytics.features.FeatureAvailability
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable

/** INTERVENTION delivers something; SUPPRESSION is the explicit "provide nothing" option that blocks targets (R10 §2.1). */
@Serializable
public enum class JitaiKind { INTERVENTION, SUPPRESSION }

/** Notification channel, suppression targeting and per-category budgets (R10 §2.1, §9.7). */
@Serializable
public enum class JitaiCategory { PHYSICAL_ACTIVITY, SLEEP_WIND_DOWN, DIGITAL_WELLBEING, STRESS_BREAK, GENERAL }

/** Lifecycle states (R10 §3.4); transitions are checked by [JitaiLifecycle]. */
@Serializable
public enum class JitaiStatus { DRAFT, PROPOSED, ACTIVE, PAUSED, EXPIRED, DECLINED, ARCHIVED }

/** Which code path created a rule. Set by the app, never taken from model output (R10 §2.1). */
@Serializable
public enum class CreatedBy { USER_MANUAL, RULE_TEMPLATE, AI_NATURAL_LANGUAGE, AI_DISCOVERED }

/** The limit column a rule is validated with (R10 §11): USER for USER_MANUAL and RULE_TEMPLATE, AI otherwise. */
public enum class RuleOrigin {
    USER,
    AI,
    ;

    public companion object {
        public fun of(createdBy: CreatedBy): RuleOrigin = when (createdBy) {
            CreatedBy.USER_MANUAL, CreatedBy.RULE_TEMPLATE -> USER
            CreatedBy.AI_NATURAL_LANGUAGE, CreatedBy.AI_DISCOVERED -> AI
        }
    }
}

/** Intervention modality (R10 §2.1). `NONE` only for SUPPRESSION. */
@Serializable
public enum class DeliveryChannel { NOTIFICATION, IMAGE, VOICE, VIDEO, NONE }

/** R10 §9.3: RESPECT never delivers in quiet hours; ALLOW_WHEN_INTERACTIVE only while the phone is in use. */
@Serializable
public enum class QuietHoursPolicy { RESPECT, ALLOW_WHEN_INTERACTIVE }

/** R10 §9.5. */
@Serializable
public enum class SnoozeMode { SUPPRESS_ONLY, RE_EVALUATE_AFTER }

/** R10 §9.5. */
@Serializable
public enum class SnoozeOption { MINUTES_30, MINUTES_60, MINUTES_120, UNTIL_WINDOW_END, UNTIL_TOMORROW }

/** Optional consented micro-randomization (R10 §15.3). */
@Serializable
public enum class ExperimentMode { NONE, MICRO_RANDOMIZED }

/** Tone of generated `ai_text` (R10 §3.3). */
@Serializable
public enum class Tone { WARM, NEUTRAL, BRIEF }

/** Variant selection of `variants` content (R10 §3.3): index = deliveryCount mod n. */
@Serializable
public enum class VariantSelection { ROTATE, }

/** Days of an active window, written `MON`..`SUN` on the wire (R10 §3.2). */
@Serializable
public enum class WeekDay(public val dayOfWeek: DayOfWeek) {
    MON(DayOfWeek.MONDAY),
    TUE(DayOfWeek.TUESDAY),
    WED(DayOfWeek.WEDNESDAY),
    THU(DayOfWeek.THURSDAY),
    FRI(DayOfWeek.FRIDAY),
    SAT(DayOfWeek.SATURDAY),
    SUN(DayOfWeek.SUNDAY),
    ;

    public companion object {
        public fun of(dayOfWeek: DayOfWeek): WeekDay = entries.first { it.dayOfWeek == dayOfWeek }
    }
}

/**
 * User-selectable trigger events (R10 §7.2). Internal events ([InternalEventType]) never create decision points and are
 * rejected in rules (E030).
 *
 * v1 deviation (integrator correction, red team lifecycle-battery-06): `LOCATION_CLASS_CHANGED` needs geofencing and
 * therefore background location, which v1 does not collect. It stays in the enum so stored rules keep decoding, but it
 * is [FeatureAvailability.Unavailable] and the validator rejects it with E028.
 *
 * @property bestEffort the event is only observed by a runtime receiver while the process is alive (R10 §7.2); rules
 *   using it get warning W08.
 */
@Serializable
public enum class JitaiEventType(
    public val availability: FeatureAvailability = FeatureAvailability.Available,
    public val bestEffort: Boolean = false,
) {
    LOCATION_CLASS_CHANGED(
        FeatureAvailability.Unavailable("location_background", "Background location is not collected in this version"),
    ),
    ACTIVITY_STATE_CHANGED,
    HEALTH_SYNC_COMPLETED,
    SLEEP_SESSION_AVAILABLE,
    NOTIFICATION_POSTED,
    POWER_CONNECTED(bestEffort = true),
    POWER_DISCONNECTED(bestEffort = true),
    SCREEN_INTERACTIVE(bestEffort = true),
    USER_PRESENT(bestEffort = true),
    ;

    public val isAvailable: Boolean get() = availability == FeatureAvailability.Available
}

/** Internal events (R10 §7.2): they reschedule work and are never valid in a rule's trigger. */
public enum class InternalEventType { TIMEZONE_CHANGED, TIME_SET, BOOT_COMPLETED, DEFINITION_CHANGED, JITAI_RESPONSE }

/** Role of an outcome metric (R10 §15.1). */
public enum class OutcomeRole { PROXIMAL, DISTAL }

/**
 * Outcome metric catalog (R10 §15.1).
 *
 * @property argKind the single arg the metric takes (`package`, `appLabel` in proposals, or `category`), or null.
 * @property windowMinutes accepted `windowMinutes` range, or null when the metric has no window (must be null).
 */
@Serializable
public enum class OutcomeMetric(
    public val role: OutcomeRole,
    public val argKind: FeatureArgKind? = null,
    public val windowMinutes: IntRange? = null,
) {
    STEPS_AFTER(OutcomeRole.PROXIMAL, windowMinutes = 10..120),
    SCREEN_MINUTES_AFTER(OutcomeRole.PROXIMAL, windowMinutes = 10..120),
    APP_MINUTES_AFTER(OutcomeRole.PROXIMAL, FeatureArgKind.PACKAGE, 10..120),
    APP_CATEGORY_MINUTES_AFTER(OutcomeRole.PROXIMAL, FeatureArgKind.APP_CATEGORY, 10..120),
    NOTIFICATION_OPENED(OutcomeRole.PROXIMAL, windowMinutes = 5..240),
    SELF_REPORT_HELPFUL(OutcomeRole.PROXIMAL),
    BEDTIME_NEXT(OutcomeRole.DISTAL),
    SLEEP_MINUTES_NEXT(OutcomeRole.DISTAL),
    STEPS_DAY_TOTAL(OutcomeRole.DISTAL),
}

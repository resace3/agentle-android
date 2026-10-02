package dev.agentle.feature.insights.port

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.DiscoveryTier
import dev.agentle.jitai.dsl.render.RenderOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The JITAI list and rule detail screens (spec §22: tabs Active, Suggested, Paused, History). Implemented by
 * APP-WIRING on top of the JITAI store (`:data`), the engine's decision log and the notification settings.
 *
 * Main safety: all functions may be called from the main thread. Read flows never complete and re-emit on change;
 * expected situations (no rules, notifications blocked, missing access) are part of the emitted value, never an
 * exception. An unexpected storage failure may surface as an [dev.agentle.core.common.AppException] with
 * [AppError.DatabaseError].
 */
public interface JitaiListPort {
    /**
     * Every rule that is not ARCHIVED or DECLINED (ACTIVE, PAUSED, DRAFT and EXPIRED), most recently modified first,
     * with today's delivery counts and what the screen needs to render rules. With no rules: `rules` empty.
     */
    public fun jitais(): Flow<JitaiOverview>

    /**
     * AI-discovered proposals waiting for review (status PROPOSED, not muted, not expired), newest first. Empty when
     * there are none or pattern discovery is off. Natural-language proposals are not listed: they open their review
     * screen directly.
     */
    public fun suggestions(): Flow<List<SuggestedJitai>>

    /**
     * Decisions of the last 30 days (at most 200, newest first) in every [DeliveryResult]: delivered or on the way,
     * waiting as an in-app card, suppressed by a safety gate, skipped at random, unknown, missed, expired, cancelled,
     * uncertain or failed. Decisions whose conditions were simply not met are not listed. Empty when nothing was
     * decided yet.
     */
    public fun history(): Flow<List<DeliveryRecord>>

    /**
     * ACTIVE -> PAUSED (`JitaiLifecycle` PAUSE). Errors: [AppError.ValidationError] with code `ILLEGAL_TRANSITION`
     * when the rule is not ACTIVE, [AppError.DatabaseError].
     */
    public suspend fun pause(jitaiId: String): Outcome<Unit>

    /**
     * PAUSED -> ACTIVE (RESUME). The implementation re-validates the stored rule first
     * (`RuleValidator.revalidate`); a rule that no longer passes stays PAUSED and the result is
     * [AppError.ValidationError] with its issue codes. Other errors: `ILLEGAL_TRANSITION`, [AppError.DatabaseError].
     */
    public suspend fun resume(jitaiId: String): Outcome<Unit>

    /**
     * Turns a rule off for good: any state -> ARCHIVED (ARCHIVE; kept for history, never evaluated). Errors:
     * `ILLEGAL_TRANSITION` for an archived rule, [AppError.DatabaseError].
     */
    public suspend fun disable(jitaiId: String): Outcome<Unit>
}

/**
 * The rules and what the list needs to show them.
 *
 * @property renderOptions clock format, the user's zone, installed-app labels and rule names for `RuleRenderer`.
 * @property globalMaxPerDay the user's overall daily limit for all reminders (R10 §9.2).
 * @property deliveredToday deliveries of all rules today (local day in [zone]).
 * @property notificationsAllowed false when notifications are blocked for the app (permission or app setting);
 *   rules then never deliver and the screen shows the fix.
 */
public data class JitaiOverview(
    val rules: List<JitaiSummary>,
    val renderOptions: RenderOptions,
    val globalMaxPerDay: Int,
    val deliveredToday: Int,
    val notificationsAllowed: Boolean,
    val zone: TimeZone,
)

/**
 * One rule with its counters.
 *
 * @property pausedReason why a PAUSED rule is paused; null for other states.
 * @property needsAccess true when a feature the rule reads lacks a permission or special access (it cannot fire).
 */
public data class JitaiSummary(
    val definition: JitaiDefinition,
    val deliveredToday: Int = 0,
    val deliveredThisWeek: Int = 0,
    val pausedReason: PauseReason? = null,
    val needsAccess: Boolean = false,
)

/** Why a rule is PAUSED. */
public enum class PauseReason {
    /** The user paused it. */
    BY_USER,

    /** It no longer passed the checks of this app version (R10 §7.5). */
    FAILED_CHECKS,
}

/** An AI-discovered proposal waiting for review. [rendering] is the renderer's sentence of the proposed rule. */
public data class SuggestedJitai(
    val proposalId: String,
    val name: String,
    val rendering: String,
    val tier: DiscoveryTier,
    val trialDays: Int,
    val createdAt: Instant,
)

/**
 * One decision of the history. [jitaiName] is the rule's name at decision time.
 *
 * @property at the decision point, shown in the user's zone.
 * @property reason why the decision ended where it did; null when nothing needs explaining (delivered, sending).
 */
public data class DeliveryRecord(
    val decisionKey: String,
    val jitaiId: String,
    val jitaiName: String,
    val at: Instant,
    val channel: DeliveryChannel,
    val result: DeliveryResult,
    val reason: OutcomeReason? = null,
)

/**
 * What became of one decision, in the user's terms: the engine's decision state (R10 §8.3) and, for a delivered one,
 * the user's first response (§8.7). Wiring maps DECIDED and DELIVERING to [SENDING]; DELIVERED with response NONE to
 * [DELIVERED], with OPENED, HELPFUL or NOT_HELPFUL to [OPENED], with DISMISSED, SNOOZED or IGNORED to the same name;
 * NOT_RANDOMIZED to [SKIPPED_AT_RANDOM]; every other listed state to the same name. NOT_TRIGGERED and NOT_AVAILABLE
 * (the conditions were not met) are not part of the history.
 */
public enum class DeliveryResult {
    /** Decided and about to be shown. */
    SENDING,

    /** Shown; no response yet. */
    DELIVERED,
    OPENED,
    DISMISSED,
    SNOOZED,
    IGNORED,

    /** Notifications were blocked, so it waits as a card inside the app (it expires after the rule's timeout). */
    CARD_PENDING,

    /** A safety check held it back; the [OutcomeReason] says which. */
    SUPPRESSED,

    /** Experiment mode skipped it at random (R10 §15.3). */
    SKIPPED_AT_RANDOM,

    /** Agentle could not tell whether the conditions were met (data missing). */
    UNKNOWN,

    /** The moment passed before Agentle could check it. */
    MISSED,

    /** It was not shown in time. */
    EXPIRED,

    /** The rule was turned off or changed before it was shown. */
    CANCELLED,

    /** It may not have been shown (the app stopped while showing it); it is never shown twice. */
    DELIVERY_UNCERTAIN,

    /** Showing it failed. */
    FAILED,
}

/**
 * Why a decision ended where it did, in plain terms. The safety gates G01-G16 of R10 §9.1 map one to one, in order,
 * to [RULE_NOT_ACTIVE] .. [LOST_ARBITRATION]. The engine's other reason codes: STATE_CHANGED -> [SITUATION_CHANGED];
 * EVENT_TOO_OLD, TOO_LATE, SLOT_NOT_REACHED and DEADLINE_PASSED -> [TOO_LATE]; OUTSIDE_WINDOW -> [OUTSIDE_WINDOW];
 * JITAI_DISABLED -> [RULE_TURNED_OFF]; DEFINITION_CHANGED -> [RULE_CHANGED]; CARD_NOT_DISPLAYED -> [CARD_NOT_SEEN];
 * anything else (POST_FAILED, NOT_FOUND_AFTER_LEASE, codes added later) -> [OTHER], since the [DeliveryResult] says it.
 */
public enum class OutcomeReason {
    /** G01: the rule was paused, a draft, or not active yet. */
    RULE_NOT_ACTIVE,

    /** G02: the rule's end date had passed. */
    RULE_ENDED,

    /** G03: the user snoozed this reminder. */
    SNOOZED,

    /** G04: all reminders were paused. */
    GLOBAL_PAUSE,

    /** G05: notifications are blocked (permission off, the channel turned off, or notifications paused). */
    NOTIFICATIONS_BLOCKED,

    /** G06. */
    QUIET_HOURS,

    /** G07. */
    DO_NOT_DISTURB,

    /** G08: another rule blocks this one at that time. */
    BLOCKED_BY_RULE,

    /** G09: too soon after this reminder's last one. */
    COOLDOWN,

    /** G10. */
    DAILY_CAP,

    /** G11. */
    WEEKLY_CAP,

    /** G12: too soon after another reminder. */
    GLOBAL_GAP,

    /** G13: the daily limit for all reminders. */
    GLOBAL_DAILY_CAP,

    /** G14: the weekly limit for all reminders. */
    GLOBAL_WEEKLY_CAP,

    /** G15: the daily limit of the delivery channel. */
    CHANNEL_CAP,

    /** G16: another reminder was chosen at the same moment. */
    LOST_ARBITRATION,

    /** The situation changed before it was shown (for example the phone was unplugged). */
    SITUATION_CHANGED,

    /** Checked too late, or not shown before its deadline. */
    TOO_LATE,

    /** Outside the rule's active hours. */
    OUTSIDE_WINDOW,

    /** The rule was turned off before it was shown. */
    RULE_TURNED_OFF,

    /** The rule was edited before it was shown; only the current version may remind. */
    RULE_CHANGED,

    /** The in-app card was not opened in time. */
    CARD_NOT_SEEN,
    OTHER,
}

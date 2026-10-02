package dev.agentle.jitai.engine.response

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.ports.QuietHours
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.LocalWindow
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.TimeZone
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The snooze policy in force for a rule and the actions it allows (R10 §9.5; jitai-correctness-14). */
public object SnoozePolicies {
    /** "Not now": snoozed until the active window ends. Always offered, whatever the policy lists. */
    public val NOT_NOW: SnoozeOption = SnoozeOption.UNTIL_WINDOW_END

    private val MINUTE_OPTIONS = setOf(SnoozeOption.MINUTES_30, SnoozeOption.MINUTES_60, SnoozeOption.MINUTES_120)

    /**
     * The rule's own policy, else the DSL default: `RE_EVALUATE_AFTER` for `daily_at` rules ([SnoozePolicy.DEFAULT_DAILY_AT])
     * and `SUPPRESS_ONLY` for the others ([SnoozePolicy.DEFAULT]).
     */
    public fun of(definition: JitaiDefinition?): SnoozePolicy = definition?.snooze ?: SnoozePolicy.defaultFor(definition?.trigger)

    /** Snooze actions come from the rule's policy; "Not now" is always allowed. */
    public fun allows(definition: JitaiDefinition?, option: SnoozeOption): Boolean = option == NOT_NOW || option in of(definition).options

    /**
     * Whether a snooze with [option] plans the one `R`-keyed follow-up: only under `RE_EVALUATE_AFTER` and only for a
     * minutes option ("remind me in an hour"). "Not now" and "until tomorrow" only suppress: the rule's next regular
     * decision point comes after them anyway.
     */
    public fun plansFollowUp(policy: SnoozePolicy, option: SnoozeOption): Boolean =
        policy.mode == SnoozeMode.RE_EVALUATE_AFTER && option in MINUTE_OPTIONS
}

/** Snooze arithmetic (R10 §9.5). Every result is a [MonotonicStamp], so G03 compares by elapsed time within one boot. */
public object SnoozeCalculator {
    /**
     * The end of a snooze chosen at [now]: `now + n` minutes; the end of the current active-window instance for
     * UNTIL_WINDOW_END ("Not now"; without a window or outside it, the next engine-day rollover); [untilTomorrow] for
     * UNTIL_TOMORROW.
     */
    public fun until(
        option: SnoozeOption,
        now: MonotonicStamp,
        definition: JitaiDefinition?,
        zone: TimeZone,
        rolloverMinute: Int,
        quietHours: QuietHours = QuietHours(enabled = false),
    ): MonotonicStamp {
        val target: Instant = when (option) {
            SnoozeOption.MINUTES_30 -> return now + MINUTES_30.minutes

            SnoozeOption.MINUTES_60 -> return now + MINUTES_60.minutes

            SnoozeOption.MINUTES_120 -> return now + MINUTES_120.minutes

            SnoozeOption.UNTIL_WINDOW_END ->
                definition?.activeWindow?.let { LocalWindow.of(it)?.instanceAt(now.wall, zone)?.end }
                    ?: EngineDays.nextRollover(now.wall, zone, rolloverMinute)

            SnoozeOption.UNTIL_TOMORROW -> untilTomorrow(now.wall, definition, zone, rolloverMinute, quietHours)
        }
        return now + (target - now.wall)
    }

    /**
     * UNTIL_TOMORROW (jitai-correctness-14): the latest of the next engine-day rollover, the end of the quiet hours in
     * force at that rollover, and the start of the rule's next active-window instance from then on. An evening rule
     * snoozed "until tomorrow" therefore wakes at tomorrow evening's window, never in the night.
     */
    public fun untilTomorrow(
        now: Instant,
        definition: JitaiDefinition?,
        zone: TimeZone,
        rolloverMinute: Int,
        quietHours: QuietHours,
    ): Instant {
        val rollover = EngineDays.nextRollover(now, zone, rolloverMinute)
        val quiet = if (quietHours.enabled) LocalWindow.of(quietHours.start, quietHours.end)?.takeIf { it.isValid } else null
        val afterQuiet = maxOf(rollover, quiet?.instanceAt(rollover, zone)?.end ?: rollover)
        val window = definition?.activeWindow?.let { LocalWindow.of(it) }?.takeIf { it.isValid } ?: return afterQuiet
        if (window.instanceAt(afterQuiet, zone) != null) return afterQuiet
        return window.nextInstanceAfter(afterQuiet, zone)?.start ?: afterQuiet
    }

    /** `max(current, candidate)` (R10 §8.7: a double tap keeps the later snooze), by elapsed time within one boot. */
    public fun later(current: MonotonicStamp?, candidate: MonotonicStamp): MonotonicStamp {
        if (current == null) return candidate
        val currentIsLater = if (current.sameBootAs(candidate)) {
            current.elapsedMillis > candidate.elapsedMillis
        } else {
            current.wall > candidate.wall
        }
        return if (currentIsLater) current else candidate
    }

    private const val MINUTES_30 = 30
    private const val MINUTES_60 = 60
    private const val MINUTES_120 = 120
}

/** Engagement-aware backoff bookkeeping (R10 §9.6; jitai-correctness-13/14). */
public object EngagementBackoff {
    /** How many counted rows a recount reads; more than enough to reach the pause threshold. */
    public const val SCAN_ROWS: Int = 16

    /**
     * The run of consecutive ignored deliveries in [newestFirst] (counted rows of one JITAI, newest first): only DELIVERED
     * rows take part (an in-app card counts once it was displayed); deliveries still waiting for a response at the head
     * are skipped; IGNORED extends the run, DISMISSED extends it unless its proximal outcome was positive
     * ([positiveOutcome]); any other response ends it.
     */
    public fun consecutiveIgnored(newestFirst: List<DecisionRecord>, positiveOutcome: (DecisionRecord) -> Boolean = { false }): Int =
        newestFirst
            .filter { it.state == DecisionState.DELIVERED }
            .dropWhile { it.content.response == JitaiResponse.NONE }
            .takeWhile { extendsRun(it, positiveOutcome) }
            .size

    private fun extendsRun(row: DecisionRecord, positiveOutcome: (DecisionRecord) -> Boolean): Boolean = when (row.content.response) {
        JitaiResponse.IGNORED -> true
        JitaiResponse.DISMISSED -> !positiveOutcome(row)
        else -> false
    }
}

/** Constant-time comparison of a notification action's nonce with the decision's (red team oauth-security-12). */
public object Nonces {
    public fun matches(expected: String?, presented: String?): Boolean {
        if (expected.isNullOrEmpty() || presented == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }
}

/** What happened to a response. */
public enum class ResponseStatus {
    /** Stored as the decision's response (the first one). */
    RECORDED,

    /** The decision already had a response; this one only reaches the response log (snoozes still apply). */
    ALREADY_RESPONDED,

    /** No decision with that key (for example after retention). */
    NOT_FOUND,

    /** The nonce did not match: nothing was written (red team oauth-security-12). */
    REJECTED,

    /** A snooze without an option, or with an option the rule's snooze policy does not offer: nothing was written. */
    INVALID_OPTION,
}

/**
 * Result of a response.
 *
 * @property snoozedUntil the JITAI's snooze end after this response, when it was a snooze.
 * @property followUpAt when the `RE_EVALUATE_AFTER` follow-up (a SNOOZE timer row) is due; never for a follow-up
 *   decision itself (R10 §12.N5).
 * @property consecutiveIgnored the recomputed run (R10 §9.6); null while the delivery prerequisite is unmet.
 * @property paused the JITAI reached the pause threshold and was paused for the in-app question.
 * @property nextTimerAt the minimum `dueAt` of the timer table after this response (re-arm `jitai-timer`).
 */
public data class ResponseReport(
    val status: ResponseStatus,
    val snoozedUntil: MonotonicStamp? = null,
    val followUpAt: Instant? = null,
    val consecutiveIgnored: Int? = null,
    val paused: Boolean = false,
    val nextTimerAt: Instant? = null,
)

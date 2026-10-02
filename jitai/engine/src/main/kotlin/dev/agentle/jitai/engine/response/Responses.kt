package dev.agentle.jitai.engine.response

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.schedule.PlannedWork
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.WorkInput
import dev.agentle.jitai.engine.schedule.WorkPolicy
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.LocalWindow
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.TimeZone
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Snooze arithmetic (R10 §9.5). Every result is a [MonotonicStamp], so G03 compares by elapsed time within one boot. */
public object SnoozeCalculator {
    /**
     * The end of a snooze chosen at [now]: `now + n` minutes, the end of the current active-window instance
     * (UNTIL_WINDOW_END; without a window or outside it, the next engine-day rollover) or the next rollover
     * (UNTIL_TOMORROW).
     */
    public fun until(
        option: SnoozeOption,
        now: MonotonicStamp,
        definition: JitaiDefinition?,
        zone: TimeZone,
        rolloverMinute: Int,
    ): MonotonicStamp {
        val target: Instant = when (option) {
            SnoozeOption.MINUTES_30 -> return now + MINUTES_30.minutes

            SnoozeOption.MINUTES_60 -> return now + MINUTES_60.minutes

            SnoozeOption.MINUTES_120 -> return now + MINUTES_120.minutes

            SnoozeOption.UNTIL_WINDOW_END ->
                definition?.activeWindow?.let { LocalWindow.of(it)?.instanceAt(now.wall, zone)?.end }
                    ?: EngineDays.nextRollover(now.wall, zone, rolloverMinute)

            SnoozeOption.UNTIL_TOMORROW -> EngineDays.nextRollover(now.wall, zone, rolloverMinute)
        }
        return now + (target - now.wall)
    }

    /** `max(current, candidate)` (R10 §8.7: a double tap keeps the later snooze), by elapsed time within one boot. */
    public fun later(current: MonotonicStamp?, candidate: MonotonicStamp): MonotonicStamp {
        if (current == null) return candidate
        val currentIsLater = if (current.sameBootAs(candidate)) {
            current.elapsedMillis > candidate.elapsedMillis
        } else {
            current.wall >
                candidate.wall
        }
        return if (currentIsLater) current else candidate
    }

    /** The `RE_EVALUATE_AFTER` follow-up work of [originalKey] at [until] (REPLACE, so a later snooze moves it). */
    public fun followUpWork(jitaiId: String, originalKey: String, until: Instant): PlannedWork = PlannedWork(
        uniqueName = "$FOLLOW_UP_PREFIX${DecisionKeys.sha256Hex(originalKey).take(HASH_CHARS)}",
        tags = setOf(SchedulePlanner.jitaiTag(jitaiId)),
        runAt = until,
        input = WorkInput.SnoozeFollowUp(jitaiId, originalKey),
        policy = WorkPolicy.REPLACE,
    )

    public const val FOLLOW_UP_PREFIX: String = "jitai-snooze-"
    private const val HASH_CHARS = 16
    private const val MINUTES_30 = 30
    private const val MINUTES_60 = 60
    private const val MINUTES_120 = 120
}

/** Engagement-aware backoff bookkeeping (R10 §9.6). */
public object EngagementBackoff {
    /** How many counted rows a recount reads; more than enough to reach the pause threshold. */
    public const val SCAN_ROWS: Int = 16

    /**
     * The run of consecutive ignored or dismissed deliveries in [newestFirst] (counted rows of one JITAI, newest first):
     * rows without a delivery and deliveries still waiting for a response at the head are skipped; IGNORED and DISMISSED
     * extend the run; any other response (OPENED and HELPFUL reset it, R10 §9.6) ends it.
     */
    public fun consecutiveIgnored(newestFirst: List<DecisionRecord>): Int {
        var run = 0
        var pendingHead = true
        for (row in newestFirst) {
            if (row.state != DecisionState.DELIVERED) continue
            val response = row.content.response
            if (response == JitaiResponse.NONE && pendingHead) continue
            pendingHead = false
            if (!response.extendsIgnoredRun) break
            run++
        }
        return run
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
}

/**
 * Result of a response.
 *
 * @property snoozedUntil the JITAI's snooze end after this response, when it was a snooze.
 * @property followUp the `RE_EVALUATE_AFTER` work to enqueue (never for a follow-up decision itself, R10 §12.N5).
 * @property consecutiveIgnored the recomputed run (R10 §9.6).
 * @property paused the JITAI reached the pause threshold and was paused for the in-app question.
 */
public data class ResponseReport(
    val status: ResponseStatus,
    val snoozedUntil: MonotonicStamp? = null,
    val followUp: PlannedWork? = null,
    val consecutiveIgnored: Int? = null,
    val paused: Boolean = false,
)

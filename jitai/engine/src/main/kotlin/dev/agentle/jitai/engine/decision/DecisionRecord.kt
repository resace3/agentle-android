package dev.agentle.jitai.engine.decision

import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Decision row states (R10 §8.3), the full enum. [counted] rows count toward caps, cooldowns and the global gap
 * (DECIDED, DELIVERING, DELIVERED, DELIVERY_UNCERTAIN); EXPIRED, CANCELLED and FAILED release their reservation; the
 * evaluation outcomes NOT_TRIGGERED, NOT_AVAILABLE, UNKNOWN, MISSED, SUPPRESSED and NOT_RANDOMIZED never count.
 */
@Serializable
public enum class DecisionState(public val counted: Boolean = false) {
    NOT_TRIGGERED,
    NOT_AVAILABLE,
    UNKNOWN,
    MISSED,
    SUPPRESSED,
    NOT_RANDOMIZED,
    DECIDED(counted = true),
    DELIVERING(counted = true),
    DELIVERED(counted = true),
    DELIVERY_UNCERTAIN(counted = true),
    FAILED,
    EXPIRED,
    CANCELLED,
    ;

    /** True once nothing can change the state any more. */
    public val isFinal: Boolean get() = this != DECIDED && this != DELIVERING
}

/**
 * The decision row state machine (R10 §8.3). Rows are inserted in an [INITIAL] state; afterwards only these
 * transitions are legal:
 * - DECIDED -> DELIVERING (claim), EXPIRED (deadline passed), CANCELLED (JITAI disabled before the claim),
 *   SUPPRESSED (the live state an event implied no longer holds, red team lifecycle-battery-05);
 * - DELIVERING -> DELIVERED (posted, or found active after the lease), DELIVERY_UNCERTAIN (lease expired and not
 *   found), FAILED (permanent error such as blocked notifications).
 * Every store implementation must reject anything else.
 */
public object DecisionStateMachine {
    public val INITIAL: Set<DecisionState> = setOf(
        DecisionState.NOT_TRIGGERED,
        DecisionState.NOT_AVAILABLE,
        DecisionState.UNKNOWN,
        DecisionState.MISSED,
        DecisionState.SUPPRESSED,
        DecisionState.NOT_RANDOMIZED,
        DecisionState.DECIDED,
    )

    private val NEXT: Map<DecisionState, Set<DecisionState>> = mapOf(
        DecisionState.DECIDED to setOf(
            DecisionState.DELIVERING,
            DecisionState.EXPIRED,
            DecisionState.CANCELLED,
            DecisionState.SUPPRESSED,
        ),
        DecisionState.DELIVERING to setOf(DecisionState.DELIVERED, DecisionState.DELIVERY_UNCERTAIN, DecisionState.FAILED),
    )

    public fun isLegal(from: DecisionState, to: DecisionState): Boolean = to in NEXT[from].orEmpty()

    public fun canInsert(state: DecisionState): Boolean = state in INITIAL
}

/**
 * Why a decision ended where it did. The first sixteen are the safety gates G01-G16 in evaluation order (R10 §9.1,
 * [gateId]); the rest come from the delivery protocol and the red-team corrections.
 */
@Serializable
public enum class ReasonCode(public val gateId: String? = null) {
    NOT_EFFECTIVE("G01"),
    EXPIRED("G02"),
    SNOOZED("G03"),
    GLOBAL_PAUSE("G04"),
    NOTIFICATIONS_BLOCKED("G05"),
    QUIET_HOURS("G06"),
    DND("G07"),
    SUPPRESSED_BY_RULE("G08"),
    COOLDOWN("G09"),
    DAILY_CAP("G10"),
    WEEKLY_CAP("G11"),
    GLOBAL_MIN_GAP("G12"),
    GLOBAL_DAILY_CAP("G13"),
    GLOBAL_WEEKLY_CAP("G14"),
    CHANNEL_CAP("G15"),
    LOST_ARBITRATION("G16"),

    /** SUPPRESSED: the live state an event implied (charging, interactive, activity) no longer holds. */
    STATE_CHANGED,

    /** MISSED (event log): `now - eventAt` exceeded the event type's `maxEventAgeMinutes`. */
    EVENT_TOO_OLD,

    /** MISSED: a `daily_at` slot ran after `slot + maxLatenessMinutes`. */
    TOO_LATE,

    /** MISSED: no tick reached this interval slot. */
    SLOT_NOT_REACHED,

    /** EXPIRED: not claimed within `deliveryDeadlineMinutes` of the decision. */
    DEADLINE_PASSED,

    /** CANCELLED: the JITAI was disabled (or is no longer effective) before the claim. */
    JITAI_DISABLED,

    /** FAILED: the delivery port reported a permanent error. */
    POST_FAILED,

    /** DELIVERY_UNCERTAIN: the lease expired and no active notification carries the tag. */
    NOT_FOUND_AFTER_LEASE,

    /** Event log: the active window was closed at the decision point. */
    OUTSIDE_WINDOW,

    /** Event log: the event was folded into a later evaluation by `debounceSeconds`. */
    DEBOUNCED,
    ;

    public val isGate: Boolean get() = gateId != null

    public companion object {
        /** G01-G16 in evaluation order. */
        public val GATES: List<ReasonCode> = entries.filter { it.isGate }
    }
}

/** The user's response to a delivered intervention (R10 §5.4 I `last_response`, §8.7). First response wins. */
@Serializable
public enum class JitaiResponse {
    NONE,
    OPENED,
    DISMISSED,
    SNOOZED,
    HELPFUL,
    NOT_HELPFUL,
    IGNORED,
    ;

    /** IGNORED and DISMISSED extend the run of consecutive ignored deliveries (R10 §9.6). */
    public val extendsIgnoredRun: Boolean get() = this == IGNORED || this == DISMISSED

    /** OPENED and HELPFUL reset the run (R10 §9.6). */
    public val resetsIgnoredRun: Boolean get() = this == OPENED || this == HELPFUL
}

/**
 * The live state an event trigger implies, re-read before posting (red team lifecycle-battery-05/07): for example
 * POWER_CONNECTED implies `charging == true`. A delivery whose implied state no longer holds is SUPPRESSED(STATE_CHANGED).
 */
@Serializable
public data class ImpliedState(val ref: FeatureRef, val expected: FeatureScalar)

/**
 * The parts of a decision that describe content and behaviour rather than budget: the snapshot, the trace, the chosen
 * content and the response. "Delete intervention history" clears them; the content-free ledger around them stays for
 * 400 days so cooldowns, caps and used keys survive the deletion (red team database-sync-06/07).
 */
public data class DecisionContent(
    val snapshotJson: String? = null,
    val snapshotHash: String? = null,
    val traceJson: String? = null,
    val traceSummaryJson: String? = null,
    val contentRef: String? = null,
    val response: JitaiResponse = JitaiResponse.NONE,
    val respondedAt: Instant? = null,
)

/**
 * One `jitai_decision` row (R10 §8.3 with the red-team additions): one resolved decision point. The decision key is
 * UNIQUE. Monotonic stamps sit next to wall time so clock changes do not move cooldowns or leases (lifecycle-battery-20).
 *
 * @property decided the decision point as evaluated (`decisionPointAt` = `decided.wall`, plus elapsed and boot count).
 * @property localDateTime the local wall time of the decision in [zoneId].
 * @property reason the gate or protocol reason for SUPPRESSED, MISSED, EXPIRED, CANCELLED, FAILED and recovered rows.
 * @property reasonDetail content-free detail such as `count=3 limit=3`.
 * @property nonce random per-delivery value that notification actions must present (red team oauth-security-12).
 * @property leaseUntil end of the 2-minute delivery lease, set by the claim.
 * @property finishedAt wall time of the final transition (EXPIRED, CANCELLED, FAILED, DELIVERY_UNCERTAIN, SUPPRESSED).
 * @property recovered DELIVERED by crash recovery after the lease expired (R10 §8.5).
 * @property impliedState for event decisions, the live state to re-check before posting.
 * @property sequence insertion order assigned by the store (0 before insert); "latest" never depends on the wall clock.
 */
public data class DecisionRecord(
    val decisionKey: String,
    val jitaiId: String,
    val jitaiVersion: Int,
    val triggerKind: TriggerKind,
    val category: JitaiCategory,
    val channel: DeliveryChannel,
    val state: DecisionState,
    val decided: MonotonicStamp,
    val zoneId: String,
    val localDateTime: LocalDateTime,
    val engineDay: LocalDate,
    val reason: ReasonCode? = null,
    val reasonDetail: String? = null,
    val conditionsResult: Tri? = null,
    val contextResult: Tri? = null,
    val randProbability: Double? = null,
    val randDraw: Double? = null,
    val nonce: String? = null,
    val claimed: MonotonicStamp? = null,
    val leaseUntil: MonotonicStamp? = null,
    val delivered: MonotonicStamp? = null,
    val finishedAt: Instant? = null,
    val recovered: Boolean = false,
    val impliedState: ImpliedState? = null,
    val content: DecisionContent = DecisionContent(),
    val sequence: Long = 0,
) {
    /** `decisionPointAt` of R10 §8.3. */
    public val decisionPointAt: Instant get() = decided.wall

    /** The notification tag of a delivered decision: always the decision key (R10 §8.5). */
    public val notificationTag: String get() = decisionKey

    /** The instant cooldowns are measured from: `deliveredAt` for DELIVERED, else the decision point (R10 §8.3). */
    public val cooldownAnchor: MonotonicStamp get() = if (state == DecisionState.DELIVERED) delivered ?: decided else decided

    /** This row with its content cleared (the content-free ledger kept after "delete intervention history"). */
    public fun withoutContent(): DecisionRecord = copy(content = DecisionContent())
}

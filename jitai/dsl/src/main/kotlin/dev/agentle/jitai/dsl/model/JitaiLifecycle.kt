package dev.agentle.jitai.dsl.model

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.time.AgentleClock
import dev.agentle.jitai.dsl.render.RuleRenderer
import dev.agentle.jitai.dsl.validation.StoredRuleVerdict
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Events of the lifecycle of R10 §3.4. */
public enum class LifecycleEvent {
    /** DRAFT -> ACTIVE: the user saves a rule from the editor or a template. */
    SAVE,

    /** PROPOSED -> ACTIVE: the user approves an AI proposal on the review screen (R10 §11.5). */
    APPROVE,

    /** PROPOSED -> DECLINED. */
    DECLINE,

    /** ACTIVE -> PAUSED (the user toggle, or a failed re-validation after an app update). */
    PAUSE,

    /** PAUSED -> ACTIVE. */
    RESUME,

    /** ACTIVE or PAUSED -> EXPIRED when `expiresAt` has passed. */
    EXPIRE,

    /** EXPIRED -> ACTIVE as a new version with a new `expiresAt`. */
    RENEW,

    /** Any state except ARCHIVED -> DRAFT as a new version. */
    EDIT,

    /** Any state except ARCHIVED -> ARCHIVED (kept for history, never evaluated). */
    ARCHIVE,
}

/**
 * The lifecycle of a JITAI (R10 §3.4) as pure functions. [next] is the transition table and rejects every transition
 * the diagram does not have; [apply] also updates the fields that go with a transition. `enabled` is true exactly in
 * ACTIVE (R10 §3.4: PAUSED is `enabled = false` on an active rule), so the engine's
 * `enabled && status == ACTIVE && !expired` holds only for running rules.
 *
 * Validate before approving: [dev.agentle.jitai.dsl.validation.RuleValidator.revalidate] (or a full validation) must
 * pass, since nothing here checks the rule itself.
 */
public object JitaiLifecycle {
    /** [AppError.ValidationError] code of a transition the diagram does not have. */
    public const val ILLEGAL_TRANSITION: String = "ILLEGAL_TRANSITION"

    /** [AppError.ValidationError] code of a renewal without a valid trial length (1-90 days). */
    public const val INVALID_RENEWAL: String = "INVALID_RENEWAL"

    /** [AppError.ValidationError] code of an APPROVE without a passing validation verdict for this rule. */
    public const val VALIDATION_REQUIRED: String = "VALIDATION_REQUIRED"

    /** [AppError.ValidationError] code of an AI_DISCOVERED approval that would not end (R10 E046). */
    public const val EXPIRY_REQUIRED: String = "EXPIRY_REQUIRED"

    /** [AppError.ValidationError] code of SAVE on an AI-written rule: AI rules become ACTIVE only by APPROVE. */
    public const val APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"

    /** [AppError.ValidationError] code of SAVE or RESUME on a rule whose `expiresAt` has passed (renew it instead). */
    public const val RULE_EXPIRED: String = "RULE_EXPIRED"

    private val RENEW_DAYS = 1..90

    /** The status after [event] from [from], or null when the transition is illegal. */
    public fun next(from: JitaiStatus, event: LifecycleEvent): JitaiStatus? = when (event) {
        LifecycleEvent.SAVE -> JitaiStatus.ACTIVE.takeIf { from == JitaiStatus.DRAFT }
        LifecycleEvent.APPROVE -> JitaiStatus.ACTIVE.takeIf { from == JitaiStatus.PROPOSED }
        LifecycleEvent.DECLINE -> JitaiStatus.DECLINED.takeIf { from == JitaiStatus.PROPOSED }
        LifecycleEvent.PAUSE -> JitaiStatus.PAUSED.takeIf { from == JitaiStatus.ACTIVE }
        LifecycleEvent.RESUME -> JitaiStatus.ACTIVE.takeIf { from == JitaiStatus.PAUSED }
        LifecycleEvent.EXPIRE -> JitaiStatus.EXPIRED.takeIf { from == JitaiStatus.ACTIVE || from == JitaiStatus.PAUSED }
        LifecycleEvent.RENEW -> JitaiStatus.ACTIVE.takeIf { from == JitaiStatus.EXPIRED }
        LifecycleEvent.EDIT -> JitaiStatus.DRAFT.takeIf { from != JitaiStatus.ARCHIVED }
        LifecycleEvent.ARCHIVE -> JitaiStatus.ARCHIVED.takeIf { from != JitaiStatus.ARCHIVED }
    }

    /**
     * [definition] after [event] at the clock's current time (whole seconds in `modifiedAt`).
     *
     * - APPROVE sets `expiresAt` from `provenance.expiresInDays` ([expiresAt]) and stores [approvedRendering] in
     *   `provenance.approvedRendering` (R10 §11.5).
     * - RENEW and EDIT create version + 1; RENEW sets a new `expiresAt` from [renewDays] (or the stored trial length),
     *   which an AI_DISCOVERED rule must have.
     *
     * Illegal transitions and invalid renewals are [AppError.ValidationError]s; nothing throws.
     */
    public fun apply(
        definition: JitaiDefinition,
        event: LifecycleEvent,
        clock: AgentleClock,
        approvedRendering: String? = null,
        renewDays: Int? = null,
        verdict: StoredRuleVerdict? = null,
    ): Outcome<JitaiDefinition> {
        val to = next(definition.status, event)
        val now = Instant.fromEpochSeconds(clock.now().epochSeconds)
        val refused = if (to == null) ILLEGAL_TRANSITION else refusal(definition, event, now, verdict)
        if (to == null || refused != null) {
            return Outcome.failure(AppError.ValidationError(listOfNotNull(refused), "${definition.status} on $event"))
        }
        val base = definition.copy(status = to, enabled = to == JitaiStatus.ACTIVE, modifiedAt = now)
        val updated = when (event) {
            LifecycleEvent.APPROVE -> approved(definition, base, now, clock, approvedRendering)
                ?: return Outcome.failure(AppError.ValidationError(listOf(EXPIRY_REQUIRED), "$event"))

            LifecycleEvent.RENEW -> {
                val days = renewDays ?: definition.provenance?.expiresInDays
                val required = definition.createdBy == CreatedBy.AI_DISCOVERED
                if ((days == null && required) || (days != null && days !in RENEW_DAYS)) {
                    return Outcome.failure(AppError.ValidationError(listOf(INVALID_RENEWAL), "renewal needs 1-90 days"))
                }
                base.copy(version = definition.version + 1, expiresAt = days?.let { expiresAt(now, clock.zone(), it) })
            }

            LifecycleEvent.EDIT -> base.copy(version = definition.version + 1)

            else -> base
        }
        return Outcome.success(updated)
    }

    /** Why [event] is refused for [definition] beyond the transition table, or null. */
    private fun refusal(definition: JitaiDefinition, event: LifecycleEvent, now: Instant, verdict: StoredRuleVerdict?): String? {
        val expired = definition.expiresAt?.let { it <= now } == true
        return when (event) {
            LifecycleEvent.SAVE -> when {
                RuleOrigin.of(definition.createdBy) == RuleOrigin.AI -> APPROVAL_REQUIRED
                expired -> RULE_EXPIRED
                else -> null
            }

            LifecycleEvent.RESUME -> RULE_EXPIRED.takeIf { expired }

            LifecycleEvent.APPROVE -> VALIDATION_REQUIRED.takeUnless {
                verdict != null && verdict.isValid && verdict.jitaiId == definition.id
            }

            else -> null
        }
    }

    /** APPROVE: `expiresAt` from the trial length, and the approved sentence with the trial end; null when a required end is missing. */
    private fun approved(
        definition: JitaiDefinition,
        base: JitaiDefinition,
        now: Instant,
        clock: AgentleClock,
        approvedRendering: String?,
    ): JitaiDefinition? {
        val days = definition.provenance?.expiresInDays
        val ends = days?.let { expiresAt(now, clock.zone(), it) } ?: definition.expiresAt
        if (ends == null && definition.createdBy == CreatedBy.AI_DISCOVERED) return null
        val withEnd = base.copy(expiresAt = ends)
        val sentence = approvedRendering?.let { text ->
            val pending = days?.let { " " + RuleRenderer.pendingExpiry(it) }
            val stripped = pending?.let { text.removeSuffix(it) } ?: text
            val end = RuleRenderer.expiry(withEnd, clock.zone())
            if (end == null || stripped.endsWith(end)) stripped else "$stripped $end"
        }
        val provenance = sentence?.let { (definition.provenance ?: Provenance()).copy(approvedRendering = it) } ?: definition.provenance
        return withEnd.copy(provenance = provenance)
    }

    /**
     * `expiresInDays` -> `expiresAt` (R10 §11.4 step 2): the start of the local day that is [days] days after the
     * approval date, in the zone at approval, as a UTC instant. Example: approved 2026-10-01 18:00 Europe/Berlin with 28
     * days -> 2026-10-29T00:00+01:00 = 2026-10-28T23:00:00Z.
     */
    public fun expiresAt(approvedAt: Instant, zone: TimeZone, days: Int): Instant {
        val date = approvedAt.toLocalDateTime(zone).date.plus(DatePeriod(days = days))
        return Instant.fromEpochSeconds(date.atStartOfDayIn(zone).epochSeconds)
    }

    /**
     * Re-validation after an app update (R10 §7.5): an ACTIVE rule whose [verdict] has errors becomes PAUSED; every
     * other rule is returned unchanged. Show [pauseNotice] for each rule this pauses.
     */
    public fun pauseIfInvalid(definition: JitaiDefinition, verdict: StoredRuleVerdict, clock: AgentleClock): JitaiDefinition {
        if (verdict.isValid || verdict.jitaiId != definition.id || definition.status != JitaiStatus.ACTIVE) return definition
        return (apply(definition, LifecycleEvent.PAUSE, clock) as? Outcome.Success)?.value ?: definition
    }

    /** The notice for a rule paused by [pauseIfInvalid] (fixed text; the name is the user's own rule name). */
    public fun pauseNotice(definition: JitaiDefinition): String =
        "\"${definition.name}\" was paused because it no longer passes the checks of this app version. Open it to review it."
}

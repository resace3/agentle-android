package dev.agentle.jitai.engine.delivery

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.common.outcomeOf
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.EnginePorts
import dev.agentle.jitai.engine.content.ContentRef
import dev.agentle.jitai.engine.content.ContentRenderer
import dev.agentle.jitai.engine.content.DefaultPlaceholderFormatter
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.content.StoredSnapshots
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.eval.RuleEvaluator
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.gates.GateEvaluator
import dev.agentle.jitai.engine.gates.GateInput
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.pipeline.PassEvaluator
import dev.agentle.jitai.engine.pipeline.RecoveryReport
import dev.agentle.jitai.engine.ports.DecisionTransaction
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.PooledText
import dev.agentle.jitai.engine.ports.PostResult
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import dev.agentle.jitai.engine.schedule.Effectiveness
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.time.EngineClock
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import dev.agentle.jitai.engine.time.isBefore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Constants of the delivery protocol (R10 §8.5). */
public object DeliveryProtocol {
    /** A claimed row is owned by its worker for this long (elapsed time within one boot); then recovery decides by tag. */
    public val LEASE: Duration = 2.minutes

    /** Downgrade reason when VOICE text cannot be synthesized (red team lifecycle-battery-18). */
    public const val TTS_UNAVAILABLE: String = "TTS_UNAVAILABLE"

    /** Downgrade reason when an IMAGE or VIDEO asset cannot be prepared. */
    public const val MEDIA_UNAVAILABLE: String = "MEDIA_UNAVAILABLE"

    /** Fail-closed live state used when the platform cannot be read (G07 then blocks). */
    public val UNKNOWN_NOTIFICATION_STATE: NotificationSystemState =
        NotificationSystemState(interruptionFilter = InterruptionFilter.UNKNOWN, notificationListenerConnected = false)

    /**
     * When the in-app card of [row] expires (jitai-correctness-13): `notificationTimeoutMinutes` of [definition] after the
     * row became CARD_PENDING, else at the next engine-day rollover after that.
     */
    public fun cardExpiry(row: DecisionRecord, definition: JitaiDefinition?, zone: TimeZone, rolloverMinute: Int): MonotonicStamp {
        val from = row.claimed ?: row.decided
        val timeout = definition?.delivery?.notificationTimeoutMinutes
        if (timeout != null) return from + timeout.coerceAtLeast(0).minutes
        val rollover = EngineDays.nextRollover(from.wall, zone, rolloverMinute)
        return from + (rollover - from.wall)
    }
}

/**
 * An in-app card waiting to be displayed (CARD_PENDING): what the app shows, rendered again from the stored row (its
 * content ref and stored snapshot), so it survives a restart. [intervention] carries the in-app text
 * ([RenderedIntervention.title] / [RenderedIntervention.body]) and the nonce responses must present.
 *
 * @property decidedAt when the decision point was resolved.
 * @property expiresAt when the card stops being offered ([DeliveryProtocol.cardExpiry]); recovery then moves it to EXPIRED.
 */
public data class PendingCard(
    val decisionKey: String,
    val jitaiId: String,
    val category: JitaiCategory,
    val channel: DeliveryChannel,
    val intervention: RenderedIntervention,
    val decidedAt: Instant,
    val expiresAt: Instant,
)

/** What a delivery reads besides its row: the current definitions by id, the effective settings and the pass zone. */
public class DeliveryEnvironment(
    public val definitions: Map<String, JitaiDefinition>,
    public val settings: EngineSettings,
    public val zone: TimeZone,
)

/**
 * The two-phase delivery protocol and crash recovery (R10 §8.5 with red team lifecycle-battery-05/18 and
 * jitai-correctness-12/13):
 *
 * 1. Cheap checks first: the delivery deadline (EXPIRED), the JITAI still armed (CANCELLED(JITAI_DISABLED)) and still
 *    at the decided version (CANCELLED(DEFINITION_CHANGED)).
 * 2. While the row is still DECIDED: content is chosen and rendered from the stored snapshot (text, image, TTS), with a
 *    downgrade to a plain notification when media is unavailable; then the live inputs of the claim are read (delivery
 *    prerequisite, interruption filter, a fresh snapshot for the implied state, `device_interactive` and the
 *    SUPPRESSION rules).
 * 3. The claim is one transaction: it re-reads the row and the definition and re-evaluates G01-G08 and the implied
 *    state. It writes DELIVERING with a 2-minute lease (elapsed time and boot count) only when everything passes;
 *    otherwise CANCELLED (G01, version), SUPPRESSED (G02-G08, STATE_CHANGED), CARD_PENDING (notifications blocked with
 *    the in-app card fallback on) or EXPIRED (deadline).
 * 4. The post with `tag = decisionKey`, then DELIVERED. A worker cancelled between the claim and the post reverts its
 *    claim (DELIVERING -> DECIDED) in a non-cancellable step; one cancelled during the post leaves the row to recovery.
 *
 * Recovery continues or expires DECIDED rows, resolves DELIVERING rows whose lease expired by looking up the tag (an
 * active notification means DELIVERED, recovered; otherwise DELIVERY_UNCERTAIN, never re-posted and still counted) and
 * expires in-app cards that were not displayed in time. Port failures leave the row where it is, so a later run retries
 * within the deadline.
 */
public class DeliveryCoordinator(
    private val ports: EnginePorts,
    private val clock: EngineClock,
    private val logger: Logger = Logger.NONE,
    private val evaluator: RuleEvaluator = RuleEvaluator(),
) {
    /** Delivers the DECIDED row [record]. */
    public suspend fun deliver(record: DecisionRecord, env: DeliveryEnvironment): DeliveryResult {
        if (record.state != DecisionState.DECIDED) return DeliveryResult.Skipped(record.decisionKey, record.state)
        val start = clock.now()
        val definition = env.definitions[record.jitaiId]
        val ended = when {
            deadlinePassed(record, definition, start) -> DecisionState.EXPIRED to ReasonCode.DEADLINE_PASSED
            definition == null || !Effectiveness.isArmed(definition) -> DecisionState.CANCELLED to ReasonCode.JITAI_DISABLED
            definition.version != record.jitaiVersion -> DecisionState.CANCELLED to ReasonCode.DEFINITION_CHANGED
            else -> null
        }
        if (ended != null || definition == null) {
            val (state, reason) = ended ?: (DecisionState.CANCELLED to ReasonCode.JITAI_DISABLED)
            return end(record, state, reason, start)
        }
        return renderAndClaim(record, definition, env, start)
    }

    /** Renders while DECIDED (text, image, speech), then claims only around the post (jitai-correctness-12). */
    private suspend fun renderAndClaim(
        record: DecisionRecord,
        definition: JitaiDefinition,
        env: DeliveryEnvironment,
        start: MonotonicStamp,
    ): DeliveryResult {
        val rendered = render(record, definition, env, start)
        val prepared = prepare(rendered) ?: return DeliveryResult.Error(record.decisionKey, "prepare_failed")
        val live = live(record, definition, env, clock.now())
        if (live == null) {
            discard(prepared)
            return DeliveryResult.Error(record.decisionKey, "live_state_unreadable")
        }
        return claimAndPost(record, definition, env, prepared, live)
    }

    /** The live inputs of the claim (jitai-correctness-12/13), read outside the transaction. */
    private class Live(
        val prerequisite: DeliveryPrerequisite,
        val filter: InterruptionFilter,
        val interactive: Tri,
        val impliedHolds: Boolean,
        val suppressedBy: List<String>,
    )

    /** What the claim transaction decided. */
    private sealed interface Claim {
        data class Claimed(val row: DecisionRecord) : Claim

        data class Ended(val state: DecisionState, val reason: ReasonCode) : Claim

        data class Lost(val state: DecisionState?) : Claim
    }

    private suspend fun live(record: DecisionRecord, definition: JitaiDefinition, env: DeliveryEnvironment, now: MonotonicStamp): Live? {
        val prerequisite = outcomeOf(mapError = { AppError.Unexpected("prerequisite:${it::class.simpleName}") }) {
            ports.delivery.prerequisite(definition.category).getOrNull()
        }.getOrNull() ?: DeliveryPrerequisite.UNKNOWN
        val filter = ports.settings.notificationState().getOrNull()?.interruptionFilter ?: InterruptionFilter.UNKNOWN
        val suppressions = PassEvaluator.suppressions(env.definitions.values.toList(), now.wall)
        val refs = PassEvaluator.liveRefs(record.impliedState, suppressions)
        val snapshot = outcomeOf(mapError = { AppError.Unexpected("live:${it::class.simpleName}") }) {
            ports.features.resolve(refs, now.wall)
        }.getOrNull() ?: return null
        val blocking = PassEvaluator.blocking(suppressions, snapshot, env.zone, evaluator)
        return Live(
            prerequisite = prerequisite,
            filter = filter,
            interactive = PassEvaluator.interactive(snapshot),
            impliedHolds = record.impliedState?.let { PassEvaluator.impliedHolds(it, snapshot) } ?: true,
            suppressedBy = PassEvaluator.blockers(definition, blocking),
        )
    }

    private suspend fun claimAndPost(
        record: DecisionRecord,
        definition: JitaiDefinition,
        env: DeliveryEnvironment,
        prepared: PreparedDelivery,
        live: Live,
    ): DeliveryResult {
        val key = record.decisionKey
        val generation = ports.store.engineState().getOrNull()?.dbGeneration
        if (generation == null) {
            discard(prepared)
            return DeliveryResult.Error(key, "engine_state_unreadable")
        }
        val now = clock.now()
        // The claim always completes, so the worker knows whether it owns the row (jitai-correctness-12).
        val result = withContext(NonCancellable) {
            ports.store.commit(generation) { tx -> claim(tx, record, definition, env, prepared, live, now) }
        }
        val claim = when (result) {
            is Outcome.Failure -> {
                discard(prepared)
                return DeliveryResult.Error(key, result.error.code)
            }

            is Outcome.Success -> result.value
        }
        return when (claim) {
            is Claim.Claimed -> post(record, claim.row, prepared, env)

            is Claim.Ended -> {
                if (claim.state == DecisionState.CARD_PENDING) keepAsCard(prepared) else discard(prepared)
                DeliveryResult.Ended(key, claim.state, claim.reason)
            }

            is Claim.Lost -> {
                discard(prepared)
                DeliveryResult.Skipped(key, claim.state)
            }
        }
    }

    /** The claim transaction: re-reads the row and the definition and re-evaluates G01-G08 and the implied state. */
    private suspend fun claim(
        tx: DecisionTransaction,
        record: DecisionRecord,
        definition: JitaiDefinition,
        env: DeliveryEnvironment,
        prepared: PreparedDelivery,
        live: Live,
        now: MonotonicStamp,
    ): Claim {
        val current = tx.decision(record.decisionKey) ?: return Claim.Lost(null)
        if (current.state != DecisionState.DECIDED) return Claim.Lost(current.state)
        val state = tx.definitionState(record.jitaiId)
        val checks = GateEvaluator.liveChecks(
            GateInput(
                definition = definition,
                definitionState = state,
                channel = current.channel,
                now = now,
                zone = env.zone,
                runtime = tx.runtime(record.jitaiId),
                settings = env.settings,
                interruptionFilter = live.filter,
                deliveryReady = live.prerequisite.met,
                interactive = live.interactive,
                suppressedBy = live.suppressedBy,
                snoozeFollowUp = current.triggerKind == TriggerKind.SNOOZE_FOLLOW_UP,
            ),
        )
        val failure = checks.firstOrNull { !it.passed }
        val ended: Pair<DecisionState, ReasonCode>? = when {
            deadlinePassed(current, definition, now) -> DecisionState.EXPIRED to ReasonCode.DEADLINE_PASSED

            failure?.gate == ReasonCode.NOT_EFFECTIVE -> DecisionState.CANCELLED to ReasonCode.JITAI_DISABLED

            state != null && state.version != current.jitaiVersion -> DecisionState.CANCELLED to ReasonCode.DEFINITION_CHANGED

            failure?.gate == ReasonCode.NOTIFICATIONS_BLOCKED && env.settings.inAppCards ->
                DecisionState.CARD_PENDING to ReasonCode.NOTIFICATIONS_BLOCKED

            failure != null -> DecisionState.SUPPRESSED to failure.gate

            !live.impliedHolds -> DecisionState.SUPPRESSED to ReasonCode.STATE_CHANGED

            else -> null
        }
        if (ended != null) {
            val (target, reason) = ended
            val updated = current.copy(
                state = target,
                reason = reason,
                reasonDetail = failure?.takeIf { it.gate == reason }?.detail,
                claimed = if (target == DecisionState.CARD_PENDING) now else current.claimed,
                finishedAt = if (target.isFinal) now.wall else current.finishedAt,
                content = current.content.copy(contentRef = prepared.intervention.contentRef.encoded),
            )
            return if (tx.compareAndSet(DecisionState.DECIDED, updated)) Claim.Ended(target, reason) else Claim.Lost(current.state)
        }
        val claimed = current.copy(
            state = DecisionState.DELIVERING,
            claimed = now,
            leaseUntil = now + DeliveryProtocol.LEASE,
            content = current.content.copy(contentRef = prepared.intervention.contentRef.encoded),
        )
        return if (tx.compareAndSet(DecisionState.DECIDED, claimed)) Claim.Claimed(claimed) else Claim.Lost(current.state)
    }

    private suspend fun post(
        original: DecisionRecord,
        claimed: DecisionRecord,
        prepared: PreparedDelivery,
        env: DeliveryEnvironment,
    ): DeliveryResult {
        var posting = false
        try {
            currentCoroutineContext().ensureActive()
            posting = true
            val posted = outcomeOf(mapError = { AppError.Unexpected("post:${it::class.simpleName}") }) { ports.delivery.post(prepared) }
            return withContext(NonCancellable) { afterPost(claimed, prepared, posted, env) }
        } catch (e: CancellationException) {
            // Cancelled before the post: undo the claim so the next run delivers (jitai-correctness-12). Cancelled during
            // the post: the notification may exist, so the row stays DELIVERING and recovery decides by tag.
            if (!posting) withContext(NonCancellable) { revert(original) }
            throw e
        }
    }

    private suspend fun afterPost(
        claimed: DecisionRecord,
        prepared: PreparedDelivery,
        posted: Outcome<PostResult>,
        env: DeliveryEnvironment,
    ): DeliveryResult = when (posted) {
        // The row stays DELIVERING; recovery after the lease decides by tag lookup (R10 §8.5).
        is Outcome.Failure -> DeliveryResult.Error(claimed.decisionKey, posted.error.detail ?: posted.error.code)

        is Outcome.Success -> when (val result = posted.value) {
            PostResult.Posted -> delivered(claimed, prepared.intervention)

            PostResult.Blocked -> {
                val target = if (env.settings.inAppCards) DecisionState.CARD_PENDING else DecisionState.SUPPRESSED
                val ended = finish(claimed, target, ReasonCode.NOTIFICATIONS_BLOCKED, null)
                val card = ended is DeliveryResult.Ended && ended.state == DecisionState.CARD_PENDING
                if (card) keepAsCard(prepared) else discard(prepared)
                ended
            }

            is PostResult.Failed -> finish(claimed, DecisionState.FAILED, ReasonCode.POST_FAILED, result.code)
        }
    }

    private suspend fun revert(original: DecisionRecord) {
        ports.store.compareAndSet(DecisionState.DELIVERING, original.copy(state = DecisionState.DECIDED))
            .onFailureLog("revert_claim")
    }

    private suspend fun delivered(claimed: DecisionRecord, intervention: RenderedIntervention): DeliveryResult {
        val done = claimed.copy(state = DecisionState.DELIVERED, delivered = clock.now())
        return when (val update = ports.store.compareAndSet(DecisionState.DELIVERING, done)) {
            is Outcome.Failure -> DeliveryResult.Error(claimed.decisionKey, update.error.code)

            is Outcome.Success -> {
                (intervention.contentRef as? ContentRef.AiPooled)?.let { ref ->
                    ports.aiTexts.markUsed(ref.itemId, claimed.decisionKey).onFailureLog("mark_used")
                }
                if (update.value) {
                    DeliveryResult.Delivered(claimed.decisionKey, recovered = false, downgradeReason = intervention.downgradeReason)
                } else {
                    DeliveryResult.Skipped(claimed.decisionKey, ports.store.decision(claimed.decisionKey).getOrNull()?.state)
                }
            }
        }
    }

    /**
     * Crash recovery (R10 §8.5): continues or expires DECIDED rows, resolves DELIVERING rows whose lease expired by
     * looking up the notification tag, and expires or cancels in-app cards. Rows still inside their lease are left to
     * their worker.
     */
    public suspend fun recover(env: DeliveryEnvironment): RecoveryReport {
        val start = clock.now()
        val rows = when (val found = ports.store.decisionsInStates(RECOVERED_STATES)) {
            is Outcome.Failure -> return RecoveryReport(start.wall, emptyList(), error = found.error.code)
            is Outcome.Success -> found.value.sortedBy { it.sequence }
        }
        val results = rows.map { row ->
            when (row.state) {
                DecisionState.DECIDED -> deliver(row, env)
                DecisionState.DELIVERING -> recoverClaimed(row)
                else -> recoverCard(row, env)
            }
        }
        return RecoveryReport(start.wall, results)
    }

    private suspend fun recoverClaimed(row: DecisionRecord): DeliveryResult {
        val now = clock.now()
        val lease = row.leaseUntil
        if (lease != null && isBefore(now, lease)) return DeliveryResult.Skipped(row.decisionKey, row.state)
        val isActive = when (val active = isActive(row)) {
            is Outcome.Failure -> return DeliveryResult.Error(row.decisionKey, active.error.detail ?: active.error.code)
            is Outcome.Success -> active.value
        }
        if (!isActive) return finish(row, DecisionState.DELIVERY_UNCERTAIN, ReasonCode.NOT_FOUND_AFTER_LEASE, null)
        val recovered = row.copy(state = DecisionState.DELIVERED, delivered = row.claimed ?: now, recovered = true)
        return when (val update = ports.store.compareAndSet(DecisionState.DELIVERING, recovered)) {
            is Outcome.Failure -> DeliveryResult.Error(row.decisionKey, update.error.code)
            is Outcome.Success -> if (update.value) DeliveryResult.Delivered(row.decisionKey, recovered = true) else skipped(row)
        }
    }

    /** Whether the notification tagged with [row]'s key is still shown; a thrown error becomes its class name only. */
    private suspend fun isActive(row: DecisionRecord): Outcome<Boolean> {
        val active = outcomeOf(mapError = { AppError.Unexpected("is_active:${it::class.simpleName}") }) {
            ports.delivery.isActive(row.notificationTag)
        }
        return when (active) {
            is Outcome.Failure -> active
            is Outcome.Success -> active.value
        }
    }

    /** CARD_PENDING: cancelled when the JITAI is no longer armed, expired when not displayed in time (jitai-correctness-13). */
    private suspend fun recoverCard(row: DecisionRecord, env: DeliveryEnvironment): DeliveryResult {
        val now = clock.now()
        val definition = env.definitions[row.jitaiId]
        return when {
            definition == null || !Effectiveness.isArmed(definition) -> end(row, DecisionState.CANCELLED, ReasonCode.JITAI_DISABLED, now)
            !isBefore(now, cardExpiry(row, definition, env)) -> end(row, DecisionState.EXPIRED, ReasonCode.CARD_NOT_DISPLAYED, now)
            else -> DeliveryResult.Skipped(row.decisionKey, row.state)
        }
    }

    /**
     * The in-app card [key] was displayed: CARD_PENDING -> DELIVERED (it now counts like a delivery, including the
     * engagement backoff). A card past its expiry becomes EXPIRED instead.
     */
    public suspend fun displayCard(key: String, env: DeliveryEnvironment): DeliveryResult {
        val row = ports.store.decision(key).getOrNull() ?: return DeliveryResult.Skipped(key, null)
        if (row.state != DecisionState.CARD_PENDING) return DeliveryResult.Skipped(key, row.state)
        val now = clock.now()
        val definition = env.definitions[row.jitaiId]
        if (definition != null && !isBefore(now, cardExpiry(row, definition, env))) {
            return end(row, DecisionState.EXPIRED, ReasonCode.CARD_NOT_DISPLAYED, now)
        }
        val shown = row.copy(state = DecisionState.DELIVERED, delivered = now)
        return when (val update = ports.store.compareAndSet(DecisionState.CARD_PENDING, shown)) {
            is Outcome.Failure -> DeliveryResult.Error(key, update.error.code)
            is Outcome.Success -> if (update.value) DeliveryResult.Delivered(key, recovered = false) else skipped(row)
        }
    }

    /**
     * The in-app cards waiting to be displayed: CARD_PENDING rows of armed JITAIs at their decided version and before their
     * expiry, oldest first, each rendered again from its stored content ref and snapshot (the in-app text). Read-only:
     * recovery moves expired cards to EXPIRED and cancels those of disabled or edited JITAIs.
     */
    public suspend fun pendingCards(env: DeliveryEnvironment): Outcome<List<PendingCard>> {
        val now = clock.now()
        val rows = when (val found = ports.store.decisionsInStates(setOf(DecisionState.CARD_PENDING))) {
            is Outcome.Failure -> return found
            is Outcome.Success -> found.value.sortedBy { it.sequence }
        }
        val cards = rows.mapNotNull { row ->
            val definition = env.definitions[row.jitaiId]
                ?.takeIf { Effectiveness.isArmed(it) && it.version == row.jitaiVersion }
                ?: return@mapNotNull null
            val expiry = cardExpiry(row, definition, env)
            if (!isBefore(now, expiry)) return@mapNotNull null
            PendingCard(
                decisionKey = row.decisionKey,
                jitaiId = row.jitaiId,
                category = row.category,
                channel = row.channel,
                intervention = render(row, definition, env, now, row.content.contentRef?.let(ContentRef::parse)),
                decidedAt = row.decided.wall,
                expiresAt = SchedulePlanner.wallOf(expiry, now),
            )
        }
        return Outcome.success(cards)
    }

    /**
     * Cancels the unclaimed rows (DECIDED and CARD_PENDING) of [jitaiId] (R10 §9.5, §12.N6; jitai-correctness-16): all of
     * them with JITAI_DISABLED when [currentVersion] is null (disabled or deleted), else those of another version with
     * DEFINITION_CHANGED.
     */
    public suspend fun cancelUnclaimed(jitaiId: String, currentVersion: Int?): List<DeliveryResult> {
        val now = clock.now()
        val rows = ports.store.decisionsInStates(setOf(DecisionState.DECIDED, DecisionState.CARD_PENDING)).getOrNull().orEmpty()
            .filter { it.jitaiId == jitaiId && (currentVersion == null || it.jitaiVersion != currentVersion) }
        val reason = if (currentVersion == null) ReasonCode.JITAI_DISABLED else ReasonCode.DEFINITION_CHANGED
        return rows.map { end(it, DecisionState.CANCELLED, reason, now) }
    }

    /** DECIDED or CARD_PENDING -> [state] with [reason]. */
    private suspend fun end(record: DecisionRecord, state: DecisionState, reason: ReasonCode, now: MonotonicStamp): DeliveryResult {
        val updated = record.copy(state = state, reason = reason, finishedAt = now.wall)
        return when (val update = ports.store.compareAndSet(record.state, updated)) {
            is Outcome.Failure -> DeliveryResult.Error(record.decisionKey, update.error.code)
            is Outcome.Success -> if (update.value) DeliveryResult.Ended(record.decisionKey, state, reason) else skipped(record)
        }
    }

    /** DELIVERING -> [state] with [reason]. */
    private suspend fun finish(claimed: DecisionRecord, state: DecisionState, reason: ReasonCode, detail: String?): DeliveryResult {
        val now = clock.now()
        val updated = claimed.copy(
            state = state,
            reason = reason,
            reasonDetail = detail,
            claimed = if (state == DecisionState.CARD_PENDING) now else claimed.claimed,
            finishedAt = if (state.isFinal) now.wall else null,
        )
        return when (val update = ports.store.compareAndSet(DecisionState.DELIVERING, updated)) {
            is Outcome.Failure -> DeliveryResult.Error(claimed.decisionKey, update.error.code)
            is Outcome.Success -> if (update.value) DeliveryResult.Ended(claimed.decisionKey, state, reason) else skipped(claimed)
        }
    }

    private suspend fun skipped(row: DecisionRecord): DeliveryResult =
        DeliveryResult.Skipped(row.decisionKey, ports.store.decision(row.decisionKey).getOrNull()?.state)

    /**
     * Renders [record]: with the content ref chosen now (a delivery), or with the [stored] one (an in-app card shown
     * later). An AI pool item that is gone or no longer usable renders the template fallback.
     */
    private suspend fun render(
        record: DecisionRecord,
        definition: JitaiDefinition,
        env: DeliveryEnvironment,
        now: MonotonicStamp,
        stored: ContentRef? = null,
    ): RenderedIntervention {
        val settings = env.settings
        val renderer = ContentRenderer(DefaultPlaceholderFormatter(settings.display))
        val ref: ContentRef
        val pooled: PooledText?
        if (stored != null) {
            ref = stored
            pooled = (stored as? ContentRef.AiPooled)?.let { pooledItem(it.itemId) }
        } else {
            val pool = if (definition.content is ContentStrategy.AiText) {
                ports.aiTexts.pooled(RuleCodec.contentHash(definition)).getOrNull().orEmpty()
            } else {
                emptyList()
            }
            val previous = (ports.store.readSnapshot { it.countedCount(definition.id) }.getOrNull() ?: 1L) - 1L
            ref = renderer.choose(definition, previous.coerceAtLeast(0), pool, record.content.snapshotHash, now.wall, settings.aiConsent)
            pooled = (ref as? ContentRef.AiPooled)?.let { chosen -> pool.firstOrNull { it.id == chosen.itemId } }
        }
        return renderer.render(
            ContentRenderer.RenderInput(
                definition = definition,
                decisionKey = record.decisionKey,
                channel = record.channel,
                snapshot = record.content.snapshotJson?.let(StoredSnapshots::decode)?.toFeatureSnapshot(),
                snapshotHash = record.content.snapshotHash,
                ref = ref,
                pooled = pooled,
                nonce = record.nonce.orEmpty(),
                now = now.wall,
                consent = settings.aiConsent,
                display = settings.display,
                privacy = settings.notificationPrivacy,
            ),
        )
    }

    /** Prepares media while DECIDED; downgrades VOICE, IMAGE and VIDEO to a plain notification when unavailable. */
    private suspend fun prepare(rendered: RenderedIntervention): PreparedDelivery? {
        val first = prepareOnce(rendered) ?: return null
        return when (first) {
            is PrepareResult.Ready -> first.prepared

            is PrepareResult.Unavailable -> {
                if (rendered.channel == DeliveryChannel.NOTIFICATION) return PreparedDelivery(rendered)
                val voice = rendered.channel == DeliveryChannel.VOICE
                val reason = if (voice) DeliveryProtocol.TTS_UNAVAILABLE else DeliveryProtocol.MEDIA_UNAVAILABLE
                val downgraded = rendered.downgraded(reason)
                logger.w(COMPONENT, "delivery downgraded", fields = mapOf("reason" to reason, "code" to first.code))
                (prepareOnce(downgraded) as? PrepareResult.Ready)?.prepared ?: PreparedDelivery(downgraded)
            }
        }
    }

    private suspend fun prepareOnce(rendered: RenderedIntervention): PrepareResult? =
        outcomeOf(mapError = { AppError.Unexpected("prepare:${it::class.simpleName}") }) { ports.delivery.prepare(rendered) }.getOrNull()

    private suspend fun discard(prepared: PreparedDelivery) {
        outcomeOf(mapError = { AppError.Unexpected("discard:${it::class.simpleName}") }) { ports.delivery.discard(prepared) }
            .onFailureLog("discard")
    }

    private suspend fun keepAsCard(prepared: PreparedDelivery) {
        // The card will show this text: it must not be offered to another delivery.
        (prepared.intervention.contentRef as? ContentRef.AiPooled)?.let { ref ->
            ports.aiTexts.markUsed(ref.itemId, prepared.intervention.decisionKey).onFailureLog("mark_used")
        }
        outcomeOf(mapError = { AppError.Unexpected("keep_as_card:${it::class.simpleName}") }) {
            ports.delivery.keepAsCard(prepared).getOrThrow()
        }.onFailureLog("keep_as_card")
    }

    /** The pool item [itemId], or null when it was purged or cannot be read (the card then shows the fallback). */
    private suspend fun pooledItem(itemId: String): PooledText? =
        outcomeOf(mapError = { AppError.Unexpected("pool_get:${it::class.simpleName}") }) { ports.aiTexts.get(itemId).getOrThrow() }
            .getOrNull()

    private fun <T> Outcome<T>.onFailureLog(step: String) {
        if (this is Outcome.Failure) logger.w(COMPONENT, "delivery side step failed", error, mapOf("step" to step))
    }

    /** The row's deadline (jitai-correctness-15), or for rows written without one `decided + deliveryDeadlineMinutes`. */
    private fun deadlinePassed(record: DecisionRecord, definition: JitaiDefinition?, now: MonotonicStamp): Boolean {
        val deadline = record.deadline ?: (record.decided + deadlineOf(definition))
        return !isBefore(now, deadline)
    }

    private fun deadlineOf(definition: JitaiDefinition?): Duration =
        (definition?.delivery?.deliveryDeadlineMinutes ?: dev.agentle.jitai.dsl.model.Delivery.DEFAULT_DELIVERY_DEADLINE_MINUTES)
            .coerceAtLeast(0).minutes

    private fun cardExpiry(row: DecisionRecord, definition: JitaiDefinition, env: DeliveryEnvironment): MonotonicStamp =
        DeliveryProtocol.cardExpiry(row, definition, env.zone, env.settings.rolloverMinute)

    private companion object {
        const val COMPONENT = "jitai-delivery"
        val RECOVERED_STATES = setOf(DecisionState.DECIDED, DecisionState.DELIVERING, DecisionState.CARD_PENDING)
    }
}

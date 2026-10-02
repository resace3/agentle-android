package dev.agentle.jitai.engine.pipeline

import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.content.SnapshotHashes
import dev.agentle.jitai.engine.content.StoredSnapshots
import dev.agentle.jitai.engine.decision.DecisionContent
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.TreeTrace
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.gates.Arbitration
import dev.agentle.jitai.engine.gates.EffectiveLimits
import dev.agentle.jitai.engine.gates.GateCheck
import dev.agentle.jitai.engine.gates.GateEvaluator
import dev.agentle.jitai.engine.gates.GateInput
import dev.agentle.jitai.engine.gates.GateReport
import dev.agentle.jitai.engine.gates.MicroRandomization
import dev.agentle.jitai.engine.outcome.OutcomePlanner
import dev.agentle.jitai.engine.ports.DecisionTransaction
import dev.agentle.jitai.engine.ports.DefinitionState
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.ports.InterruptionFilter
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.NonceSource
import dev.agentle.jitai.engine.schedule.TimerPlan
import dev.agentle.jitai.engine.schedule.TimerRow
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A change to one JITAI's runtime state, applied inside the commit (debounce bookkeeping). */
public class RuntimeUpdate(public val jitaiId: String, public val transform: (JitaiRuntimeState) -> JitaiRuntimeState)

/** The event watermark move of an event pass: commit only if the stored watermark is still [expected]. */
public data class WatermarkAdvance(val expected: Long, val to: Long)

/**
 * The live delivery state a commit gates on, read right before the transaction: the interruption filter (G07) and the
 * delivery prerequisite of each category (G05, jitai-correctness-13). A category without an entry counts as unmet.
 */
public data class LiveState(
    val interruptionFilter: InterruptionFilter,
    val prerequisites: Map<JitaiCategory, DeliveryPrerequisite> = emptyMap(),
) {
    public fun prerequisite(category: JitaiCategory): DeliveryPrerequisite = prerequisites[category] ?: DeliveryPrerequisite.UNKNOWN
}

/**
 * What a pass commits besides its evaluated points.
 *
 * @property missed scheduled points resolved as MISSED without an evaluation.
 * @property retries `daily_at` staleness retries: their timer rows move, no decision row is written.
 * @property evalLog evaluation-log entries the pass made before the commit (event points that are not eligible).
 * @property runtime runtime-state changes applied inside the commit (debounce bookkeeping).
 * @property watermark the event watermark move; the commit is rejected when another pass moved it first.
 * @property timerDeletes timer rows the pass handled or found stale (fired prefetches, rows to re-plan).
 */
public data class PassWrites(
    val missed: List<MissedPoint> = emptyList(),
    val retries: List<StalenessRetry> = emptyList(),
    val evalLog: List<EvalLogEntry> = emptyList(),
    val runtime: List<RuntimeUpdate> = emptyList(),
    val watermark: WatermarkAdvance? = null,
    val timerDeletes: Set<String> = emptySet(),
)

/**
 * Everything one serialized commit needs (R10 §8.4). [evaluations] were made outside the transaction against one
 * snapshot; the gates are evaluated inside it against the counts as they are now.
 *
 * @property plan when set, the commit ends with a re-plan of the whole timer table ([TimerReconciler]).
 */
public class CommitRequest(
    public val now: MonotonicStamp,
    public val zone: TimeZone,
    public val settings: EngineSettings,
    public val live: LiveState,
    public val salt: ByteArray? = null,
    public val snapshot: FeatureSnapshot? = null,
    public val evaluations: List<CandidateEvaluation> = emptyList(),
    public val writes: PassWrites = PassWrites(),
    public val plan: PlanContext? = null,
)

/** Result of [CommitResolver.commit]. */
public sealed interface CommitResult {
    /** Rows inserted (as stored, with their sequence), evaluation-log entries appended, deferrals and the new timer table. */
    public data class Committed(
        val written: List<DecisionRecord>,
        val evalLog: List<EvalLogEntry>,
        val deferred: List<Deferral>,
        val plan: TimerPlan?,
    ) : CommitResult

    /** Another event pass moved the watermark first; nothing was written (red team database-sync-04). */
    public data object Stale : CommitResult
}

/**
 * The inside of the serialized commit (R10 §8.1 steps 5-8, §8.4; red team database-sync-05; jitai-correctness-01/05):
 *
 * - drops keys that exist and points whose definition version changed since the snapshot (only current definitions are
 *   candidates, jitai-correctness-16);
 * - evaluates G01-G15 against the counts read in this transaction (G01 against the stored definition), counting DECIDED
 *   and DELIVERING rows (and CARD_PENDING for the JITAI's own budget);
 * - defers a scheduled point within its lateness when only Do Not Disturb or the global gap blocks it, and a scheduled
 *   point that lost the arbitration (G16) until the gap after the winner;
 * - draws the micro-randomization, builds every row and inserts it with insert-if-absent;
 * - writes evaluation-log traces only when a JITAI's result changed (jitai-correctness-10);
 * - deletes the timer rows of resolved points, moves deferred and retried ones, plans OUTCOME rows and re-plans.
 *
 * Scheduled points write every resolution; event points write only delivery-eligible rows and log the rest (R10 §8.2).
 */
public class CommitResolver(private val nonces: NonceSource) {
    public suspend fun commit(tx: DecisionTransaction, request: CommitRequest): CommitResult {
        request.writes.watermark?.let { if (tx.currentWatermark() != it.expected) return CommitResult.Stale }
        val batch = Batch(tx, request)
        batch.resolve()
        val written = batch.insert()
        val log = batch.appendLog()
        request.writes.runtime.forEach { update -> tx.putRuntime(update.transform(tx.runtime(update.jitaiId))) }
        batch.applyTimers(written)
        request.writes.watermark?.let { tx.advanceWatermark(it.to) }
        val plan = request.plan?.let { TimerReconciler.reconcile(tx, it) }
        return CommitResult.Committed(written, log, batch.deferred, plan)
    }

    private class Contender(val evaluation: CandidateEvaluation, val gates: GateReport, val lastDelivered: Instant?)

    /** An evaluation-log entry waiting for the signature check; [signature] is null for entries without a trace. */
    private class PendingLog(val entry: EvalLogEntry, val signature: String?)

    private inner class Batch(private val tx: DecisionTransaction, private val request: CommitRequest) {
        private val now = request.now
        private val rows = mutableListOf<DecisionRecord>()
        private val rowTimers = mutableListOf<String>()
        private val log = request.writes.evalLog.map { PendingLog(it, null) }.toMutableList()
        private val seen = mutableSetOf<String>()
        private val timerDeletes = request.writes.timerDeletes.toMutableSet()
        private val timerPuts = mutableListOf<TimerRow>()
        private val definitions = mutableMapOf<String, JitaiDefinition>()
        private var existing: Set<String> = emptySet()
        private var recent: List<DecisionRecord> = emptyList()
        val deferred = mutableListOf<Deferral>()

        suspend fun resolve() {
            val keys =
                request.evaluations.map { it.point.key } + request.writes.missed.map { it.key } +
                    request.writes.retries.map { it.point.key }
            existing = if (keys.isEmpty()) emptySet() else tx.existingKeys(keys)
            val today = EngineDays.of(now.wall, request.zone, request.settings.rolloverMinute)
            recent = if (request.evaluations.isEmpty()) emptyList() else tx.countedInEngineDays(GateEvaluator.recentDays(today))
            val contenders = mutableListOf<Contender>()
            request.evaluations.forEach { evaluation ->
                val point = evaluation.point
                if (!claim(point.key, point.timer)) return@forEach
                val state = tx.definitionState(point.definition.id)
                if (state != null && state.version != point.definition.version) {
                    point.timer?.let { timerDeletes += it.key }
                    return@forEach
                }
                definitions[point.definition.id] = point.definition
                when {
                    !evaluation.eligible && point.kind.writesEveryResolution -> add(outcomeRow(evaluation), point.timer)
                    !evaluation.eligible -> log += logEntry(evaluation, outcomeOf(evaluation), stateChanged(evaluation))
                    else -> gate(evaluation, state)?.let { contenders += it }
                }
            }
            arbitrate(contenders)
            request.writes.missed.forEach { missed ->
                if (claim(missed.key, missed.timer)) {
                    definitions[missed.definition.id] = missed.definition
                    add(missedRow(missed), missed.timer)
                }
            }
            request.writes.retries.forEach(::retry)
        }

        suspend fun insert(): List<DecisionRecord> =
            rows.mapNotNull { row -> if (tx.insertIfAbsent(row)) tx.decision(row.decisionKey) ?: row else null }

        /** Appends the log; a trace is kept only when the JITAI's result signature changed (jitai-correctness-10). */
        suspend fun appendLog(): List<EvalLogEntry> = log.map { pending ->
            val signature = pending.signature
            val runtime = if (signature == null) null else tx.runtime(pending.entry.jitaiId)
            val changed = runtime == null || runtime.lastEvalSignature != signature
            val entry = if (changed) pending.entry else pending.entry.copy(traceJson = null)
            tx.appendEvalLog(entry)
            if (runtime != null && runtime.lastEvalSignature != signature) tx.putRuntime(runtime.copy(lastEvalSignature = signature))
            entry
        }

        suspend fun applyTimers(written: List<DecisionRecord>) {
            (rowTimers + timerDeletes).forEach { tx.deleteTimer(it) }
            timerPuts.forEach { tx.putTimer(it) }
            written.forEach { row ->
                definitions[row.jitaiId]?.let { definition ->
                    OutcomePlanner.timerRows(row, definition, request.zone).forEach { tx.putTimer(it) }
                }
            }
        }

        /** False (and the point's timer row goes) when [key] already has a row or was resolved earlier in this batch. */
        private fun claim(key: String, timer: TimerRow?): Boolean {
            if (key in existing || !seen.add(key)) {
                timer?.let { timerDeletes += it.key }
                return false
            }
            return true
        }

        private fun add(row: DecisionRecord, timer: TimerRow?) {
            rows += row
            timer?.let { rowTimers += it.key }
        }

        private suspend fun gate(evaluation: CandidateEvaluation, state: DefinitionState?): Contender? {
            val point = evaluation.point
            val definition = point.definition
            val latestOwn = tx.recentCounted(definition.id, 1).firstOrNull()
            val input = GateInput(
                definition = definition,
                definitionState = state,
                channel = definition.delivery.channel,
                now = now,
                zone = request.zone,
                runtime = tx.runtime(definition.id),
                settings = request.settings,
                interruptionFilter = request.live.interruptionFilter,
                // With the in-app card fallback on, blocked notifications are decided at the claim (CARD_PENDING).
                deliveryReady = request.live.prerequisite(definition.category).met || request.settings.inAppCards,
                interactive = request.snapshot?.let(PassEvaluator::interactive) ?: Tri.UNKNOWN,
                suppressedBy = evaluation.suppressedBy,
                recent = recent,
                latestOwn = latestOwn,
                snoozeFollowUp = point.snoozeFollowUp,
            )
            val gates = GateEvaluator.evaluate(input)
            val failure = gates.firstFailure ?: return Contender(evaluation, gates, latestOwn?.cooldownAnchor?.wall)
            val retryAt = deferral(point, gates, input)
            if (retryAt != null) {
                defer(evaluation, gates.checks, retryAt, failure.gate)
            } else {
                add(gatedRow(evaluation, gates.checks, DecisionState.SUPPRESSED, failure.gate), point.timer)
            }
            return null
        }

        /**
         * When a scheduled point may run again (jitai-correctness-01/05): only when every failing gate is Do Not Disturb
         * (retry in 10 minutes, at the latest at `latestAt`) or the global gap (retry when it ends), and the retry lies
         * after now and no later than `latestAt`.
         */
        private fun deferral(point: DecisionPoint, gates: GateReport, input: GateInput): Instant? {
            val latest = point.latestAt ?: return null
            if (point.timer == null) return null
            val failing = gates.checks.filterNot { it.passed }.map { it.gate }
            if (failing.isEmpty() || !DEFERRABLE.containsAll(failing)) return null
            val at = failing.maxOf { gate ->
                if (gate == ReasonCode.DND) {
                    minOf(now.wall + DND_RETRY, latest)
                } else {
                    now.wall + (GateEvaluator.minGapEndsIn(input) ?: Duration.ZERO)
                }
            }
            return at.takeIf { it > now.wall && it <= latest }
        }

        private fun defer(evaluation: CandidateEvaluation, checks: List<GateCheck>, until: Instant, reason: ReasonCode) {
            val point = evaluation.point
            val timer = point.timer ?: return
            timerPuts += timer.copy(dueAt = until, deferrals = timer.deferrals + 1)
            deferred += Deferral(point.key, point.definition.id, until, reason)
            log += logEntry(evaluation, EvalOutcome.DEFERRED, reason, checks)
        }

        private fun retry(retry: StalenessRetry) {
            val point = retry.point
            val timer = point.timer
            if (!claim(point.key, timer) || timer == null) return
            timerPuts += timer.copy(dueAt = retry.retryAt, deferrals = timer.deferrals + 1)
            log += logEntry(retry.evaluation, EvalOutcome.RETRY, null)
        }

        /**
         * G16 and step 7: the best contender is DECIDED (or NOT_RANDOMIZED), the others lose arbitration. R10 §8.1 runs
         * arbitration (step 6) before micro-randomization (step 7), so the losers lose whatever the winner draws: a
         * NOT_RANDOMIZED winner does not free the pass's one delivery, and losers defer or end as LOST_ARBITRATION as usual.
         */
        private fun arbitrate(contenders: List<Contender>) {
            if (contenders.isEmpty()) return
            val byId = contenders.associateBy { it.evaluation.point.definition.id }
            val ranked = Arbitration.rank(
                contenders.map {
                    val definition = it.evaluation.point.definition
                    Arbitration.Contender(definition, EffectiveLimits.of(definition).priority, it.lastDelivered)
                },
            ).map { byId.getValue(it.definition.id) }
            val winner = ranked.first()
            val winnerChecks = winner.gates.checks + GateCheck(ReasonCode.LOST_ARBITRATION, true, "contenders=${ranked.size}")
            add(randomized(winner.evaluation, winnerChecks), winner.evaluation.point.timer)
            val gapEnd = now.wall + request.settings.minGapMinutes.minutes
            val lost = GateCheck(ReasonCode.LOST_ARBITRATION, false, "winner=${winner.evaluation.point.definition.id}")
            ranked.drop(1).forEach { loser ->
                val point = loser.evaluation.point
                val checks = loser.gates.checks + lost
                val latest = point.latestAt
                if (latest != null && point.timer != null && gapEnd <= latest) {
                    defer(loser.evaluation, checks, gapEnd, ReasonCode.LOST_ARBITRATION)
                } else {
                    add(gatedRow(loser.evaluation, checks, DecisionState.SUPPRESSED, ReasonCode.LOST_ARBITRATION), point.timer)
                }
            }
        }

        private fun randomized(evaluation: CandidateEvaluation, checks: List<GateCheck>): DecisionRecord {
            val key = evaluation.point.key
            val probability = MicroRandomization.probability(evaluation.point.definition.experiment)
                ?: return decided(gatedRow(evaluation, checks, DecisionState.DECIDED, null), evaluation.point)
            val salt = request.salt ?: error("micro-randomized decision without an install salt")
            val draw = MicroRandomization.draw(salt, key)
            val deliver = MicroRandomization.deliver(salt, key, probability)
            val state = if (deliver) DecisionState.DECIDED else DecisionState.NOT_RANDOMIZED
            val row = gatedRow(evaluation, checks, state, null, probability, draw)
            return if (deliver) decided(row, evaluation.point) else row
        }

        /**
         * A DECIDED row gets its nonce and its delivery deadline, anchored at the nominal time (jitai-correctness-15):
         * `nominalAt + deliveryDeadlineMinutes`. A scheduled point decided at or after that instant (it was deferred, or its
         * timer ran late within the lateness bound) may still be delivered until its `latestAt`.
         */
        private fun decided(row: DecisionRecord, point: DecisionPoint): DecisionRecord {
            val anchored = point.nominalAt + point.definition.delivery.deliveryDeadlineMinutes.coerceAtLeast(0).minutes
            val latest = point.latestAt
            val deadline = if (latest != null && now.wall >= anchored) maxOf(anchored, latest) else anchored
            return row.copy(nonce = nonces.nextNonce(), deadline = now + (deadline - now.wall))
        }

        private fun base(evaluation: CandidateEvaluation, state: DecisionState): DecisionRecord {
            val point = evaluation.point
            val definition = point.definition
            return DecisionRecord(
                decisionKey = point.key,
                jitaiId = definition.id,
                jitaiVersion = definition.version,
                triggerKind = point.kind,
                category = definition.category,
                channel = definition.delivery.channel,
                state = state,
                decided = now,
                zoneId = request.zone.id,
                localDateTime = point.nominalAt.toLocalDateTime(request.zone),
                engineDay = EngineDays.of(now.wall, request.zone, request.settings.rolloverMinute),
                nominalAt = point.nominalAt,
                conditionsResult = evaluation.conditions.result,
                contextResult = evaluation.context?.result,
                impliedState = point.impliedState,
            )
        }

        private fun content(evaluation: CandidateEvaluation, trace: DecisionTrace): DecisionContent {
            val snapshot = request.snapshot
            return DecisionContent(
                snapshotJson = snapshot?.let { StoredSnapshots.encode(StoredSnapshots.of(PassEvaluator.subset(it, evaluation.point))) },
                snapshotHash = snapshot?.let { SnapshotHashes.contextHash(evaluation.point.definition, it) },
                traceJson = TraceCodec.encode(trace),
                traceSummaryJson = TraceCodec.summary(trace),
            )
        }

        private fun outcomeRow(evaluation: CandidateEvaluation): DecisionRecord {
            val state = evaluation.outcome ?: DecisionState.UNKNOWN
            val reason = stateChanged(evaluation)
            val trace = DecisionTrace(evaluation.conditions, evaluation.context, reason = reason)
            return base(evaluation, state).copy(reason = reason, content = content(evaluation, trace))
        }

        private fun gatedRow(
            evaluation: CandidateEvaluation,
            checks: List<GateCheck>,
            state: DecisionState,
            reason: ReasonCode?,
            probability: Double? = null,
            draw: Double? = null,
        ): DecisionRecord {
            val failed = checks.firstOrNull { it.gate == reason && !it.passed }
            val trace = DecisionTrace(
                conditions = evaluation.conditions,
                context = evaluation.context,
                gates = checks,
                suppressedBy = evaluation.suppressedBy.takeIf { it.isNotEmpty() },
                reason = reason,
                randProbability = probability,
                randDraw = draw,
            )
            return base(evaluation, state).copy(
                reason = reason,
                reasonDetail = failed?.detail,
                randProbability = probability,
                randDraw = draw,
                content = content(evaluation, trace),
            )
        }

        private fun logEntry(
            evaluation: CandidateEvaluation,
            outcome: EvalOutcome,
            reason: ReasonCode?,
            gates: List<GateCheck>? = null,
        ): PendingLog {
            val trace = DecisionTrace(evaluation.conditions, evaluation.context, gates = gates, reason = reason)
            val entry = EvalLogEntry(
                at = now.wall,
                jitaiId = evaluation.point.definition.id,
                triggerKind = evaluation.point.kind,
                eventType = evaluation.point.eventType,
                outcome = outcome,
                reason = reason,
                traceJson = TraceCodec.encode(trace),
            )
            return PendingLog(entry, EvalSignatures.of(outcome, trace))
        }

        private fun missedRow(missed: MissedPoint): DecisionRecord {
            val definition = missed.definition
            val shift = now.wall - missed.nominalAt
            val decided = MonotonicStamp(missed.nominalAt, (now.elapsedMillis - shift.inWholeMilliseconds).coerceAtLeast(0), now.bootCount)
            val trace = DecisionTrace(reason = missed.reason)
            return DecisionRecord(
                decisionKey = missed.key,
                jitaiId = definition.id,
                jitaiVersion = definition.version,
                triggerKind = missed.kind,
                category = definition.category,
                channel = definition.delivery.channel,
                state = DecisionState.MISSED,
                decided = decided,
                zoneId = request.zone.id,
                localDateTime = missed.nominalAt.toLocalDateTime(request.zone),
                engineDay = EngineDays.of(missed.nominalAt, request.zone, request.settings.rolloverMinute),
                nominalAt = missed.nominalAt,
                reason = missed.reason,
                reasonDetail = "late=${shift.coerceAtLeast(0.milliseconds).inWholeSeconds}s",
                content = DecisionContent(traceJson = TraceCodec.encode(trace), traceSummaryJson = TraceCodec.summary(trace)),
            )
        }
    }

    private companion object {
        /** Gates that defer a scheduled point instead of suppressing it (jitai-correctness-01/05). */
        val DEFERRABLE: Set<ReasonCode> = setOf(ReasonCode.DND, ReasonCode.GLOBAL_MIN_GAP)

        /** Do Not Disturb is checked again after this long. */
        val DND_RETRY: Duration = 10.minutes

        /** The evaluation-log outcome of a non-eligible event point. */
        fun outcomeOf(evaluation: CandidateEvaluation): EvalOutcome = when (evaluation.outcome) {
            DecisionState.NOT_AVAILABLE -> EvalOutcome.NOT_AVAILABLE
            DecisionState.UNKNOWN -> EvalOutcome.UNKNOWN
            else -> EvalOutcome.NOT_TRIGGERED
        }

        /** STATE_CHANGED when the rules passed but the live state the event implied was already gone. */
        fun stateChanged(evaluation: CandidateEvaluation): ReasonCode? =
            if (evaluation.conditions.result == Tri.TRUE && evaluation.context?.result == Tri.TRUE && !evaluation.impliedHolds) {
                ReasonCode.STATE_CHANGED
            } else {
                null
            }
    }
}

/**
 * The result signature of an evaluation-log entry (jitai-correctness-10): the outcome, the reason, every node result of
 * both trees and the failing gates, but no values. A JITAI's trace is written again only when this changes, so a rule
 * that stays NOT_TRIGGERED all day writes one trace, not one per evaluation.
 */
public object EvalSignatures {
    private const val LENGTH = 16

    public fun of(outcome: EvalOutcome, trace: DecisionTrace): String {
        val text = listOf(
            outcome.name,
            trace.reason?.name ?: "-",
            nodes(trace.conditions),
            nodes(trace.context),
            trace.gates?.filterNot { it.passed }?.joinToString(",") { it.gate.name } ?: "-",
        ).joinToString("|")
        return DecisionKeys.sha256Hex(text).take(LENGTH)
    }

    private fun nodes(tree: TreeTrace?): String = tree?.nodes?.joinToString(",") { "${it.path}=${it.result}" } ?: "-"
}

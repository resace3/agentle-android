package dev.agentle.jitai.engine.pipeline

import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.jitai.engine.content.SnapshotHashes
import dev.agentle.jitai.engine.decision.DecisionContent
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.eval.Tri
import dev.agentle.jitai.engine.gates.Arbitration
import dev.agentle.jitai.engine.gates.EffectiveLimits
import dev.agentle.jitai.engine.gates.GateCheck
import dev.agentle.jitai.engine.gates.GateEvaluator
import dev.agentle.jitai.engine.gates.GateInput
import dev.agentle.jitai.engine.gates.GateReport
import dev.agentle.jitai.engine.gates.MicroRandomization
import dev.agentle.jitai.engine.ports.DecisionTransaction
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.NonceSource
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A change to one JITAI's runtime state, applied inside the commit (debounce bookkeeping). */
public class RuntimeUpdate(public val jitaiId: String, public val transform: (JitaiRuntimeState) -> JitaiRuntimeState)

/** The event watermark move of an event pass: commit only if the stored watermark is still [expected]. */
public data class WatermarkAdvance(val expected: Long, val to: Long)

/**
 * Everything one serialized commit needs (R10 §8.4). [evaluations] were made outside the transaction against one snapshot;
 * the gates are evaluated inside it against the counts as they are now.
 */
public class CommitRequest(
    public val now: MonotonicStamp,
    public val zone: TimeZone,
    public val settings: EngineSettings,
    public val notifications: NotificationSystemState,
    public val salt: ByteArray?,
    public val snapshot: FeatureSnapshot?,
    public val evaluations: List<CandidateEvaluation>,
    public val missed: List<MissedPoint> = emptyList(),
    public val evalLog: List<EvalLogEntry> = emptyList(),
    public val runtime: List<RuntimeUpdate> = emptyList(),
    public val watermark: WatermarkAdvance? = null,
)

/** Result of [CommitResolver.commit]. */
public sealed interface CommitResult {
    /** Rows inserted (as stored, with their sequence) and evaluation-log entries appended. */
    public data class Committed(val written: List<DecisionRecord>, val evalLog: List<EvalLogEntry>) : CommitResult

    /** Another event pass moved the watermark first; nothing was written (red team database-sync-04). */
    public data object Stale : CommitResult
}

/**
 * The inside of the serialized commit (R10 §8.1 steps 5-8, §8.4; red team database-sync-05): drops keys that exist,
 * evaluates G01-G15 against the counts read in this transaction (G01 against the stored definition), arbitrates (G16),
 * draws the micro-randomization, builds every row and inserts it with insert-if-absent. Scheduled points write every
 * resolution; event points write only delivery-eligible rows and log the rest (R10 §8.2).
 */
public class CommitResolver(private val nonces: NonceSource) {
    private val json = Json { encodeDefaults = false }

    public suspend fun commit(tx: DecisionTransaction, request: CommitRequest): CommitResult {
        request.watermark?.let { if (tx.currentWatermark() != it.expected) return CommitResult.Stale }
        val keys = request.evaluations.map { it.point.key } + request.missed.map { it.key }
        val existing = tx.existingKeys(keys)
        val rows = mutableListOf<DecisionRecord>()
        val log = request.evalLog.toMutableList()
        val today = EngineDays.of(request.now.wall, request.zone, request.settings.rolloverMinute)
        val recent = tx.countedInEngineDays(GateEvaluator.recentDays(today))
        val contenders = mutableListOf<Contender>()
        val seen = mutableSetOf<String>()
        request.evaluations.filter { it.point.key !in existing && seen.add(it.point.key) }.forEach { evaluation ->
            when {
                !evaluation.eligible && evaluation.point.kind.writesEveryResolution -> rows += outcomeRow(request, evaluation)

                !evaluation.eligible -> log += logEntry(request, evaluation)

                else -> {
                    val definition = evaluation.point.definition
                    val latestOwn = tx.recentCounted(definition.id, 1).firstOrNull()
                    val gates = GateEvaluator.evaluate(
                        GateInput(
                            definition = definition,
                            definitionState = tx.definitionState(definition.id),
                            channel = definition.delivery.channel,
                            now = request.now,
                            zone = request.zone,
                            runtime = tx.runtime(definition.id),
                            settings = request.settings,
                            notifications = request.notifications,
                            interactive = request.snapshot?.let(PassEvaluator::interactive) ?: Tri.UNKNOWN,
                            suppressedBy = evaluation.suppressedBy,
                            recent = recent,
                            latestOwn = latestOwn,
                            snoozeFollowUp = evaluation.point.snoozeFollowUp,
                        ),
                    )
                    val failure = gates.firstFailure
                    if (failure != null) {
                        rows += gatedRow(request, evaluation, gates.checks, DecisionState.SUPPRESSED, failure.gate)
                    } else {
                        contenders += Contender(evaluation, gates, latestOwn?.cooldownAnchor?.wall)
                    }
                }
            }
        }
        rows += arbitrate(request, contenders)
        rows += request.missed.filter { it.key !in existing && seen.add(it.key) }.map { missedRow(request, it) }
        val written = rows.mapNotNull { row -> if (tx.insertIfAbsent(row)) tx.decision(row.decisionKey) ?: row else null }
        log.forEach { tx.appendEvalLog(it) }
        request.runtime.forEach { update -> tx.putRuntime(update.transform(tx.runtime(update.jitaiId))) }
        request.watermark?.let { tx.advanceWatermark(it.to) }
        return CommitResult.Committed(written, log)
    }

    private class Contender(val evaluation: CandidateEvaluation, val gates: GateReport, val lastDelivered: Instant?)

    /** G16 and step 7: the best contender is DECIDED (or NOT_RANDOMIZED), the others lose arbitration. */
    private fun arbitrate(request: CommitRequest, contenders: List<Contender>): List<DecisionRecord> {
        if (contenders.isEmpty()) return emptyList()
        val byId = contenders.associateBy { it.evaluation.point.definition.id }
        val ranked = Arbitration.rank(
            contenders.map {
                val definition = it.evaluation.point.definition
                Arbitration.Contender(definition, EffectiveLimits.of(definition).priority, it.lastDelivered)
            },
        ).map { byId.getValue(it.definition.id) }
        val winner = ranked.first()
        val winnerChecks = winner.gates.checks + GateCheck(ReasonCode.LOST_ARBITRATION, true, "contenders=${ranked.size}")
        val losers = ranked.drop(1).map { loser ->
            val checks =
                loser.gates.checks + GateCheck(ReasonCode.LOST_ARBITRATION, false, "winner=${winner.evaluation.point.definition.id}")
            gatedRow(request, loser.evaluation, checks, DecisionState.SUPPRESSED, ReasonCode.LOST_ARBITRATION)
        }
        return listOf(randomized(request, winner.evaluation, winnerChecks)) + losers
    }

    private fun randomized(request: CommitRequest, evaluation: CandidateEvaluation, checks: List<GateCheck>): DecisionRecord {
        val key = evaluation.point.key
        val probability = MicroRandomization.probability(evaluation.point.definition.experiment)
            ?: return gatedRow(request, evaluation, checks, DecisionState.DECIDED, null).copy(nonce = nonces.nextNonce())
        val salt = request.salt ?: error("micro-randomized decision without an install salt")
        val draw = MicroRandomization.draw(salt, key)
        val deliver = MicroRandomization.deliver(salt, key, probability)
        val state = if (deliver) DecisionState.DECIDED else DecisionState.NOT_RANDOMIZED
        val row = gatedRow(request, evaluation, checks, state, null, probability, draw)
        return if (deliver) row.copy(nonce = nonces.nextNonce()) else row
    }

    private fun base(request: CommitRequest, evaluation: CandidateEvaluation, state: DecisionState): DecisionRecord {
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
            decided = request.now,
            zoneId = request.zone.id,
            localDateTime = request.now.wall.toLocalDateTime(request.zone),
            engineDay = EngineDays.of(request.now.wall, request.zone, request.settings.rolloverMinute),
            conditionsResult = evaluation.conditions.result,
            contextResult = evaluation.context?.result,
            impliedState = point.impliedState,
        )
    }

    private fun content(request: CommitRequest, evaluation: CandidateEvaluation, trace: DecisionTrace): DecisionContent {
        val snapshot = request.snapshot
        return DecisionContent(
            snapshotJson = snapshot?.let { json.encodeToString(FeatureSnapshot.serializer(), PassEvaluator.subset(it, evaluation.point)) },
            snapshotHash = snapshot?.let { SnapshotHashes.contextHash(evaluation.point.definition, it) },
            traceJson = TraceCodec.encode(trace),
            traceSummaryJson = TraceCodec.summary(trace),
        )
    }

    private fun outcomeRow(request: CommitRequest, evaluation: CandidateEvaluation): DecisionRecord {
        val state = evaluation.outcome ?: DecisionState.UNKNOWN
        val reason = stateChanged(evaluation)
        val trace = DecisionTrace(evaluation.conditions, evaluation.context, reason = reason)
        return base(request, evaluation, state).copy(reason = reason, content = content(request, evaluation, trace))
    }

    private fun gatedRow(
        request: CommitRequest,
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
        return base(request, evaluation, state).copy(
            reason = reason,
            reasonDetail = failed?.detail,
            randProbability = probability,
            randDraw = draw,
            content = content(request, evaluation, trace),
        )
    }

    private fun logEntry(request: CommitRequest, evaluation: CandidateEvaluation): EvalLogEntry {
        val outcome = when (evaluation.outcome) {
            DecisionState.NOT_AVAILABLE -> EvalOutcome.NOT_AVAILABLE
            DecisionState.UNKNOWN -> EvalOutcome.UNKNOWN
            else -> EvalOutcome.NOT_TRIGGERED
        }
        val reason = stateChanged(evaluation)
        val trace = DecisionTrace(evaluation.conditions, evaluation.context, reason = reason)
        return EvalLogEntry(
            at = request.now.wall,
            jitaiId = evaluation.point.definition.id,
            triggerKind = evaluation.point.kind,
            eventType = evaluation.point.eventType,
            outcome = outcome,
            reason = reason,
            traceJson = TraceCodec.encode(trace),
        )
    }

    /** STATE_CHANGED when the rules passed but the live state the event implied was already gone. */
    private fun stateChanged(evaluation: CandidateEvaluation): ReasonCode? =
        if (evaluation.conditions.result == Tri.TRUE && evaluation.context?.result == Tri.TRUE && !evaluation.impliedHolds) {
            ReasonCode.STATE_CHANGED
        } else {
            null
        }

    private fun missedRow(request: CommitRequest, missed: MissedPoint): DecisionRecord {
        val definition = missed.definition
        val shift = request.now.wall - missed.slotAt
        val decided =
            MonotonicStamp(missed.slotAt, (request.now.elapsedMillis - shift.inWholeMilliseconds).coerceAtLeast(0), request.now.bootCount)
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
            localDateTime = missed.slotAt.toLocalDateTime(request.zone),
            engineDay = EngineDays.of(missed.slotAt, request.zone, request.settings.rolloverMinute),
            reason = missed.reason,
            reasonDetail = "late=${shift.coerceAtLeast(0.milliseconds).inWholeSeconds}s",
            content = DecisionContent(traceJson = TraceCodec.encode(trace), traceSummaryJson = TraceCodec.summary(trace)),
        )
    }
}

package dev.agentle.jitai.engine

import dev.agentle.analytics.features.FeatureResolver
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.core.common.onFailure
import dev.agentle.core.common.outcomeOf
import dev.agentle.core.time.AgentleClock
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.OutcomeRole
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.engine.content.ContentRenderer
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.delivery.DeliveryCoordinator
import dev.agentle.jitai.engine.delivery.DeliveryEnvironment
import dev.agentle.jitai.engine.delivery.DeliveryProtocol
import dev.agentle.jitai.engine.delivery.PendingCard
import dev.agentle.jitai.engine.eval.RuleEvaluator
import dev.agentle.jitai.engine.gates.Backoff
import dev.agentle.jitai.engine.gates.MicroRandomization
import dev.agentle.jitai.engine.outcome.OutcomeDataPort
import dev.agentle.jitai.engine.outcome.OutcomeEvaluator
import dev.agentle.jitai.engine.outcome.OutcomePlanner
import dev.agentle.jitai.engine.outcome.OutcomePositivity
import dev.agentle.jitai.engine.outcome.OutcomeResult
import dev.agentle.jitai.engine.outcome.OutcomeState
import dev.agentle.jitai.engine.pipeline.CandidateEvaluation
import dev.agentle.jitai.engine.pipeline.CommitRequest
import dev.agentle.jitai.engine.pipeline.CommitResolver
import dev.agentle.jitai.engine.pipeline.CommitResult
import dev.agentle.jitai.engine.pipeline.DecisionPoint
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.pipeline.EventsReport
import dev.agentle.jitai.engine.pipeline.LiveState
import dev.agentle.jitai.engine.pipeline.MissedPoint
import dev.agentle.jitai.engine.pipeline.PassEvaluator
import dev.agentle.jitai.engine.pipeline.PassKind
import dev.agentle.jitai.engine.pipeline.PassReport
import dev.agentle.jitai.engine.pipeline.PassWrites
import dev.agentle.jitai.engine.pipeline.PlanContext
import dev.agentle.jitai.engine.pipeline.RecoveryReport
import dev.agentle.jitai.engine.pipeline.RuntimeUpdate
import dev.agentle.jitai.engine.pipeline.StalenessRetry
import dev.agentle.jitai.engine.pipeline.SyncReason
import dev.agentle.jitai.engine.pipeline.SyncRequest
import dev.agentle.jitai.engine.pipeline.TimerReconciler
import dev.agentle.jitai.engine.pipeline.TimerReport
import dev.agentle.jitai.engine.pipeline.WatermarkAdvance
import dev.agentle.jitai.engine.ports.AiTextPoolPort
import dev.agentle.jitai.engine.ports.DailyFeatureRefresher
import dev.agentle.jitai.engine.ports.DecisionStore
import dev.agentle.jitai.engine.ports.DeliveryPort
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.ports.JitaiRepositoryPort
import dev.agentle.jitai.engine.ports.JitaiRuntimeState
import dev.agentle.jitai.engine.ports.NonceSource
import dev.agentle.jitai.engine.ports.NotificationSystemState
import dev.agentle.jitai.engine.ports.PendingTriggerEvent
import dev.agentle.jitai.engine.ports.ResponseWrite
import dev.agentle.jitai.engine.ports.RetentionCutoffs
import dev.agentle.jitai.engine.ports.RetentionReport
import dev.agentle.jitai.engine.ports.SecureNonceSource
import dev.agentle.jitai.engine.ports.SettingsPort
import dev.agentle.jitai.engine.ports.TriggerEvent
import dev.agentle.jitai.engine.ports.TriggerEventFeed
import dev.agentle.jitai.engine.response.EngagementBackoff
import dev.agentle.jitai.engine.response.Nonces
import dev.agentle.jitai.engine.response.ResponseReport
import dev.agentle.jitai.engine.response.ResponseStatus
import dev.agentle.jitai.engine.response.SnoozeCalculator
import dev.agentle.jitai.engine.response.SnoozePolicies
import dev.agentle.jitai.engine.schedule.Effectiveness
import dev.agentle.jitai.engine.schedule.EventPolicy
import dev.agentle.jitai.engine.schedule.ReplanReason
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.TimerChecks
import dev.agentle.jitai.engine.schedule.TimerKind
import dev.agentle.jitai.engine.schedule.TimerPlan
import dev.agentle.jitai.engine.schedule.TimerRow
import dev.agentle.jitai.engine.schedule.TimerVerdict
import dev.agentle.jitai.engine.time.BootCountSource
import dev.agentle.jitai.engine.time.EngineClock
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import dev.agentle.jitai.engine.time.elapsedBetween
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The ports the engine talks to. All are interfaces declared in this module (plus [FeatureResolver], the shared
 * contract of `:analytics:features`): rules read metrics only through [features].
 *
 * @property outcomeData outcome measurement and storage; without it OUTCOME timer rows are dropped unrecorded.
 * @property dailyRefresh recomputes dirty days before a pass takes its snapshot (jitai-correctness-08); optional.
 */
public class EnginePorts(
    public val repository: JitaiRepositoryPort,
    public val store: DecisionStore,
    public val delivery: DeliveryPort,
    public val settings: SettingsPort,
    public val features: FeatureResolver,
    public val events: TriggerEventFeed,
    public val aiTexts: AiTextPoolPort,
    public val outcomeData: OutcomeDataPort? = null,
    public val dailyRefresh: DailyFeatureRefresher? = null,
)

/** Result of a definition change: cancelled unclaimed rows (R10 §12.N6), purged pool items and the new timer table. */
public data class DefinitionChangeReport(val cancelled: List<DeliveryResult>, val purgedTexts: Int, val plan: TimerPlan)

/** Result of [JitaiEngine.applyRetention]: the ledger and logs (R10 §8.8) and the expired AI pool items (24 h). */
public data class MaintenanceReport(val retention: RetentionReport, val expiredTexts: Int)

/**
 * The JITAI decision engine (R10 §7-§9 with the architecture red-team and integrator corrections). Every public method
 * is one worker entry point; each returns an [Outcome] and never throws for expected failures (cancellation is
 * rethrown). Decisions are idempotent: running any entry point twice, concurrently or after a crash never delivers twice
 * (decision keys, insert-if-absent, the claim transaction and the serialized commit of [DecisionStore.commit]).
 *
 * Scheduling is a persisted timer table (jitai-correctness-05/07): the background team keeps one unique one-time work,
 * [SchedulePlanner.TIMER_WORK], aimed at the minimum `dueAt`, and calls [runTimer] when it fires; every entry point
 * reports where to aim next. Inside one process the evaluating entry points are serialized ([runTimer], [runEvents],
 * [replan], [recover], [sweepIgnored], [onDefinitionChanged]); across workers the store's serialized commit and the
 * claim transaction keep every cap.
 *
 * Time comes only from [clock] (wall, elapsed and zone) plus [boot]; the zone is re-read at the start of every pass and
 * the JVM default zone is never used.
 */
public class JitaiEngine(
    private val ports: EnginePorts,
    clock: AgentleClock,
    boot: BootCountSource,
    nonces: NonceSource = SecureNonceSource(),
    private val logger: Logger = Logger.NONE,
    private val evaluator: RuleEvaluator = RuleEvaluator(),
) {
    private val clock = EngineClock(clock, boot)
    private val coordinator = DeliveryCoordinator(ports, this.clock, logger, evaluator)
    private val resolver = CommitResolver(nonces)
    private val evaluatorLock = Mutex()

    /**
     * The `jitai-timer` work (jitai-correctness-05/07): crash recovery, the ignored sweep, then every timer row due within
     * the 2-minute coalescing window. PREFETCH rows become [SyncRequest]s, OUTCOME rows are computed and recorded, SLOT and
     * SNOOZE rows are verified ([TimerChecks]) and evaluated together in one pass that arbitrates across them, and a due
     * BACKSTOP drains the trigger events. Ends with the re-plan; [TimerReport.nextDueAt] is where the work aims next.
     */
    public suspend fun runTimer(): Outcome<TimerReport> = guard("timer") { serialized { timer() } }

    /**
     * The event worker `jitai-eval-events` (R10 §7.3; red team database-sync-04): clears the dirty flag, evaluates the
     * events after the watermark, and repeats while ingestion marked the store dirty again, at most [maxPasses] times.
     */
    public suspend fun runEvents(maxPasses: Int = DEFAULT_EVENT_PASSES): Outcome<EventsReport> =
        guard("events") { serialized { events(maxPasses) } }

    /**
     * Rebuilds every timer row from the current definitions at the current instant and zone (jitai-correctness-04): the
     * background team calls it on `TIME_SET` (CLOCK), `TIMEZONE_CHANGED`, an offset change, `BOOT_COMPLETED` and
     * `MY_PACKAGE_REPLACED`, then aims `jitai-timer` at [TimerPlan.nextDueAt].
     */
    public suspend fun replan(reason: ReplanReason): Outcome<TimerPlan> = guard("replan") { serialized { reconcile(context(), reason) } }

    /** Crash recovery alone (process start, boot; R10 §8.5). */
    public suspend fun recover(): Outcome<RecoveryReport> = guard("recover") { serialized { coordinator.recover(context().delivery) } }

    /**
     * A notification action or in-app response (R10 §8.7): checks the per-delivery [nonce], checks a snooze against the
     * rule's snooze policy, records the first response, applies the snooze (`snoozedUntil = max(old, new)`), plans the
     * one `R`-keyed follow-up for `RE_EVALUATE_AFTER`, and recounts the engagement backoff (pausing at 5) unless the
     * delivery prerequisite is unmet (jitai-correctness-13/14).
     */
    public suspend fun recordResponse(
        decisionKey: String,
        nonce: String,
        response: JitaiResponse,
        snooze: SnoozeOption? = null,
    ): Outcome<ResponseReport> = guard("response") {
        val record = ports.store.decision(decisionKey).getOrThrow()
        when {
            record == null -> ResponseReport(ResponseStatus.NOT_FOUND)

            !Nonces.matches(record.nonce, nonce) -> ResponseReport(ResponseStatus.REJECTED)

            else -> {
                val context = context()
                val definition = context.byId[record.jitaiId]
                val invalid = response == JitaiResponse.SNOOZED && (snooze == null || !SnoozePolicies.allows(definition, snooze))
                if (invalid) ResponseReport(ResponseStatus.INVALID_OPTION) else applyResponse(context, record, response, snooze)
            }
        }
    }

    /** The in-app card [decisionKey] was displayed (CARD_PENDING -> DELIVERED, jitai-correctness-13). */
    public suspend fun markCardDisplayed(decisionKey: String): Outcome<DeliveryResult> =
        guard("card_displayed") { coordinator.displayCard(decisionKey, context().delivery) }

    /**
     * The in-app cards waiting to be displayed (CARD_PENDING), oldest first, each rendered from its stored row (content
     * ref and snapshot, the in-app text), so they come back unchanged after a restart. Cards past
     * [DeliveryProtocol.cardExpiry] and cards of disabled or edited JITAIs are left out (recovery ends them). Read-only.
     */
    public suspend fun pendingCards(): Outcome<List<PendingCard>> =
        guard("pending_cards") { coordinator.pendingCards(context().delivery).getOrThrow() }

    /**
     * Marks DELIVERED decisions without a response as IGNORED once their notification timed out, or, without a timeout,
     * once their engine day ended (R10 §9.6). Returns the keys marked. [runTimer] runs it too.
     */
    public suspend fun sweepIgnored(): Outcome<List<String>> = guard("ignored") { serialized { ignored(context()) } }

    /**
     * A definition was saved, edited, disabled, deleted or expired (R10 §7.5, §9.5; jitai-correctness-05/16/17): the
     * repository already deleted its timer rows in the same transaction; this cancels its unclaimed rows (all of them when
     * it is no longer armed, those of an older version otherwise), purges pool texts written for other content and re-plans.
     */
    public suspend fun onDefinitionChanged(jitaiId: String): Outcome<DefinitionChangeReport> = guard("definition_changed") {
        serialized {
            val context = context()
            val definition = context.byId[jitaiId]
            val armed = definition?.takeIf { Effectiveness.isArmed(it) }
            val cancelled = coordinator.cancelUnclaimed(jitaiId, armed?.version)
            val purged = ports.aiTexts.purge(jitaiId, definition?.let(RuleCodec::contentHash))
                .onFailure { logger.w(COMPONENT, "pool purge failed", it) }
                .getOrNull() ?: 0
            DefinitionChangeReport(cancelled, purged, reconcile(context, ReplanReason.DEFINITION_CHANGED))
        }
    }

    /**
     * After an app upgrade: re-validates every stored definition that is not paused already with
     * `RuleValidator.revalidate`; each failing one is paused with a notice ([JitaiRepositoryPort.pauseInvalid]) and goes
     * through [onDefinitionChanged]. Returns how many were paused.
     */
    public suspend fun revalidateStoredDefinitions(): Outcome<Int> = guard("revalidate") {
        val now = clock.now().wall
        val failing = ports.repository.definitions().getOrThrow()
            .filter { it.status != JitaiStatus.PAUSED && it.status != JitaiStatus.ARCHIVED }
            .map { RuleValidator.revalidate(it) }
            .filterNot { it.isValid }
        failing.forEach { verdict ->
            ports.repository.pauseInvalid(verdict.jitaiId, verdict.codes.map { it.name }, now).getOrThrow()
            onDefinitionChanged(verdict.jitaiId).getOrThrow()
        }
        failing.size
    }

    /** The UTC offset in seconds the current timer plan was made with (null: no plan); drives DST checks below API 37. */
    public suspend fun plannedOffsetSeconds(): Outcome<Int?> = guard("planned_offset") {
        ports.store.timers().getOrThrow().firstNotNullOfOrNull { it.offsetSeconds }
    }

    /** Retention (R10 §8.8): ledger 400 days, evaluation log 30 days, full traces 90 days; pooled AI texts 24 hours. */
    public suspend fun applyRetention(): Outcome<MaintenanceReport> = guard("retention") {
        val now = clock.now().wall
        val retention = ports.store.applyRetention(
            RetentionCutoffs(
                ledgerBefore = now - LEDGER_DAYS.days,
                evalLogBefore = now - EVAL_LOG_DAYS.days,
                fullTraceBefore = now - TRACE_DAYS.days,
            ),
        ).getOrThrow()
        val expired = ports.aiTexts.purgeExpired(now - ContentRenderer.MAX_POOLED_AGE)
            .onFailure { logger.w(COMPONENT, "pool expiry failed", it) }
            .getOrNull() ?: 0
        MaintenanceReport(retention, expired)
    }

    /** "Delete intervention history": content goes, the content-free ledger stays (red team database-sync-06/07). */
    public suspend fun deleteInterventionHistory(): Outcome<Int> = guard("delete_history") {
        ports.store.deleteInterventionHistory().getOrThrow()
    }

    /** Computes the [role] outcome of [decisionKey] (R10 §8.7, §15.1); null without a row or an outcome spec. */
    public suspend fun computeOutcome(decisionKey: String, role: OutcomeRole): Outcome<OutcomeResult?> = guard("outcome") {
        val context = context()
        val record = ports.store.decision(decisionKey).getOrThrow()
        val definition = record?.let { context.byId[it.jitaiId] }
        val planned = if (record != null && definition != null) {
            OutcomePlanner.plan(record, definition, context.zone).firstOrNull { it.role == role }
        } else {
            null
        }
        if (record == null || planned == null) {
            null
        } else {
            OutcomeEvaluator.compute(planned, record, context.now.wall, context.zone, ports.outcomeData).getOrThrow()
        }
    }

    /** The minimum `dueAt` of the timer table: where `jitai-timer` must aim (null: nothing to wake for). */
    public suspend fun nextDueAt(): Outcome<Instant?> = guard("next_due") {
        ports.store.timers().getOrThrow().minOfOrNull { it.dueAt }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Context

    private class Context(
        val now: MonotonicStamp,
        val zone: TimeZone,
        val settings: EngineSettings,
        val notifications: NotificationSystemState,
        val definitions: List<JitaiDefinition>,
    ) {
        val byId: Map<String, JitaiDefinition> = definitions.associateBy { it.id }

        val delivery: DeliveryEnvironment get() = DeliveryEnvironment(byId, settings, zone)

        fun plan(reason: ReplanReason): PlanContext = PlanContext(
            reason = reason,
            now = now,
            zone = zone,
            definitions = definitions,
            backstopMinutes = settings.tickProfile.tickMinutes,
            listenerConnected = notifications.notificationListenerConnected,
        )

        /** The same definitions and settings at a later instant (the zone is re-read). */
        fun at(now: MonotonicStamp, zone: TimeZone): Context = Context(now, zone, settings, notifications, definitions)
    }

    private suspend fun context(): Context {
        val definitions = ports.repository.definitions().getOrThrow()
        val settings = ports.settings.engineSettings().getOrThrow().effective()
        val notifications = ports.settings.notificationState().getOrNull() ?: DeliveryProtocol.UNKNOWN_NOTIFICATION_STATE
        return Context(clock.now(), clock.zone(), settings, notifications, definitions)
    }

    private fun Context.later(): Context = at(clock.now(), clock.zone())

    private suspend fun generation(): String = ports.store.engineState().getOrThrow().dbGeneration

    private suspend fun reconcile(
        context: Context,
        reason: ReplanReason,
        deletes: Set<String> = emptySet(),
        puts: List<TimerRow> = emptyList(),
    ): TimerPlan = ports.store.commit(generation()) { tx ->
        deletes.forEach { tx.deleteTimer(it) }
        puts.forEach { tx.putTimer(it) }
        TimerReconciler.reconcile(tx, context.plan(reason))
    }.getOrThrow()

    // ---------------------------------------------------------------------------------------------------------------
    // The timer

    /** Due rows sorted into what each needs. */
    private class DueRows(
        val points: List<DecisionPoint>,
        val missed: List<MissedPoint>,
        val replan: Map<String, String>,
        val prefetches: List<TimerRow>,
        val outcomes: List<TimerRow>,
        val backstop: TimerRow?,
    )

    private suspend fun timer(): TimerReport {
        val start = context()
        val recovery = coordinator.recover(start.delivery)
        val ignored = ignored(start.later())
        val context = start.later()
        reconcile(context, ReplanReason.EVALUATION)
        val due = sort(context, ports.store.timers().getOrThrow().filter { it.dueAt <= context.now.wall + SchedulePlanner.COALESCE })
        val syncs = due.prefetches.mapNotNull { prefetch(it, context) }
        val outcomes = outcomes(context, due.outcomes)
        val pass = if (due.points.isEmpty() && due.missed.isEmpty() && due.replan.isEmpty()) {
            null
        } else {
            evaluate(context, due.points, due.missed, due.replan.keys)
        }
        val events = due.backstop?.let { eventPass(context.later())?.report }
        val end = context.later()
        val handled = due.prefetches.map { it.key } + outcomes.done + listOfNotNull(due.backstop?.key)
        // Expire first: the re-plan then reads the new status, so nextDueAt never aims at an expired rule's slot.
        val expired = expire(end)
        val plan = reconcile(if (expired.isEmpty()) end else context(), ReplanReason.EVALUATION, handled.toSet(), outcomes.reschedule)
        return TimerReport(
            at = context.now.wall,
            recovery = recovery,
            ignored = ignored,
            pass = pass?.copy(expired = expired),
            events = events,
            syncRequests = syncs + pass?.syncRequests.orEmpty(),
            outcomes = outcomes.results,
            replanned = due.replan.values.toList(),
            nextDueAt = plan.nextDueAt,
        )
    }

    private fun sort(context: Context, due: List<TimerRow>): DueRows {
        val points = mutableListOf<DecisionPoint>()
        val missed = mutableListOf<MissedPoint>()
        val replan = linkedMapOf<String, String>()
        due.filter { it.kind == TimerKind.SLOT || it.kind == TimerKind.SNOOZE }.forEach { row ->
            val definition = context.byId[row.jitaiId]
            val verdict = TimerChecks.verify(row, definition, context.now.wall, context.zone)
            when {
                definition == null -> replan[row.key] = "definition_missing"

                verdict is TimerVerdict.Evaluate ->
                    points += DecisionPoint(definition, verdict.key, verdict.kind, verdict.nominalAt, verdict.latestAt, timer = row)

                verdict is TimerVerdict.Missed ->
                    missed += MissedPoint(definition, verdict.key, verdict.kind, verdict.nominalAt, verdict.reason, timer = row)

                verdict is TimerVerdict.Replan -> replan[row.key] = verdict.why
            }
        }
        return DueRows(
            points = points,
            missed = missed,
            replan = replan,
            prefetches = due.filter { it.kind == TimerKind.PREFETCH },
            outcomes = due.filter { it.kind == TimerKind.OUTCOME },
            backstop = due.firstOrNull { it.kind == TimerKind.BACKSTOP },
        )
    }

    /** A due PREFETCH row of the current version becomes a sync request (R10 §7.4). */
    private fun prefetch(row: TimerRow, context: Context): SyncRequest? {
        val definition = context.byId[row.jitaiId] ?: return null
        val key = row.decisionKey ?: return null
        if (definition.version != row.version || row.featureIds.isEmpty()) return null
        return SyncRequest(definition.id, key, row.featureIds, SyncReason.PREFETCH)
    }

    /** Outcomes computed by a timer run: recorded results, rows to delete and PENDING rows to compute again. */
    private class OutcomeRun(val results: List<OutcomeResult>, val done: List<String>, val reschedule: List<TimerRow>)

    /** Due OUTCOME rows (R10 §8.7): final results are recorded, then their rows go; PENDING ones are due again later. */
    private suspend fun outcomes(context: Context, rows: List<TimerRow>): OutcomeRun {
        val results = mutableListOf<OutcomeResult>()
        val done = mutableListOf<String>()
        val reschedule = mutableListOf<TimerRow>()
        val now = context.now.wall
        rows.forEach { row ->
            val record = row.decisionKey?.let { ports.store.decision(it).getOrThrow() }
            val definition = record?.let { context.byId[it.jitaiId] }
            val planned = definition?.let { OutcomePlanner.plan(record, it, context.zone).firstOrNull { p -> p.role == row.role } }
            val port = ports.outcomeData
            if (record == null || planned == null || port == null) {
                done += row.key
                return@forEach
            }
            val result = OutcomeEvaluator.compute(planned, record, now, context.zone, port).getOrNull()
                ?.takeIf { it.state != OutcomeState.PENDING }
            if (result != null && port.record(result).getOrNull() != null) {
                results += result
                done += row.key
            } else {
                reschedule += row.copy(dueAt = minOf(now + OutcomePlanner.PENDING_RETRY, maxOf(planned.unavailableAt, now + 1.minutes)))
            }
        }
        return OutcomeRun(results, done, reschedule)
    }

    /** One serialized evaluation of the due decision points (jitai-correctness-05): one snapshot, one commit, arbitration. */
    private suspend fun evaluate(
        context: Context,
        points: List<DecisionPoint>,
        missed: List<MissedPoint>,
        deletes: Set<String>,
    ): PassReport {
        val prepared = prepare(context, points, missed.map { it.key })
        val retries = mutableListOf<StalenessRetry>()
        val evaluations = prepared.evaluations.filter { evaluation ->
            val retry = stalenessRetry(evaluation, context.now.wall)
            retry?.let { retries += it }
            retry == null
        }
        return commitAndDeliver(
            kind = PassKind.TIMER,
            context = context,
            prepared = Prepared(prepared.generation, prepared.snapshot, evaluations),
            writes = PassWrites(missed = missed, retries = retries, timerDeletes = deletes),
        ) ?: PassReport(PassKind.TIMER, context.now.wall)
    }

    /**
     * The `daily_at` staleness retry (R10 §8.2): when the point is UNKNOWN only because remote data is stale or not yet
     * synced, it runs again at slot + 10 or + 20 minutes (within the lateness) after a sync; otherwise null.
     */
    private fun stalenessRetry(evaluation: CandidateEvaluation, now: Instant): StalenessRetry? {
        val point = evaluation.point
        val latest = point.latestAt?.takeIf { point.kind == TriggerKind.DAILY_AT && point.timer != null } ?: return null
        val sync = PassEvaluator.retryableStaleness(evaluation) ?: return null
        val at = RETRY_OFFSETS_MINUTES.map { point.nominalAt + it.minutes }.firstOrNull { it > now && it <= latest }
        return at?.let { StalenessRetry(evaluation, it, sync) }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Events

    /** Result of one event pass; [more] when the batch was full and events remain after the new watermark. */
    private class EventPass(val report: PassReport, val more: Boolean)

    private suspend fun events(maxPasses: Int): EventsReport {
        val passes = mutableListOf<PassReport>()
        var more = false
        repeat(maxPasses.coerceAtLeast(1)) {
            ports.store.clearDirtyIfSet().getOrThrow()
            val pass = eventPass(context())
            if (pass != null) {
                passes += pass.report
                more = pass.more
                if (!more && !ports.store.engineState().getOrThrow().dirty) {
                    return EventsReport(passes, followUpNeeded = false, nextDueAt = currentNextDueAt())
                }
            }
        }
        val followUp = more || ports.store.engineState().getOrThrow().dirty
        return EventsReport(passes, followUpNeeded = followUp, nextDueAt = currentNextDueAt())
    }

    private suspend fun currentNextDueAt(): Instant? = ports.store.timers().getOrThrow().minOfOrNull { it.dueAt }

    /**
     * One event pass (R10 §7.3): per candidate rule the latest fresh semantic event wins; events older than their bound
     * are MISSED(EVENT_TOO_OLD) in the evaluation log; a rule evaluated less than `debounceSeconds` ago keeps the event
     * pending (the BACKSTOP row wakes for it); the watermark advances in the same transaction as the decisions. Null when
     * another pass moved the watermark first.
     */
    private suspend fun eventPass(context: Context): EventPass? {
        val state = ports.store.engineState().getOrThrow()
        val batch = ports.events.eventsAfter(state.watermark, EVENT_BATCH).getOrThrow().sortedBy { it.changeSeq }
        val rules = context.definitions.filter {
            Effectiveness.isArmed(it) && Effectiveness.isIntervention(it) && it.trigger is Trigger.Event
        }
        val runtimes = rules.associate { it.id to ports.store.runtime(it.id).getOrThrow() }
        if (batch.isEmpty() && runtimes.values.none { it.pendingEvent != null }) {
            return EventPass(PassReport(PassKind.EVENTS, context.now.wall), more = false)
        }
        val now = context.now
        val log = mutableListOf<EvalLogEntry>()
        val updates = mutableListOf<RuntimeUpdate>()
        val points = mutableListOf<DecisionPoint>()
        val semantic = batch.filter { it.isSemantic && it.type.isAvailable }
        rules.forEach { rule ->
            val trigger = rule.trigger as Trigger.Event
            val runtime = runtimes.getValue(rule.id)
            val pending = runtime.pendingEvent?.let { TriggerEvent(it.changeSeq, it.type, it.eventAt, activityState = it.activityState) }
            val matching = semantic.filter { it.type in trigger.events } + listOfNotNull(pending)
            val (old, fresh) = matching.partition { EventPolicy.isTooOld(it, now, context.settings) }
            old.maxByOrNull { it.changeSeq }?.let { event ->
                log += EvalLogEntry(now.wall, rule.id, TriggerKind.EVENT, event.type, EvalOutcome.MISSED, ReasonCode.EVENT_TOO_OLD)
            }
            val latest = fresh.maxWithOrNull(compareBy<TriggerEvent>({ it.eventAt }, { it.changeSeq }))
            if (latest == null) {
                if (pending != null) updates += RuntimeUpdate(rule.id) { it.copy(pendingEvent = null) }
                return@forEach
            }
            if (!Effectiveness.windowOpen(rule, now.wall, context.zone)) {
                log += EvalLogEntry(
                    now.wall,
                    rule.id,
                    TriggerKind.EVENT,
                    latest.type,
                    EvalOutcome.OUTSIDE_WINDOW,
                    ReasonCode.OUTSIDE_WINDOW,
                )
                updates += RuntimeUpdate(rule.id) { it.copy(pendingEvent = null) }
                return@forEach
            }
            val since = runtime.lastEventEvaluation?.let { elapsedBetween(it, now) }
            if (since != null && since < EventPolicy.debounce(trigger)) {
                log += EvalLogEntry(now.wall, rule.id, TriggerKind.EVENT, latest.type, EvalOutcome.DEBOUNCED, ReasonCode.DEBOUNCED)
                val held = PendingTriggerEvent(latest.changeSeq, latest.type, latest.eventAt, latest.activityState)
                updates += RuntimeUpdate(rule.id) { it.copy(pendingEvent = held) }
                return@forEach
            }
            points += DecisionPoint(
                definition = rule,
                key = DecisionKeys.event(rule.id, latest.eventAt),
                kind = TriggerKind.EVENT,
                nominalAt = latest.eventAt,
                eventType = latest.type,
                impliedState = EventPolicy.impliedState(latest.type, latest.activityState),
            )
            updates += RuntimeUpdate(rule.id) { it.copy(lastEventEvaluation = now, pendingEvent = null) }
        }
        val target = batch.maxOfOrNull { it.changeSeq } ?: state.watermark
        val report = commitAndDeliver(
            kind = PassKind.EVENTS,
            context = context,
            prepared = prepare(context, points, emptyList()),
            writes = PassWrites(
                evalLog = log,
                runtime = updates,
                watermark = WatermarkAdvance(state.watermark, maxOf(target, state.watermark)),
            ),
        ) ?: return null
        val expired = expire(context)
        return EventPass(report.copy(expired = expired), more = batch.size >= EVENT_BATCH)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Prepare, commit, deliver

    /** A prepared pass: the database [generation] it read from, its snapshot and the evaluated points. */
    private class Prepared(val generation: String, val snapshot: FeatureSnapshot?, val evaluations: List<CandidateEvaluation>)

    /**
     * Steps 2-4 of R10 §8.1 inside one read transaction (red team database-sync-05): drop points whose key exists, bring
     * the daily features of dirty days up to date (jitai-correctness-08), resolve every feature once, then evaluate the
     * rules and the SUPPRESSION rules against that one snapshot. The database generation is read first, so a commit after
     * a restore or replacement of the database is rejected (database-sync-04).
     */
    private suspend fun prepare(context: Context, points: List<DecisionPoint>, missedKeys: List<String>): Prepared {
        val generation = generation()
        if (points.isEmpty()) return Prepared(generation, null, emptyList())
        ports.dailyRefresh?.let { refresher ->
            outcomeOf(mapError = { AppError.Unexpected("daily_refresh:${it::class.simpleName}") }) {
                refresher.refreshDirtyDays(context.now.wall).getOrThrow()
            }.onFailure { logger.w(COMPONENT, "daily refresh failed", it) }
        }
        val suppressions = PassEvaluator.suppressions(context.definitions, context.now.wall)
        val (open, snapshot) = ports.store.readSnapshot { view ->
            val existing = view.existingKeys(points.map { it.key } + missedKeys)
            val open = points.filter { it.key !in existing }.distinctBy { it.key }
            open to if (open.isEmpty()) null else ports.features.resolve(PassEvaluator.refs(open, suppressions), context.now.wall)
        }.getOrThrow()
        if (snapshot == null) return Prepared(generation, null, emptyList())
        val blocking = PassEvaluator.blocking(suppressions, snapshot, context.zone, evaluator)
        return Prepared(generation, snapshot, open.map { PassEvaluator.evaluate(it, snapshot, blocking, evaluator) })
    }

    /** Commit, then deliver the DECIDED rows. Null when an event pass lost the watermark race. */
    private suspend fun commitAndDeliver(kind: PassKind, context: Context, prepared: Prepared, writes: PassWrites): PassReport? {
        val randomized = prepared.evaluations.any { it.eligible && MicroRandomization.probability(it.point.definition.experiment) != null }
        val request = CommitRequest(
            now = context.now,
            zone = context.zone,
            settings = context.settings,
            live = liveState(context, prepared.evaluations),
            salt = if (randomized) ports.settings.installSalt().getOrThrow() else null,
            snapshot = prepared.snapshot,
            evaluations = prepared.evaluations,
            writes = writes,
            plan = context.plan(ReplanReason.EVALUATION),
        )
        val committed = when (val result = ports.store.commit(prepared.generation) { tx -> resolver.commit(tx, request) }.getOrThrow()) {
            CommitResult.Stale -> return null
            is CommitResult.Committed -> result
        }
        val deliveries = committed.written.filter { it.state == DecisionState.DECIDED }.map { coordinator.deliver(it, context.delivery) }
        logPass(kind, committed.written, deliveries)
        return PassReport(
            kind = kind,
            at = context.now.wall,
            written = committed.written,
            evalLog = committed.evalLog,
            deliveries = deliveries,
            deferred = committed.deferred,
            syncRequests = writes.retries.map {
                SyncRequest(it.point.definition.id, it.point.key, it.syncFeatures, SyncReason.STALENESS_RETRY)
            },
            nextDueAt = committed.plan?.nextDueAt,
        )
    }

    /** G05 and G07 inputs read right before the commit: the delivery prerequisite of every eligible category. */
    private suspend fun liveState(context: Context, evaluations: List<CandidateEvaluation>): LiveState {
        val categories = evaluations.filter { it.eligible }.mapTo(linkedSetOf()) { it.point.definition.category }
        return LiveState(context.notifications.interruptionFilter, categories.associateWith { prerequisite(it) })
    }

    private suspend fun prerequisite(category: JitaiCategory): DeliveryPrerequisite =
        outcomeOf(mapError = { AppError.Unexpected("prerequisite:${it::class.simpleName}") }) {
            ports.delivery.prerequisite(category).getOrNull()
        }.getOrNull() ?: DeliveryPrerequisite.UNKNOWN

    /** G02 side effect (R10 §9.1): armed definitions past `expiresAt` move to EXPIRED (the repository deletes their timers). */
    private suspend fun expire(context: Context): List<String> = context.definitions
        .filter { Effectiveness.isArmed(it) && it.expiresAt?.let { at -> at <= context.now.wall } == true }
        .map { definition ->
            ports.repository.markExpired(definition.id, context.now.wall)
                .onFailure { logger.w(COMPONENT, "mark expired failed", it) }
            definition.id
        }

    // ---------------------------------------------------------------------------------------------------------------
    // Responses

    /** What the response transaction wrote. */
    private class Applied(val write: ResponseWrite, val runtime: JitaiRuntimeState?, val run: Int?, val plan: TimerPlan?)

    private suspend fun applyResponse(
        context: Context,
        record: DecisionRecord,
        response: JitaiResponse,
        snooze: SnoozeOption?,
    ): ResponseReport {
        val now = context.now
        val definition = context.byId[record.jitaiId]
        val policy = SnoozePolicies.of(definition)
        val option = snooze?.takeIf { response == JitaiResponse.SNOOZED }
        val target = option?.let {
            SnoozeCalculator.until(it, now, definition, context.zone, context.settings.rolloverMinute, context.settings.quietHours)
        }
        val followUp = option != null && SnoozePolicies.plansFollowUp(policy, option) && record.triggerKind != TriggerKind.SNOOZE_FOLLOW_UP
        // Never back off or pause while notifications cannot be delivered (jitai-correctness-13).
        val blocked = !prerequisite(record.category).met
        val positive = if (blocked) emptySet() else positiveOutcomes(context, record.jitaiId)
        val applied = ports.store.commit(generation()) { tx ->
            val write = tx.recordResponse(record.decisionKey, response, now.wall)
            if (write == ResponseWrite.NOT_FOUND) return@commit Applied(write, null, null, null)
            var runtime = tx.runtime(record.jitaiId)
            if (target != null) {
                runtime = runtime.copy(
                    snoozedUntil = SnoozeCalculator.later(runtime.snoozedUntil, target),
                    snoozeMode = policy.mode,
                    followUpOf = if (followUp) record.decisionKey else runtime.followUpOf,
                )
            }
            val run = if (blocked) {
                null
            } else {
                val recent = tx.recentCounted(record.jitaiId, EngagementBackoff.SCAN_ROWS)
                EngagementBackoff.consecutiveIgnored(recent) { it.decisionKey in positive }
            }
            run?.let { runtime = runtime.copy(consecutiveIgnored = it) }
            tx.putRuntime(runtime)
            Applied(write, runtime, run, TimerReconciler.reconcile(tx, context.plan(ReplanReason.RESPONSE)))
        }.getOrThrow()
        val status = when (applied.write) {
            ResponseWrite.RECORDED -> ResponseStatus.RECORDED
            ResponseWrite.ALREADY_RESPONDED -> ResponseStatus.ALREADY_RESPONDED
            ResponseWrite.NOT_FOUND -> return ResponseReport(ResponseStatus.NOT_FOUND)
        }
        val run = applied.run
        val paused = run != null && run >= Backoff.PAUSE_AT && definition?.let { Effectiveness.isArmed(it) } == true &&
            ports.repository.pauseForBackoff(record.jitaiId, run, now.wall).getOrNull() != null
        val snoozedUntil = applied.runtime?.snoozedUntil?.takeIf { target != null }
        return ResponseReport(
            status = status,
            snoozedUntil = snoozedUntil,
            followUpAt = snoozedUntil?.takeIf { followUp }?.let { SchedulePlanner.wallOf(it, now) },
            consecutiveIgnored = run,
            paused = paused,
            nextTimerAt = if (paused) currentNextDueAt() else applied.plan?.nextDueAt,
        )
    }

    /** DISMISSED rows of [jitaiId] whose proximal outcome is positive (jitai-correctness-14; [OutcomePositivity]). */
    private suspend fun positiveOutcomes(context: Context, jitaiId: String): Set<String> {
        val definition = context.byId[jitaiId] ?: return emptySet()
        if (definition.outcome == null) return emptySet()
        val rows = ports.store.readSnapshot { it.recentCounted(jitaiId, EngagementBackoff.SCAN_ROWS) }.getOrThrow()
        return rows.filter { it.state == DecisionState.DELIVERED && it.content.response == JitaiResponse.DISMISSED }.filter { row ->
            val planned = OutcomePlanner.plan(row, definition, context.zone).firstOrNull { it.role == OutcomeRole.PROXIMAL }
            val result = planned?.let { OutcomeEvaluator.compute(it, row, context.now.wall, context.zone, ports.outcomeData).getOrNull() }
            result != null && OutcomePositivity.isPositive(result)
        }.mapTo(linkedSetOf()) { it.decisionKey }
    }

    private suspend fun ignored(context: Context): List<String> {
        val marked = mutableListOf<String>()
        context.definitions.filter { Effectiveness.isIntervention(it) }.forEach { definition ->
            val rows = ports.store.readSnapshot { it.recentCounted(definition.id, EngagementBackoff.SCAN_ROWS) }.getOrThrow()
            rows.filter { it.state == DecisionState.DELIVERED && it.content.response == JitaiResponse.NONE }.forEach { row ->
                val delivered = row.delivered?.wall ?: row.decisionPointAt
                val due = definition.delivery.notificationTimeoutMinutes?.let { delivered + it.minutes }
                    ?: EngineDays.nextRollover(delivered, context.zone, context.settings.rolloverMinute)
                if (context.now.wall >= due) {
                    applyResponse(context, row, JitaiResponse.IGNORED, null)
                    marked += row.decisionKey
                }
            }
        }
        return marked
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun logPass(kind: PassKind, written: List<DecisionRecord>, deliveries: List<DeliveryResult>) {
        if (written.isEmpty() && deliveries.isEmpty()) return
        logger.i(
            COMPONENT,
            "pass committed",
            mapOf(
                "kind" to kind.name,
                "rows" to written.size,
                "states" to written.groupingBy { it.state.name }.eachCount().toSortedMap().toString(),
                "delivered" to deliveries.count { it is DeliveryResult.Delivered },
            ),
        )
    }

    private suspend fun <T> serialized(block: suspend () -> T): T = evaluatorLock.withLock { block() }

    private suspend fun <T> guard(step: String, block: suspend () -> T): Outcome<T> =
        outcomeOf(mapError = { AppError.Unexpected("$step:${it::class.simpleName}") }) { block() }
            .onFailure { logger.w(COMPONENT, "engine step failed", it, mapOf("step" to step)) }

    public companion object {
        public const val DEFAULT_EVENT_PASSES: Int = 3
        public const val EVENT_BATCH: Int = 500
        public const val EVENTS_WORK: String = "jitai-eval-events"
        public const val LEDGER_DAYS: Int = 400
        public const val EVAL_LOG_DAYS: Int = 30
        public const val TRACE_DAYS: Int = 90
        private val RETRY_OFFSETS_MINUTES = listOf(10, 20)
        private const val COMPONENT = "jitai-engine"
    }
}

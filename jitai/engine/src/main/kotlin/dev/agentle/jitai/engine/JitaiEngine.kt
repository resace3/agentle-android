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
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.OutcomeRole
import dev.agentle.jitai.dsl.model.SnoozeMode
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.dsl.model.SnoozePolicy
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionRecord
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.delivery.DeliveryCoordinator
import dev.agentle.jitai.engine.delivery.DeliveryProtocol
import dev.agentle.jitai.engine.eval.RuleEvaluator
import dev.agentle.jitai.engine.gates.Backoff
import dev.agentle.jitai.engine.gates.MicroRandomization
import dev.agentle.jitai.engine.outcome.OutcomeDataPort
import dev.agentle.jitai.engine.outcome.OutcomeEvaluator
import dev.agentle.jitai.engine.outcome.OutcomePlanner
import dev.agentle.jitai.engine.outcome.OutcomeResult
import dev.agentle.jitai.engine.outcome.PlannedOutcome
import dev.agentle.jitai.engine.pipeline.CandidateEvaluation
import dev.agentle.jitai.engine.pipeline.CommitRequest
import dev.agentle.jitai.engine.pipeline.CommitResolver
import dev.agentle.jitai.engine.pipeline.CommitResult
import dev.agentle.jitai.engine.pipeline.DailyAtReport
import dev.agentle.jitai.engine.pipeline.DailyAtStatus
import dev.agentle.jitai.engine.pipeline.DecisionPoint
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.pipeline.EventsReport
import dev.agentle.jitai.engine.pipeline.MissedPoint
import dev.agentle.jitai.engine.pipeline.PassEvaluator
import dev.agentle.jitai.engine.pipeline.PassKind
import dev.agentle.jitai.engine.pipeline.PassReport
import dev.agentle.jitai.engine.pipeline.RecoveryReport
import dev.agentle.jitai.engine.pipeline.RuntimeUpdate
import dev.agentle.jitai.engine.pipeline.TickReport
import dev.agentle.jitai.engine.pipeline.WatermarkAdvance
import dev.agentle.jitai.engine.ports.AiTextPoolPort
import dev.agentle.jitai.engine.ports.DecisionStore
import dev.agentle.jitai.engine.ports.DeliveryPort
import dev.agentle.jitai.engine.ports.EngineSettings
import dev.agentle.jitai.engine.ports.EvalLogEntry
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.ports.JitaiRepositoryPort
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
import dev.agentle.jitai.engine.schedule.DailyAtSlots
import dev.agentle.jitai.engine.schedule.Effectiveness
import dev.agentle.jitai.engine.schedule.EventPolicy
import dev.agentle.jitai.engine.schedule.IntervalSlots
import dev.agentle.jitai.engine.schedule.MissedSlot
import dev.agentle.jitai.engine.schedule.PlannedWork
import dev.agentle.jitai.engine.schedule.RescheduleSignal
import dev.agentle.jitai.engine.schedule.SchedulePlan
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.WorkInput
import dev.agentle.jitai.engine.schedule.WorkPolicy
import dev.agentle.jitai.engine.time.BootCountSource
import dev.agentle.jitai.engine.time.EngineClock
import dev.agentle.jitai.engine.time.EngineDays
import dev.agentle.jitai.engine.time.MonotonicStamp
import dev.agentle.jitai.engine.time.elapsedBetween
import dev.agentle.jitai.engine.time.isBefore
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The ports the engine talks to. All are interfaces declared in this module (plus [FeatureResolver], the shared
 * contract of `:analytics:features`): rules read metrics only through [features].
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
)

/** A schedule plan plus the MISSED rows it resolved (R10 §7.4, §12.O7). */
public data class PlanReport(val plan: SchedulePlan, val missed: PassReport?)

/** Result of a definition change: cancelled unclaimed rows (R10 §12.N6) and the re-plan. */
public data class DefinitionChangeReport(val cancelled: List<DeliveryResult>, val plan: PlanReport)

/**
 * The JITAI decision engine (R10 §7-§9 with the architecture red-team corrections). Every public method is one worker
 * entry point; each returns an [Outcome] and never throws for expected failures (cancellation is rethrown). Decisions
 * are idempotent: running any entry point twice, concurrently or after a crash never delivers twice (decision keys,
 * insert-if-absent, conditional updates and the serialized commit of [DecisionStore.commit]).
 *
 * Time comes only from [clock] (wall, elapsed and zone) plus [boot]; the zone is re-read at the start of every pass.
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
    private val coordinator = DeliveryCoordinator(ports, this.clock, logger)
    private val resolver = CommitResolver(nonces)

    /**
     * The periodic `jitai-tick` (R10 §7.4): crash recovery, the interval pass (current slots plus MISSED rows for slots
     * no tick reached), an event pass as a backstop, and the next tick (override past inactive windows).
     */
    public suspend fun runTick(): Outcome<TickReport> = guard("tick") { tick() }

    /**
     * The event worker `jitai-eval-events` (R10 §7.3; red team database-sync-04): clears the dirty flag, evaluates the
     * events after the watermark, and repeats while ingestion marked the store dirty again, at most [maxPasses] times.
     * [EventsReport.followUpNeeded] asks the caller to enqueue one more run.
     */
    public suspend fun runEvents(maxPasses: Int = DEFAULT_EVENT_PASSES): Outcome<EventsReport> = guard("events") { events(maxPasses) }

    /** One `daily_at` work unit for ([date], [time]) (R10 §7.4; red team lifecycle-battery-04). */
    public suspend fun runDailyAt(jitaiId: String, date: LocalDate, time: String): Outcome<DailyAtReport> =
        guard("daily_at") { dailyAt(jitaiId, date, time) }

    /** The `RE_EVALUATE_AFTER` follow-up of [originalKey] (R10 §9.5, key `R`). Null when nothing was due. */
    public suspend fun runSnoozeFollowUp(originalKey: String): Outcome<PassReport?> =
        guard("snooze_follow_up") { snoozeFollowUp(originalKey) }

    /** Crash recovery alone (process start, boot; R10 §8.5). */
    public suspend fun recover(): Outcome<RecoveryReport> = guard("recover") {
        val context = context()
        coordinator.recover(context.byId, context.settings)
    }

    /**
     * A notification action or in-app response (R10 §8.7): checks the per-delivery [nonce], records the first response,
     * applies a snooze ([snooze], `snoozedUntil = max(old, new)`), plans the `RE_EVALUATE_AFTER` follow-up and recounts the
     * engagement backoff (pausing at 5).
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
            else -> applyResponse(record, response, snooze)
        }
    }

    /**
     * Marks DELIVERED decisions without a response as IGNORED once their notification timed out, or, without a timeout,
     * once their engine day ended (R10 §9.6). Returns the keys marked.
     */
    public suspend fun sweepIgnored(): Outcome<List<String>> = guard("ignored") { ignored() }

    /** A definition was saved, edited, disabled or deleted (R10 §7.5, §9.5): cancel unclaimed rows if needed and re-plan. */
    public suspend fun onDefinitionChanged(jitaiId: String): Outcome<DefinitionChangeReport> = guard("definition_changed") {
        val context = context()
        val definition = context.byId[jitaiId]
        val cancelled = if (definition == null || !Effectiveness.isEffective(definition, context.now.wall)) {
            coordinator.cancelUnclaimed(jitaiId)
        } else {
            emptyList()
        }
        DefinitionChangeReport(cancelled, plan(context, RescheduleSignal.DEFINITION_CHANGED, jitaiId))
    }

    /**
     * Recomputes the schedule (R10 §7.5): `daily_at` work per date, prefetches, the tick and its override, cadence and
     * availability reports. Missed `daily_at` slots are written as MISSED rows.
     */
    public suspend fun plan(signal: RescheduleSignal, changedJitaiId: String? = null): Outcome<PlanReport> = guard("plan") {
        plan(context(), signal, changedJitaiId)
    }

    /** Retention (R10 §8.8): ledger 400 days, evaluation log 30 days, full traces 90 days. */
    public suspend fun applyRetention(): Outcome<RetentionReport> = guard("retention") {
        val now = clock.now().wall
        ports.store.applyRetention(
            RetentionCutoffs(
                ledgerBefore = now - LEDGER_DAYS.days,
                evalLogBefore = now - EVAL_LOG_DAYS.days,
                fullTraceBefore =
                now - TRACE_DAYS.days,
            ),
        ).getOrThrow()
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

    // ---------------------------------------------------------------------------------------------------------------
    // Passes

    private class Context(
        val now: MonotonicStamp,
        val zone: TimeZone,
        val settings: EngineSettings,
        val notifications: NotificationSystemState,
        val definitions: List<JitaiDefinition>,
    ) {
        val byId: Map<String, JitaiDefinition> = definitions.associateBy { it.id }
    }

    private suspend fun context(): Context {
        val definitions = ports.repository.definitions().getOrThrow()
        val settings = ports.settings.engineSettings().getOrThrow().effective()
        val notifications = ports.settings.notificationState().getOrNull() ?: DeliveryProtocol.UNKNOWN_NOTIFICATION_STATE
        return Context(clock.now(), clock.zone(), settings, notifications, definitions)
    }

    private suspend fun tick(): TickReport {
        val start = context()
        val recovery = coordinator.recover(start.byId, start.settings)
        val context = context()
        val pass = intervalPass(context)
        val events = eventPass(context())
        val next = SchedulePlanner.tickPlan(
            context.definitions,
            context.now.wall,
            context.zone,
            context.settings.tickProfile,
            afterCurrentSlot = true,
        )
        return TickReport(recovery, pass, events?.report, next)
    }

    private suspend fun intervalPass(context: Context): PassReport {
        val points = mutableListOf<DecisionPoint>()
        val missed = mutableListOf<MissedPoint>()
        context.definitions
            .filter { Effectiveness.isArmed(it) && Effectiveness.isIntervention(it) && it.trigger is Trigger.Interval }
            .forEach { definition ->
                IntervalSlots.currentSlot(definition, context.now.wall, context.zone)?.let { slot ->
                    points += DecisionPoint(definition, slot.key(definition.id), TriggerKind.INTERVAL)
                }
                IntervalSlots.unreached(definition, context.now.wall, context.zone, notBefore = definition.modifiedAt).forEach { slot ->
                    missed +=
                        MissedPoint(definition, slot.key(definition.id), TriggerKind.INTERVAL, slot.start, ReasonCode.SLOT_NOT_REACHED)
                }
            }
        return runPass(PassKind.TICK, context, points, missed) ?: PassReport(PassKind.TICK, context.now.wall)
    }

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
                if (!more && !ports.store.engineState().getOrThrow().dirty) return EventsReport(passes, followUpNeeded = false)
            }
        }
        return EventsReport(passes, followUpNeeded = more || ports.store.engineState().getOrThrow().dirty)
    }

    /**
     * One event pass (R10 §7.3): per candidate rule the latest fresh semantic event wins; events older than their bound
     * are MISSED(EVENT_TOO_OLD) in the evaluation log; a rule evaluated less than `debounceSeconds` ago keeps the event
     * pending; the watermark advances in the same transaction as the decisions. Null when another pass moved the
     * watermark first.
     */
    private suspend fun eventPass(context: Context): EventPass? {
        val state = ports.store.engineState().getOrThrow()
        val batch = ports.events.eventsAfter(state.watermark, EVENT_BATCH).getOrThrow().sortedBy { it.changeSeq }
        val rules = context.definitions.filter {
            Effectiveness.isArmed(it) && Effectiveness.isIntervention(it) &&
                it.trigger is Trigger.Event
        }
        val runtimes = rules.associate { it.id to ports.store.runtime(it.id).getOrThrow() }
        if (batch.isEmpty() && runtimes.values.none { it.pendingEvent != null }) {
            return EventPass(PassReport(PassKind.EVENTS, context.now.wall), more = false)
        }
        val now = context.now
        val log = mutableListOf<EvalLogEntry>()
        val updates = mutableListOf<RuntimeUpdate>()
        val points = mutableListOf<DecisionPoint>()
        val wakeAt = mutableListOf<Instant>()
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
            val latest = fresh.maxWithOrNull(compareBy<TriggerEvent>({ it.eventAt }, { it.changeSeq })) ?: run {
                if (pending != null) updates += RuntimeUpdate(rule.id) { it.copy(pendingEvent = null) }
                return@forEach
            }
            if (!Effectiveness.windowOpen(rule, now.wall, context.zone)) {
                log +=
                    EvalLogEntry(now.wall, rule.id, TriggerKind.EVENT, latest.type, EvalOutcome.OUTSIDE_WINDOW, ReasonCode.OUTSIDE_WINDOW)
                updates += RuntimeUpdate(rule.id) { it.copy(pendingEvent = null) }
                return@forEach
            }
            val debounce = EventPolicy.debounce(trigger)
            val since = runtime.lastEventEvaluation?.let { elapsedBetween(it, now) }
            if (since != null && since < debounce) {
                log += EvalLogEntry(now.wall, rule.id, TriggerKind.EVENT, latest.type, EvalOutcome.DEBOUNCED, ReasonCode.DEBOUNCED)
                val held = PendingTriggerEvent(latest.changeSeq, latest.type, latest.eventAt, latest.activityState)
                updates += RuntimeUpdate(rule.id) { it.copy(pendingEvent = held) }
                wakeAt += now.wall + (debounce - since)
                return@forEach
            }
            points += DecisionPoint(
                definition = rule,
                key = DecisionKeys.event(rule.id, latest.eventAt),
                kind = TriggerKind.EVENT,
                eventType = latest.type,
                impliedState = EventPolicy.impliedState(latest.type, latest.activityState),
            )
            updates += RuntimeUpdate(rule.id) { it.copy(lastEventEvaluation = now, pendingEvent = null) }
        }
        val target = batch.maxOfOrNull { it.changeSeq } ?: state.watermark
        val report = runPass(
            kind = PassKind.EVENTS,
            context = context,
            points = points,
            evalLog = log,
            runtime = updates,
            watermark = WatermarkAdvance(state.watermark, maxOf(target, state.watermark)),
        ) ?: return null
        val more = batch.size >= EVENT_BATCH
        val followUp = listOfNotNull(
            wakeAt.minOrNull()?.let { eventsWork(it) },
            if (more) eventsWork(now.wall) else null,
        ).distinctBy { it.runAt }.take(1)
        return EventPass(report.copy(followUp = report.followUp + followUp), more)
    }

    private fun eventsWork(at: Instant) = PlannedWork(EVENTS_WORK, setOf(EVENTS_WORK), at, WorkInput.EventsFollowUp, WorkPolicy.KEEP)

    private suspend fun dailyAt(jitaiId: String, date: LocalDate, time: String): DailyAtReport {
        val context = context()
        val definition = context.byId[jitaiId]
        val trigger = definition?.trigger as? Trigger.DailyAt
        if (definition == null || trigger == null || time !in trigger.times ||
            !Effectiveness.isArmed(definition) || !Effectiveness.isIntervention(definition)
        ) {
            return DailyAtReport(DailyAtStatus.NOT_SCHEDULED, null, null)
        }
        val now = context.now
        val slot = DailyAtSlots.slotInstant(date, time, context.zone) ?: return DailyAtReport(DailyAtStatus.NOT_SCHEDULED, null, null)
        val key = DecisionKeys.dailyAt(jitaiId, date, time)
        val timing = DailyAtSlots.timing(now.wall, slot, trigger.maxLatenessMinutes)
        if (timing == DailyAtSlots.Timing.TOO_EARLY) {
            return DailyAtReport(DailyAtStatus.TOO_EARLY, slot, null, SchedulePlanner.dailyAtWork(jitaiId, date, time, runAt = slot))
        }
        val next = SchedulePlanner.nextAfterRun(definition, date, time, now.wall, context.zone)
        if (timing == DailyAtSlots.Timing.TOO_LATE) {
            val missed = MissedPoint(definition, key, TriggerKind.DAILY_AT, slot, ReasonCode.TOO_LATE)
            return DailyAtReport(DailyAtStatus.MISSED, slot, runPass(PassKind.DAILY_AT, context, emptyList(), listOf(missed)), next)
        }
        if (!Effectiveness.windowOpen(definition, now.wall, context.zone)) {
            val entry = EvalLogEntry(now.wall, jitaiId, TriggerKind.DAILY_AT, null, EvalOutcome.OUTSIDE_WINDOW, ReasonCode.OUTSIDE_WINDOW)
            val pass = runPass(PassKind.DAILY_AT, context, emptyList(), evalLog = listOf(entry))
            return DailyAtReport(DailyAtStatus.OUTSIDE_WINDOW, slot, pass, next)
        }
        val point = DecisionPoint(definition, key, TriggerKind.DAILY_AT)
        val prepared = prepare(context, listOf(point), emptyList())
        val retry = prepared.evaluations.firstOrNull()?.let { evaluation ->
            val sync = PassEvaluator.retryableStaleness(evaluation) ?: return@let null
            retryAt(slot, now.wall, trigger.maxLatenessMinutes)?.let { at -> sync to at }
        }
        if (retry != null) {
            val (sync, at) = retry
            val work = listOf(
                SchedulePlanner.dailyAtWork(jitaiId, date, time, runAt = at).let {
                    it.copy(uniqueName = "${it.uniqueName}-r${(at - slot).inWholeMinutes}", policy = WorkPolicy.KEEP)
                },
                PlannedWork(
                    uniqueName = "${DailyAtSlots.prefetchName(jitaiId, date, time)}-r${(at - slot).inWholeMinutes}",
                    tags = setOf(SchedulePlanner.jitaiTag(jitaiId), SchedulePlanner.TAG_PREFETCH),
                    runAt = now.wall,
                    input = WorkInput.Prefetch(jitaiId, date, time, sync),
                    policy = WorkPolicy.KEEP,
                ),
            )
            val pass = PassReport(PassKind.DAILY_AT, now.wall, retryAt = at, syncFeatures = sync, followUp = work)
            return DailyAtReport(DailyAtStatus.RETRY, slot, pass, next)
        }
        val pass = commitAndDeliver(PassKind.DAILY_AT, context, prepared, emptyList())
        return DailyAtReport(DailyAtStatus.EVALUATED, slot, pass, next)
    }

    /** The next staleness retry (slot + 10 or + 20 minutes, R10 §8.2) after [now] within the lateness bound, if any. */
    private fun retryAt(slot: Instant, now: Instant, maxLatenessMinutes: Int): Instant? =
        RETRY_OFFSETS_MINUTES.map { slot + it.minutes }.firstOrNull { it > now && it <= slot + maxLatenessMinutes.minutes }

    private suspend fun snoozeFollowUp(originalKey: String): PassReport? {
        val context = context()
        val original = ports.store.decision(originalKey).getOrThrow() ?: return null
        if (original.triggerKind == TriggerKind.SNOOZE_FOLLOW_UP) return null
        val definition = context.byId[original.jitaiId] ?: return null
        if (!Effectiveness.isArmed(definition) || !Effectiveness.isIntervention(definition)) return null
        val now = context.now
        val until = ports.store.runtime(definition.id).getOrThrow().snoozedUntil
        if (until != null && isBefore(now, until + (-DailyAtSlots.EARLY_TOLERANCE))) {
            return PassReport(
                PassKind.SNOOZE_FOLLOW_UP,
                now.wall,
                followUp = listOf(SnoozeCalculator.followUpWork(definition.id, originalKey, until.wall)),
            )
        }
        val key = DecisionKeys.snoozeFollowUp(definition.id, originalKey)
        if (!Effectiveness.windowOpen(definition, now.wall, context.zone)) {
            val entry =
                EvalLogEntry(
                    now.wall,
                    definition.id,
                    TriggerKind.SNOOZE_FOLLOW_UP,
                    null,
                    EvalOutcome.OUTSIDE_WINDOW,
                    ReasonCode.OUTSIDE_WINDOW,
                )
            return runPass(PassKind.SNOOZE_FOLLOW_UP, context, emptyList(), evalLog = listOf(entry))
        }
        val point = DecisionPoint(definition, key, TriggerKind.SNOOZE_FOLLOW_UP, snoozeFollowUp = true)
        return runPass(PassKind.SNOOZE_FOLLOW_UP, context, listOf(point))
    }

    private class Prepared(val snapshot: FeatureSnapshot?, val evaluations: List<CandidateEvaluation>)

    /**
     * Steps 2-4 of R10 §8.1 inside one read transaction (red team database-sync-05): drop points whose key exists, resolve
     * every feature once, then evaluate the rules and the SUPPRESSION rules against that one snapshot.
     */
    private suspend fun prepare(context: Context, points: List<DecisionPoint>, missedKeys: List<String>): Prepared {
        if (points.isEmpty()) return Prepared(null, emptyList())
        val suppressions = PassEvaluator.suppressions(context.definitions, context.now.wall)
        val (open, snapshot) = ports.store.readSnapshot { view ->
            val existing = view.existingKeys(points.map { it.key } + missedKeys)
            val open = points.filter { it.key !in existing }.distinctBy { it.key }
            open to if (open.isEmpty()) null else ports.features.resolve(PassEvaluator.refs(open, suppressions), context.now.wall)
        }.getOrThrow()
        if (snapshot == null) return Prepared(null, emptyList())
        val blocking = PassEvaluator.blocking(suppressions, snapshot, context.zone, evaluator)
        return Prepared(snapshot, open.map { PassEvaluator.evaluate(it, snapshot, blocking, evaluator) })
    }

    /** Prepare, commit, deliver, plan outcomes. Null when an event pass lost the watermark race. */
    private suspend fun runPass(
        kind: PassKind,
        context: Context,
        points: List<DecisionPoint>,
        missed: List<MissedPoint> = emptyList(),
        evalLog: List<EvalLogEntry> = emptyList(),
        runtime: List<RuntimeUpdate> = emptyList(),
        watermark: WatermarkAdvance? = null,
    ): PassReport? {
        val prepared = prepare(context, points, missed.map { it.key })
        return commitAndDeliver(kind, context, prepared, missed, evalLog, runtime, watermark)
    }

    private suspend fun commitAndDeliver(
        kind: PassKind,
        context: Context,
        prepared: Prepared,
        missed: List<MissedPoint>,
        evalLog: List<EvalLogEntry> = emptyList(),
        runtime: List<RuntimeUpdate> = emptyList(),
        watermark: WatermarkAdvance? = null,
    ): PassReport? {
        val needsCommit = prepared.evaluations.isNotEmpty() || missed.isNotEmpty() || evalLog.isNotEmpty() || runtime.isNotEmpty() ||
            (watermark != null && watermark.to != watermark.expected)
        if (!needsCommit) {
            val expired = expire(context)
            return PassReport(
                kind,
                context.now.wall,
                expired = expired,
                cancelTags = expired.mapTo(linkedSetOf(), SchedulePlanner::jitaiTag),
            )
        }
        val randomized = prepared.evaluations.any { it.eligible && MicroRandomization.probability(it.point.definition.experiment) != null }
        val salt = if (randomized) ports.settings.installSalt().getOrThrow() else null
        val generation = ports.store.engineState().getOrThrow().dbGeneration
        val request = CommitRequest(
            now = context.now,
            zone = context.zone,
            settings = context.settings,
            notifications = context.notifications,
            salt = salt,
            snapshot = prepared.snapshot,
            evaluations = prepared.evaluations,
            missed = missed,
            evalLog = evalLog,
            runtime = runtime,
            watermark = watermark,
        )
        val committed = when (val result = ports.store.commit(generation) { tx -> resolver.commit(tx, request) }.getOrThrow()) {
            CommitResult.Stale -> return null
            is CommitResult.Committed -> result
        }
        // After the commit, so the commit's G01 re-check still sees the definition as it was evaluated (R10 §12.M2).
        val expired = expire(context)
        val deliveries = committed.written.filter { it.state == DecisionState.DECIDED }.map { row ->
            coordinator.deliver(row, context.byId, context.settings)
        }
        val outcomes: List<PlannedOutcome> = committed.written.flatMap { row ->
            context.byId[row.jitaiId]?.let { OutcomePlanner.plan(row, it, context.zone) }.orEmpty()
        }
        logPass(kind, committed.written, deliveries)
        return PassReport(
            kind = kind,
            at = context.now.wall,
            written = committed.written,
            evalLog = committed.evalLog,
            deliveries = deliveries,
            expired = expired,
            cancelTags = expired.mapTo(linkedSetOf(), SchedulePlanner::jitaiTag),
            outcomes = outcomes,
        )
    }

    /** G02 side effect (R10 §9.1): armed definitions past `expiresAt` move to EXPIRED and their work is cancelled. */
    private suspend fun expire(context: Context): List<String> = context.definitions
        .filter { Effectiveness.isArmed(it) && it.expiresAt?.let { at -> at <= context.now.wall } == true }
        .map { definition ->
            ports.repository.markExpired(definition.id, context.now.wall)
                .onFailure { logger.w(COMPONENT, "mark expired failed", it) }
            definition.id
        }

    private suspend fun plan(context: Context, signal: RescheduleSignal, changedJitaiId: String?): PlanReport {
        val dailyAt = context.definitions.filter { it.trigger is Trigger.DailyAt }
        val today = context.now.wall.toLocalDateTime(context.zone).date
        val keys = dailyAt.flatMap { definition ->
            val times = (definition.trigger as Trigger.DailyAt).times
            (-1..PLAN_LOOKAHEAD_DAYS).flatMap { offset ->
                val date = today.plus(offset, DateTimeUnit.DAY)
                times.map { DecisionKeys.dailyAt(definition.id, date, it) }
            }
        }
        val used = if (keys.isEmpty()) emptySet() else ports.store.readSnapshot { it.existingKeys(keys) }.getOrThrow()
        val plan = SchedulePlanner.plan(
            definitions = context.definitions,
            now = context.now.wall,
            zone = context.zone,
            profile = context.settings.tickProfile,
            signal = signal,
            changedJitaiId = changedJitaiId,
            usedKeys = used,
            listenerConnected = context.notifications.notificationListenerConnected,
        )
        val missed = resolveMissed(context, plan.missed)
        return PlanReport(plan, missed)
    }

    private suspend fun resolveMissed(context: Context, slots: List<MissedSlot>): PassReport? {
        val points = slots.mapNotNull { slot ->
            context.byId[slot.jitaiId]?.let { definition ->
                MissedPoint(
                    definition,
                    slot.decisionKey,
                    DecisionKeys.kindOf(slot.decisionKey) ?: TriggerKind.DAILY_AT,
                    slot.slotAt,
                    slot.reason,
                )
            }
        }
        if (points.isEmpty()) return null
        return runPass(PassKind.MISSED, context, emptyList(), points)
    }

    private suspend fun applyResponse(record: DecisionRecord, response: JitaiResponse, snooze: SnoozeOption?): ResponseReport {
        val now = clock.now()
        val zone = clock.zone()
        val status = when (ports.store.recordResponse(record.decisionKey, response, now.wall).getOrThrow()) {
            ResponseWrite.RECORDED -> ResponseStatus.RECORDED
            ResponseWrite.ALREADY_RESPONDED -> ResponseStatus.ALREADY_RESPONDED
            ResponseWrite.NOT_FOUND -> return ResponseReport(ResponseStatus.NOT_FOUND)
        }
        var snoozedUntil: MonotonicStamp? = null
        var followUp: PlannedWork? = null
        if (response == JitaiResponse.SNOOZED && snooze != null) {
            val definition = ports.repository.definitions().getOrThrow().firstOrNull { it.id == record.jitaiId }
            val settings = ports.settings.engineSettings().getOrThrow().effective()
            val policy = definition?.snooze ?: SnoozePolicy.DEFAULT
            val target = SnoozeCalculator.until(snooze, now, definition, zone, settings.rolloverMinute)
            val updated = ports.store.updateRuntime(record.jitaiId) {
                it.copy(snoozedUntil = SnoozeCalculator.later(it.snoozedUntil, target), snoozeMode = policy.mode)
            }.getOrThrow()
            snoozedUntil = updated.snoozedUntil
            if (policy.mode == SnoozeMode.RE_EVALUATE_AFTER && record.triggerKind != TriggerKind.SNOOZE_FOLLOW_UP && snoozedUntil != null) {
                followUp = SnoozeCalculator.followUpWork(record.jitaiId, record.decisionKey, snoozedUntil.wall)
            }
        }
        val rows = ports.store.readSnapshot { it.recentCounted(record.jitaiId, EngagementBackoff.SCAN_ROWS) }.getOrThrow()
        val run = EngagementBackoff.consecutiveIgnored(rows)
        ports.store.updateRuntime(record.jitaiId) { it.copy(consecutiveIgnored = run) }.getOrThrow()
        val paused = run >= Backoff.PAUSE_AT && ports.repository.pauseForBackoff(record.jitaiId, run, now.wall).getOrNull() != null
        return ResponseReport(status, snoozedUntil, followUp, run, paused)
    }

    private suspend fun ignored(): List<String> {
        val context = context()
        val marked = mutableListOf<String>()
        context.definitions.filter { Effectiveness.isIntervention(it) }.forEach { definition ->
            val rows = ports.store.readSnapshot { it.recentCounted(definition.id, EngagementBackoff.SCAN_ROWS) }.getOrThrow()
            rows.filter { it.state == DecisionState.DELIVERED && it.content.response == JitaiResponse.NONE }.forEach { row ->
                val delivered = row.delivered?.wall ?: row.decisionPointAt
                val due = definition.delivery.notificationTimeoutMinutes?.let { delivered + it.minutes }
                    ?: EngineDays.nextRollover(delivered, context.zone, context.settings.rolloverMinute)
                if (context.now.wall >= due) {
                    applyResponse(row, JitaiResponse.IGNORED, null)
                    marked += row.decisionKey
                }
            }
        }
        return marked
    }

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
        private const val PLAN_LOOKAHEAD_DAYS = 4
        private val RETRY_OFFSETS_MINUTES = listOf(10, 20)
        private const val COMPONENT = "jitai-engine"
    }
}

package dev.agentle.jitai.engine.pipeline

import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.engine.ports.DecisionTransaction
import dev.agentle.jitai.engine.schedule.Effectiveness
import dev.agentle.jitai.engine.schedule.PlanInput
import dev.agentle.jitai.engine.schedule.ReplanReason
import dev.agentle.jitai.engine.schedule.SchedulePlanner
import dev.agentle.jitai.engine.schedule.TimerPlan
import dev.agentle.jitai.engine.time.MonotonicStamp
import kotlinx.datetime.TimeZone

/**
 * What a re-plan reads besides the transaction: the current definitions, the clock and the collection profile.
 *
 * @property backstopMinutes the BACKSTOP period (the collection profile's tick, jitai-correctness-10).
 * @property listenerConnected the notification listener's state (availability of best-effort event types).
 */
public data class PlanContext(
    val reason: ReplanReason,
    val now: MonotonicStamp,
    val zone: TimeZone,
    val definitions: List<JitaiDefinition>,
    val backstopMinutes: Int,
    val listenerConnected: Boolean,
)

/**
 * Rebuilds the whole timer table inside a transaction (jitai-correctness-04/05): reads the used decision keys, the timer
 * rows and the runtime state as they are now, runs the pure [SchedulePlanner.replan] and replaces the table. Every commit
 * that resolves, defers or snoozes a decision point ends with this, so the table always matches the ledger it was
 * committed with.
 */
public object TimerReconciler {
    public suspend fun reconcile(tx: DecisionTransaction, context: PlanContext): TimerPlan {
        val definitions = context.definitions.map { current(tx, it) }
        val runtimes = definitions.associate { it.id to tx.runtime(it.id) }
        val candidates = SchedulePlanner.candidateKeys(definitions, context.now.wall, context.zone, runtimes)
        val input = PlanInput(
            definitions = definitions,
            usedKeys = if (candidates.isEmpty()) emptySet() else tx.existingKeys(candidates),
            existing = tx.timers(),
            runtimes = runtimes,
            backstopMinutes = context.backstopMinutes,
            listenerConnected = context.listenerConnected,
        )
        val plan = SchedulePlanner.replan(context.reason, context.now, context.zone, input)
        tx.replaceTimers(plan.rows)
        return plan
    }

    /**
     * [definition] when it is still stored as the pass read it; otherwise (edited, disabled, paused or deleted while the
     * pass ran) a disarmed copy, so no row is planned for a version that is no longer current (jitai-correctness-16). Its
     * change already deleted its rows and the next re-plan reads the stored definition.
     */
    private suspend fun current(tx: DecisionTransaction, definition: JitaiDefinition): JitaiDefinition {
        if (!Effectiveness.isArmed(definition)) return definition
        val stored = tx.definitionState(definition.id)
        val same = stored != null && stored.version == definition.version && stored.isEffective
        return if (same) definition else definition.copy(enabled = false)
    }
}

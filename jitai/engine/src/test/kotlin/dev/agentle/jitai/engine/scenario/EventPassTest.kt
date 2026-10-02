package dev.agentle.jitai.engine.scenario

import com.google.common.truth.Truth.assertThat
import dev.agentle.analytics.features.FeatureRef
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.ActiveWindow
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiEventType
import dev.agentle.jitai.dsl.model.Trigger
import dev.agentle.jitai.engine.F0
import dev.agentle.jitai.engine.JitaiEngine
import dev.agentle.jitai.engine.Leaves
import dev.agentle.jitai.engine.Rules
import dev.agentle.jitai.engine.bool
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.ImpliedState
import dev.agentle.jitai.engine.decision.ReasonCode
import dev.agentle.jitai.engine.decision.TriggerKind
import dev.agentle.jitai.engine.enumValue
import dev.agentle.jitai.engine.int
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.ports.ChangeTransition
import dev.agentle.jitai.engine.ports.EvalOutcome
import dev.agentle.jitai.engine.ports.EventOrigin
import dev.agentle.jitai.engine.schedule.WorkInput
import dev.agentle.jitai.engine.schedule.WorkPolicy
import dev.agentle.jitai.engine.testing.EngineHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Event triggers (R10 §7.3 with red team database-sync-04 and lifecycle-battery-05/06/07): age bound, origins, semantic
 * transitions, debounce, the implied live state, the watermark race, the dirty loop and the database generation.
 */
class EventPassTest {
    private val now = F0.local("2026-10-01T22:30")

    /** E1: POWER_CONNECTED, no conditions, debounce 60 s. */
    private val e1 = Rules.rule("E1", Trigger.Event(listOf(JitaiEventType.POWER_CONNECTED)), category = JitaiCategory.STRESS_BREAK)

    private fun harness(vararg definitions: dev.agentle.jitai.dsl.model.JitaiDefinition = arrayOf(e1)): EngineHarness {
        val harness = F0.harness(now, *definitions)
        harness.features.set("charging", bool(true, now))
        return harness
    }

    private fun eventKey(at: kotlin.time.Instant) = DecisionKeys.event("E1", at)

    @Test
    fun `a fresh live event is decided and delivered with its implied state, and the watermark advances`() = runTest {
        val harness = harness()
        val eventAt = now - 30.seconds
        harness.events.emit(JitaiEventType.POWER_CONNECTED, eventAt)

        val report = harness.engine.runEvents().getOrThrow()

        val row = harness.store.row(eventKey(eventAt))!!
        assertThat(row.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(row.triggerKind).isEqualTo(TriggerKind.EVENT)
        assertThat(row.impliedState).isEqualTo(ImpliedState(FeatureRef("charging"), FeatureScalar.BoolValue(true)))
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(1)
        assertThat(harness.store.engineState().getOrThrow().dirty).isFalse()
        assertThat(report.passes).hasSize(1)
        assertThat(report.followUpNeeded).isFalse()
        assertThat(harness.store.runtimeOf("E1").lastEventEvaluation!!.wall).isEqualTo(now)
    }

    @Test
    fun `an event older than its bound is MISSED(EVENT_TOO_OLD) in the evaluation log and never dispatched`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 11.minutes)

        harness.engine.runEvents().getOrThrow()

        val entry = harness.store.evalLog().single()
        assertThat(entry.outcome).isEqualTo(EvalOutcome.MISSED)
        assertThat(entry.reason).isEqualTo(ReasonCode.EVENT_TOO_OLD)
        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(1)
    }

    @Test
    fun `the age bound is per event type`() = runTest {
        val harness = harness()
        harness.settings.settings = F0.SETTINGS.copy(maxEventAgeMinutes = mapOf(JitaiEventType.POWER_CONNECTED to 20))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 15.minutes)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.row(eventKey(now - 15.minutes))!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventOrigin::class)
    fun `polling, sync replay and backfill events are dispatched only within the age bound`(origin: EventOrigin) = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 2.hours(), origin = origin)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 2.minutes, origin = origin)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.rows().map { it.decisionKey }).containsExactly(eventKey(now - 2.minutes))
        assertThat(harness.store.evalLog().single().reason).isEqualTo(ReasonCode.EVENT_TOO_OLD)
    }

    @Test
    fun `the age prefers a same-boot monotonic stamp over the wall clock`() = runTest {
        val harness = harness()
        val clock = harness.clock
        val nowStamp = dev.agentle.jitai.engine.time.MonotonicStamp(clock.now(), clock.elapsed().inWholeMilliseconds, clock.bootCount())
        // The wall clock says 1 minute ago, elapsed realtime says 30 minutes ago (the clock was moved).
        val stamp = nowStamp.copy(wall = now - 1.minutes, elapsedMillis = nowStamp.elapsedMillis - 30.minutes.inWholeMilliseconds)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes, stamp = stamp)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.evalLog().single().reason).isEqualTo(ReasonCode.EVENT_TOO_OLD)
    }

    @Test
    fun `only semantic transitions dispatch - updates and tombstones advance the watermark silently`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes, transition = ChangeTransition.UPDATED)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes, transition = ChangeTransition.TOMBSTONED)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes, transition = ChangeTransition.PROCESSED)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.store.evalLog()).isEmpty()
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(3)
    }

    @Test
    fun `a sleep session that becomes processed is a semantic transition`() = runTest {
        val sleep = Rules.rule(
            "SL",
            Trigger.Event(listOf(JitaiEventType.SLEEP_SESSION_AVAILABLE)),
            category = JitaiCategory.SLEEP_WIND_DOWN,
        )
        val harness = harness(sleep)
        harness.events.emit(JitaiEventType.SLEEP_SESSION_AVAILABLE, now - 1.minutes, transition = ChangeTransition.PROCESSED)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.row(DecisionKeys.event("SL", now - 1.minutes))!!.state).isEqualTo(DecisionState.DELIVERED)
        assertThat(harness.store.row(DecisionKeys.event("SL", now - 1.minutes))!!.impliedState).isNull()
    }

    @Test
    fun `LOCATION_CLASS_CHANGED does not exist in v1 - never dispatched`() = runTest {
        val place = Rules.rule("PL", Trigger.Event(listOf(JitaiEventType.LOCATION_CLASS_CHANGED)))
        val harness = harness(place)
        harness.events.emit(JitaiEventType.LOCATION_CLASS_CHANGED, now - 1.minutes)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(1)
    }

    @Test
    fun `the latest of several matching events wins, earlier ones are folded into it`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 3.minutes)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 20.seconds)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.rows().single().decisionKey).isEqualTo(eventKey(now - 20.seconds))
    }

    @Test
    fun `a rule whose conditions are false logs NOT_TRIGGERED and keeps the key free for a later event`() = runTest {
        val gated = e1.copy(conditions = Leaves.gte(Leaves.SCREEN, 45))
        val harness = harness(gated)
        harness.features.set(Leaves.SCREEN, int(10, now))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        harness.engine.runEvents().getOrThrow()
        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.store.evalLog().single().outcome).isEqualTo(EvalOutcome.NOT_TRIGGERED)

        // A later event in the same 15-minute bucket can still fire.
        harness.clock.advanceBy(2.minutes)
        harness.features.set(Leaves.SCREEN, int(50, now + 2.minutes))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 30.seconds)
        harness.engine.runEvents().getOrThrow()
        assertThat(eventKey(now - 30.seconds)).isEqualTo(eventKey(now - 1.minutes))
        assertThat(harness.store.row(eventKey(now - 30.seconds))!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `UNKNOWN conditions and an unavailable context are logged, not written`() = runTest {
        val unknown = e1.copy(conditions = Leaves.gte(Leaves.SCREEN, 45))
        val context = e1.copy(id = "E2", contextRequirements = Leaves.eq("headphones_connected", true))
        val harness = harness(unknown, context)
        harness.features.set("headphones_connected", bool(false, now))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.store.evalLog().map { it.jitaiId to it.outcome })
            .containsExactly("E1" to EvalOutcome.UNKNOWN, "E2" to EvalOutcome.NOT_AVAILABLE)
    }

    @Test
    fun `an event whose implied state is already gone at evaluation is logged with STATE_CHANGED`() = runTest {
        val harness = harness()
        harness.features.set("charging", bool(false, now))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        harness.engine.runEvents().getOrThrow()

        val entry = harness.store.evalLog().single()
        assertThat(entry.outcome).isEqualTo(EvalOutcome.NOT_TRIGGERED)
        assertThat(entry.reason).isEqualTo(ReasonCode.STATE_CHANGED)
        assertThat(harness.store.rows()).isEmpty()
    }

    @Test
    fun `a live state that changes between the decision and the post is SUPPRESSED(STATE_CHANGED), no notification`() = runTest {
        val harness = harness()
        var reads = 0
        harness.features.provide(FeatureRef("charging")) { at -> FeatureValue.Known(FeatureScalar.BoolValue(++reads == 1), at) }
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        val report = harness.engine.runEvents().getOrThrow()

        val key = eventKey(now - 1.minutes)
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(harness.store.row(key)!!.reason).isEqualTo(ReasonCode.STATE_CHANGED)
        assertThat(
            report.passes.single().deliveries,
        ).containsExactly(DeliveryResult.Ended(key, DecisionState.SUPPRESSED, ReasonCode.STATE_CHANGED))
        assertThat(harness.delivery.posts).isEmpty()
    }

    @Test
    fun `ACTIVITY_STATE_CHANGED implies the activity entered`() = runTest {
        val walk = Rules.rule("W", Trigger.Event(listOf(JitaiEventType.ACTIVITY_STATE_CHANGED)), category = JitaiCategory.PHYSICAL_ACTIVITY)
        val harness = harness(walk)
        harness.features.set("activity_state", enumValue("STILL", now))
        harness.events.emit(JitaiEventType.ACTIVITY_STATE_CHANGED, now - 1.minutes, activityState = "WALKING")

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.evalLog().single().reason).isEqualTo(ReasonCode.STATE_CHANGED)
    }

    @Test
    fun `an unreadable live state at delivery leaves the row DECIDED for recovery`() = runTest {
        val harness = harness()
        var reads = 0
        harness.features.onResolve = { if (++reads == 2) error("resolver down") }
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        val report = harness.engine.runEvents().getOrThrow()

        val key = eventKey(now - 1.minutes)
        assertThat(report.passes.single().deliveries).containsExactly(DeliveryResult.Error(key, "implied_state_unreadable"))
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DECIDED)
        harness.clock.advanceBy(1.minutes)
        harness.engine.recover().getOrThrow()
        assertThat(harness.store.row(key)!!.state).isEqualTo(DecisionState.DELIVERED)
    }

    @Test
    fun `debounce - an event within debounceSeconds of the last evaluation is held and evaluated by the follow-up`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 30.seconds)
        harness.engine.runEvents().getOrThrow()

        harness.clock.advanceBy(30.seconds)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now + 20.seconds)
        val held = harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.evalLog().single().outcome).isEqualTo(EvalOutcome.DEBOUNCED)
        assertThat(harness.store.runtimeOf("E1").pendingEvent!!.eventAt).isEqualTo(now + 20.seconds)
        val wake = held.passes.single().followUp.single()
        assertThat(wake.runAt).isEqualTo(now + 60.seconds)
        assertThat(wake.input).isEqualTo(WorkInput.EventsFollowUp)
        assertThat(wake.uniqueName).isEqualTo(JitaiEngine.EVENTS_WORK)
        assertThat(wake.policy).isEqualTo(WorkPolicy.KEEP)

        harness.clock.advanceTo(now + 60.seconds)
        harness.engine.runEvents().getOrThrow()

        val second = harness.store.row(eventKey(now + 20.seconds))!!
        assertThat(second.state).isEqualTo(DecisionState.SUPPRESSED)
        assertThat(second.reason).isEqualTo(ReasonCode.COOLDOWN)
        assertThat(harness.store.runtimeOf("E1").pendingEvent).isNull()
    }

    @Test
    fun `a pending event that aged out is dropped as EVENT_TOO_OLD`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 30.seconds)
        harness.engine.runEvents().getOrThrow()
        harness.clock.advanceBy(30.seconds)
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now + 20.seconds)
        harness.engine.runEvents().getOrThrow()

        harness.clock.advanceBy(20.minutes)
        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.runtimeOf("E1").pendingEvent).isNull()
        assertThat(harness.store.evalLog().last().reason).isEqualTo(ReasonCode.EVENT_TOO_OLD)
    }

    @Test
    fun `an event outside the rule's window is logged OUTSIDE_WINDOW`() = runTest {
        val harness = harness(e1.copy(activeWindow = ActiveWindow("08:00", "20:00")))
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        harness.engine.runEvents().getOrThrow()

        assertThat(harness.store.evalLog().single().outcome).isEqualTo(EvalOutcome.OUTSIDE_WINDOW)
        assertThat(harness.store.rows()).isEmpty()
    }

    @Test
    fun `watermark race - a pass whose watermark moved before its commit writes nothing (one delivery overall)`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)
        val other = harness.newEngine()
        var raced = false
        harness.features.onResolve = {
            if (!raced) {
                raced = true
                other.runEvents().getOrThrow()
            }
        }

        val report = harness.engine.runEvents().getOrThrow()

        assertThat(raced).isTrue()
        assertThat(harness.store.rows()).hasSize(1)
        assertThat(harness.delivery.posts).hasSize(1)
        assertThat(report.passes.flatMap { it.written }).isEmpty()
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(1)
    }

    @Test
    fun `dirty loop - an event ingested during a pass is picked up by the next loop iteration`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)
        var ingested = false
        harness.features.onResolve = {
            if (!ingested) {
                ingested = true
                harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 10.seconds)
            }
        }

        val report = harness.engine.runEvents().getOrThrow()

        assertThat(report.passes).hasSize(2)
        assertThat(report.followUpNeeded).isFalse()
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(2)
        assertThat(harness.store.evalLog().single().outcome).isEqualTo(EvalOutcome.DEBOUNCED)
    }

    @Test
    fun `dirty loop - with one pass allowed, a still-dirty store asks for a follow-up run`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)
        harness.features.onResolve = {
            harness.features.onResolve = null
            harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 10.seconds)
        }

        val report = harness.engine.runEvents(maxPasses = 1).getOrThrow()

        assertThat(report.followUpNeeded).isTrue()
    }

    @Test
    fun `a full batch asks for an immediate follow-up`() = runTest {
        val harness = harness()
        repeat(JitaiEngine.EVENT_BATCH + 1) { harness.events.emit(JitaiEventType.USER_PRESENT, now - 1.minutes) }

        val report = harness.engine.runEvents(maxPasses = 1).getOrThrow()

        assertThat(report.followUpNeeded).isTrue()
        assertThat(report.passes.single().followUp.single().runAt).isEqualTo(now)
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(JitaiEngine.EVENT_BATCH.toLong())
    }

    @Test
    fun `a commit against another database generation is rejected and writes nothing`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)
        harness.features.onResolve = { harness.store.replaceGeneration("db-restored") }

        val result = harness.engine.runEvents()

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isEqualTo(AppError.DatabaseError("generation_mismatch"))
        assertThat(harness.store.rows()).isEmpty()
        assertThat(harness.store.engineState().getOrThrow().watermark).isEqualTo(0)
    }

    @Test
    fun `the tick runs an event pass as a backstop`() = runTest {
        val harness = harness()
        harness.events.emit(JitaiEventType.POWER_CONNECTED, now - 1.minutes)

        val tick = harness.engine.runTick().getOrThrow()

        assertThat(tick.events!!.written.single().decisionKey).isEqualTo(eventKey(now - 1.minutes))
        assertThat(tick.nextTick).isNull()
    }

    private fun Int.hours() = (this * 60).minutes
}

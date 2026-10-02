package dev.agentle.interventions.wiring

import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.common.map
import dev.agentle.interventions.ports.CardDecisions
import dev.agentle.interventions.ports.CardDisplay
import dev.agentle.interventions.ports.InterventionResponse
import dev.agentle.interventions.ports.InterventionResponseRecorder
import dev.agentle.interventions.ports.JitaiStopper
import dev.agentle.interventions.ports.ResponseKind
import dev.agentle.interventions.ports.ResponseVerdict
import dev.agentle.jitai.engine.JitaiEngine
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.decision.DecisionState
import dev.agentle.jitai.engine.decision.JitaiResponse
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.ports.DecisionStore
import dev.agentle.jitai.engine.response.ResponseStatus

/**
 * [InterventionResponseRecorder] over `JitaiEngine.recordResponse` (nonce checked by the engine). "Stop this JITAI"
 * records NOT_HELPFUL and, only once the nonce was accepted, disables the JITAI through [stopper].
 */
class EngineResponseRecorder(private val engine: JitaiEngine, private val stopper: JitaiStopper) : InterventionResponseRecorder {
    override suspend fun record(response: InterventionResponse): Outcome<ResponseVerdict> {
        val kind = when (response.kind) {
            ResponseKind.OPENED -> JitaiResponse.OPENED
            ResponseKind.SNOOZED, ResponseKind.NOT_NOW -> JitaiResponse.SNOOZED
            ResponseKind.STOP_JITAI -> JitaiResponse.NOT_HELPFUL
            ResponseKind.DISMISSED -> JitaiResponse.DISMISSED
        }
        val verdict = engine.recordResponse(response.decisionKey, response.nonce, kind, response.snooze).map { verdictOf(it.status) }
        val jitaiId = DecisionKeys.jitaiIdOf(response.decisionKey)
        return if (jitaiId != null && response.kind == ResponseKind.STOP_JITAI && (verdict as? Outcome.Success)?.value?.accepted == true) {
            verdict.flatMap { v -> stopper.stop(jitaiId).map { v } }
        } else {
            verdict
        }
    }

    private fun verdictOf(status: ResponseStatus): ResponseVerdict = when (status) {
        ResponseStatus.RECORDED -> ResponseVerdict.RECORDED
        ResponseStatus.ALREADY_RESPONDED -> ResponseVerdict.ALREADY_RESPONDED
        ResponseStatus.NOT_FOUND -> ResponseVerdict.NOT_FOUND
        ResponseStatus.REJECTED -> ResponseVerdict.REJECTED
        ResponseStatus.INVALID_OPTION -> ResponseVerdict.INVALID_OPTION
    }
}

/** [CardDecisions] over the decision store (CARD_PENDING rows) and `JitaiEngine.markCardDisplayed`. */
class EngineCardDecisions(private val engine: JitaiEngine, private val store: DecisionStore) : CardDecisions {
    override suspend fun pendingKeys(): Outcome<Set<String>> =
        store.decisionsInStates(setOf(DecisionState.CARD_PENDING)).map { rows -> rows.mapTo(mutableSetOf()) { it.decisionKey } }

    override suspend fun markDisplayed(decisionKey: String): Outcome<CardDisplay> = engine.markCardDisplayed(decisionKey).map { result ->
        val shown = result is DeliveryResult.Delivered ||
            (result is DeliveryResult.Skipped && result.state == DecisionState.DELIVERED)
        if (shown) CardDisplay.SHOWN else CardDisplay.GONE
    }
}

package dev.agentle.interventions.wiring

import dev.agentle.core.common.Outcome
import dev.agentle.core.common.map
import dev.agentle.core.common.onFailure
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
import dev.agentle.jitai.engine.delivery.PendingCard
import dev.agentle.jitai.engine.pipeline.DeliveryResult
import dev.agentle.jitai.engine.response.ResponseStatus

/**
 * [InterventionResponseRecorder] over `JitaiEngine.recordResponse` (nonce checked by the engine). "Stop this JITAI"
 * disables the JITAI through [stopper] (the list's Disable path), then calls `onDefinitionChanged`, then records
 * NOT_HELPFUL, in that order (the order the engine owner set).
 */
class EngineResponseRecorder(private val engine: JitaiEngine, private val stopper: JitaiStopper) : InterventionResponseRecorder {
    override suspend fun record(response: InterventionResponse): Outcome<ResponseVerdict> {
        val kind = when (response.kind) {
            ResponseKind.OPENED -> JitaiResponse.OPENED
            ResponseKind.SNOOZED, ResponseKind.NOT_NOW -> JitaiResponse.SNOOZED
            ResponseKind.STOP_JITAI -> JitaiResponse.NOT_HELPFUL
            ResponseKind.DISMISSED -> JitaiResponse.DISMISSED
        }
        if (response.kind == ResponseKind.STOP_JITAI) {
            val jitaiId = DecisionKeys.jitaiIdOf(response.decisionKey)
            if (jitaiId != null) {
                stopper.stop(jitaiId).onFailure { return Outcome.Failure(it) }
                engine.onDefinitionChanged(jitaiId).onFailure { return Outcome.Failure(it) }
            }
        }
        return engine.recordResponse(response.decisionKey, response.nonce, kind, response.snooze).map { verdictOf(it.status) }
    }

    private fun verdictOf(status: ResponseStatus): ResponseVerdict = when (status) {
        ResponseStatus.RECORDED -> ResponseVerdict.RECORDED
        ResponseStatus.ALREADY_RESPONDED -> ResponseVerdict.ALREADY_RESPONDED
        ResponseStatus.NOT_FOUND -> ResponseVerdict.NOT_FOUND
        ResponseStatus.REJECTED -> ResponseVerdict.REJECTED
        ResponseStatus.INVALID_OPTION -> ResponseVerdict.INVALID_OPTION
    }
}

/** [CardDecisions] over `JitaiEngine.pendingCards` and `JitaiEngine.markCardDisplayed`. */
class EngineCardDecisions(private val engine: JitaiEngine) : CardDecisions {
    override suspend fun pendingCards(): Outcome<List<PendingCard>> = engine.pendingCards()

    override suspend fun markDisplayed(decisionKey: String): Outcome<CardDisplay> = engine.markCardDisplayed(decisionKey).map { result ->
        val shown = result is DeliveryResult.Delivered ||
            (result is DeliveryResult.Skipped && result.state == DecisionState.DELIVERED)
        if (shown) CardDisplay.SHOWN else CardDisplay.GONE
    }
}

package dev.agentle.interventions.response

import android.content.Intent
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.onFailure
import dev.agentle.core.common.onSuccess
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.interventions.notification.InterventionLink
import dev.agentle.interventions.notification.InterventionNotifier
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.interventions.ports.InterventionResponse
import dev.agentle.interventions.ports.InterventionResponseRecorder
import dev.agentle.interventions.ports.ResponseKind
import dev.agentle.interventions.ports.ResponseSurface
import dev.agentle.interventions.ports.ResponseVerdict

/**
 * Applies a response from any surface: the recorder (the engine) checks the nonce and records it; only then does the
 * device change. A REJECTED nonce changes nothing (red team correction 7). NOT_FOUND (the decision is gone) clears the
 * leftovers. A recorder failure keeps the notification and the card, so the user can try again.
 */
class InterventionResponses(
    private val recorder: InterventionResponseRecorder,
    private val notifier: InterventionNotifier,
    private val cards: InterventionCardStore,
    private val logger: Logger,
) {
    suspend fun apply(response: InterventionResponse): Outcome<ResponseVerdict> {
        val fields = mapOf("kind" to response.kind.name, "surface" to response.surface.name)
        return recorder.record(response)
            .onSuccess { verdict ->
                if (verdict.accepted || verdict == ResponseVerdict.NOT_FOUND) {
                    notifier.cancel(response.decisionKey)
                    cards.remove(response.decisionKey).onFailure { logger.w(COMPONENT, "card not removed", it, fields) }
                }
                logger.i(COMPONENT, "response handled", fields + ("verdict" to verdict.name))
            }
            .onFailure { logger.w(COMPONENT, "response not recorded", it, fields) }
    }

    private companion object {
        const val COMPONENT = "interventions.response"
    }
}

/** Where a tap on an intervention leads. */
data class OpenedIntervention(val route: AppRoute.InterventionDetail, val autoplay: Boolean)

/**
 * The deep-link side of a tap (content intent, Listen or Watch). The app's activity calls [open] from `onCreate` and
 * `onNewIntent` and navigates to the result. It records OPENED first: a wrong nonce or an unknown decision opens
 * nothing; a recorder failure still opens the detail screen (without autoplay), since navigation changes no data.
 */
class InterventionOpener(private val responses: InterventionResponses) {
    suspend fun open(intent: Intent?): OpenedIntervention? {
        val link = InterventionLink.from(intent) ?: return null
        val response = InterventionResponse(link.decisionKey, link.nonce, ResponseKind.OPENED, ResponseSurface.NOTIFICATION)
        return when (val outcome = responses.apply(response)) {
            is Outcome.Success -> if (outcome.value.accepted) OpenedIntervention(link.route, link.autoplay) else null
            is Outcome.Failure -> OpenedIntervention(link.route, autoplay = false)
        }
    }
}

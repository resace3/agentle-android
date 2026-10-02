package dev.agentle.interventions.card

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.common.map
import dev.agentle.core.common.onFailure
import dev.agentle.core.time.AgentleClock
import dev.agentle.core.ui.navigation.AppRoute
import dev.agentle.interventions.ports.CardDecisions
import dev.agentle.interventions.ports.CardDisplay
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.interventions.ports.InterventionResponse
import dev.agentle.interventions.ports.ResponseKind
import dev.agentle.interventions.ports.ResponseSurface
import dev.agentle.interventions.ports.ResponseVerdict
import dev.agentle.interventions.response.InterventionResponses
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaRef
import dev.agentle.jitai.dsl.model.SnoozeOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the user did on an in-app card. */
sealed interface CardAction {
    val kind: ResponseKind
    val snooze: SnoozeOption? get() = null

    data object Open : CardAction {
        override val kind: ResponseKind get() = ResponseKind.OPENED
    }

    /** One of the card's [InterventionCard.snoozeOptions]. */
    data class Snooze(val option: SnoozeOption) : CardAction {
        override val kind: ResponseKind get() = ResponseKind.SNOOZED
        override val snooze: SnoozeOption get() = option
    }

    /** "Not now": snoozed until the active window ends. */
    data object NotNow : CardAction {
        override val kind: ResponseKind get() = ResponseKind.NOT_NOW
        override val snooze: SnoozeOption get() = SnoozeOption.UNTIL_WINDOW_END
    }

    data object Stop : CardAction {
        override val kind: ResponseKind get() = ResponseKind.STOP_JITAI
    }

    data object Dismiss : CardAction {
        override val kind: ResponseKind get() = ResponseKind.DISMISSED
    }
}

/** Result of [InterventionCards.respond]. */
sealed interface CardResult {
    /** Recorded (or already answered); [route] is set for an accepted [CardAction.Open]. */
    data class Done(val verdict: ResponseVerdict, val route: AppRoute.InterventionDetail?) : CardResult

    /** No such card, it expired, or the engine no longer has it as a card: the app drops it. */
    data object Gone : CardResult

    /** The response could not be recorded; the card stays so the user can try again. */
    data class Failed(val error: AppError) : CardResult
}

/**
 * The app's side of the in-app card fallback (jitai-correctness-13). The engine lists the CARD_PENDING cards with their
 * text and expiry ([CardDecisions.pendingCards]); the store keeps the media `keepAsCard` held and a copy of each shown
 * card. [pending] shows both; [onDisplayed] tells the engine (CARD_PENDING -> DELIVERED); [respond] records a response
 * with the card's nonce through [InterventionResponses], like a notification action; [refresh] drops what is gone.
 */
class InterventionCards(
    private val store: InterventionCardStore,
    private val decisions: CardDecisions,
    private val responses: InterventionResponses,
    private val library: MediaLibrary,
    private val clock: AgentleClock,
    private val logger: Logger,
) {
    /** Cards to show, oldest first: the engine's pending cards and the shown ones, not expired (checked on every emission). */
    fun pending(): Flow<List<InterventionCard>> = store.observe().map { stored -> visible(stored) }

    /** Decision keys of every stored card: their media must not be evicted. */
    suspend fun storedKeys(): Set<String> = store.all().getOrNull().orEmpty().mapTo(mutableSetOf()) { it.decisionKey }

    /**
     * Reconciles the store with the engine (app start, dashboard resume, the daily maintenance pass): a stored card goes,
     * with its generated media, when it expired or when it was never shown and the engine no longer lists it.
     * Returns how many cards went.
     */
    suspend fun refresh(): Outcome<Int> = decisions.pendingCards().map { listed ->
        val now = clock.now()
        val keys = listed.mapTo(mutableSetOf()) { it.decisionKey }
        store.all().getOrNull().orEmpty().count { card ->
            val keep = !card.isExpired(now) && (card.displayedAt != null || card.decisionKey in keys)
            !keep && drop(card)
        }
    }.onFailure { logger.w(COMPONENT, "card refresh failed", it) }

    /** The app showed the card: the engine counts it as delivered from now on; a card the engine no longer has goes. */
    suspend fun onDisplayed(decisionKey: String): Outcome<CardDisplay> {
        val local = store.get(decisionKey).getOrNull()
        if (local?.displayedAt != null) {
            if (!local.isExpired(clock.now())) return Outcome.success(CardDisplay.SHOWN)
            drop(local)
            return Outcome.success(CardDisplay.GONE)
        }
        val listed = when (val cards = decisions.pendingCards()) {
            is Outcome.Failure -> return cards
            is Outcome.Success -> cards.value.firstOrNull { it.decisionKey == decisionKey }
        }
        if (listed == null || clock.now() >= listed.expiresAt) {
            local?.let { drop(it) }
            return Outcome.success(CardDisplay.GONE)
        }
        return decisions.markDisplayed(decisionKey).map { display ->
            when (display) {
                CardDisplay.SHOWN -> store.put(InterventionCard.of(listed, local).copy(displayedAt = clock.now()))
                    .onFailure { logger.w(COMPONENT, "card display not stored", it) }

                CardDisplay.GONE -> local?.let { drop(it) }
            }
            display
        }.onFailure { logger.w(COMPONENT, "card display not recorded", it) }
    }

    suspend fun respond(decisionKey: String, action: CardAction): CardResult {
        // A response always follows a display; make sure the engine counted it before the response lands.
        when (val shown = onDisplayed(decisionKey)) {
            is Outcome.Failure -> return CardResult.Failed(shown.error)
            is Outcome.Success -> if (shown.value == CardDisplay.GONE) return CardResult.Gone
        }
        val card = store.get(decisionKey).getOrNull() ?: return CardResult.Gone
        val response = InterventionResponse(card.decisionKey, card.nonce, action.kind, ResponseSurface.IN_APP_CARD, action.snooze)
        return when (val outcome = responses.apply(response)) {
            is Outcome.Failure -> CardResult.Failed(outcome.error)

            is Outcome.Success -> {
                val route = AppRoute.InterventionDetail(card.decisionKey).takeIf { action == CardAction.Open && outcome.value.accepted }
                CardResult.Done(outcome.value, route)
            }
        }
    }

    private suspend fun visible(stored: List<InterventionCard>): List<InterventionCard> {
        val now = clock.now()
        val local = stored.associateBy { it.decisionKey }
        val listed = decisions.pendingCards()
            .onFailure { logger.w(COMPONENT, "pending cards unavailable", it) }
            .getOrNull().orEmpty()
            .map { InterventionCard.of(it, local[it.decisionKey]) }
        // A shown card's stored copy wins: the engine stops listing it once it is DELIVERED.
        val shown = stored.filter { it.displayedAt != null }
        val shownKeys = shown.mapTo(mutableSetOf()) { it.decisionKey }
        return (shown + listed.filterNot { it.decisionKey in shownKeys })
            .filterNot { it.isExpired(now) }
            .sortedBy { it.createdAt }
    }

    /** Removes [card] and the media generated for its decision; true when the card was removed. */
    private suspend fun drop(card: InterventionCard): Boolean {
        val removed = store.remove(card.decisionKey).getOrNull() == true
        if (removed) library.discardFor(MediaRef.parse(card.mediaRef), card.decisionKey)
        return removed
    }

    private companion object {
        const val COMPONENT = "interventions.cards"
    }
}

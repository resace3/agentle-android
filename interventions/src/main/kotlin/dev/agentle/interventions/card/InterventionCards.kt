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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
 * The app's side of the in-app card fallback (jitai-correctness-13). The delivery port keeps a blocked delivery as a
 * candidate card; [refresh] confirms candidates the engine moved to CARD_PENDING and drops the rest; [pending] shows the
 * confirmed ones; [onDisplayed] tells the engine (CARD_PENDING -> DELIVERED); [respond] records a response with the
 * card's nonce through [InterventionResponses], like a notification action.
 */
class InterventionCards(
    private val store: InterventionCardStore,
    private val decisions: CardDecisions,
    private val responses: InterventionResponses,
    private val library: MediaLibrary,
    private val clock: AgentleClock,
    private val logger: Logger,
) {
    /** Cards to show, oldest first: confirmed or already displayed, and not expired (checked on every emission). */
    fun pending(): Flow<List<InterventionCard>> = store.observe().map { cards ->
        val now = clock.now()
        cards.filter { it.isVisible(now) }
    }

    /** Decision keys of every stored card, candidates included: their media must not be evicted. */
    suspend fun storedKeys(): Set<String> = store.all().getOrNull().orEmpty().mapTo(mutableSetOf()) { it.decisionKey }

    /**
     * Reconciles the stored cards with the engine (app start, dashboard resume, the daily maintenance pass): a card whose
     * decision is CARD_PENDING is confirmed; a displayed card stays until it is answered or expires; any other card goes
     * with its generated media, except a candidate younger than [CANDIDATE_GRACE] (its post may still be finishing).
     * Returns how many cards went.
     */
    suspend fun refresh(): Outcome<Int> = decisions.pendingKeys().map { pendingKeys ->
        val now = clock.now()
        store.all().getOrNull().orEmpty().count { card ->
            val keep = !card.isExpired(now) && when {
                card.decisionKey in pendingKeys -> true
                card.displayedAt != null -> true
                card.confirmed -> false
                else -> now - card.createdAt < CANDIDATE_GRACE
            }
            when {
                !keep -> drop(card)
                card.decisionKey in pendingKeys && !card.confirmed -> {
                    store.put(card.copy(confirmed = true)).onFailure { logger.w(COMPONENT, "card not confirmed", it) }
                    false
                }
                else -> false
            }
        }
    }.onFailure { logger.w(COMPONENT, "card refresh failed", it) }

    /** The app showed the card: the engine counts it as delivered from now on; a card the engine no longer has goes. */
    suspend fun onDisplayed(decisionKey: String): Outcome<CardDisplay> {
        val card = store.get(decisionKey).getOrNull() ?: return Outcome.success(CardDisplay.GONE)
        if (card.isExpired(clock.now())) {
            drop(card)
            return Outcome.success(CardDisplay.GONE)
        }
        if (card.displayedAt != null) return Outcome.success(CardDisplay.SHOWN)
        return decisions.markDisplayed(decisionKey).map { display ->
            when (display) {
                CardDisplay.SHOWN -> store.put(card.copy(confirmed = true, displayedAt = clock.now()))
                    .onFailure { logger.w(COMPONENT, "card display not stored", it) }
                CardDisplay.GONE -> drop(card)
            }
            display
        }.onFailure { logger.w(COMPONENT, "card display not recorded", it) }
    }

    suspend fun respond(decisionKey: String, action: CardAction): CardResult {
        val card = store.get(decisionKey).getOrNull()?.takeUnless { it.isExpired(clock.now()) } ?: return CardResult.Gone
        // A response always follows a display; make sure the engine counted it before the response lands.
        if (card.displayedAt == null) {
            when (val shown = onDisplayed(decisionKey)) {
                is Outcome.Failure -> return CardResult.Failed(shown.error)
                is Outcome.Success -> if (shown.value == CardDisplay.GONE) return CardResult.Gone
            }
        }
        val response = InterventionResponse(card.decisionKey, card.nonce, action.kind, ResponseSurface.IN_APP_CARD, action.snooze)
        return when (val outcome = responses.apply(response)) {
            is Outcome.Failure -> CardResult.Failed(outcome.error)
            is Outcome.Success -> {
                val route = AppRoute.InterventionDetail(card.decisionKey).takeIf { action == CardAction.Open && outcome.value.accepted }
                CardResult.Done(outcome.value, route)
            }
        }
    }

    /** Removes [card] and the media generated for its decision; true when the card was removed. */
    private suspend fun drop(card: InterventionCard): Boolean {
        val removed = store.remove(card.decisionKey).getOrNull() == true
        if (removed) library.discardFor(MediaRef.parse(card.mediaRef), card.decisionKey)
        return removed
    }

    companion object {
        /** A candidate kept by a post that reported `Blocked` waits this long for the engine's CARD_PENDING (the lease). */
        val CANDIDATE_GRACE: Duration = 2.minutes
        private const val COMPONENT = "interventions.cards"
    }
}

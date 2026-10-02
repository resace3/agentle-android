package dev.agentle.interventions.card

import dev.agentle.core.common.Outcome
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.delivery.PendingCard
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/**
 * The in-app card of a delivery whose notification was blocked (jitai-correctness-13). It shows the in-app text
 * ([title], [body]), never on the lock screen. Text and expiry come from `JitaiEngine.pendingCards()`; the store keeps
 * only what the engine does not: the prepared media ([mediaRef]) and, once shown, a copy of the card ([displayedAt]).
 *
 * @property nonce the delivery's nonce; every card response presents it.
 * @property mediaRef the prepared media (`media:<id>` or `asset:<path>`), shown in the app.
 * @property expiresAt the engine's card expiry (`DeliveryProtocol.cardExpiry`).
 * @property displayedAt when the app first showed the card (the engine then counts it as delivered).
 */
data class InterventionCard(
    val decisionKey: String,
    val jitaiId: String,
    val jitaiName: String,
    val category: JitaiCategory,
    val channel: DeliveryChannel,
    val title: String,
    val body: String,
    val nonce: String,
    val snoozeOptions: List<SnoozeOption>,
    val createdAt: Instant,
    val expiresAt: Instant,
    val mediaRef: String? = null,
    val displayedAt: Instant? = null,
) {
    fun isExpired(now: Instant): Boolean = now >= expiresAt

    companion object {
        /** The card of [pending], with the media and display time kept locally in [local]. */
        fun of(pending: PendingCard, local: InterventionCard?): InterventionCard =
            from(pending.intervention, pending.decidedAt, pending.expiresAt, local?.mediaRef).copy(displayedAt = local?.displayedAt)

        /** The local record `DeliveryPort.keepAsCard` stores: the media only; never shown until the engine lists the card. */
        fun kept(prepared: PreparedDelivery, now: Instant): InterventionCard = from(prepared.intervention, now, now, prepared.mediaRef)

        private fun from(intervention: RenderedIntervention, createdAt: Instant, expiresAt: Instant, mediaRef: String?) = InterventionCard(
            decisionKey = intervention.decisionKey,
            jitaiId = intervention.jitaiId,
            jitaiName = intervention.jitaiName,
            category = intervention.category,
            channel = intervention.channel,
            title = intervention.title,
            body = intervention.body,
            nonce = intervention.nonce,
            snoozeOptions = intervention.snoozeOptions,
            createdAt = createdAt,
            expiresAt = expiresAt,
            mediaRef = mediaRef,
        )
    }
}

/** Process-local [InterventionCardStore] for the staging graph and tests; cards are lost when the process dies. */
class InMemoryInterventionCardStore : InterventionCardStore {
    private val cards = MutableStateFlow<Map<String, InterventionCard>>(emptyMap())

    override fun observe(): Flow<List<InterventionCard>> = cards.map { byKey -> byKey.values.sortedBy { it.createdAt } }

    override suspend fun put(card: InterventionCard): Outcome<Unit> {
        cards.update { it + (card.decisionKey to card) }
        return Outcome.success(Unit)
    }

    override suspend fun get(decisionKey: String): Outcome<InterventionCard?> = Outcome.success(cards.value[decisionKey])

    override suspend fun all(): Outcome<List<InterventionCard>> = Outcome.success(cards.value.values.sortedBy { it.createdAt })

    override suspend fun remove(decisionKey: String): Outcome<Boolean> {
        var removed = false
        cards.update { current ->
            removed = decisionKey in current
            current - decisionKey
        }
        return Outcome.success(removed)
    }
}

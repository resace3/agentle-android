package dev.agentle.interventions.card

import dev.agentle.core.common.Outcome
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The in-app card of a delivery whose notification was blocked (jitai-correctness-13). It shows the in-app text
 * ([title], [body]), never on the lock screen. A card starts as a candidate; it is shown once the engine confirmed the
 * decision is CARD_PENDING ([confirmed]) and stays until it is answered or expires.
 *
 * @property nonce the delivery's nonce; every card response presents it.
 * @property mediaRef the prepared media (`media:<id>` or `asset:<path>`), shown in the app.
 * @property expiresAt the rule's notification timeout after [createdAt], or the card TTL when the rule sets none.
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
    val confirmed: Boolean = false,
    val displayedAt: Instant? = null,
) {
    fun isExpired(now: Instant): Boolean = now >= expiresAt

    /** Shown in the app: confirmed by the engine (or already displayed) and not expired. */
    fun isVisible(now: Instant): Boolean = (confirmed || displayedAt != null) && !isExpired(now)

    companion object {
        /** A candidate card for [prepared], created at [now]; it lives `timeoutMinutes` (or [ttl] without a timeout). */
        fun candidate(prepared: PreparedDelivery, now: Instant, ttl: Duration): InterventionCard {
            val intervention = prepared.intervention
            val life = intervention.timeoutMinutes?.takeIf { it > 0 }?.minutes ?: ttl
            return InterventionCard(
                decisionKey = intervention.decisionKey,
                jitaiId = intervention.jitaiId,
                jitaiName = intervention.jitaiName,
                category = intervention.category,
                channel = intervention.channel,
                title = intervention.title,
                body = intervention.body,
                nonce = intervention.nonce,
                snoozeOptions = intervention.snoozeOptions,
                createdAt = now,
                expiresAt = now + life,
                mediaRef = prepared.mediaRef,
            )
        }
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

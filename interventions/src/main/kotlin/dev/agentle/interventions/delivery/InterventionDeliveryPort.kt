package dev.agentle.interventions.delivery

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.common.outcomeOf
import dev.agentle.core.time.AgentleClock
import dev.agentle.interventions.card.InterventionCard
import dev.agentle.interventions.image.ImagePreparer
import dev.agentle.interventions.notification.InterventionNotifier
import dev.agentle.interventions.notification.NotifyResult
import dev.agentle.interventions.ports.InterventionCardStore
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaPictures
import dev.agentle.interventions.storage.MediaRef
import dev.agentle.interventions.video.VideoPreparer
import dev.agentle.interventions.voice.VoicePreparer
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.ports.DeliveryPort
import dev.agentle.jitai.engine.ports.DeliveryPrerequisite
import dev.agentle.jitai.engine.ports.PostResult
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlin.coroutines.cancellation.CancellationException

/**
 * The engine's [DeliveryPort] on the device. [prepare] does all slow work (template card, TTS, clip lookup) while the
 * decision is DECIDED; [post] is fast and idempotent (`tag = decisionKey`). A post blocked by the prerequisite returns
 * [PostResult.Blocked]; when the engine then moves the decision to CARD_PENDING, [keepAsCard] keeps its media for the
 * in-app card.
 */
class InterventionDeliveryPort(
    private val notifier: InterventionNotifier,
    private val access: NotificationStateReader,
    private val cards: InterventionCardStore,
    private val images: ImagePreparer,
    private val voices: VoicePreparer,
    private val videos: VideoPreparer,
    private val library: MediaLibrary,
    private val pictures: MediaPictures,
    private val clock: AgentleClock,
    private val logger: Logger,
) : DeliveryPort {
    override suspend fun prerequisite(category: JitaiCategory): Outcome<DeliveryPrerequisite> =
        outcomeOf(mapError = { AppError.Unexpected("prerequisite:${it::class.simpleName}") }) { access.read().prerequisite(category) }

    override suspend fun prepare(intervention: RenderedIntervention): PrepareResult = try {
        when (intervention.channel) {
            DeliveryChannel.NOTIFICATION, DeliveryChannel.NONE -> PrepareResult.Ready(PreparedDelivery(intervention))
            DeliveryChannel.IMAGE -> images.prepare(intervention)
            DeliveryChannel.VOICE -> voices.prepare(intervention)
            DeliveryChannel.VIDEO -> videos.prepare(intervention)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(COMPONENT, "prepare failed", fields = mapOf("error" to e::class.simpleName, "channel" to intervention.channel.name))
        PrepareResult.Unavailable(InterventionCodes.PREPARE_ERROR)
    }

    override suspend fun post(prepared: PreparedDelivery): PostResult {
        val intervention = prepared.intervention
        if (intervention.channel == DeliveryChannel.NONE) return PostResult.Failed(InterventionCodes.CHANNEL_NONE)
        if (!access.read().allows(intervention.category)) return PostResult.Blocked
        val picture = if (intervention.detailed && intervention.channel == DeliveryChannel.IMAGE) pictures.load(prepared.mediaRef) else null
        return when (val result = notifier.post(prepared, picture)) {
            NotifyResult.Posted -> PostResult.Posted
            NotifyResult.PermissionMissing -> PostResult.Blocked
            is NotifyResult.Failed -> PostResult.Failed(result.code)
        }
    }

    override suspend fun isActive(tag: String): Outcome<Boolean> =
        outcomeOf(mapError = { AppError.Unexpected("is_active:${it::class.simpleName}") }) { notifier.isActive(tag) }

    /** The delivery will not be shown: release the media generated for it. */
    override suspend fun discard(prepared: PreparedDelivery) {
        library.discardFor(MediaRef.parse(prepared.mediaRef), prepared.intervention.decisionKey)
    }

    /**
     * The decision became CARD_PENDING: keep the prepared media for the in-app card. The card's text and expiry come from
     * `JitaiEngine.pendingCards()`; a card already stored (a repeated call) is left as it is.
     */
    override suspend fun keepAsCard(prepared: PreparedDelivery): Outcome<Unit> {
        val key = prepared.intervention.decisionKey
        return when (val existing = cards.get(key)) {
            is Outcome.Failure -> existing.also { logger.w(COMPONENT, "card not read", it.error) }
            is Outcome.Success -> if (existing.value != null) {
                Outcome.success(Unit)
            } else {
                cards.put(InterventionCard.kept(prepared, clock.now())).also { r ->
                    if (r is Outcome.Failure) logger.w(COMPONENT, "card not stored", r.error)
                }
            }
        }
    }

    private companion object {
        const val COMPONENT = "interventions.delivery"
    }
}

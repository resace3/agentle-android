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
import dev.agentle.interventions.ports.InterventionSettings
import dev.agentle.interventions.ports.InterventionSettingsSource
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
import kotlin.time.Duration.Companion.minutes

/**
 * The engine's [DeliveryPort] on the device. [prepare] does all slow work (template card, TTS, clip lookup) while the
 * decision is DECIDED; [post] is fast and idempotent (`tag = decisionKey`). A post blocked by the prerequisite returns
 * [PostResult.Blocked]; with in-app cards on, a blocked or refused delivery is kept as a candidate card
 * ([dev.agentle.interventions.card.InterventionCards.refresh] confirms it against CARD_PENDING).
 */
class InterventionDeliveryPort(
    private val notifier: InterventionNotifier,
    private val access: NotificationStateReader,
    private val settings: InterventionSettingsSource,
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
        val current = settings.settings().getOrNull() ?: InterventionSettings()
        if (!access.read().allows(intervention.category)) return blocked(prepared, current)
        val picture = if (intervention.detailed && intervention.channel == DeliveryChannel.IMAGE) pictures.load(prepared.mediaRef) else null
        return when (val result = notifier.post(prepared, picture)) {
            NotifyResult.Posted -> PostResult.Posted
            NotifyResult.PermissionMissing -> blocked(prepared, current)
            is NotifyResult.Failed -> PostResult.Failed(result.code)
        }
    }

    override suspend fun isActive(tag: String): Outcome<Boolean> =
        outcomeOf(mapError = { AppError.Unexpected("is_active:${it::class.simpleName}") }) { notifier.isActive(tag) }

    /** The claim was refused or lost: with in-app cards on, keep it as a candidate card, else release its media. */
    override suspend fun discard(prepared: PreparedDelivery) {
        val current = settings.settings().getOrNull() ?: InterventionSettings()
        if (current.inAppCards) {
            keepCard(prepared, current)
        } else {
            library.discardFor(MediaRef.parse(prepared.mediaRef), prepared.intervention.decisionKey)
        }
    }

    private suspend fun blocked(prepared: PreparedDelivery, current: InterventionSettings): PostResult {
        if (current.inAppCards) keepCard(prepared, current)
        return PostResult.Blocked
    }

    private suspend fun keepCard(prepared: PreparedDelivery, current: InterventionSettings) {
        val existing = cards.get(prepared.intervention.decisionKey).getOrNull()
        val card = existing ?: InterventionCard.candidate(prepared, clock.now(), current.cardTtlMinutes.minutes)
        if (existing == null) cards.put(card).getOrNull() ?: logger.w(COMPONENT, "card not stored")
    }

    private companion object {
        const val COMPONENT = "interventions.delivery"
    }
}

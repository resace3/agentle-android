package dev.agentle.interventions.image

import dev.agentle.ai.api.AiCapabilities
import dev.agentle.ai.api.AiCapability
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.model.GenerationMethod
import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.delivery.InterventionCodes
import dev.agentle.interventions.ports.InterventionSettings
import dev.agentle.interventions.ports.InterventionSettingsSource
import dev.agentle.interventions.storage.BundledMedia
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaRef
import dev.agentle.interventions.storage.MediaSpec
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

/**
 * IMAGE deliveries, before the claim (red team correction 5: all slow work in `prepare`). The rule's asset id picks:
 * - `card:<template>`: a [TemplateRenderer] card with the rendered title and body, stored as generated media;
 * - otherwise a bundled picture (`assets/media/<assetId>.png`) or a stored image with that id.
 * Anything else is `Unavailable`, and the engine downgrades the delivery to a plain notification (R09 F22).
 */
class ImagePreparer(
    private val renderer: TemplateRenderer,
    private val library: MediaLibrary,
    private val bundled: BundledMedia,
    private val settings: InterventionSettingsSource,
    private val logger: Logger,
    private val render: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun prepare(intervention: RenderedIntervention): PrepareResult {
        val assetId = intervention.assetId ?: return unavailable(InterventionCodes.IMAGE_UNAVAILABLE)
        return if (assetId.startsWith(TEMPLATE_PREFIX)) {
            renderCard(intervention, assetId.removePrefix(TEMPLATE_PREFIX))
        } else {
            existing(intervention, assetId)
        }
    }

    private suspend fun existing(intervention: RenderedIntervention, assetId: String): PrepareResult {
        val bundledPath = bundled.find(assetId, MediaKind.IMAGE)
        val ref = when {
            bundledPath != null -> MediaRef.Bundled(bundledPath)
            library.record(assetId)?.artifact?.kind == MediaKind.IMAGE -> MediaRef.Stored(assetId)
            else -> return unavailable(InterventionCodes.IMAGE_UNAVAILABLE)
        }
        return PrepareResult.Ready(PreparedDelivery(intervention, ref.encoded))
    }

    private suspend fun renderCard(intervention: RenderedIntervention, template: String): PrepareResult {
        if (template !in TEMPLATES) return unavailable(InterventionCodes.IMAGE_UNAVAILABLE)
        if (library.admit(MediaKind.IMAGE, CARD_ESTIMATE_BYTES).getOrNull() != true) return unavailable(InterventionCodes.MEDIA_QUOTA)
        val expiryDays = (settings.settings().getOrNull() ?: InterventionSettings()).mediaExpiryDays
        val target = library.newTarget(MediaKind.IMAGE, PNG)
        val written = try {
            withContext(render) {
                val card = renderer.render(CardContent(intervention.title, intervention.body, intervention.category), CardSize.NOTIFICATION)
                renderer.writePng(card.bitmap, target.file)
                card.bitmap.recycle()
            }
            true
        } catch (e: CancellationException) {
            target.file.delete()
            throw e
        } catch (e: Exception) {
            logger.w(COMPONENT, "card render failed", fields = mapOf("error" to e::class.simpleName))
            target.file.delete()
            false
        }
        if (!written) return unavailable(InterventionCodes.IMAGE_RENDER_FAILED)
        val spec = MediaSpec(
            kind = MediaKind.IMAGE,
            method = GenerationMethod.LOCAL_TEMPLATE,
            mimeType = MIME_PNG,
            decisionKey = intervention.decisionKey,
            jitaiId = intervention.jitaiId,
            expiresAfter = expiryDays.days,
        )
        return when (val stored = library.register(target, spec)) {
            is Outcome.Success -> PrepareResult.Ready(PreparedDelivery(intervention, MediaRef.Stored(stored.value.artifact.id).encoded))
            is Outcome.Failure -> unavailable(InterventionCodes.MEDIA_STORE_FAILED)
        }
    }

    private fun unavailable(code: String): PrepareResult {
        logger.i(COMPONENT, "image unavailable", fields = mapOf("code" to code))
        return PrepareResult.Unavailable(code)
    }

    companion object {
        /** Asset ids that ask for a template card instead of a file. */
        const val TEMPLATE_PREFIX: String = "card:"

        /** v1 templates: `message` draws the category icon, the title and the body. */
        val TEMPLATES: Set<String> = setOf("message")
        private const val PNG = "png"
        private const val MIME_PNG = "image/png"

        /** Admission estimate for a 1024x512 card (R09 §9.4: "PNG: 1 MB"). */
        private const val CARD_ESTIMATE_BYTES = 1024L * 1024L
        private const val COMPONENT = "interventions.image"
    }
}

/** A provider image request. Prompts carry only minimized, user-approved context (R09 §7.1, never raw health data). */
data class ImageRequest(val description: String, val size: CardSize)

/**
 * Provider image generation, behind the capability flag (R09 §7). No v1 provider generates images (Sign in with ChatGPT
 * is text-only), so every call returns `UnsupportedFeature`; image interventions use [TemplateRenderer] cards or bundled
 * pictures instead.
 */
class ProviderImageGeneration(private val capabilities: () -> AiCapabilities) {
    @Suppress("UnusedParameter")
    suspend fun generate(request: ImageRequest): Outcome<File> {
        val detail = if (capabilities().isAvailable(AiCapability.IMAGE_GENERATION)) "no image route in v1" else "provider lacks image generation"
        return Outcome.failure(AppError.UnsupportedFeature(FEATURE, detail))
    }

    companion object {
        const val FEATURE: String = "image_generation"
    }
}

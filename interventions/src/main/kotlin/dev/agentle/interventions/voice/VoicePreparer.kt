package dev.agentle.interventions.voice

import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.model.GenerationMethod
import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.delivery.InterventionCodes
import dev.agentle.interventions.ports.InterventionSettings
import dev.agentle.interventions.ports.InterventionSettingsSource
import dev.agentle.interventions.ports.TtsEngineConsent
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaRef
import dev.agentle.interventions.storage.MediaSpec
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import java.util.Locale
import kotlin.time.Duration.Companion.days

/**
 * VOICE deliveries, before the claim (R09 §13.1 #1): [RenderedIntervention.spokenText] (the posted text, so a generic
 * notification is never read out in detail) becomes a WAV in the media store, and the notification gets a Listen action
 * that opens the app. Nothing is spoken here. Any [TtsFailure] is `Unavailable(code)`, and the engine downgrades the
 * delivery to a plain notification (`TTS_UNAVAILABLE`).
 */
class VoicePreparer(
    private val synthesizer: TtsSynthesizer,
    private val library: MediaLibrary,
    private val settings: InterventionSettingsSource,
    private val engineConsent: TtsEngineConsent,
    private val deviceLocale: () -> Locale,
    private val logger: Logger,
) {
    suspend fun prepare(intervention: RenderedIntervention): PrepareResult {
        val text = intervention.spokenText
        if (text.isBlank()) return unavailable(InterventionCodes.TTS_EMPTY_TEXT)
        if (library.admit(MediaKind.VOICE, estimateBytes(text)).getOrNull() != true) return unavailable(InterventionCodes.MEDIA_QUOTA)
        val current = settings.settings().getOrNull() ?: InterventionSettings()
        val locale = current.voiceLocaleTag?.let(Locale::forLanguageTag)?.takeIf { it.language.isNotEmpty() } ?: deviceLocale()
        val allowed = engineConsent.allowedEngines().getOrNull().orEmpty()
        val target = library.newTarget(MediaKind.VOICE, WAV)
        return when (val result = synthesizer.synthesize(text, locale, target.file, allowed)) {
            is TtsResult.Failure -> unavailable(result.failure.code)

            is TtsResult.Success -> {
                val spec = MediaSpec(
                    kind = MediaKind.VOICE,
                    method = GenerationMethod.LOCAL_TTS,
                    mimeType = MIME_WAV,
                    decisionKey = intervention.decisionKey,
                    jitaiId = intervention.jitaiId,
                    expiresAfter = current.mediaExpiryDays.days,
                )
                when (val stored = library.register(target, spec)) {
                    is Outcome.Success -> PrepareResult.Ready(
                        PreparedDelivery(intervention, MediaRef.Stored(stored.value.artifact.id).encoded),
                    )

                    is Outcome.Failure -> unavailable(InterventionCodes.MEDIA_STORE_FAILED)
                }
            }
        }
    }

    private fun unavailable(code: String): PrepareResult {
        logger.i(COMPONENT, "voice unavailable", fields = mapOf("code" to code))
        return PrepareResult.Unavailable(code)
    }

    companion object {
        private const val WAV = "wav"
        private const val MIME_WAV = "audio/wav"
        private const val COMPONENT = "interventions.voice"

        /** 48 kHz mono PCM16 at about 12 characters a second, plus slack; real engines usually write 16-24 kHz. */
        private const val BYTES_PER_CHAR = 8L * 1024L
        private const val BASE_BYTES = 256L * 1024L

        fun estimateBytes(text: String): Long = BASE_BYTES + BYTES_PER_CHAR * text.length
    }
}

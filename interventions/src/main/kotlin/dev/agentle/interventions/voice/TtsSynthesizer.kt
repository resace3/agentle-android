package dev.agentle.interventions.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.agentle.interventions.delivery.InterventionCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Why a synthesis produced no WAV (R09 §2.8, F1-F9). [code] is the content-free reason the delivery records. */
sealed class TtsFailure(val code: String) {
    data object EngineMissing : TtsFailure(InterventionCodes.TTS_ENGINE_MISSING)

    /** Engines exist, but none is allow-listed (privacy-ai-19). */
    data object EngineNotAllowed : TtsFailure(InterventionCodes.TTS_ENGINE_NOT_ALLOWED)

    data class InitFailed(val status: Int) : TtsFailure(InterventionCodes.TTS_INIT_FAILED)

    data object InitTimeout : TtsFailure(InterventionCodes.TTS_INIT_TIMEOUT)

    /** `LANG_MISSING_DATA` or `ERROR_NOT_INSTALLED_YET`: the app may offer `ACTION_INSTALL_TTS_DATA` from a screen. */
    data object LanguageMissing : TtsFailure(InterventionCodes.TTS_LANGUAGE_MISSING)

    data object LanguageUnsupported : TtsFailure(InterventionCodes.TTS_LANGUAGE_UNSUPPORTED)

    /** No on-device voice for the language; network voices are never used. */
    data object NoOfflineVoice : TtsFailure(InterventionCodes.TTS_NO_OFFLINE_VOICE)

    data object QueueRejected : TtsFailure(InterventionCodes.TTS_QUEUE_REJECTED)

    /** `onError(id, code)` with one of `TextToSpeech.ERROR_*`. */
    data class SynthesisError(val errorCode: Int) : TtsFailure(InterventionCodes.TTS_SYNTHESIS_ERROR)

    /** `onStop`: flushed or interrupted by the engine or another client. */
    data class Stopped(val interrupted: Boolean) : TtsFailure(InterventionCodes.TTS_STOPPED)

    data object Timeout : TtsFailure(InterventionCodes.TTS_TIMEOUT)

    /** The file is missing, not RIFF/WAVE, not 16-bit PCM, or empty. */
    data object InvalidOutput : TtsFailure(InterventionCodes.TTS_INVALID_OUTPUT)

    data object EmptyText : TtsFailure(InterventionCodes.TTS_EMPTY_TEXT)
}

/** Result of [TtsSynthesizer.synthesize]. */
sealed interface TtsResult {
    data class Success(val file: File, val wav: WavInfo, val engine: String, val voiceName: String?) : TtsResult

    data class Failure(val failure: TtsFailure) : TtsResult
}

/** Creates the engine client. Tests pass one that keeps the instance and fires `onInit`. */
fun interface TtsFactory {
    fun create(context: Context, listener: TextToSpeech.OnInitListener, enginePackage: String): TextToSpeech

    companion object {
        val DEFAULT: TtsFactory = TtsFactory { context, listener, engine -> TextToSpeech(context, listener, engine) }
    }
}

/**
 * Text to an app-private WAV with `synthesizeToFile` (R09 §2.4), for `prepare` (never speaks, never touches audio focus):
 * - only through an allow-listed engine ([TtsEnginePolicy]), pinned by package, with on-device voices only: a voice that
 *   needs the network is never chosen and network synthesis is requested off (privacy-ai-19);
 * - init and every chunk have a timeout; text longer than `getMaxSpeechInputLength()` is split ([TtsTextChunker]) and
 *   the parts are joined ([Wav.concat]);
 * - the output is validated (RIFF/WAVE, PCM 16-bit, frames > 0), staged next to [out] and renamed into place;
 * - cancelling the coroutine stops the engine and rethrows; the engine is always shut down and temp files deleted.
 */
class TtsSynthesizer(
    private val context: Context,
    private val engines: TtsEnginePolicy = TtsEnginePolicy(context),
    private val factory: TtsFactory = TtsFactory.DEFAULT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val initTimeout: Duration = INIT_TIMEOUT,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    /** Synthesizes [text] in [locale] into [out]; [allowedEngines] are the engines the user allowed by name. */
    suspend fun synthesize(text: String, locale: Locale, out: File, allowedEngines: Set<String> = emptySet()): TtsResult {
        if (text.isBlank()) return TtsResult.Failure(TtsFailure.EmptyText)
        val engine = when (val choice = engines.choose(allowedEngines)) {
            EngineChoice.Missing -> return TtsResult.Failure(TtsFailure.EngineMissing)
            EngineChoice.NotAllowed -> return TtsResult.Failure(TtsFailure.EngineNotAllowed)
            is EngineChoice.Use -> choice.packageName
        }
        val init = CompletableDeferred<Int>()
        // The listener "may be called immediately, before the instance is fully constructed": it only completes.
        val tts = factory.create(context, { status -> init.complete(status) }, engine)
        val temps = mutableListOf<File>()
        return try {
            val failure = awaitInit(init) ?: configure(tts, locale)
            if (failure != null) TtsResult.Failure(failure) else render(tts, text, out, temps, engine)
        } catch (e: CancellationException) {
            tts.stop() // interrupts the utterance "whether played or rendered to file"
            throw e
        } finally {
            tts.shutdown()
            withContext(NonCancellable + io) { temps.forEach { it.delete() } }
        }
    }

    private suspend fun awaitInit(init: CompletableDeferred<Int>): TtsFailure? {
        val status = withTimeoutOrNull(initTimeout) { init.await() }
        return when (status) {
            null -> TtsFailure.InitTimeout
            TextToSpeech.SUCCESS -> null
            else -> TtsFailure.InitFailed(status)
        }
    }

    /** Language check, then an on-device voice; null when the engine is ready. */
    private fun configure(tts: TextToSpeech, locale: Locale): TtsFailure? = when (tts.isLanguageAvailable(locale)) {
        TextToSpeech.LANG_MISSING_DATA -> TtsFailure.LanguageMissing
        TextToSpeech.LANG_NOT_SUPPORTED -> TtsFailure.LanguageUnsupported
        else -> {
            val voice = pickVoice(tts.voices.orEmpty(), locale)
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) TtsFailure.NoOfflineVoice else null
        }
    }

    private suspend fun render(tts: TextToSpeech, text: String, out: File, temps: MutableList<File>, engine: String): TtsResult {
        val pending = ConcurrentHashMap<String, CompletableDeferred<ChunkEnd>>()
        tts.setOnUtteranceProgressListener(CompletionListener(pending))
        val chunks = TtsTextChunker.split(text, TextToSpeech.getMaxSpeechInputLength())
        val parts = chunks.mapIndexed { i, _ -> File(out.parentFile, "${out.name}.part$i$TMP").also { temps += it } }
        val staged = File(out.parentFile, "${out.name}$TMP").also { temps += it }
        withContext(io) { out.parentFile?.mkdirs() }
        chunks.forEachIndexed { i, chunk ->
            synthesizeChunk(tts, chunk, parts[i], pending)?.let { return TtsResult.Failure(it) }
        }
        val info = withContext(io) {
            val joined = if (parts.size == 1) parts[0].takeIf { it.renameTo(staged) }?.let { Wav.parse(staged) } else Wav.concat(parts, staged)
            joined?.takeIf { it.isPcm16 && it.frameCount > 0 && staged.renameTo(out) }
        }
        return info?.let { TtsResult.Success(out, it, engine, tts.voice?.name) } ?: TtsResult.Failure(TtsFailure.InvalidOutput)
    }

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        chunk: String,
        part: File,
        pending: ConcurrentHashMap<String, CompletableDeferred<ChunkEnd>>,
    ): TtsFailure? {
        val id = ids()
        val done = CompletableDeferred<ChunkEnd>().also { pending[id] = it }
        if (tts.synthesizeToFile(chunk, requestParams(), part, id) != TextToSpeech.SUCCESS) return TtsFailure.QueueRejected
        val end = withTimeoutOrNull(chunkTimeout(chunk.length)) { done.await() }
        pending.remove(id)
        return when (end) {
            null -> TtsFailure.Timeout
            ChunkEnd.Done -> null
            is ChunkEnd.Failed -> end.failure
        }
    }

    /** How one chunk ended; a type without null, so a timeout is never mistaken for success. */
    private sealed interface ChunkEnd {
        data object Done : ChunkEnd

        data class Failed(val failure: TtsFailure) : ChunkEnd
    }

    /** Asks for embedded synthesis and no network synthesis (feature keys deprecated in API 21, still read by engines). */
    @Suppress("DEPRECATION")
    private fun requestParams(): Bundle = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
        putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
    }

    /** Completes each chunk's deferred. Callbacks arrive on a binder thread (the map is concurrent). */
    private class CompletionListener(private val pending: Map<String, CompletableDeferred<ChunkEnd>>) : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            pending[utteranceId]?.complete(ChunkEnd.Done)
        }

        @Deprecated("Deprecated in API 21", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) {
            pending[utteranceId]?.complete(ChunkEnd.Failed(TtsFailure.SynthesisError(TextToSpeech.ERROR)))
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val failure = if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) TtsFailure.LanguageMissing else TtsFailure.SynthesisError(errorCode)
            pending[utteranceId]?.complete(ChunkEnd.Failed(failure))
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            pending[utteranceId]?.complete(ChunkEnd.Failed(TtsFailure.Stopped(interrupted)))
        }
    }

    companion object {
        /** R09 §2.3; a product guess (U2): measure on low-end devices. */
        val INIT_TIMEOUT: Duration = 5.seconds
        private const val TMP = ".tmp"

        /** 30 s plus 60 ms per character (R09 §2.4, U2). */
        fun chunkTimeout(length: Int): Duration = 30.seconds + (60L * length).milliseconds

        /**
         * The best on-device voice for [locale]: same language (same country first), never one that needs the network
         * or is not installed yet, local before higher quality.
         */
        @Suppress("DEPRECATION")
        fun pickVoice(voices: Set<Voice>, locale: Locale): Voice? = voices.asSequence()
            .filter { it.locale.language == locale.language }
            .filterNot { it.isNetworkConnectionRequired }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS in it.features.orEmpty() }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .sortedWith(compareBy<Voice> { locale.country.isNotEmpty() && it.locale.country != locale.country }.thenByDescending { it.quality })
            .firstOrNull()
    }
}

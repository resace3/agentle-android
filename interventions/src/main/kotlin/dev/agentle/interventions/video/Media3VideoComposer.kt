@file:OptIn(UnstableApi::class)

package dev.agentle.interventions.video

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Media3 Transformer (R09 §3.2): PNG slides + WAV to H.264/AAC MP4, platform diagnostics off, temp file then rename. */
class Media3VideoComposer(private val context: Context) : VideoComposer {
    override suspend fun compose(slides: List<Slide>, narration: File, out: File, spec: VideoSpec): VideoOutcome =
        withContext(Dispatchers.Main.immediate) {
            val tmp = File(out.parentFile, out.name + ".tmp")
            out.parentFile?.mkdirs()
            val main = Handler(Looper.getMainLooper())
            suspendCancellableCoroutine { cont ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val renamed = tmp.renameTo(out)
                        if (!renamed) tmp.delete()
                        cont.resume(if (renamed) VideoOutcome.Success(out, out.length()) else VideoOutcome.Failure(RENAME_FAILED))
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        tmp.delete() // Transformer never deletes its output
                        cont.resume(failureOf(exportException.errorCode))
                    }
                }
                val encoders = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(spec.videoBitrate).build())
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(spec.audioBitrate).build())
                    .build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .setUsePlatformDiagnostics(false)
                    .addListener(listener)
                    .build()
                transformer.start(composition(slides, narration, spec), tmp.path)
                cont.invokeOnCancellation {
                    main.post {
                        transformer.cancel()
                        tmp.delete()
                    }
                }
            }
        }

    private fun composition(slides: List<Slide>, narration: File, spec: VideoSpec): Composition {
        val images = slides.map { slide ->
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(slide.png))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(slide.durationMs)
                .build()
            EditedMediaItem.Builder(item).setFrameRate(spec.frameRate).build()
        }
        val audio = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(narration))).build()
        val present = Presentation.createForWidthAndHeight(spec.width, spec.height, Presentation.LAYOUT_SCALE_TO_FIT)
        return Composition.Builder(EditedMediaItemSequence.withVideoFrom(images), EditedMediaItemSequence.withAudioFrom(listOf(audio)))
            .setEffects(Effects(emptyList(), listOf(present)))
            .build()
    }

    companion object {
        private const val RENAME_FAILED = "RENAME_FAILED"
        private val FALLBACK_CODES = setOf(
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        )

        fun failureOf(code: Int): VideoOutcome.Failure =
            VideoOutcome.Failure("EXPORT_" + ExportException.getErrorCodeName(code), useFallback = code in FALLBACK_CODES)
    }
}

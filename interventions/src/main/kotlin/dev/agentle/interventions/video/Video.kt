package dev.agentle.interventions.video

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.core.model.GenerationMethod
import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.delivery.InterventionCodes
import dev.agentle.interventions.storage.BundledMedia
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaRef
import dev.agentle.interventions.storage.MediaSpec
import dev.agentle.interventions.voice.Wav
import dev.agentle.jitai.engine.content.RenderedIntervention
import dev.agentle.jitai.engine.ports.PrepareResult
import dev.agentle.jitai.engine.ports.PreparedDelivery
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Slide durations matching the narration parts (R09 Appendix A.4); boundaries accumulate in µs and are rounded once. */
object SlideTimeline {
    fun allocate(partDurationsUs: List<Long>, gapMs: Long = 150, tailMs: Long = 300): List<Long> {
        require(partDurationsUs.isNotEmpty()) { "at least one slide" }
        require(partDurationsUs.all { it > 0 }) { "empty narration part" }
        var boundaryUs = 0L
        var previousMs = 0L
        return partDurationsUs.mapIndexed { i, partUs ->
            boundaryUs += partUs + 1_000L * (if (i == partDurationsUs.lastIndex) tailMs else gapMs)
            val boundaryMs = (boundaryUs + 500) / 1_000
            (boundaryMs - previousMs).also { previousMs = boundaryMs }
        }
    }
}

/** One slide: a PNG at the output size, shown for [durationMs]. */
data class Slide(val png: File, val durationMs: Long)

/** H.264/AAC MP4, 720x1280 at 30 fps, at most 90 s (R09 §13.1 #3). */
data class VideoSpec(
    val width: Int = 720,
    val height: Int = 1280,
    val frameRate: Int = 30,
    val videoBitrate: Int = 1_500_000,
    val audioBitrate: Int = 64_000,
    val maxDurationMs: Long = 90_000,
) {
    /** Admission estimate: bitrate times duration plus 10 %. */
    fun estimateBytes(durationMs: Long): Long = (videoBitrate + audioBitrate).toLong() * durationMs / 8_000 * 11 / 10
}

sealed interface VideoOutcome {
    data class Success(val file: File, val sizeBytes: Long) : VideoOutcome

    /** [code] is content-free; [useFallback] marks codes a MediaCodec fallback could handle (5001, 4001, 4003). */
    data class Failure(val code: String, val useFallback: Boolean = false) : VideoOutcome
}

/** Composes slides and a narration WAV into an MP4 at `out` (a temp file, then renamed). Cancellable. */
fun interface VideoComposer {
    suspend fun compose(slides: List<Slide>, narration: File, out: File, spec: VideoSpec): VideoOutcome
}

/** What [VideoStudio.plan] decided: slides with their durations, the joined narration and the total length. */
data class VideoPlan(val slides: List<Slide>, val narration: File, val durationMs: Long)

/**
 * User-initiated video only (R09 §3.1): a visible screen renders one PNG per slide and one narration WAV per slide; the
 * studio joins the narration, times the slides, enforces the 90 s cap and the quota, composes and registers the MP4.
 */
class VideoStudio(private val composer: VideoComposer, private val library: MediaLibrary, private val spec: VideoSpec = VideoSpec()) {
    fun plan(pngs: List<File>, narrationParts: List<File>, narration: File): VideoPlan? {
        if (pngs.isEmpty() || pngs.size != narrationParts.size) return null
        val parts = narrationParts.map { part -> Wav.parse(part)?.durationUs?.takeIf { it > 0 } ?: return null }
        Wav.concat(narrationParts, narration, GAP_MS.toInt(), TAIL_MS.toInt()) ?: return null
        val durations = SlideTimeline.allocate(parts, GAP_MS, TAIL_MS)
        return VideoPlan(pngs.zip(durations) { png, ms -> Slide(png, ms) }, narration, durations.sum())
    }

    /** Returns the new media id. */
    suspend fun compose(plan: VideoPlan, jitaiId: String?): Outcome<String> {
        if (plan.durationMs > spec.maxDurationMs) return failure(InterventionCodes.VIDEO_UNAVAILABLE)
        if (library.admit(MediaKind.VIDEO, spec.estimateBytes(plan.durationMs)).getOrNull() !=
            true
        ) {
            return failure(InterventionCodes.MEDIA_QUOTA)
        }
        val target = library.newTarget(MediaKind.VIDEO, "mp4")
        val result = try {
            composer.compose(plan.slides, plan.narration, target.file, spec)
        } catch (e: CancellationException) {
            target.file.delete()
            throw e
        }
        return when (result) {
            is VideoOutcome.Failure -> failure(result.code)

            is VideoOutcome.Success -> {
                val media = MediaSpec(MediaKind.VIDEO, GenerationMethod.LOCAL_COMPOSITION, "video/mp4", null, jitaiId, null)
                when (val stored = library.register(target, media)) {
                    is Outcome.Success -> Outcome.success(stored.value.artifact.id)
                    is Outcome.Failure -> stored
                }
            }
        }
    }

    private fun failure(code: String): Outcome<String> = Outcome.failure(AppError.Unexpected(code))

    companion object {
        const val GAP_MS: Long = 150
        const val TAIL_MS: Long = 300
    }
}

/** VIDEO deliveries never compose: a bundled clip, or a previously composed video stored under the rule's asset id. */
class VideoPreparer(private val library: MediaLibrary, private val bundled: BundledMedia) {
    suspend fun prepare(intervention: RenderedIntervention): PrepareResult {
        val assetId = intervention.assetId ?: return PrepareResult.Unavailable(InterventionCodes.VIDEO_UNAVAILABLE)
        val ref = bundled.find(assetId, MediaKind.VIDEO)?.let(MediaRef::Bundled)
            ?: MediaRef.Stored(assetId).takeIf { library.record(assetId)?.artifact?.kind == MediaKind.VIDEO }
            ?: return PrepareResult.Unavailable(InterventionCodes.VIDEO_UNAVAILABLE)
        return PrepareResult.Ready(PreparedDelivery(intervention, ref.encoded))
    }
}

package dev.agentle.interventions

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrNull
import dev.agentle.interventions.storage.InMemoryMediaMetadataStore
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaPaths
import dev.agentle.interventions.storage.PendingDeliveries
import dev.agentle.interventions.video.CodecPlan
import dev.agentle.interventions.video.FallbackVideoComposer
import dev.agentle.interventions.video.VideoComposer
import dev.agentle.interventions.video.VideoOutcome
import dev.agentle.interventions.video.VideoSpec
import dev.agentle.interventions.video.VideoStudio
import dev.agentle.interventions.voice.Wav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val store = InMemoryMediaMetadataStore()
    private val library by lazy {
        MediaLibrary(
            MediaPaths({ tmp.root }, { File(tmp.root, "cache") }),
            store,
            Fixtures.clock(),
            PendingDeliveries { emptySet() },
            Logger.NONE,
            io = Dispatchers.Unconfined,
        )
    }

    private fun wav(name: String, ms: Int): File {
        val frames = 16 * ms
        return tmp.newFile(name).apply { writeBytes(Wav.header(16_000, 1, 16, frames * 2L) + ByteArray(frames * 2)) }
    }

    private val writing = VideoComposer { _, _, out, _ ->
        out.parentFile.mkdirs()
        out.writeBytes(ByteArray(100))
        VideoOutcome.Success(out, 100)
    }

    @Test
    fun `studio plans slides from narration and registers the composed video`() = runTest {
        val studio = VideoStudio(writing, library)
        val pngs = listOf(tmp.newFile("1.png"), tmp.newFile("2.png"))
        val plan = studio.plan(pngs, listOf(wav("a.wav", 1_000), wav("b.wav", 2_000)), File(tmp.root, "n.wav"))!!
        assertThat(plan.slides.map { it.durationMs }).containsExactly(1_150L, 2_300L).inOrder()
        val id = studio.compose(plan, "jitai-1").getOrNull()!!
        assertThat(library.record(id)).isNotNull()
    }

    @Test
    fun `studio refuses mismatched input, over-long videos and composer failures`() = runTest {
        val studio = VideoStudio(writing, library, VideoSpec(maxDurationMs = 1_000))
        assertThat(studio.plan(listOf(tmp.newFile("1.png")), emptyList(), File(tmp.root, "n.wav"))).isNull()
        val plan = studio.plan(listOf(tmp.newFile("2.png")), listOf(wav("a.wav", 2_000)), File(tmp.root, "n.wav"))!!
        assertThat(studio.compose(plan, null)).isInstanceOf(Outcome.Failure::class.java)
        val failing = VideoStudio({ _, _, _, _ -> VideoOutcome.Failure("EXPORT_X") }, library)
        assertThat(failing.compose(plan, null)).isInstanceOf(Outcome.Failure::class.java)
        assertThat(store.all().getOrNull()).isEmpty()
    }

    @Test
    fun `cancelling a compose deletes the target`() = runTest {
        val started = CompletableDeferred<File>()
        val hanging = VideoComposer { _, _, out, _ ->
            out.parentFile.mkdirs()
            out.writeBytes(ByteArray(1))
            started.complete(out)
            CompletableDeferred<VideoOutcome>().await()
        }
        val studio = VideoStudio(hanging, library)
        val plan = studio.plan(listOf(tmp.newFile("1.png")), listOf(wav("a.wav", 500)), File(tmp.root, "n.wav"))!!
        val job = launch { studio.compose(plan, null) }
        val target = started.await()
        job.cancel()
        job.join()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `fallback runs only for fallback codes`() = runTest {
        var fallbackCalls = 0
        val fallback = VideoComposer { _, _, out, _ ->
            fallbackCalls++
            VideoOutcome.Success(out, 1)
        }
        val out = File(tmp.root, "o.mp4")
        val retry = FallbackVideoComposer({ _, _, _, _ -> VideoOutcome.Failure("EXPORT_ENC", useFallback = true) }, fallback)
        assertThat(retry.compose(emptyList(), out, out, VideoSpec())).isInstanceOf(VideoOutcome.Success::class.java)
        val noRetry = FallbackVideoComposer({ _, _, _, _ -> VideoOutcome.Failure("EXPORT_IO") }, fallback)
        assertThat(noRetry.compose(emptyList(), out, out, VideoSpec())).isEqualTo(VideoOutcome.Failure("EXPORT_IO"))
        assertThat(fallbackCalls).isEqualTo(1)
    }

    @Test
    fun `codec plan covers every slide at the frame rate`() {
        val frames = CodecPlan.frames(listOf(1_000L, 500L), 30)
        assertThat(frames).hasSize(45)
        assertThat(frames.count { it.slideIndex == 0 }).isEqualTo(30)
        assertThat(frames.last().slideIndex).isEqualTo(1)
        assertThat(frames.zipWithNext().all { (a, b) -> b.ptsUs > a.ptsUs }).isTrue()
    }

    @Test
    fun `codec plan reads whole PCM frames with matching timestamps`() {
        val info = Wav.parse(wav("a.wav", 1_000))!!
        val chunks = CodecPlan.audio(info, chunkBytes = 3_001)
        assertThat(chunks.sumOf { it.size.toLong() }).isEqualTo(info.dataSize)
        assertThat(chunks.all { it.size % info.bytesPerFrame == 0 }).isTrue()
        assertThat(chunks[1].ptsUs).isEqualTo(1_500L * 1_000_000 / 16_000)
    }

    @Test
    fun `i420 maps white and black to limited range luma`() {
        val yuv = CodecPlan.i420(intArrayOf(-1, -1, 0xFF000000.toInt(), 0xFF000000.toInt()), 2, 2)
        assertThat(yuv).hasLength(6)
        assertThat(yuv[0].toInt() and 0xFF).isEqualTo(235)
        assertThat(yuv[2].toInt() and 0xFF).isEqualTo(16)
    }
}

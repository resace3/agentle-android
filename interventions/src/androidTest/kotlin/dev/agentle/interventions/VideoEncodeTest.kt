package dev.agentle.interventions

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.agentle.interventions.video.CodecVideoComposer
import dev.agentle.interventions.video.Media3VideoComposer
import dev.agentle.interventions.video.Slide
import dev.agentle.interventions.video.VideoComposer
import dev.agentle.interventions.video.VideoOutcome
import dev.agentle.interventions.video.VideoSpec
import dev.agentle.interventions.voice.Wav
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A real encode on the emulator matrix: both composers produce an MP4 with one H.264 and one AAC track. */
@RunWith(AndroidJUnit4::class)
class VideoEncodeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "encode-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val spec = VideoSpec(width = 360, height = 640)

    private fun slide(name: String, color: Int): File = File(dir, name).apply {
        val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun narration(): File {
        val frames = 16_000 * 2
        return File(dir, "n.wav").apply { writeBytes(Wav.header(16_000, 1, 16, frames * 2L) + ByteArray(frames * 2)) }
    }

    private fun encodeWith(composer: VideoComposer, name: String) = runBlocking {
        val slides = listOf(Slide(slide("a.png", Color.RED), 1_000), Slide(slide("b.png", Color.BLUE), 1_000))
        val out = File(dir, name)
        val result = composer.compose(slides, narration(), out, spec)
        assertThat(result).isInstanceOf(VideoOutcome.Success::class.java)
        val mimes = MediaExtractor().run {
            setDataSource(out.path)
            val list = (0 until trackCount).map { getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            release()
            list
        }
        assertThat(mimes).containsExactly(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC)
        assertThat(File(dir, "$name.tmp").exists()).isFalse()
    }

    @Test
    fun media3EncodesAnMp4() = encodeWith(Media3VideoComposer(context), "media3.mp4")

    @Test
    fun mediaCodecFallbackEncodesAnMp4() = encodeWith(CodecVideoComposer(), "codec.mp4")
}

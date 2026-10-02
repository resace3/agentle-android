@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "NestedBlockDepth",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.agentle.interventions.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.agentle.interventions.voice.Wav
import dev.agentle.interventions.voice.WavInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** The frame and audio schedule of the MediaCodec fallback, as pure functions (unit-tested without a codec). */
object CodecPlan {
    /** One video frame: the slide it shows and its presentation time. */
    data class Frame(val slideIndex: Int, val ptsUs: Long)

    /** One PCM read: [offset] bytes into the data chunk, [size] bytes, presented at [ptsUs]. */
    data class AudioChunk(val offset: Long, val size: Int, val ptsUs: Long)

    /** Frames at [fps] covering the slides' durations; each frame shows the slide whose interval contains its time. */
    fun frames(durationsMs: List<Long>, fps: Int): List<Frame> {
        require(durationsMs.isNotEmpty() && fps > 0)
        val boundariesUs = durationsMs.runningReduce(Long::plus).map { it * US_PER_MS }
        val total = ((boundariesUs.last() * fps + US_PER_S / 2) / US_PER_S).coerceAtLeast(1)
        var slide = 0
        return (0 until total).map { i ->
            val pts = i * US_PER_S / fps
            while (slide < boundariesUs.lastIndex && pts >= boundariesUs[slide]) slide++
            Frame(slide, pts)
        }
    }

    /** Whole-frame PCM chunks of at most [chunkBytes], with timestamps from the frames already read. */
    fun audio(wav: WavInfo, chunkBytes: Int = DEFAULT_AUDIO_CHUNK): List<AudioChunk> {
        val frameBytes = wav.bytesPerFrame
        require(frameBytes > 0 && wav.sampleRate > 0)
        val step = (chunkBytes / frameBytes).coerceAtLeast(1) * frameBytes
        val chunks = ArrayList<AudioChunk>()
        var offset = 0L
        while (offset < wav.dataSize) {
            val size = minOf(step.toLong(), wav.dataSize - offset).toInt()
            chunks += AudioChunk(offset, size, offset / frameBytes * US_PER_S / wav.sampleRate)
            offset += size
        }
        return chunks
    }

    /** Planar I420 (Y, then U, then V) of an ARGB image, BT.601 limited range. */
    fun i420(argb: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        val uBase = width * height
        val vBase = uBase + uBase / 4
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                out[y * width + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (y % 2 == 0 && x % 2 == 0) {
                    val i = (y / 2) * (width / 2) + x / 2
                    out[uBase + i] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    out[vBase + i] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
        return out
    }

    const val DEFAULT_AUDIO_CHUNK: Int = 8 * 1024
    private const val US_PER_MS = 1_000L
    private const val US_PER_S = 1_000_000L
}

/**
 * The fallback composer (R09 §3.2): MediaCodec H.264 + AAC into MediaMuxer, used when Media3 fails with a code marked
 * `useFallback` (frame processing, encoder init, unsupported format). Audio is encoded first (small: AAC at 64 kb/s), then
 * video streams into the muxer. 16-bit PCM WAV only. Temp file then rename; cancellation deletes the temp file.
 */
class CodecVideoComposer(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) : VideoComposer {
    override suspend fun compose(slides: List<Slide>, narration: File, out: File, spec: VideoSpec): VideoOutcome =
        withContext(dispatcher) {
            val tmp = File(out.parentFile, out.name + ".tmp")
            out.parentFile?.mkdirs()
            try {
                val wav = Wav.parse(narration)?.takeIf { it.audioFormat == PCM && it.bitsPerSample == PCM_BITS }
                    ?: return@withContext VideoOutcome.Failure(BAD_AUDIO)
                encode(slides, narration, wav, tmp, spec)
                if (tmp.renameTo(out)) VideoOutcome.Success(out, out.length()) else VideoOutcome.Failure(RENAME_FAILED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VideoOutcome.Failure("CODEC_" + (e::class.simpleName ?: "ERROR"))
            } finally {
                tmp.delete()
            }
        }

    private suspend fun encode(slides: List<Slide>, narration: File, wav: WavInfo, tmp: File, spec: VideoSpec) {
        val (audioFormat, audioSamples) = encodeAudio(narration, wav, spec)
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var started = false
        try {
            codec.configure(videoFormat(spec), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val frames = CodecPlan.frames(slides.map { it.durationMs }, spec.frameRate)
            val yuv = slides.map { CodecPlan.i420(argbOf(it.png, spec), spec.width, spec.height) }
            var audioTrack = -1
            var videoTrack = -1
            val onFormat = { format: MediaFormat ->
                videoTrack = muxer.addTrack(format)
                audioTrack = muxer.addTrack(audioFormat)
                muxer.start()
                started = true
                audioSamples.forEach { (info, bytes) -> muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), info) }
            }
            val write = { buffer: ByteBuffer, info: MediaCodec.BufferInfo -> muxer.writeSampleData(videoTrack, buffer, info) }
            var next = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        if (next < frames.size) {
                            val frame = frames[next++]
                            val data = yuv[frame.slideIndex]
                            fillImage(codec, index, data, spec)
                            codec.queueInputBuffer(index, 0, data.size, frame.ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                outputDone = drain(codec, onFormat, write)
            }
        } finally {
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }

    private suspend fun encodeAudio(narration: File, wav: WavInfo, spec: VideoSpec): Pair<MediaFormat, List<EncodedSample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, wav.channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, spec.audioBitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CodecPlan.DEFAULT_AUDIO_CHUNK)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<EncodedSample>()
        var outFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(narration, "r").use { file ->
                val chunks = CodecPlan.audio(wav)
                var next = 0
                var inputDone = false
                var outputDone = false
                val chunk = ByteArray(CodecPlan.DEFAULT_AUDIO_CHUNK)
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            if (next < chunks.size) {
                                val c = chunks[next++]
                                file.seek(wav.dataOffset + c.offset)
                                file.readFully(chunk, 0, c.size)
                                checkNotNull(codec.getInputBuffer(index)).apply { clear() }.put(chunk, 0, c.size)
                                codec.queueInputBuffer(index, 0, c.size, c.ptsUs, 0)
                            } else {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        }
                    }
                    outputDone = drain(codec, { outFormat = it }) { buffer, info ->
                        val bytes = ByteArray(info.size).also { buffer.get(it) }
                        samples += MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) } to bytes
                    }
                }
            }
        } finally {
            codec.release()
        }
        return checkNotNull(outFormat) to samples
    }

    /** Takes encoder output until none is ready; true at end of stream. Codec config buffers are not written. */
    private fun drain(codec: MediaCodec, onFormat: (MediaFormat) -> Unit, write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)

                index >= 0 -> {
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.position(info.offset).limit(info.offset + info.size)
                        write(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    /** Copies planar I420 into the codec's flexible YUV image, honouring its strides. */
    private fun fillImage(codec: MediaCodec, index: Int, i420: ByteArray, spec: VideoSpec) {
        val image = checkNotNull(codec.getInputImage(index))
        val sizes = intArrayOf(spec.width * spec.height, spec.width * spec.height / 4, spec.width * spec.height / 4)
        var base = 0
        image.planes.forEachIndexed { p, plane ->
            val w = if (p == 0) spec.width else spec.width / 2
            val h = if (p == 0) spec.height else spec.height / 2
            val buf = plane.buffer
            for (row in 0 until h) {
                for (col in 0 until w) {
                    buf.put(row * plane.rowStride + col * plane.pixelStride, i420[base + row * w + col])
                }
            }
            base += sizes[p]
        }
    }

    private fun argbOf(png: File, spec: VideoSpec): IntArray {
        val frame = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame).apply { drawColor(Color.BLACK) }
        BitmapFactory.decodeFile(png.path)?.let { source ->
            val scale = minOf(spec.width.toFloat() / source.width, spec.height.toFloat() / source.height)
            val w = (source.width * scale).toInt()
            val h = (source.height * scale).toInt()
            val left = (spec.width - w) / 2
            val top = (spec.height - h) / 2
            canvas.drawBitmap(source, null, Rect(left, top, left + w, top + h), null)
            source.recycle()
        }
        return IntArray(spec.width * spec.height).also { frame.getPixels(it, 0, spec.width, 0, 0, spec.width, spec.height) }
            .also { frame.recycle() }
    }

    private fun videoFormat(spec: VideoSpec): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, spec.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PCM = 1
        const val PCM_BITS = 16
        const val BAD_AUDIO = "CODEC_BAD_AUDIO"
        const val RENAME_FAILED = "RENAME_FAILED"
    }
}

/** One encoded sample held in memory until the muxer starts. */
private typealias EncodedSample = Pair<MediaCodec.BufferInfo, ByteArray>

/** Media3 first; a failure marked `useFallback` is retried once with [fallback] (R09 §3.2). */
class FallbackVideoComposer(private val primary: VideoComposer, private val fallback: VideoComposer) : VideoComposer {
    override suspend fun compose(slides: List<Slide>, narration: File, out: File, spec: VideoSpec): VideoOutcome =
        when (val first = primary.compose(slides, narration, out, spec)) {
            is VideoOutcome.Failure -> if (first.useFallback) fallback.compose(slides, narration, out, spec) else first
            is VideoOutcome.Success -> first
        }
}

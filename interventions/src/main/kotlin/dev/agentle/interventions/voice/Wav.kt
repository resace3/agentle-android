package dev.agentle.interventions.voice

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parsed RIFF/WAVE header of a PCM file (what `TextToSpeech.synthesizeToFile` writes; R09 §2.4, Appendix A.2). */
data class WavInfo(
    /** 1 = PCM integer, 3 = IEEE float. */
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / BITS_PER_BYTE)
    val frameCount: Long get() = if (bytesPerFrame == 0) 0 else dataSize / bytesPerFrame
    val durationUs: Long get() = if (sampleRate == 0) 0 else frameCount * MICROS_PER_SECOND / sampleRate
    val isPcm16: Boolean get() = audioFormat == PCM && bitsPerSample == BITS_16

    private companion object {
        const val BITS_PER_BYTE = 8
        const val MICROS_PER_SECOND = 1_000_000L
        const val PCM = 1
        const val BITS_16 = 16
    }
}

/** WAV header parsing and concatenation (R09 Appendix A.2), tolerant of engines that write the length late. */
object Wav {
    private const val MAX_HEADER_SCAN = 4096
    private const val RIFF_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val FMT_MIN = 16
    private const val UNKNOWN_SIZE = 0xFFFFFFFFL
    private const val HEADER_BYTES = 44
    private const val COPY_BUFFER = 64 * 1024
    private const val MILLIS_PER_SECOND = 1000L

    /**
     * Parses the header. A data size of 0 or 0xFFFFFFFF (header written before the length was known, or an interrupted
     * synthesis) is replaced by the real file length; `LIST` and other chunks are skipped.
     */
    fun parse(header: ByteArray, fileLength: Long): WavInfo? {
        if (header.size < RIFF_HEADER || ascii(header, 0) != "RIFF" || ascii(header, CHUNK_HEADER) != "WAVE") return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        var pos = RIFF_HEADER
        var fmt: IntArray? = null
        var result: WavInfo? = null
        while (result == null && pos + CHUNK_HEADER <= header.size) {
            val id = ascii(header, pos)
            val size = bb.getInt(pos + 4).toLong() and UNKNOWN_SIZE
            val body = pos + CHUNK_HEADER
            if (id == "fmt " && body + FMT_MIN <= header.size) {
                fmt = intArrayOf(
                    bb.getShort(body).toInt() and 0xFFFF,
                    bb.getShort(body + 2).toInt() and 0xFFFF,
                    bb.getInt(body + 4),
                    bb.getShort(body + 14).toInt() and 0xFFFF,
                )
            } else if (id == "data") {
                val f = fmt ?: return null
                val available = (fileLength - body).coerceAtLeast(0)
                val declared = if (size == 0L || size == UNKNOWN_SIZE) available else size
                result = WavInfo(f[0], f[1], f[2], f[3], body.toLong(), minOf(declared, available))
            }
            // Chunks are word aligned; a huge chunk before `data` means this is not a file we can read.
            pos = if (size > MAX_HEADER_SCAN) header.size else body + ((size + 1) and 1L.inv()).toInt()
        }
        return result
    }

    fun parse(file: File): WavInfo? {
        if (!file.isFile) return null
        val length = file.length()
        val header = ByteArray(minOf(length, MAX_HEADER_SCAN.toLong()).toInt())
        RandomAccessFile(file, "r").use { it.readFully(header) }
        return parse(header, length)
    }

    /** Canonical 44-byte PCM header. */
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / Byte.SIZE_BITS
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((HEADER_BYTES - CHUNK_HEADER + dataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(FMT_MIN)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / Byte.SIZE_BITS).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize.toInt())
        }.array()
    }

    /**
     * Joins PCM WAV files of one format (chunked TTS output) into [out], with [silenceBetweenMs] between inputs and
     * [trailingSilenceMs] after the last, so a narration can be exactly as long as its slide timeline. Null when an
     * input is unreadable or the formats differ.
     */
    fun concat(inputs: List<File>, out: File, silenceBetweenMs: Int = 150, trailingSilenceMs: Int = 0): WavInfo? {
        val infos = inputs.map { parse(it) ?: return null }
        val first = infos.firstOrNull() ?: return null
        val sameFormat = infos.all {
            it.sampleRate == first.sampleRate && it.channels == first.channels &&
                it.bitsPerSample == first.bitsPerSample && it.audioFormat == first.audioFormat
        }
        if (!sameFormat) return null
        val silenceBytes = first.sampleRate.toLong() * silenceBetweenMs / MILLIS_PER_SECOND * first.bytesPerFrame
        val tailBytes = first.sampleRate.toLong() * trailingSilenceMs / MILLIS_PER_SECOND * first.bytesPerFrame
        val total = infos.sumOf { it.dataSize } + silenceBytes * (infos.size - 1) + tailBytes
        out.outputStream().buffered().use { os ->
            os.write(header(first.sampleRate, first.channels, first.bitsPerSample, total))
            inputs.forEachIndexed { i, f ->
                copyData(f, infos[i], os)
                if (i < inputs.lastIndex) os.write(ByteArray(silenceBytes.toInt()))
            }
            if (tailBytes > 0) os.write(ByteArray(tailBytes.toInt()))
        }
        return parse(out)
    }

    private fun copyData(file: File, info: WavInfo, os: OutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val buf = ByteArray(COPY_BUFFER)
            var remaining = info.dataSize
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                os.write(buf, 0, n)
                remaining -= n
            }
        }
    }

    private fun ascii(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
}

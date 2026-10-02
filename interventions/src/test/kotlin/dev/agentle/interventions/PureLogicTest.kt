package dev.agentle.interventions

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.storage.EvictionCandidate
import dev.agentle.interventions.storage.MediaEvictionPolicy
import dev.agentle.interventions.storage.MediaQuota
import dev.agentle.interventions.video.SlideTimeline
import dev.agentle.interventions.voice.TtsTextChunker
import dev.agentle.interventions.voice.Wav
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class PureLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pcm(name: String, rate: Int, frames: Int): File =
        tmp.newFile(name).apply { writeBytes(Wav.header(rate, 1, 16, frames * 2L) + ByteArray(frames * 2)) }

    @Test
    fun `wav concat adds gaps and tail`() {
        val info = Wav.concat(listOf(pcm("a.wav", 16_000, 16_000), pcm("b.wav", 16_000, 8_000)), File(tmp.root, "o.wav"), 150, 300)!!
        assertThat(info.frameCount).isEqualTo(16_000L + 8_000 + 2_400 + 4_800)
    }

    @Test
    fun `wav parse rejects text output`() {
        assertThat(Wav.parse(tmp.newFile("x.wav").apply { writeText("hello\n") })).isNull()
    }

    @Test
    fun `chunker respects the limit`() {
        val text = (1..40).joinToString(" ") { "Sentence $it is here." }
        val chunks = TtsTextChunker.split(text, 100)
        assertThat(chunks.all { it.length <= 100 }).isTrue()
        assertThat(chunks.joinToString(" ")).isEqualTo(text)
    }

    @Test
    fun `slide timeline sums to the narration`() {
        val durations = SlideTimeline.allocate(listOf(1_000_400L, 2_000_300L, 700_000L))
        assertThat(durations.sum()).isEqualTo((3_700_700L + 2 * 150_000 + 300_000 + 500) / 1_000)
    }

    @Test
    fun `eviction takes expired first and never pending`() {
        val now = Instant.parse("2026-10-01T10:00:00Z")
        fun c(id: String, mib: Long, pending: Boolean = false, expires: Instant? = null) = EvictionCandidate(
            id = id,
            kind = MediaKind.IMAGE,
            sizeBytes = mib * 1024 * 1024,
            createdAt = now - 2.days,
            lastUsedAt = now - (mib).hours,
            pending = pending,
            expiresAt = expires,
        )
        val chosen = MediaEvictionPolicy.select(
            listOf(c("expired", 1, expires = now - 1.hours), c("old", 40), c("pending", 40, pending = true)),
            MediaQuota(),
            now,
        )
        assertThat(chosen).contains("expired")
        assertThat(chosen).doesNotContain("pending")
    }
}

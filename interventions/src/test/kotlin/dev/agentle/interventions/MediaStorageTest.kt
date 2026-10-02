package dev.agentle.interventions

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Logger
import dev.agentle.core.common.getOrNull
import dev.agentle.core.model.GenerationMethod
import dev.agentle.core.model.MediaKind
import dev.agentle.interventions.storage.InMemoryMediaMetadataStore
import dev.agentle.interventions.storage.MediaLibrary
import dev.agentle.interventions.storage.MediaPaths
import dev.agentle.interventions.storage.MediaQuota
import dev.agentle.interventions.storage.MediaRef
import dev.agentle.interventions.storage.MediaSpec
import dev.agentle.interventions.storage.PendingDeliveries
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MediaStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val clock = Fixtures.clock()
    private val store = InMemoryMediaMetadataStore()
    private var pendingKeys = emptySet<String>()
    private val paths by lazy { MediaPaths({ tmp.root }, { File(tmp.root, "cache") }) }
    private var nextId = 0

    private fun library(quota: MediaQuota = MediaQuota()) = MediaLibrary(
        paths,
        store,
        clock,
        PendingDeliveries { pendingKeys },
        Logger.NONE,
        quota = quota,
        io = UnconfinedTestDispatcher(),
        ids = { "m${nextId++}" },
    )

    private suspend fun MediaLibrary.add(bytes: Int, key: String? = null, expiresAfter: Duration? = null): String {
        val target = newTarget(MediaKind.IMAGE, "png")
        target.file.parentFile.mkdirs()
        target.file.writeBytes(ByteArray(bytes))
        val spec = MediaSpec(MediaKind.IMAGE, GenerationMethod.LOCAL_TEMPLATE, "image/png", key, "jitai-1", expiresAfter)
        return register(target, spec).getOrNull()!!.artifact.id
    }

    private val small = MediaQuota(totalBytes = 1_000, perKindBytes = mapOf(MediaKind.IMAGE to 1_000), minAge = 10.minutes)

    @Test
    fun `admission refuses what cannot fit and evicts the least recently used`() = runTest {
        val lib = library(small)
        val a = lib.add(400)
        clock.advanceBy(1.minutes)
        val b = lib.add(400)
        clock.advanceBy(1.hours)
        lib.touch(a)
        assertThat(lib.admit(MediaKind.IMAGE, 300).getOrNull()).isTrue()
        assertThat(lib.record(b)).isNull()
        assertThat(lib.record(a)).isNotNull()
        assertThat(lib.admit(MediaKind.IMAGE, 2_000).getOrNull()).isFalse()
    }

    @Test
    fun `expired media goes, pending and young media stay`() = runTest {
        val lib = library()
        val expired = lib.add(10, expiresAfter = 1.hours)
        val pending = lib.add(10, key = "k-pending", expiresAfter = 1.hours)
        clock.advanceBy(2.hours)
        val young = lib.add(10, expiresAfter = 1.minutes)
        clock.advanceBy(2.minutes)
        pendingKeys = setOf("k-pending")
        lib.runMaintenance()
        assertThat(lib.record(expired)).isNull()
        assertThat(lib.record(pending)).isNotNull()
        assertThat(lib.record(young)).isNotNull()
    }

    @Test
    fun `the orphan sweep removes old files without rows and rows without files`() = runTest {
        val lib = library()
        val gone = lib.add(10)
        lib.fileOf(store.get(gone).getOrNull()!!)!!.delete()
        val orphan = File(paths.dir(MediaKind.IMAGE), "orphan.png").apply { writeBytes(ByteArray(5)) }
        val temp = File(paths.dir(MediaKind.IMAGE), "x.png.tmp").apply { writeBytes(ByteArray(5)) }
        orphan.setLastModified(0)
        temp.setLastModified(0)
        clock.advanceBy(1.days)
        val report = lib.runMaintenance().getOrNull()!!
        assertThat(report.missingRows).isEqualTo(1)
        assertThat(orphan.exists()).isFalse()
        assertThat(temp.exists()).isFalse()
    }

    @Test
    fun `discard deletes only media made for that decision, delete-all removes everything`() = runTest {
        val lib = library()
        val mine = lib.add(10, key = "k1")
        val other = lib.add(10, key = "k2")
        lib.discardFor(MediaRef.Stored(other), "k1")
        lib.discardFor(MediaRef.Stored(mine), "k1")
        assertThat(lib.record(mine)).isNull()
        assertThat(lib.record(other)).isNotNull()
        File(paths.shareDir.apply { mkdirs() }, "copy.png").writeBytes(ByteArray(3))
        val report = lib.deleteAllGenerated().getOrNull()!!
        assertThat(report.rows).isEqualTo(1)
        assertThat(report.shareCopies).isEqualTo(1)
        assertThat(paths.root.walkTopDown().count { it.isFile }).isEqualTo(0)
    }
}

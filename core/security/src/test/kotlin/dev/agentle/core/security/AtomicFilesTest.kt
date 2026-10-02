package dev.agentle.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Atomic small-file writes (tmp, fsync, rename) used for wrapped keys, vault blobs and security state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AtomicFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a write creates missing directories and leaves no temp file`() {
        val target = File(temp.root, "a/b/c.bin")
        AtomicFiles.write(target, byteArrayOf(1, 2, 3))
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(target.path + AtomicFiles.TEMP_SUFFIX).exists()).isFalse()
    }

    @Test
    fun `a write that fails midway keeps the previous content`() {
        val target = File(temp.root, "state.bin")
        AtomicFiles.write(target, byteArrayOf(9, 9))
        assertThrows(IOException::class.java) {
            AtomicFiles.write(target) { out ->
                out.write(byteArrayOf(1, 2, 3, 4))
                throw IOException("disk full")
            }
        }
        assertThat(target.readBytes()).isEqualTo(byteArrayOf(9, 9))
        assertThat(File(target.path + AtomicFiles.TEMP_SUFFIX).exists()).isFalse()
    }

    @Test
    fun `delete removes the file and a leftover temp file`() {
        val target = File(temp.root, "x.bin")
        target.writeBytes(byteArrayOf(1))
        File(target.path + AtomicFiles.TEMP_SUFFIX).writeBytes(byteArrayOf(2))
        assertThat(AtomicFiles.delete(target)).isTrue()
        assertThat(target.exists()).isFalse()
        assertThat(File(target.path + AtomicFiles.TEMP_SUFFIX).exists()).isFalse()
        assertThat(AtomicFiles.delete(target)).isTrue()
    }
}

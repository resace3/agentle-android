package dev.agentle.data.deletion

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Delete all as a resumable sequence (round 2 correction 6): a crash at every step finishes on the next start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DeleteAllServiceTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeSteps(var crashAt: String? = null, var leftovers: Boolean = false) : DeleteAllSteps {
        val calls = mutableListOf<String>()
        var filesPresent = true

        private fun step(name: String) {
            if (crashAt == name) {
                crashAt = null
                throw IllegalStateException("crash")
            }
            calls += name
        }

        override suspend fun stopProducers() = step("stop")

        override suspend fun revokeRemote() = step("revoke")

        override suspend fun closeDatabase() = step("close")

        override suspend fun deleteFilesAndKeys() {
            step("delete")
            filesPresent = leftovers
        }

        override suspend fun verifyGone(): Boolean {
            step("verify")
            return !filesPresent
        }

        override suspend fun clearApplicationUserData() = step("clear")
    }

    @Test
    fun `runs every step in order and removes the marker before clearing`() = runTest {
        val marker = folder.root.resolve("delete-all/marker")
        val steps = FakeSteps()

        assertThat(DeleteAllService(marker, steps).deleteAll()).isEqualTo(DeleteAllOutcome.CLEARED)

        assertThat(steps.calls).containsExactly("stop", "revoke", "close", "delete", "verify", "clear").inOrder()
        assertThat(marker.exists()).isFalse()
    }

    @Test
    fun `a crash at any step resumes from that step on the next start`() = runTest {
        for (crash in listOf("stop", "revoke", "close", "delete", "verify", "clear")) {
            val marker = folder.newFolder().resolve("marker")
            val steps = FakeSteps(crashAt = crash)
            val service = DeleteAllService(marker, steps)

            assertThat(runCatching { service.deleteAll() }.isFailure).isTrue()
            assertThat(marker.exists()).isEqualTo(crash != "clear")

            val resumed = DeleteAllService(marker, steps).resumeIfPending()
            if (crash == "clear") {
                // The marker is removed before the system call; app data clearing itself is the system's job.
                assertThat(resumed).isNull()
            } else {
                assertThat(resumed).isEqualTo(DeleteAllOutcome.CLEARED)
                assertThat(steps.calls.last()).isEqualTo("clear")
            }
            assertThat(marker.exists()).isFalse()
        }
    }

    @Test
    fun `files left after the wipe keep the marker for a retry`() = runTest {
        val marker = folder.root.resolve("marker")
        val steps = FakeSteps(leftovers = true)

        assertThat(DeleteAllService(marker, steps).deleteAll()).isEqualTo(DeleteAllOutcome.VERIFY_FAILED)
        assertThat(marker.exists()).isTrue()
        assertThat(steps.calls).doesNotContain("clear")

        steps.leftovers = false
        assertThat(DeleteAllService(marker, steps).resumeIfPending()).isEqualTo(DeleteAllOutcome.CLEARED)
    }

    @Test
    fun `nothing is pending without a marker`() = runTest {
        assertThat(DeleteAllService(folder.root.resolve("none"), FakeSteps()).resumeIfPending()).isNull()
    }
}

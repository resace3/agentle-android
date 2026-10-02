package dev.agentle.data.deletion

import java.io.File
import java.io.IOException

/**
 * The parts of "delete all data" that touch the outside of the database (docs/ARCHITECTURE.md §5.5). Every step is
 * idempotent: after a crash the service runs the interrupted step again.
 */
interface DeleteAllSteps {
    /** Cancels workers, unregisters listeners and stops the foreground service, so nothing writes any more. */
    suspend fun stopProducers()

    /** Revokes remote grants and tokens (best effort; a network failure does not stop the local wipe). */
    suspend fun revokeRemote()

    suspend fun closeDatabase()

    /** Deletes the database files, settings, media and caches, and crypto-erases the keys. */
    suspend fun deleteFilesAndKeys()

    /** True when no personal file or key is left. */
    suspend fun verifyGone(): Boolean

    /** `ActivityManager.clearApplicationUserData()`: the process ends. */
    suspend fun clearApplicationUserData()
}

/** How a delete-all run ended. */
enum class DeleteAllOutcome {
    /** The app data was cleared (in production the process is gone before this returns). */
    CLEARED,

    /** Files or keys were still present after the wipe; the marker stays and the next start retries. */
    VERIFY_FAILED,
}

/**
 * Delete all data as a resumable sequence (round 2 correction 6): marker file, stop producers, revoke remote, close
 * the database, delete files and keys, verify, clear the application user data. The marker file (in no-backup
 * storage, outside every deleted directory) holds the next step, written atomically before the step runs; at app
 * start [resumeIfPending] finishes an interrupted run.
 */
class DeleteAllService(private val marker: File, private val steps: DeleteAllSteps) {
    suspend fun deleteAll(): DeleteAllOutcome = runFrom(if (pendingStep() == null) STEP_STOP else checkNotNull(pendingStep()))

    suspend fun resumeIfPending(): DeleteAllOutcome? = pendingStep()?.let { runFrom(it) }

    /** The step an interrupted run stopped at, or null. */
    fun pendingStep(): Int? = try {
        if (marker.exists()) marker.readText().trim().toIntOrNull()?.coerceIn(STEP_STOP, STEP_CLEAR) ?: STEP_STOP else null
    } catch (expected: IOException) {
        STEP_STOP
    }

    private suspend fun runFrom(first: Int): DeleteAllOutcome {
        for (step in first..STEP_CLEAR) {
            record(step)
            when (step) {
                STEP_STOP -> steps.stopProducers()

                STEP_REVOKE -> steps.revokeRemote()

                STEP_CLOSE -> steps.closeDatabase()

                STEP_DELETE -> steps.deleteFilesAndKeys()

                STEP_VERIFY -> if (!steps.verifyGone()) return DeleteAllOutcome.VERIFY_FAILED.also { record(STEP_DELETE) }

                STEP_CLEAR -> {
                    marker.delete()
                    steps.clearApplicationUserData()
                }
            }
        }
        return DeleteAllOutcome.CLEARED
    }

    private fun record(step: Int) {
        marker.parentFile?.mkdirs()
        val temp = File(marker.parentFile, marker.name + ".tmp")
        temp.writeText(step.toString())
        if (!temp.renameTo(marker)) {
            marker.writeText(step.toString())
            temp.delete()
        }
    }

    companion object {
        const val STEP_STOP: Int = 1
        const val STEP_REVOKE: Int = 2
        const val STEP_CLOSE: Int = 3
        const val STEP_DELETE: Int = 4
        const val STEP_VERIFY: Int = 5
        const val STEP_CLEAR: Int = 6
    }
}

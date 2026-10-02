package dev.agentle.background.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.agentle.background.BackgroundJobs
import dev.agentle.background.JobResult

/** The one worker class: reads the work name from its input and delegates to [BackgroundJobs]. */
@HiltWorker
public class AgentleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val jobs: BackgroundJobs,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val work = inputData.getString(KEY_WORK) ?: return Result.failure()
        return toResult(jobs.run(work, runAttemptCount))
    }

    internal companion object {
        const val KEY_WORK = "work"

        fun toResult(result: JobResult): Result = when (result) {
            JobResult.SUCCESS -> Result.success()
            JobResult.RETRY -> Result.retry()
            JobResult.FAILURE -> Result.failure()
        }
    }
}

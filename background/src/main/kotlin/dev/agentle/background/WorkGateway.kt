package dev.agentle.background

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The only code that calls WorkManager's enqueue, update and cancel methods (R02 §4.1 "one gateway"); a source-scan
 * test enforces it. Every call is awaited, so the scheduler's decisions see committed state.
 */
public interface WorkGateway {
    public suspend fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest)

    public suspend fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)

    /** Updates a not-finished request in place (keeps its id); used for a BLOCKED timer re-arm. */
    public suspend fun update(request: OneTimeWorkRequest)

    public suspend fun cancelUnique(name: String)

    public suspend fun cancelAll()

    public suspend fun infos(name: String): List<WorkInfo>
}

public class WorkManagerGateway(private val workManager: () -> WorkManager) : WorkGateway {
    override suspend fun enqueuePeriodic(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) {
        workManager().enqueueUniquePeriodicWork(name, policy, request).result.awaitResult()
    }

    override suspend fun enqueueOneTime(name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
        workManager().enqueueUniqueWork(name, policy, request).result.awaitResult()
    }

    override suspend fun update(request: OneTimeWorkRequest) {
        workManager().updateWork(request).awaitResult()
    }

    override suspend fun cancelUnique(name: String) {
        workManager().cancelUniqueWork(name).result.awaitResult()
    }

    override suspend fun cancelAll() {
        workManager().cancelAllWork().result.awaitResult()
    }

    override suspend fun infos(name: String): List<WorkInfo> = workManager().getWorkInfosForUniqueWorkFlow(name).first()
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            }
        },
        Runnable::run,
    )
    cont.invokeOnCancellation { cancel(false) }
}

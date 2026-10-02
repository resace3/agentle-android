package dev.agentle.background

import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One work's state for the diagnostics screen. [circuitOpen] is true once its breaker has opened. */
public data class WorkerState(
    val name: String,
    val state: WorkInfo.State?,
    val runs: Int,
    val lastSuccessEpochMs: Long?,
    val lastFailureCode: String?,
    val consecutiveFailures: Int,
    val circuitOpen: Boolean,
)

/** Worker states plus the last observed standby bucket. */
public data class BackgroundState(val workers: List<WorkerState>, val standbyBucket: Int?)

public object BackgroundDiagnostics {
    /** Pure merge, testable without WorkManager. Each request carries its unique name as a tag. */
    public fun merge(infos: List<WorkInfo>, stats: Map<String, WorkStats>, bucket: Int?): BackgroundState = BackgroundState(
        workers = WorkNames.ALL.map { name ->
            val s = stats[name] ?: WorkStats()
            val mine = infos.filter { name in it.tags }
            WorkerState(
                name = name,
                state = (mine.firstOrNull { !it.state.isFinished } ?: mine.lastOrNull())?.state,
                runs = s.runs,
                lastSuccessEpochMs = s.lastSuccessEpochMs,
                lastFailureCode = s.lastFailureCode,
                consecutiveFailures = s.consecutiveFailures,
                circuitOpen = s.consecutiveFailures >= BackgroundJobs.BREAKER_THRESHOLD,
            )
        },
        standbyBucket = bucket,
    )

    public fun flow(gateway: WorkGateway, store: SchedulerStore): Flow<BackgroundState> = combine(
        gateway.observe(WorkNames.ALL),
        store.stats,
    ) { infos, stats -> merge(infos, stats, store.getLong(BackgroundJobs.KEY_BUCKET)?.toInt()) }
}

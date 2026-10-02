package dev.agentle.background

import dev.agentle.background.port.SchedulerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app calls [start] once from `Application.onCreate` with an application scope: a cheap process-start check
 * (enqueues the unique `reconcile` only when boot, version, zone or the 24 h self-heal say so), then it follows the
 * profile setting so a change UPDATEs the periodic works.
 */
public class BackgroundStarter @Inject constructor(private val scheduler: WorkScheduler, private val settings: SchedulerSettings) {
    public fun start(scope: CoroutineScope): Job = scope.launch {
        scheduler.onProcessStart()
        settings.profile.distinctUntilChanged().collect { scheduler.onProfileChanged(it) }
    }
}

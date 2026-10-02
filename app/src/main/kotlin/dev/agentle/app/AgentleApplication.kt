package dev.agentle.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.agentle.background.BackgroundStarter
import dev.agentle.connectors.android.AndroidCollectorsGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class AgentleApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var collectors: AndroidCollectorsGraph

    @Inject lateinit var background: BackgroundStarter

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Live sources and receivers first, then the scheduler's process-start check (reconcile, periodic sweeps).
        collectors.start()
        background.start(appScope)
    }
}

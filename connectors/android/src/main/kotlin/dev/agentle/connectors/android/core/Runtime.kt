package dev.agentle.connectors.android.core

import android.content.Context
import dev.agentle.connectors.api.CollectionSettings
import dev.agentle.connectors.api.CollectionSettingsStore
import dev.agentle.connectors.api.CollectorEventWriter
import dev.agentle.connectors.api.CoverageEndCause
import dev.agentle.connectors.api.CoverageRecorder
import dev.agentle.core.common.AppDispatchers
import dev.agentle.core.common.Logger
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Shared dependencies of every on-device collector. */
public class CollectorRuntime(
    public val context: Context,
    public val clock: AgentleClock,
    public val logger: Logger,
    public val dispatchers: AppDispatchers,
    public val writer: CollectorEventWriter,
    public val coverage: SafeCoverage,
    public val settings: SettingsSource,
    public val events: EventFactory,
    public val hasher: IdentifierHasher,
    /** Process-lifetime scope for live sources and debounced work. */
    public val scope: CoroutineScope,
)

/**
 * The user's collection settings: the bound [CollectionSettingsStore] when ANDROID-DATA provides one, otherwise an
 * in-memory copy with the privacy-preserving defaults (changes then last until the process dies).
 */
public class SettingsSource(private val store: CollectionSettingsStore?) {
    private val memory = MutableStateFlow(CollectionSettings())

    public val flow: Flow<CollectionSettings> get() = store?.settings ?: memory

    public suspend fun current(): CollectionSettings {
        val bound = store ?: return memory.value
        return try {
            withTimeoutOrNull(READ_TIMEOUT) { bound.settings.first() } ?: CollectionSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            CollectionSettings()
        }
    }

    public suspend fun update(transform: (CollectionSettings) -> CollectionSettings) {
        val bound = store
        if (bound == null) {
            memory.update(transform)
        } else {
            bound.update(transform)
        }
    }

    private companion object {
        val READ_TIMEOUT = 5.seconds
    }
}

/**
 * A [CoverageRecorder] wrapper that never throws (coverage bookkeeping must not break collection). Coverage ids are
 * capability ids ([dev.agentle.connectors.api.CapabilityIds], see [CoverageIds]).
 */
public class SafeCoverage(private val recorder: CoverageRecorder?, private val logger: Logger) {
    public val isBound: Boolean get() = recorder != null

    public suspend fun open(collector: String, at: Instant): Unit = guard("open") { it.open(collector, at) }

    public suspend fun heartbeat(collector: String, at: Instant): Unit = guard("heartbeat") { it.heartbeat(collector, at) }

    public suspend fun close(collector: String, at: Instant, cause: CoverageEndCause): Unit = guard("close") {
        it.close(collector, at, cause)
    }

    public suspend fun open(ids: Collection<String>, at: Instant): Unit = ids.forEach { open(it, at) }

    public suspend fun heartbeat(ids: Collection<String>, at: Instant): Unit = ids.forEach { heartbeat(it, at) }

    public suspend fun close(ids: Collection<String>, at: Instant, cause: CoverageEndCause): Unit = ids.forEach { close(it, at, cause) }

    public suspend fun openIntervals(): Map<String, Instant> {
        val bound = recorder ?: return emptyMap()
        return try {
            bound.openIntervals()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(COMPONENT, "Coverage intervals unreadable", fields = mapOf("error" to e::class.simpleName))
            emptyMap()
        }
    }

    private suspend fun guard(operation: String, block: suspend (CoverageRecorder) -> Unit) {
        val bound = recorder ?: return
        try {
            block(bound)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(COMPONENT, "Coverage write failed", fields = mapOf("operation" to operation, "error" to e::class.simpleName))
        }
    }

    private companion object {
        const val COMPONENT = "collectors.coverage"
    }
}

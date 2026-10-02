package dev.agentle.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coalesces concurrent calls: while one [run] is in flight, other callers await its result instead of starting a
 * second one. Used for token refresh (refresh tokens rotate and are single-use; a second concurrent refresh would
 * trip reuse detection) and for "sync now" buttons.
 */
public class SingleFlight<T> {
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<T>? = null

    public suspend fun run(block: suspend () -> T): T {
        val (deferred, owner) = mutex.withLock {
            val existing = inFlight
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<T>()
                inFlight = created
                created to true
            }
        }
        if (!owner) return deferred.await()
        try {
            val value = block()
            deferred.complete(value)
            return value
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }
}

package dev.agentle.core.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coalesces concurrent calls: while one [run] is in flight, other callers await its result instead of starting a
 * second one. Used for token refresh (refresh tokens rotate and are single-use; a second concurrent refresh would
 * trip reuse detection) and for "sync now" buttons.
 *
 * A caller's cancellation only ever ends that caller's wait:
 * - with a [scope], the block runs there, detached from every caller, and finishes even if all of them are
 *   cancelled. Token refresh needs this: the server may already have rotated the refresh token, so abandoning the
 *   response would lose the only valid one. Give the scope a `SupervisorJob`, so a failed run does not cancel it.
 * - without a scope, the block runs in the first caller's coroutine. If that caller is cancelled, the others start
 *   a new run instead of receiving a cancellation that is not theirs.
 */
public class SingleFlight<T>(private val scope: CoroutineScope? = null) {
    private val lock = Any()
    private var inFlight: Deferred<T>? = null

    public suspend fun run(block: suspend () -> T): T {
        while (true) {
            val (deferred, owner) = join(block)
            deferred.start()
            if (owner && deferred is CompletableDeferred<T>) return runAsOwner(deferred, block)
            try {
                return deferred.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                // The run was cancelled, not this caller: start or join the next one.
                if (scope != null && !scope.isActive) throw e
                clear(deferred)
            }
        }
    }

    /** Returns the run to wait for and whether the caller must execute it; a scoped run starts outside the lock. */
    private fun join(block: suspend () -> T): Pair<Deferred<T>, Boolean> = synchronized(lock) {
        inFlight?.takeIf { !it.isCompleted }?.let { return it to false }
        val created: Deferred<T> = scope?.async(start = CoroutineStart.LAZY) { block() } ?: CompletableDeferred()
        inFlight = created
        if (scope != null) created.invokeOnCompletion { clear(created) }
        created to (scope == null)
    }

    private suspend fun runAsOwner(deferred: CompletableDeferred<T>, block: suspend () -> T): T {
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
            clear(deferred)
        }
    }

    private fun clear(deferred: Deferred<T>) {
        synchronized(lock) { if (inFlight === deferred) inFlight = null }
    }
}

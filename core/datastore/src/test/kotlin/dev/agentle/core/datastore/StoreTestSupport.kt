package dev.agentle.core.datastore

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException

/** Records diagnostics so tests can check that only a store name, an operation and an error class are reported. */
class RecordingDiagnostics : StoreDiagnostics {
    val resets = mutableListOf<Pair<String, String>>()
    val ioFailures = mutableListOf<Triple<String, String, String>>()

    override fun onReset(store: String, errorClass: String) {
        resets += store to errorClass
    }

    override fun onIoFailure(store: String, operation: String, errorClass: String) {
        ioFailures += Triple(store, operation, errorClass)
    }
}

/** A DataStore whose reads or writes fail with an IOException while the matching flag is set. */
class FaultyDataStore<T>(private val delegate: DataStore<T>) : DataStore<T> {
    @Volatile var failReads: Boolean = false

    @Volatile var failWrites: Boolean = false

    override val data: Flow<T> = flow {
        if (failReads) throw IOException("injected read failure")
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        if (failReads || failWrites) throw IOException("injected write failure")
        return delegate.updateData(transform)
    }
}

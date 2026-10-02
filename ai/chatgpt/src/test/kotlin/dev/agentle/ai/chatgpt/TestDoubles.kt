package dev.agentle.ai.chatgpt

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Interceptor
import okhttp3.Response
import javax.net.ssl.SSLHandshakeException

/** Holds the first write that matches [holdWhen] until [release], so a test can act between a server answer and its write. */
internal class GatedStore(private val inner: InMemoryCredentialStore) : CredentialStore {
    @Volatile private var match: ((SiwcVault) -> Boolean)? = null
    private val gate = CompletableDeferred<Unit>()

    /** Completes when the held write arrived. */
    val reached: CompletableDeferred<Unit> = CompletableDeferred()

    fun holdWhen(predicate: (SiwcVault) -> Boolean) {
        match = predicate
    }

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun read(): VaultRead = inner.read()

    override suspend fun write(vault: SiwcVault): Outcome<Unit> {
        val predicate = match
        if (predicate != null && predicate(vault)) {
            match = null
            reached.complete(Unit)
            gate.await()
        }
        return inner.write(vault)
    }

    override suspend fun wipe(): Outcome<Unit> = inner.wipe()
}

/** Persists writes until one matches [crashAfter], then fails every later write: the process died right after it. */
internal class CrashingStore(private val inner: InMemoryCredentialStore, private val crashAfter: (SiwcVault) -> Boolean) : CredentialStore {
    @Volatile var crashed: Boolean = false
        private set

    override suspend fun read(): VaultRead = inner.read()

    override suspend fun write(vault: SiwcVault): Outcome<Unit> {
        if (crashed) return Outcome.Failure(AppError.DatabaseError("process_died"))
        val written = inner.write(vault)
        if (crashAfter(vault)) crashed = true
        return written
    }

    override suspend fun wipe(): Outcome<Unit> = inner.wipe()
}

/**
 * A TLS-intercepting middlebox (red team testing-build round 3): requests whose path ends with the configured suffix fail
 * the handshake. Simulated with an interceptor because the JVM tests have no TLS fake (no okhttp-tls in the catalog).
 */
internal class TlsInterception : Interceptor {
    @Volatile private var suffix: String? = null

    fun failHandshakes(pathSuffix: String) {
        suffix = pathSuffix
    }

    fun stop() {
        suffix = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val failing = suffix
        if (failing != null && chain.request().url.encodedPath.endsWith(failing)) throw SSLHandshakeException("simulated interception")
        return chain.proceed(chain.request())
    }
}

internal fun SiwcHttpClients.withAuthInterceptor(interceptor: Interceptor): SiwcHttpClients =
    SiwcHttpClients(auth.newBuilder().addInterceptor(interceptor).build(), api, stream)

internal fun SiwcHttpClients.withApiInterceptor(interceptor: Interceptor): SiwcHttpClients = SiwcHttpClients(
    auth,
    api.newBuilder().addInterceptor(interceptor).build(),
    stream.newBuilder().addInterceptor(interceptor).build(),
)

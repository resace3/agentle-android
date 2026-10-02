package dev.agentle.core.oauth

import dev.agentle.core.network.CleartextNotPermittedException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** OkHttp settings for authorization-server calls (docs/research/06 §8.3, docs/research/04 §3.5). */
public object OAuthHttpClients {
    public val CONNECT_TIMEOUT: Duration = 15.seconds
    public val CALL_TIMEOUT: Duration = 30.seconds

    /**
     * Derives the token/revocation client from the app's base client (built by `HttpClientFactory`, so TLS-only
     * outside the fake flavor): redirects are never followed, and failed connections are never retried silently, so a
     * refresh POST is sent at most once per attempt and cannot trip `refresh_token_reused`.
     */
    public fun tokenEndpointClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT.toJavaDuration())
        .callTimeout(CALL_TIMEOUT.toJavaDuration())
        .build()
}

/** Executes the call without blocking a thread; cancelling the coroutine cancels the call. */
public suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** Reads at most [maxBytes] of the body as UTF-8; null if it is larger (the excess is never buffered). */
public fun Response.bodyUpTo(maxBytes: Long): String? {
    val source = body.source()
    source.request(maxBytes + 1)
    return if (source.buffer.size > maxBytes) null else source.buffer.readUtf8()
}

/** A stable code for a transport failure; never the exception message (it can contain URLs or input). */
public fun transportFailureKind(e: IOException): String = when (e) {
    is UnknownHostException -> "dns"
    is ConnectException -> "connect"
    is InterruptedIOException -> "timeout"
    is SSLException -> "tls"
    else -> "io:${e::class.simpleName ?: "unknown"}"
}

/** True if OkHttp refused the request locally (cleartext outside the fake flavor). */
public fun isLocallyBlocked(e: IOException): Boolean = e is CleartextNotPermittedException

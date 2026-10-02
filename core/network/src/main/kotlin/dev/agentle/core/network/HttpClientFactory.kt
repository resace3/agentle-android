package dev.agentle.core.network

import dev.agentle.core.common.Logger
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * @property allowCleartextLoopback only the `fake` flavor sets this, so its in-process fake servers on 127.0.0.1
 *   can be reached over http. Every other cleartext request fails before it leaves the device.
 * @property allowedHosts the egress allow-list: exact host names this client may reach, checked for every request
 *   and every redirect hop. The app always sets it (prod: the Google Health and OpenAI hosts of
 *   docs/ARCHITECTURE.md §1 rule 2; fake: loopback only). `null` means unrestricted and is for JVM tests only.
 *   A client with an allow-list does not follow redirects: a 3xx comes back to the caller, so no connection is ever
 *   opened to a host the server names. The network-layer check still blocks a redirect hop if a derived client turns
 *   redirects back on, but by then the connection to that host exists, so keep redirects off.
 */
public data class HttpClientConfig(
    val userAgent: String,
    val connectTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 30.seconds,
    val writeTimeout: Duration = 30.seconds,
    val callTimeout: Duration = 90.seconds,
    val allowCleartextLoopback: Boolean = false,
    val allowedHosts: Set<String>? = null,
)

/** Thrown (as an [IOException], so OkHttp reports it as a call failure) when a request would go out in cleartext. */
public class CleartextNotPermittedException(url: HttpUrl) : IOException("Cleartext request blocked: ${url.scheme}://${url.host}")

/** Thrown (as an [IOException]) when a request or redirect targets a host outside the egress allow-list. */
public class EgressNotAllowedException(public val host: String) : IOException("Request to a host outside the egress allow-list blocked")

/** Builds the app's OkHttp clients: timeouts, TLS-only connection specs, user agent, metadata-only logging. */
public object HttpClientFactory {
    public fun create(config: HttpClientConfig, logger: Logger = Logger.NONE, nowMs: () -> Long = { 0L }): OkHttpClient {
        val specs = if (config.allowCleartextLoopback) {
            listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
        } else {
            listOf(ConnectionSpec.MODERN_TLS)
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.toJavaDuration())
            .readTimeout(config.readTimeout.toJavaDuration())
            .writeTimeout(config.writeTimeout.toJavaDuration())
            .callTimeout(config.callTimeout.toJavaDuration())
            .connectionSpecs(specs)
            .followSslRedirects(false)
        config.allowedHosts?.let {
            builder.followRedirects(false)
            builder.addInterceptor(EgressAllowListInterceptor(it))
        }
        builder
            .addInterceptor(TransportSecurityInterceptor(config.allowCleartextLoopback))
            .addInterceptor(UserAgentInterceptor(config.userAgent))
            .addInterceptor(MetadataLoggingInterceptor(logger, nowMs))
        // Again at the network layer: redirects never pass through application interceptors.
        config.allowedHosts?.let { builder.addNetworkInterceptor(EgressAllowListInterceptor(it)) }
        builder.addNetworkInterceptor(TransportSecurityInterceptor(config.allowCleartextLoopback))
        return builder.build()
    }
}

/** Rejects non-https requests unless [allowCleartextLoopback] and the host is a loopback address. */
public class TransportSecurityInterceptor(private val allowCleartextLoopback: Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !(allowCleartextLoopback && isLoopback(url.host))) throw CleartextNotPermittedException(url)
        return chain.proceed(chain.request())
    }

    public companion object {
        /** Literal loopback hosts only; never resolves DNS (a resolver could map any name to 127.0.0.1). */
        public fun isLoopback(host: String): Boolean = when {
            host.equals("localhost", ignoreCase = true) -> true

            host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':') ->
                runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)

            else -> false
        }
    }
}

/** Rejects requests whose host is not exactly one of [allowedHosts] (case-insensitive); never resolves DNS. */
public class EgressAllowListInterceptor(allowedHosts: Set<String>) : Interceptor {
    private val allowed = allowedHosts.map { it.lowercase() }.toSet()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host.lowercase()
        if (host !in allowed) throw EgressNotAllowedException(host)
        return chain.proceed(chain.request())
    }
}

public class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/**
 * Logs method, host, path, status and duration. Never headers, query strings or bodies: those carry tokens,
 * authorization codes and personal data (docs/ARCHITECTURE.md §14).
 */
public class MetadataLoggingInterceptor(private val logger: Logger, private val nowMs: () -> Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val where = "${request.method} ${request.url.host}${request.url.encodedPath}"
        val start = nowMs()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            logger.w(COMPONENT, "$where failed", fields = mapOf("exception" to e::class.simpleName))
            throw e
        }
        logger.d(COMPONENT, "$where -> ${response.code}", fields = mapOf("ms" to (nowMs() - start)))
        return response
    }

    private companion object {
        const val COMPONENT = "http"
    }
}

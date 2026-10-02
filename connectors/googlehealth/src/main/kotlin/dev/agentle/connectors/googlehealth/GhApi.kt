package dev.agentle.connectors.googlehealth

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.network.ResponseBodies
import dev.agentle.core.network.RetryPolicy
import dev.agentle.core.network.SlidingWindowRateLimiter
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One read request; [label] names it in logs (method and data type only: no query, no body). */
internal sealed interface GhRequest {
    val label: String
    val pageToken: String?

    data class Data(
        val method: GhMethod,
        val dataType: String,
        val filter: String? = null,
        val pageSize: Int? = null,
        override val pageToken: String? = null,
        /** The `:rollUp` / `:dailyRollUp` body without the page token. */
        val body: JsonObject? = null,
    ) : GhRequest {
        override val label: String get() = "${method.name.lowercase()} $dataType"
    }

    data object Identity : GhRequest {
        override val label: String = "identity"
        override val pageToken: String? = null
    }

    data object Settings : GhRequest {
        override val label: String = "settings"
        override val pageToken: String? = null
    }

    data class PairedDevices(val pageSize: Int, override val pageToken: String? = null) : GhRequest {
        override val label: String get() = "pairedDevices"
    }
}

/** The parsed JSON object of a successful call, or why it failed. */
internal sealed interface GhResult {
    data class Ok(val json: JsonObject) : GhResult

    data class Failed(val failure: GhFailure) : GhResult
}

/**
 * The Google Health HTTP client (docs/research/05 §7.3, §7.6): bearer token from [GoogleHealthAuthorizer], strict
 * client-side rate limit (4/s and 200/min), and the in-run retry policy:
 * - 401: invalidate the token, get a new one silently, retry once; a second 401 is [GhFailure.NeedsReauth];
 * - 429: honor `Retry-After` up to 60 s, else back off 2 s and 4 s (jitter); at most 2 retries;
 * - 5xx, I/O and timeouts: up to 3 retries after 1 s, 2 s and 4 s (jitter), or `Retry-After` when sent;
 * - 2xx with a malformed or non-JSON body: one retry, then [GhFailure.Transient].
 * Waits go through [sleep] (virtual time in tests). Nothing it logs or returns carries a token, a URL query, a body or
 * an exception message.
 */
internal class GhApi(
    private val service: GhService,
    private val authorizer: GoogleHealthAuthorizer,
    private val clock: AgentleClock,
    private val limiter: SlidingWindowRateLimiter,
    private val sleep: suspend (Duration) -> Unit,
    private val random: Random,
    private val logger: Logger,
) {
    @Volatile private var token: String? = null
    private val tokenLock = kotlinx.coroutines.sync.Mutex()

    /** Requests sent in this connector's lifetime (diagnostics and tests). */
    var requests: Int = 0
        private set

    /** Asks the authorizer for a token; background callers pass `interactive = false`. */
    suspend fun authorize(interactive: Boolean): GoogleAuthorization = tokenLock.withLock { authorizeLocked(interactive) }

    private suspend fun authorizeLocked(interactive: Boolean): GoogleAuthorization {
        val result = authorizer.token(interactive)
        token = (result as? GoogleAuthorization.Token)?.value
        return result
    }

    /** Forgets the cached token (disconnect): later calls fail with NeedsReauth until [authorize] runs again. */
    suspend fun clearToken() {
        tokenLock.withLock { token = null }
    }

    /** Executes [request]; [valid] checks the shape of a 2xx body (a failed check counts as a malformed body). */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun execute(request: GhRequest, valid: (JsonObject) -> Boolean = { true }): GhResult {
        var reauthorized = false
        var rateRetries = 0
        var transientRetries = 0
        var malformedRetries = 0
        while (true) {
            val bearer = token ?: return GhResult.Failed(GhFailure.NeedsReauth)
            limiter.acquire()
            requests++
            val outcome = send(request, bearer)
            val failure = when (outcome) {
                is Sent.Body -> {
                    val json = GhJson.parseObject(outcome.text)?.takeIf(valid)
                    if (json != null) return GhResult.Ok(json)
                    GhFailure.Transient(GhFailure.MALFORMED)
                }

                is Sent.Malformed -> GhFailure.Transient(GhFailure.MALFORMED)

                is Sent.Error -> outcome.failure

                is Sent.Io -> GhFailure.Transient(outcome.code)
            }
            val wait: Duration? = when {
                failure == GhFailure.NeedsReauth && outcome is Sent.Error && !reauthorized -> {
                    reauthorized = true
                    when (val renewed = renew(bearer)) {
                        null -> {
                            logger.i(COMPONENT, "token renewed after 401", mapOf("call" to request.label))
                            Duration.ZERO
                        }

                        else -> return GhResult.Failed(renewed)
                    }
                }

                failure is GhFailure.RateLimited -> RATE_LIMIT.delayFor(++rateRetries, AppError.RateLimited(failure.retryAfter), random)

                failure is GhFailure.Transient && failure.code == GhFailure.MALFORMED ->
                    if (malformedRetries++ < MALFORMED_RETRIES) MALFORMED_WAIT else null

                failure is GhFailure.Transient -> transientWait(++transientRetries, failure, outcome)

                else -> null
            }
            if (wait == null) {
                logger.w(COMPONENT, "request failed", failure.toAppError(), mapOf("call" to request.label))
                return GhResult.Failed(failure)
            }
            if (wait > Duration.ZERO) {
                logger.d(COMPONENT, "retrying", mapOf("call" to request.label, "waitMs" to wait.inWholeMilliseconds))
                sleep(wait)
            }
        }
    }

    /** Clears the rejected token and gets a new one silently; null when a retry may go ahead. */
    private suspend fun renew(rejected: String): GhFailure? = tokenLock.withLock {
        // Another caller may have renewed (or a disconnect cleared) the token meanwhile.
        if (token != rejected) return@withLock if (token == null) GhFailure.NeedsReauth else null
        authorizer.invalidate(rejected)
        when (val next = authorizeLocked(interactive = false)) {
            is GoogleAuthorization.Token -> null
            is GoogleAuthorization.NeedsResolution, GoogleAuthorization.Denied -> GhFailure.NeedsReauth
            is GoogleAuthorization.Failure -> GhFailure.AuthorizerFailed(next.statusCode)
        }
    }

    private fun transientWait(retry: Int, failure: GhFailure.Transient, outcome: Sent): Duration? {
        val retryAfter = (outcome as? Sent.Error)?.retryAfter
        if (retry > TRANSIENT.maxRetries) return null
        if (retryAfter != null) return retryAfter.takeIf { it <= TRANSIENT.maxRetryAfter }
        return TRANSIENT.delayFor(retry, failure.toAppError(), random)
    }

    private sealed interface Sent {
        data class Body(val text: String) : Sent

        data object Malformed : Sent

        data class Error(val failure: GhFailure, val retryAfter: Duration?) : Sent

        data class Io(val code: String) : Sent
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun send(request: GhRequest, bearer: String): Sent {
        val response: Response<ResponseBody> = try {
            call(request, "Bearer $bearer")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Sent.Io(ioCode(e))
        } catch (e: RuntimeException) {
            // OkHttp and Retrofit report broken responses as runtime exceptions; only the class name is kept.
            return Sent.Io("io_${e::class.simpleName}")
        }
        if (!response.isSuccessful) {
            val errorText = try {
                response.errorBody()?.use { it.string() }
            } catch (e: IOException) {
                logger.d(COMPONENT, "error body unreadable", mapOf("exception" to e::class.simpleName))
                null
            }
            val headers = response.headers()
            val failure = GhErrors.classify(
                status = response.code(),
                contentType = headers["Content-Type"],
                body = errorText,
                retryAfter = headers["Retry-After"],
                wwwAuthenticate = headers["www-authenticate"],
                hadPageToken = request.pageToken != null,
                now = clock.now(),
            )
            val retryAfter = dev.agentle.core.network.RetryAfter.parse(headers["Retry-After"], clock.now())
            return Sent.Error(failure, retryAfter)
        }
        val contentType = response.headers()["Content-Type"]
        val text = try {
            response.body()?.use { it.string() }.orEmpty()
        } catch (e: IOException) {
            // Headers said 2xx but the body broke off (R1b): treated like a malformed body.
            logger.d(COMPONENT, "body read failed", mapOf("exception" to e::class.simpleName))
            return Sent.Malformed
        }
        // R2: a captive portal answers 200 with HTML. A body without Content-Type is accepted only if it is JSON.
        val jsonType = ResponseBodies.isJsonContentType(contentType) || (contentType == null && ResponseBodies.looksLikeJson(text))
        return if (jsonType && text.isNotBlank()) Sent.Body(text) else Sent.Malformed
    }

    private suspend fun call(request: GhRequest, authorization: String): Response<ResponseBody> = when (request) {
        is GhRequest.Data -> when (request.method) {
            GhMethod.LIST -> service.list(authorization, request.dataType, request.filter, request.pageSize, request.pageToken)
            GhMethod.RECONCILE -> service.reconcile(authorization, request.dataType, request.filter, request.pageSize, request.pageToken)
            GhMethod.ROLL_UP -> service.rollUp(authorization, request.dataType, bodyOf(request))
            GhMethod.DAILY_ROLL_UP -> service.dailyRollUp(authorization, request.dataType, bodyOf(request))
            GhMethod.PAIRED_DEVICES -> service.pairedDevices(authorization, request.pageSize, request.pageToken)
        }

        GhRequest.Identity -> service.identity(authorization)

        GhRequest.Settings -> service.settings(authorization)

        is GhRequest.PairedDevices -> service.pairedDevices(authorization, request.pageSize, request.pageToken)
    }

    /** The body, with the page token appended last so every page repeats the first page's fields (hygiene H7). */
    private fun bodyOf(request: GhRequest.Data) = buildString {
        val base = GhJson.encode(requireNotNull(request.body) { "rollUp needs a body" })
        if (request.pageToken == null) {
            append(base)
        } else {
            append(base.removeSuffix("}"))
            if (base.length > 2) append(',')
            append("\"pageToken\":").append(GhJson.encode(kotlinx.serialization.json.JsonPrimitive(request.pageToken)))
            append('}')
        }
    }.toRequestBody(JSON)

    private fun ioCode(e: IOException): String = when (e) {
        is java.io.InterruptedIOException -> "timeout"
        is java.net.UnknownHostException -> "dns"
        is java.net.ConnectException -> "connect"
        is javax.net.ssl.SSLException -> "tls"
        is dev.agentle.core.network.EgressNotAllowedException -> "egress_blocked"
        is dev.agentle.core.network.CleartextNotPermittedException -> "cleartext_blocked"
        else -> "io_${e::class.simpleName}"
    }

    companion object {
        const val COMPONENT: String = "googlehealth"
        private val JSON = "application/json".toMediaType()
        private const val MALFORMED_RETRIES = 1
        private val MALFORMED_WAIT = 1.seconds

        /** 429: 2 retries after about 2 s and 4 s; `Retry-After` honored up to 60 s (docs/research/05 §7.6). */
        val RATE_LIMIT: RetryPolicy =
            RetryPolicy(maxRetries = 2, initialDelay = 2.seconds, maxDelay = 8.seconds, maxRetryAfter = 60.seconds)

        /** 5xx, I/O, timeouts: 3 retries after about 1 s, 2 s and 4 s. */
        val TRANSIENT: RetryPolicy = RetryPolicy(maxRetries = 3, initialDelay = 1.seconds, maxDelay = 8.seconds, maxRetryAfter = 60.seconds)
    }
}

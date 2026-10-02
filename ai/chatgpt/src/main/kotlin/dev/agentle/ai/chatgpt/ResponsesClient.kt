package dev.agentle.ai.chatgpt

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.network.RetryAfter
import dev.agentle.core.oauth.ErrorBody
import dev.agentle.core.oauth.awaitResponse
import dev.agentle.core.oauth.blockedReason
import dev.agentle.core.oauth.bodyUpTo
import dev.agentle.core.oauth.transportFailureKind
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Roles the direct route accepts (R06 §4.2: system-role items are rejected; use `instructions` or developer). */
public enum class InputRole(public val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    DEVELOPER("developer"),
}

/** One `input` item. `toString` never shows the content (AI payloads are never logged). */
public data class InputMessage(val role: InputRole, val content: String) {
    override fun toString(): String = "InputMessage(role=${role.wire}, chars=${content.length})"
}

/**
 * A `POST /v1/responses` body as docs/research/06 §4.2 and §4.5 define it: `model`, optional `instructions`, an array
 * `input`, `store: false`, `stream: true`, and nothing else. [encode] checks the result against [RequestFieldGuard].
 */
public data class ResponsesRequest(val model: String, val instructions: String?, val input: List<InputMessage>) {
    init {
        require(model.isNotBlank()) { "a model is required" }
        require(input.isNotEmpty()) { "input must contain at least one item" }
    }

    public fun encode(): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            instructions?.let { put("instructions", it) }
            putJsonArray("input") {
                input.forEach { item ->
                    addJsonObject {
                        put("role", item.role.wire)
                        put("content", item.content)
                    }
                }
            }
            put("store", false)
            put("stream", true)
        }
        val violations = RequestFieldGuard.violations(body)
        check(violations.isEmpty()) { "request fields refused: $violations" }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    override fun toString(): String = "ResponsesRequest(model=$model, items=${input.size})"
}

/**
 * The request whitelist of R06 §4.4 (red team: "It must refuse to send forbidden fields"): only the documented fields,
 * none of the unsupported ones, `store` exactly false and `stream` exactly true.
 */
public object RequestFieldGuard {
    public val ALLOWED: Set<String> = setOf("model", "instructions", "input", "store", "stream")

    /** R06 §4.4 "Unsupported request fields" (exact list), plus `previous_response_id` and `service_tier`. */
    public val FORBIDDEN: Set<String> = setOf(
        "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent",
        "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation",
        "user", "previous_response_id", "service_tier",
    )

    /** The offending field names; empty when [body] may be sent. */
    public fun violations(body: JsonObject): List<String> {
        val fields = body.keys.filter { it in FORBIDDEN || it !in ALLOWED }
        val store = (body["store"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        val stream = (body["stream"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        val roles = (body["input"] as? JsonArray)?.map { ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.content }
        return fields + listOfNotNull(
            "store".takeIf { store != "false" },
            "stream".takeIf { stream != "true" },
            "input".takeIf { roles == null || roles.isEmpty() || roles.any { role -> InputRole.entries.none { it.wire == role } } },
        )
    }
}

/** Last chance to stop a request: sees the exact body bytes right before they are written (red team round 1 item 6). */
public fun interface BeforeSend {
    /** Null to send; an error aborts the call before a byte of the body leaves the device. */
    public fun check(body: ByteArray): AppError?

    public companion object {
        public val NONE: BeforeSend = BeforeSend { null }
    }
}

/** The text of a completed response. `toString` never shows it. */
public data class ResponseText(val text: String, val model: String?, val requestId: String?) {
    override fun toString(): String = "ResponseText(chars=${text.length}, model=$model)"
}

/**
 * `POST {api}/responses` with SSE streaming (docs/research/06 §4.2-4.4, §8.3): `Accept: text/event-stream`, success only
 * on `response.completed`, `response.failed` and `error` events mapped by code, partial text discarded on any failure,
 * a missing Content-Type tolerated and any other than `text/event-stream` refused. It does not handle 401 itself:
 * [SiwcSessionManager.withAccessToken] owns the refresh-and-retry rule (red team oauth-security-08). Request and response
 * bodies are never logged or persisted.
 */
public class ResponsesClient(
    private val config: SiwcConfig,
    private val http: OkHttpClient,
    private val clock: AgentleClock,
    private val logger: Logger = Logger.NONE,
) {
    private val parser = ResponseStreamParser(MAX_EVENT_BYTES, MAX_TEXT_CHARS)

    public suspend fun create(
        accessToken: String,
        request: ResponsesRequest,
        beforeSend: BeforeSend = BeforeSend.NONE,
    ): SiwcResult<ResponseText> {
        val call = http.newCall(
            Request.Builder()
                .url(config.responsesUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", EVENT_STREAM)
                .post(GuardedBody(request.encode(), beforeSend))
                .build(),
        )
        val response = try {
            call.awaitResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BeforeSendRejected) {
            logger.w(COMPONENT, "request stopped before sending", e.error)
            return SiwcResult.Failed(SiwcFailure(null, e.error))
        } catch (e: IOException) {
            return SiwcResult.Failed(SiwcApiHttp.transportFailure(e))
        }
        val result = readCancellably(call) { response.use(::read) }
        logger.i(COMPONENT, "response finished", fields = mapOf("outcome" to SiwcApiHttp.describe(result)))
        return result
    }

    private fun read(response: Response): SiwcResult<ResponseText> {
        val requestId = SiwcApiHttp.requestId(response)
        val contentType = response.header("Content-Type")
        return when {
            !response.isSuccessful -> SiwcResult.Failed(SiwcApiHttp.apiFailure(response, requestId, clock))

            contentType == null || contentType.startsWith(EVENT_STREAM, ignoreCase = true) ->
                parser.parse(response.body.source(), requestId)

            contentType.startsWith("text/html", ignoreCase = true) -> SiwcResult.Failed(SiwcErrorMapper.captivePortal())

            else -> SiwcResult.Failed(SiwcErrorMapper.invalidResponse(requestId, "content_type"))
        }
    }

    private companion object {
        const val COMPONENT = "siwc.responses"
        const val EVENT_STREAM = "text/event-stream"

        /** DevKit caps (R06 §4.3): 4 MiB per event, 16 MiB of text. */
        const val MAX_EVENT_BYTES = 4L * 1024 * 1024
        const val MAX_TEXT_CHARS = 16L * 1024 * 1024
    }
}

/**
 * Reads a response body off the caller's thread; cancelling the caller (or a disconnect) cancels the call, which
 * unblocks the read.
 */
internal suspend fun <T> readCancellably(call: Call, read: () -> T): T = coroutineScope {
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { read() }
    } finally {
        watcher.cancel()
    }
}

/** The request body: writes the exact bytes only after [beforeSend] accepted them; never replayed by OkHttp. */
internal class GuardedBody(private val bytes: ByteArray, private val beforeSend: BeforeSend) : RequestBody() {
    override fun contentType(): MediaType = JSON

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        beforeSend.check(bytes.copyOf())?.let { throw BeforeSendRejected(it) }
        sink.write(bytes)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** Carries a [BeforeSend] veto out of OkHttp's call (an [IOException], so OkHttp fails the call with it). */
internal class BeforeSendRejected(val error: AppError) : IOException("request body refused before sending")

/** Shared HTTP handling of the two API routes. */
internal object SiwcApiHttp {
    private const val MAX_ERROR_BYTES = 64L * 1024
    private const val MAX_REQUEST_ID = 128

    fun requestId(response: Response): String? = response.header("x-request-id")?.take(MAX_REQUEST_ID)

    /** A non-2xx answer: the error code, `param`, status, Retry-After and request id; never the message. */
    fun apiFailure(response: Response, requestId: String?, clock: AgentleClock): SiwcFailure {
        val body = try {
            response.bodyUpTo(MAX_ERROR_BYTES)
        } catch (_: IOException) {
            null
        }
        val now = clock.now()
        val retryAfter = RetryAfter.parse(response.header("Retry-After"), now)
        return SiwcErrorMapper.api(response.code, ErrorBody.parse(body, response.header("Content-Type")), requestId, retryAfter, now)
    }

    fun transportFailure(e: IOException): SiwcFailure =
        blockedReason(e)?.let(SiwcErrorMapper::blocked) ?: SiwcErrorMapper.transport(transportFailureKind(e))

    fun describe(result: SiwcResult<*>): String = when (result) {
        is SiwcResult.Ok -> "ok"
        is SiwcResult.Failed -> "${result.failure.status?.state}:${result.failure.status?.reason}:${result.failure.error.code}"
    }
}

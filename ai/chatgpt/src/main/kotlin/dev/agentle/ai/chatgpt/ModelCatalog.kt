package dev.agentle.ai.chatgpt

import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import dev.agentle.core.oauth.awaitResponse
import dev.agentle.core.oauth.bodyUpTo
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** One model the user may pick (R06 §4.1). [displayName] is the server's label, shown as is. */
public data class ChatGptModel(val slug: String, val displayName: String?)

/**
 * `GET {api}/models` (docs/research/06 §4.1, §8.2): only entries with `visibility == "list"`, in the server's order.
 * The list is cached per registration (client id) and reloaded on demand or after [invalidate] (the provider calls it
 * when a response says `model_not_found`). Calls go through [SiwcSessionManager.withAccessToken], which owns the 401
 * rule.
 */
public class ModelCatalog(
    private val config: SiwcConfig,
    private val http: OkHttpClient,
    private val session: SiwcSessionManager,
    private val clock: AgentleClock,
    private val logger: Logger = Logger.NONE,
) {
    private val mutex = Mutex()

    @Volatile private var cached: Cached? = null

    private class Cached(val clientId: String?, val models: List<ChatGptModel>)

    /** The listed models of the current registration, from the cache unless [reload]. */
    public suspend fun models(reload: Boolean = false): Outcome<List<ChatGptModel>> {
        cachedFor(session.currentClientId(), reload)?.let { return Outcome.Success(it) }
        return mutex.withLock { cachedFor(session.currentClientId(), reload)?.let { Outcome.Success(it) } ?: fetch() }
    }

    /** Forgets the cached list; the next [models] call fetches it again. */
    public fun invalidate() {
        cached = null
    }

    private fun cachedFor(clientId: String?, reload: Boolean): List<ChatGptModel>? =
        cached?.takeIf { !reload && clientId != null && it.clientId == clientId }?.models

    private suspend fun fetch(): Outcome<List<ChatGptModel>> {
        val result = session.withAccessToken { token -> get(token) }
        if (result is Outcome.Success) cached = Cached(session.currentClientId(), result.value)
        logger.i(COMPONENT, "models fetched", fields = mapOf("ok" to (result is Outcome.Success)))
        return result
    }

    private suspend fun get(accessToken: String): SiwcResult<List<ChatGptModel>> {
        val call = http.newCall(
            Request.Builder()
                .url(config.modelsUrl)
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build(),
        )
        val response = try {
            call.awaitResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SiwcResult.Failed(SiwcApiHttp.transportFailure(e))
        }
        return readCancellably(call) { response.use(::read) }
    }

    private fun read(response: Response): SiwcResult<List<ChatGptModel>> {
        val requestId = SiwcApiHttp.requestId(response)
        if (!response.isSuccessful) return SiwcResult.Failed(SiwcApiHttp.apiFailure(response, requestId, clock))
        val body = try {
            response.bodyUpTo(MAX_BODY_BYTES)
        } catch (e: IOException) {
            return SiwcResult.Failed(SiwcApiHttp.transportFailure(e))
        }
        return ModelListParser.parse(body, requestId)
    }

    private companion object {
        const val COMPONENT = "siwc.models"
        const val MAX_BODY_BYTES = 1024L * 1024
    }
}

/** Parses a `GET /v1/models` body (R06 §4.1); tested against the fake's literal fixtures (red team oauth-security-14). */
internal object ModelListParser {
    /** [body] is null when it exceeded the size limit. A 2xx that is not JSON at all is a captive portal. */
    fun parse(body: String?, requestId: String?): SiwcResult<List<ChatGptModel>> {
        val text = body ?: return SiwcResult.Failed(SiwcErrorMapper.invalidResponse(requestId, "models"))
        val element = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return SiwcResult.Failed(SiwcErrorMapper.captivePortal())
        }
        val models = ((element as? JsonObject)?.get("models") as? JsonArray)
            ?: return SiwcResult.Failed(SiwcErrorMapper.invalidResponse(requestId, "models"))
        return SiwcResult.Ok(
            models.filterIsInstance<JsonObject>()
                .filter { it.string("visibility") == LISTED }
                .mapNotNull { entry ->
                    entry.string("slug")?.takeIf { it.isNotBlank() }?.let { ChatGptModel(it, entry.string("display_name")) }
                },
        )
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val LISTED = "list"
}

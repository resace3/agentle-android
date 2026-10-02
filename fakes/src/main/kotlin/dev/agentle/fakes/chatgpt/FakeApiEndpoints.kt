package dev.agentle.fakes.chatgpt

import dev.agentle.core.time.AgentleClock
import dev.agentle.fakes.chatgpt.ChatGptScenario.ADMISSION_401
import dev.agentle.fakes.chatgpt.ChatGptScenario.ADMISSION_403
import dev.agentle.fakes.chatgpt.ChatGptScenario.ADMISSION_503
import dev.agentle.fakes.chatgpt.ChatGptScenario.BAD_GATEWAY_HTML
import dev.agentle.fakes.chatgpt.ChatGptScenario.EXPIRED_ACCESS_TOKEN
import dev.agentle.fakes.chatgpt.ChatGptScenario.GATEWAY_TIMEOUT_EMPTY
import dev.agentle.fakes.chatgpt.ChatGptScenario.INVALID_USER
import dev.agentle.fakes.chatgpt.ChatGptScenario.SERVER_ERROR
import dev.agentle.fakes.chatgpt.FakeResponses.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import java.util.concurrent.TimeUnit

/**
 * `GET {api}/models`, `POST {api}/responses` and every other API route (docs/research/06 §9.2), with the inference
 * scenarios of §9.4 and the transport faults of docs/research/08 §5.5.
 */
internal class FakeApiEndpoints(private val state: FakeChatGptState, private val clock: AgentleClock) {
    fun models(request: FakeRequest): MockResponse {
        bearerProblem(request)?.let { return it }
        val attempt = synchronized(state.lock) { state.attempt(request.scenario, FakeRoute.MODELS) }
        return when {
            request.scenario == EXPIRED_ACCESS_TOKEN && attempt == 1 -> json(UNAUTHORIZED, ChatGptFixtures.API_TOKEN_EXPIRED)
            request.scenario in MODELS_FAILURES -> admission.getValue(request.scenario)()
            else -> json(OK, ChatGptFixtures.MODELS)
        }
    }

    fun responses(request: FakeRequest): MockResponse {
        bearerProblem(request)?.let { return it }
        if (request.recorded.headers["Accept"]?.contains("text/event-stream") != true) {
            synchronized(state.lock) { state.violation("H-ACCEPT-EVENT-STREAM") }
        }
        val attempt = synchronized(state.lock) { state.attempt(request.scenario, FakeRoute.RESPONSES) }
        val scenarioAnswer = when {
            request.scenario == EXPIRED_ACCESS_TOKEN -> if (attempt == 1) json(UNAUTHORIZED, ChatGptFixtures.API_TOKEN_EXPIRED) else null
            else -> admission[request.scenario]?.invoke()
        }
        return scenarioAnswer ?: validationProblem(request) ?: stream(request.scenario)
    }

    /** Any route other than the two documented ones (R06 §9.2 "Any other route"). */
    fun otherRoute(): MockResponse = json(FORBIDDEN, ChatGptFixtures.ROUTE_NOT_SUPPORTED)

    private fun bearerProblem(request: FakeRequest): MockResponse? {
        val token = request.recorded.headers["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        val grant = token?.let { synchronized(state.lock) { state.accessTokens[it] } }
        return when {
            grant == null -> json(UNAUTHORIZED, ChatGptFixtures.DETAIL_UNAUTHORIZED)
            clock.now() >= grant.expiresAt -> json(UNAUTHORIZED, ChatGptFixtures.API_TOKEN_EXPIRED)
            else -> null
        }
    }

    /** R06 §9.2: the request rules of §4.2/§4.4, each rejection naming the offending field in `param`. */
    private fun validationProblem(request: FakeRequest): MockResponse? {
        val contentType = request.recorded.headers["Content-Type"].orEmpty()
        val body = try {
            Json.parseToJsonElement(request.body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        if (!contentType.startsWith("application/json") || body == null) return json(BAD_REQUEST, ChatGptFixtures.INVALID_JSON)
        val param = unsupportedParam(body)
        val model = (body["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            param != null -> json(BAD_REQUEST, ChatGptFixtures.unsupportedCapability(param))
            model != ChatGptFixtures.MODEL && model != ChatGptFixtures.HIDDEN_MODEL -> json(NOT_FOUND, ChatGptFixtures.MODEL_NOT_FOUND)
            else -> null
        }
    }

    private fun unsupportedParam(body: JsonObject): String? {
        val field = body.keys.firstOrNull { it in FORBIDDEN_FIELDS } ?: body.keys.firstOrNull { it !in DOCUMENTED_FIELDS }
        val input = body["input"] as? JsonArray
        return field ?: when {
            !body.isLiteral("stream", true) -> "stream"
            !body.isLiteral("store", false) -> "store"
            input == null || input.any(::isRejectedItem) -> "input"
            hasImageGenerationTool(body["tools"]) -> "tools"
            isJsonSchemaFormat(body["text"]) -> "text.format"
            else -> null
        }
    }

    private fun JsonObject.isLiteral(name: String, expected: Boolean): Boolean {
        val value = this[name] as? JsonPrimitive ?: return false
        return !value.isString && value.booleanOrNull == expected
    }

    /** System-role items are rejected (R06 §4.2); the DevKit allows only user, assistant and developer. */
    private fun isRejectedItem(item: JsonElement): Boolean {
        val role = ((item as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull
        return item !is JsonObject || (role != null && role !in ALLOWED_ROLES)
    }

    private fun hasImageGenerationTool(tools: JsonElement?): Boolean =
        (tools as? JsonArray)?.any { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "image_generation" } == true

    private fun isJsonSchemaFormat(text: JsonElement?): Boolean {
        val format = (text as? JsonObject)?.get("format") as? JsonObject
        return (format?.get("type") as? JsonPrimitive)?.contentOrNull == "json_schema"
    }

    private fun stream(scenario: ChatGptScenario): MockResponse {
        val builder = MockResponse.Builder().code(OK).body(STREAM_BODIES[scenario] ?: ChatGptFixtures.STREAM_SUCCESS)
        when (scenario) {
            ChatGptScenario.NO_CONTENT_TYPE -> Unit
            ChatGptScenario.WRONG_CONTENT_TYPE -> builder.setHeader("Content-Type", FakeResponses.JSON)
            else -> builder.setHeader("Content-Type", FakeResponses.EVENT_STREAM)
        }
        when (scenario) {
            ChatGptScenario.STREAM_CUT -> builder.onResponseBody(SocketEffect.CloseSocket())
            ChatGptScenario.SLOW_STREAM -> builder.throttleBody(SLOW_BYTES, SLOW_PERIOD_MS, TimeUnit.MILLISECONDS)
            ChatGptScenario.STALL -> builder.onResponseStart(SocketEffect.Stall)
            else -> Unit
        }
        return builder.build()
    }

    private companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val TOO_MANY_REQUESTS = 429
        const val INTERNAL_ERROR = 500
        const val BAD_GATEWAY = 502
        const val SERVICE_UNAVAILABLE = 503
        const val GATEWAY_TIMEOUT = 504
        const val SLOW_BYTES = 64L
        const val SLOW_PERIOD_MS = 50L

        /** R06 §4.4 "Unsupported request fields" (exact list) plus `previous_response_id` and `service_tier`. */
        val FORBIDDEN_FIELDS = setOf(
            "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent",
            "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation",
            "user", "previous_response_id", "service_tier",
        )

        /** Fields R06 §4.2/§4.4 document for the direct route; anything else is UNDOCUMENTED and rejected here. */
        val DOCUMENTED_FIELDS = setOf("model", "input", "instructions", "store", "stream", "text", "tools")
        val ALLOWED_ROLES = setOf("user", "assistant", "developer")

        val MODELS_FAILURES = setOf(
            ADMISSION_401,
            ADMISSION_403,
            ADMISSION_503,
            INVALID_USER,
            SERVER_ERROR,
            BAD_GATEWAY_HTML,
            GATEWAY_TIMEOUT_EMPTY,
        )

        val admission: Map<ChatGptScenario, () -> MockResponse> = mapOf(
            ADMISSION_401 to { json(UNAUTHORIZED, ChatGptFixtures.DETAIL_UNAUTHORIZED) },
            ADMISSION_403 to { json(FORBIDDEN, ChatGptFixtures.DETAIL_FORBIDDEN) },
            ADMISSION_503 to { json(SERVICE_UNAVAILABLE, ChatGptFixtures.DETAIL_UNAVAILABLE) },
            INVALID_USER to { json(UNAUTHORIZED, ChatGptFixtures.INVALID_USER) },
            ChatGptScenario.NOT_ELIGIBLE to { json(FORBIDDEN, ChatGptFixtures.NOT_ELIGIBLE) },
            ChatGptScenario.UNAVAILABLE to { json(SERVICE_UNAVAILABLE, ChatGptFixtures.USAGE_UNAVAILABLE) },
            ChatGptScenario.USER_UNAVAILABLE to { json(SERVICE_UNAVAILABLE, ChatGptFixtures.USER_UNAVAILABLE) },
            ChatGptScenario.USAGE_LIMIT to { json(TOO_MANY_REQUESTS, ChatGptFixtures.USAGE_LIMIT) },
            ChatGptScenario.RATE_LIMITED_GENERIC to {
                json(TOO_MANY_REQUESTS, ChatGptFixtures.RATE_LIMIT_GENERIC, mapOf("Retry-After" to "20"))
            },
            ChatGptScenario.GRANT_NOT_AUTHORIZED to { json(FORBIDDEN, ChatGptFixtures.GRANT_NOT_AUTHORIZED) },
            ChatGptScenario.INVALID_AUTHORIZATION_CONTEXT to { json(FORBIDDEN, ChatGptFixtures.INVALID_AUTHORIZATION_CONTEXT) },
            ChatGptScenario.CLIENT_NOT_ENABLED to {
                json(FORBIDDEN, ChatGptFixtures.legacyError("subscription_sharing_v2_client_not_enabled"))
            },
            ChatGptScenario.UNSUPPORTED_CAPABILITY to { json(BAD_REQUEST, ChatGptFixtures.unsupportedCapability("temperature")) },
            ChatGptScenario.MODEL_NOT_FOUND to { json(NOT_FOUND, ChatGptFixtures.MODEL_NOT_FOUND) },
            SERVER_ERROR to { json(INTERNAL_ERROR, ChatGptFixtures.SERVER_ERROR) },
            BAD_GATEWAY_HTML to { FakeResponses.html(BAD_GATEWAY, ChatGptFixtures.BAD_GATEWAY_HTML) },
            GATEWAY_TIMEOUT_EMPTY to { FakeResponses.empty(GATEWAY_TIMEOUT) },
        )

        val STREAM_BODIES = mapOf(
            ChatGptScenario.MID_STREAM_USAGE_LIMIT to ChatGptFixtures.STREAM_FAILED_USAGE_LIMIT,
            ChatGptScenario.MID_STREAM_UNAVAILABLE to ChatGptFixtures.STREAM_FAILED_USAGE_UNAVAILABLE,
            ChatGptScenario.INCOMPLETE to ChatGptFixtures.STREAM_INCOMPLETE,
            ChatGptScenario.ERROR_EVENT to ChatGptFixtures.STREAM_ERROR_EVENT,
        )
    }
}

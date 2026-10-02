package dev.agentle.ai.chatgpt

import dev.agentle.ai.api.AiCapabilities
import dev.agentle.ai.api.AiCapability
import dev.agentle.ai.api.AiImageResult
import dev.agentle.ai.api.AiProvider
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.ai.api.AiStructuredResult
import dev.agentle.ai.api.AiTextResult
import dev.agentle.ai.api.CapabilitySupport
import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Logger
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The consent check on the exact bytes about to leave the device (red team round 1 item 6). It recovers the three
 * envelope strings from the request body, hashes them with [AiRequestEnvelope.inputDigest] and asks the AI-CONTEXT
 * [AiSendVerifier] to accept that digest. Runs on OkHttp's thread, so it blocks on the verifier.
 */
internal class EnvelopeBeforeSend(private val envelope: AiRequestEnvelope, private val verifier: AiSendVerifier) : BeforeSend {
    override fun check(body: ByteArray): AppError? {
        val digest = SentInput.digest(body) ?: return AppError.ConsentViolation(emptySet(), "sent_input_unreadable")
        return when (val verdict = runBlocking { verifier.verifyBeforeSend(envelope, digest) }) {
            is Outcome.Success -> null
            is Outcome.Failure -> verdict.error
        }
    }
}

/** Reads `instructions` and the `input` item contents back out of an encoded request body. */
internal object SentInput {
    fun digest(body: ByteArray): String? {
        val json = runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull() ?: return null
        val instructions = (json["instructions"] as? JsonPrimitive)?.contentOrNull ?: return null
        val items = (json["input"] as? JsonArray)?.map { ((it as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull }
        val data = items?.getOrNull(0)
        return if (data == null || items.size > 2 || items.any { it == null }) {
            null
        } else {
            AiRequestEnvelope.inputDigest(instructions, data, items.getOrNull(1))
        }
    }
}

/**
 * The ChatGPT [AiProvider] (docs/ARCHITECTURE.md §8, §9) on the user's ChatGPT plan through Sign in with ChatGPT.
 *
 * - Every call goes through [SiwcSessionManager.withAccessToken]; it never starts a sign-in (background calls stay
 *   background-safe) and never falls back to another provider or an API key.
 * - Structured output is prompted JSON validated locally (`json_schema` is undocumented on the direct route).
 * - A stream that broke before `response.completed` is retried once (R06 §8.1); `model_not_found` invalidates the
 *   model catalog. Nothing of a request or a response is logged or stored.
 */
public class ChatGptAiProvider(
    private val session: SiwcSessionManager,
    private val responses: ResponsesClient,
    private val models: ModelCatalog,
    private val sendVerifier: AiSendVerifier,
    scope: CoroutineScope,
    private val preferredModel: () -> String? = { null },
    private val logger: Logger = Logger.NONE,
) : AiProvider {
    override val id: String = ID

    override val state: StateFlow<AiProviderState> =
        session.snapshot.map { it.toProviderState() }.stateIn(scope, SharingStarted.Eagerly, session.snapshot.value.toProviderState())

    /** The table of docs/ARCHITECTURE.md §8 while a plan connection exists; nothing while disconnected or ineligible. */
    override fun capabilities(): AiCapabilities = when (state.value) {
        is AiProviderState.Connected, is AiProviderState.UsageLimited, is AiProviderState.Unavailable -> CAPABILITIES
        else -> AiCapabilities.NONE
    }

    override suspend fun analyze(request: AiRequestEnvelope): Outcome<AiTextResult> = when (val reply = complete(request)) {
        is Outcome.Failure -> reply

        is Outcome.Success ->
            if (reply.value.text.isBlank()) {
                Outcome.Failure(AppError.ParsingError("empty_response"))
            } else {
                Outcome.Success(AiTextResult(reply.value.text, reply.value.model, request.requestId))
            }
    }

    override suspend fun generateStructuredResult(request: AiRequestEnvelope, schema: OutputSchema): Outcome<AiStructuredResult> =
        when (val reply = complete(request)) {
            is Outcome.Failure -> reply

            is Outcome.Success -> StructuredOutput.extract(reply.value.text)
                ?.let { Outcome.Success(AiStructuredResult(it, reply.value.model, request.requestId, schema)) }
                ?: Outcome.Failure(AppError.ParsingError("structured_output_not_json"))
        }

    /** The direct route has no image generation (R06 §4.4); the app renders images locally. */
    override suspend fun generateImage(request: AiRequestEnvelope): Outcome<AiImageResult> =
        Outcome.Failure(AppError.UnsupportedFeature("image_generation", "local_renderer"))

    private suspend fun complete(envelope: AiRequestEnvelope): Outcome<ResponseText> {
        val model = when (val chosen = chooseModel()) {
            is Outcome.Failure -> return chosen
            is Outcome.Success -> chosen.value
        }
        val request = PromptBuilder.build(model, envelope)
        val beforeSend = EnvelopeBeforeSend(envelope, sendVerifier)
        var result = session.withAccessToken { token -> responses.create(token, request, beforeSend) }
        if (result.isInterruptedStream()) {
            logger.i(COMPONENT, "stream interrupted; retrying once")
            result = session.withAccessToken { token -> responses.create(token, request, beforeSend) }
        }
        when (result) {
            is Outcome.Success -> session.setModel(result.value.model ?: model)
            is Outcome.Failure -> if (result.error.detail == MODEL_UNAVAILABLE) models.invalidate()
        }
        logger.i(COMPONENT, "request finished", fields = mapOf("purpose" to envelope.purpose, "ok" to (result is Outcome.Success)))
        return result
    }

    /** The user's choice if the catalog lists it, otherwise the first listed model. */
    private suspend fun chooseModel(): Outcome<String> = when (val listed = models.models()) {
        is Outcome.Failure -> listed

        is Outcome.Success -> {
            val preferred = preferredModel()
            val slug = listed.value.firstOrNull { it.slug == preferred }?.slug ?: listed.value.firstOrNull()?.slug
            if (slug == null) Outcome.Failure(AppError.RemoteServerError(OK_STATUS, "no_model_listed")) else Outcome.Success(slug)
        }
    }

    private fun Outcome<ResponseText>.isInterruptedStream(): Boolean =
        this is Outcome.Failure && error is AppError.NetworkUnavailable && error.detail == STREAM_INTERRUPTED

    public companion object {
        public const val ID: String = "chatgpt"
        private const val COMPONENT = "siwc.provider"
        private const val STREAM_INTERRUPTED = "stream_interrupted"
        private const val MODEL_UNAVAILABLE = "model_unavailable"
        private const val OK_STATUS = 200

        /** docs/ARCHITECTURE.md §8; image input is not offered because the envelope carries no images. */
        public val CAPABILITIES: AiCapabilities = AiCapabilities(
            mapOf(
                AiCapability.TEXT_REASONING to CapabilitySupport.SUPPORTED,
                AiCapability.STRUCTURED_OUTPUT to CapabilitySupport.PROMPTED_JSON,
                AiCapability.IMAGE_GENERATION to CapabilitySupport.UNSUPPORTED,
                AiCapability.VOICE_GENERATION to CapabilitySupport.LOCAL,
                AiCapability.VIDEO_GENERATION to CapabilitySupport.LOCAL,
                AiCapability.BACKGROUND_INFERENCE to CapabilitySupport.USER_BUDGETED,
                AiCapability.IMAGE_INPUT to CapabilitySupport.UNSUPPORTED,
            ),
        )
    }
}

/**
 * Builds the Responses request from an envelope (docs/ARCHITECTURE.md §9). It sends exactly what the user approved:
 * the envelope's app-constant `instructions`, its quoted data as a `developer` item and its quoted user request (if
 * any) as a `user` item. Nothing is added, so the bytes hash to [AiRequestEnvelope.inputSha256]. The
 * structured-output contract is part of the envelope's instructions. [AiRequestEnvelope.maxOutputTokens] is ignored:
 * the direct route does not accept `max_output_tokens` (R06 §4.4), so that cap cannot be enforced here.
 */
internal object PromptBuilder {
    fun build(model: String, envelope: AiRequestEnvelope): ResponsesRequest = ResponsesRequest(
        model = model,
        instructions = envelope.instructions,
        input = listOfNotNull(
            InputMessage(InputRole.DEVELOPER, envelope.dataInputJson),
            envelope.userInputJson?.let { InputMessage(InputRole.USER, it) },
        ),
    )
}

/** Prompted JSON (docs/ARCHITECTURE.md §8): the reply must be one JSON object; the schema owner validates it further. */
internal object StructuredOutput {
    private val FENCE = Regex("^```[A-Za-z]*\\s*\\n?(.*?)\\n?```$", RegexOption.DOT_MATCHES_ALL)

    /** The reply as compact JSON if it is one JSON object (optionally inside one code fence), otherwise null. */
    fun extract(text: String): String? {
        val trimmed = text.trim()
        val unfenced = FENCE.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        return try {
            (Json.parseToJsonElement(unfenced) as? JsonObject)?.toString()
        } catch (_: SerializationException) {
            // The message embeds the model's output; it is dropped (red team privacy-ai-11).
            null
        }
    }
}

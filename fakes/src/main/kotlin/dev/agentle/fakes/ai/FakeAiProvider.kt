package dev.agentle.fakes.ai

import dev.agentle.ai.api.AiCapabilities
import dev.agentle.ai.api.AiCapability
import dev.agentle.ai.api.AiImageResult
import dev.agentle.ai.api.AiProvider
import dev.agentle.ai.api.AiProviderState
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.ai.api.AiSendVerifier
import dev.agentle.ai.api.AiStructuredResult
import dev.agentle.ai.api.AiTextResult
import dev.agentle.ai.api.CapabilitySupport
import dev.agentle.ai.api.OutputSchema
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.flatMap
import dev.agentle.core.common.map
import dev.agentle.core.model.AiDataCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the fake does with the next call. */
public enum class FakeAiScenario {
    /** A valid reply for the purpose and schema ([FakeAiResponses]). */
    SUCCESS,

    /** A reply cut off in the middle of its JSON. */
    INVALID_JSON,

    /** Well-formed JSON that breaks the schema: wrong version, an unknown key, missing keys. */
    SCHEMA_VIOLATION,

    /** A reply that obeys text injected into the request: it echoes it, adds a link and markup, and sets app-owned fields. */
    PROMPT_INJECTION_ECHO,

    /** The account's usage limit is reached ([AppError.NotEligible] `usage_limit`). */
    USAGE_LIMIT,

    /** No reply within [FakeAiProvider.timeout] (a coroutine delay, so virtual time in tests). */
    TIMEOUT,

    /** The connection is lost ([AppError.NetworkUnavailable]). */
    NETWORK_LOSS,
}

/** Which provider call a journal entry records. */
public enum class FakeAiOperation { ANALYZE, STRUCTURED, IMAGE }

/**
 * What a call to the fake would have sent: the exact three strings a provider sends ([sentInstructions],
 * [sentDataInput], [sentUserInput]) plus metadata. Kept in memory only, for assertions; a real provider keeps nothing.
 */
public data class FakeAiCall(
    val requestId: String,
    val purpose: AiPurpose,
    val mode: AiRequestMode,
    val operation: FakeAiOperation,
    val schema: OutputSchema?,
    val consentVersion: Int,
    val inputSha256: String,
    val categories: Set<AiDataCategory>,
    val scenario: FakeAiScenario,
    /** False when the send verifier refused the envelope; nothing was "sent" then. */
    val sent: Boolean,
    val sentInstructions: String,
    val sentDataInput: String,
    val sentUserInput: String?,
) {
    /** Never shows the sent text. */
    override fun toString(): String =
        "FakeAiCall(requestId=$requestId, purpose=$purpose, operation=$operation, scenario=$scenario, sent=$sent)"
}

/**
 * The deterministic [AiProvider] of the fake flavor and of tests (docs/ARCHITECTURE.md section 9). It never opens a
 * connection. Each call checks the connection state, then (like the real provider immediately before its network send)
 * asks the [verifier] to approve the exact input it would send, records a [FakeAiCall] and answers according to the
 * scenario scripted for the request's purpose (default [defaultScenario]).
 *
 * Capabilities mirror the ChatGPT provider of docs/ARCHITECTURE.md section 8: text, prompted JSON, no image generation,
 * voice and video produced locally, background work only within a user budget.
 */
public class FakeAiProvider(
    private val verifier: AiSendVerifier? = null,
    public val timeout: Duration = DEFAULT_TIMEOUT,
    public val model: String = MODEL,
    override val id: String = ID,
) : AiProvider {
    private val lock = Any()
    private val scenarios = HashMap<AiPurpose, FakeAiScenario>()
    private val texts = HashMap<Pair<AiPurpose, String?>, String>()
    private val calls = ArrayDeque<FakeAiCall>()
    private val mutableState = MutableStateFlow<AiProviderState>(AiProviderState.Connected(ACCOUNT_LABEL, model))

    @Volatile
    public var defaultScenario: FakeAiScenario = FakeAiScenario.SUCCESS

    override val state: StateFlow<AiProviderState> = mutableState.asStateFlow()

    /** The calls so far, oldest first (at most [JOURNAL_LIMIT]). */
    public val journal: List<FakeAiCall> get() = synchronized(lock) { calls.toList() }

    public fun setState(state: AiProviderState) {
        mutableState.value = state
    }

    /** Use [scenario] for every later call with [purpose]. */
    public fun script(purpose: AiPurpose, scenario: FakeAiScenario) {
        synchronized(lock) { scenarios[purpose] = scenario }
    }

    /** Reply with [text] on success for [purpose] and [schemaName] (null: free text from [analyze]). */
    public fun respondWith(purpose: AiPurpose, schemaName: String?, text: String) {
        synchronized(lock) { texts[purpose to schemaName] = text }
    }

    public fun clearJournal() {
        synchronized(lock) { calls.clear() }
    }

    override fun capabilities(): AiCapabilities = CAPABILITIES

    override suspend fun analyze(request: AiRequestEnvelope): Outcome<AiTextResult> =
        admit(request, FakeAiOperation.ANALYZE, null).flatMap { scenario ->
            respond(scenario) {
                AiTextResult(scriptedText(request, null) ?: FakeAiResponses.text(request, scenario), model, request.requestId)
            }
        }

    override suspend fun generateStructuredResult(request: AiRequestEnvelope, schema: OutputSchema): Outcome<AiStructuredResult> =
        admit(request, FakeAiOperation.STRUCTURED, schema).flatMap { scenario ->
            respond(scenario) {
                val scripted = scriptedText(request, schema.name)?.takeIf { scenario == FakeAiScenario.SUCCESS }
                AiStructuredResult(scripted ?: FakeAiResponses.structured(request, schema, scenario), model, request.requestId, schema)
            }
        }

    /** Image generation is not available through this provider, as through ChatGPT sign-in (R09 section 7). */
    override suspend fun generateImage(request: AiRequestEnvelope): Outcome<AiImageResult> =
        admit(request, FakeAiOperation.IMAGE, null).flatMap {
            Outcome.Failure(AppError.UnsupportedFeature(AiCapability.IMAGE_GENERATION.name))
        }

    /** The checks before a send: connection state, then the send verifier on the exact input. Records the call. */
    private suspend fun admit(request: AiRequestEnvelope, operation: FakeAiOperation, schema: OutputSchema?): Outcome<FakeAiScenario> {
        stateFailure(state.value)?.let { return Outcome.Failure(it) }
        val scenario = synchronized(lock) { scenarios[request.purpose] } ?: defaultScenario
        val sentDigest = AiRequestEnvelope.inputDigest(request.instructions, request.dataInputJson, request.userInputJson)
        val verdict = verifier?.verifyBeforeSend(request, sentDigest) ?: Outcome.Success(Unit)
        record(request, operation, schema, scenario, sentDigest, sent = verdict is Outcome.Success)
        return verdict.map { scenario }
    }

    private suspend fun <T> respond(scenario: FakeAiScenario, reply: () -> T): Outcome<T> = when (scenario) {
        FakeAiScenario.USAGE_LIMIT -> {
            mutableState.value = AiProviderState.UsageLimited(untilEpochMs = null)
            Outcome.Failure(AppError.NotEligible(USAGE_LIMIT_REASON))
        }

        FakeAiScenario.TIMEOUT -> {
            delay(timeout)
            Outcome.Failure(AppError.NetworkUnavailable(detail = "timeout"))
        }

        FakeAiScenario.NETWORK_LOSS -> Outcome.Failure(AppError.NetworkUnavailable())

        FakeAiScenario.SUCCESS, FakeAiScenario.INVALID_JSON, FakeAiScenario.SCHEMA_VIOLATION, FakeAiScenario.PROMPT_INJECTION_ECHO ->
            Outcome.Success(reply())
    }

    private fun stateFailure(state: AiProviderState): AppError? = when (state) {
        is AiProviderState.Connected -> null
        AiProviderState.Disconnected, AiProviderState.Connecting, AiProviderState.NeedsReauth -> AppError.AuthenticationRequired(id)
        is AiProviderState.NotEligible -> AppError.NotEligible(state.reason)
        is AiProviderState.UsageLimited -> AppError.NotEligible(USAGE_LIMIT_REASON)
        is AiProviderState.Unavailable -> AppError.NetworkUnavailable()
    }

    private fun scriptedText(request: AiRequestEnvelope, schemaName: String?): String? =
        synchronized(lock) { texts[request.purpose to schemaName] }

    private fun record(
        request: AiRequestEnvelope,
        operation: FakeAiOperation,
        schema: OutputSchema?,
        scenario: FakeAiScenario,
        digest: String,
        sent: Boolean,
    ) {
        val entry = FakeAiCall(
            requestId = request.requestId,
            purpose = request.purpose,
            mode = request.mode,
            operation = operation,
            schema = schema,
            consentVersion = request.consentVersion,
            inputSha256 = digest,
            categories = request.categories,
            scenario = scenario,
            sent = sent,
            sentInstructions = request.instructions,
            sentDataInput = request.dataInputJson,
            sentUserInput = request.userInputJson,
        )
        synchronized(lock) {
            calls.addLast(entry)
            while (calls.size > JOURNAL_LIMIT) calls.removeFirst()
        }
    }

    public companion object {
        public const val ID: String = "fake"
        public const val MODEL: String = "fake-model-1"
        public const val ACCOUNT_LABEL: String = "Fake ChatGPT account"
        public const val USAGE_LIMIT_REASON: String = "usage_limit"
        public const val JOURNAL_LIMIT: Int = 50

        /** A real request that streams nothing for this long is treated as failed (design value). */
        public val DEFAULT_TIMEOUT: Duration = 180.seconds

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

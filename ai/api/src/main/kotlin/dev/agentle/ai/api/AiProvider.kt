package dev.agentle.ai.api

import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.StateFlow

/** How a provider supports one capability (spec §12: capabilities must be introspectable). */
public enum class CapabilitySupport {
    /** Supported natively by the provider. */
    SUPPORTED,

    /** Structured output obtained by prompting for JSON and validating locally (no server-side schema). */
    PROMPTED_JSON,

    /** Produced on the device (Android TTS, local composition), not by the AI provider. */
    LOCAL,

    /** Allowed only within a user-set budget (e.g. background generations). */
    USER_BUDGETED,

    /** Not available through this provider. */
    UNSUPPORTED,
}

public enum class AiCapability {
    TEXT_REASONING,
    STRUCTURED_OUTPUT,
    IMAGE_GENERATION,
    VOICE_GENERATION,
    VIDEO_GENERATION,
    BACKGROUND_INFERENCE,
    IMAGE_INPUT,
}

/** Introspectable capability table of a provider in its current state. */
public data class AiCapabilities(val support: Map<AiCapability, CapabilitySupport>) {
    public operator fun get(capability: AiCapability): CapabilitySupport = support[capability] ?: CapabilitySupport.UNSUPPORTED

    public fun isAvailable(capability: AiCapability): Boolean = get(capability) != CapabilitySupport.UNSUPPORTED

    public companion object {
        public val NONE: AiCapabilities = AiCapabilities(AiCapability.entries.associateWith { CapabilitySupport.UNSUPPORTED })
    }
}

/** Connection state of the AI provider as the UI and feature gates see it. */
public sealed interface AiProviderState {
    public data object Disconnected : AiProviderState

    public data object Connecting : AiProviderState

    public data class Connected(val accountLabel: String?, val model: String?) : AiProviderState

    public data object NeedsReauth : AiProviderState

    public data class NotEligible(val reason: String) : AiProviderState

    public data class UsageLimited(val untilEpochMs: Long?) : AiProviderState

    public data class Unavailable(val reason: String) : AiProviderState
}

/**
 * The AI abstraction (spec §12). Every call receives an [AiRequestEnvelope] built by the ContextSelectionEngine,
 * never raw database content. Implementations: ChatGPT (Sign in with ChatGPT), a deterministic fake, and an
 * unavailable provider. Implementations must not silently fall back to any other provider or API key.
 */
public interface AiProvider {
    public val id: String
    public val state: StateFlow<AiProviderState>

    public fun capabilities(): AiCapabilities

    /** Free-text analysis (insight explanations). */
    public suspend fun analyze(request: AiRequestEnvelope): Outcome<AiTextResult>

    /** JSON output for [schema]; the caller validates it with the schema's validator before use. */
    public suspend fun generateStructuredResult(request: AiRequestEnvelope, schema: OutputSchema): Outcome<AiStructuredResult>

    /** Image generation; providers without the capability return `AppError.UnsupportedFeature`. */
    public suspend fun generateImage(request: AiRequestEnvelope): Outcome<AiImageResult>
}

package dev.agentle.ai.api

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The provider bound while no AI account is connected (docs/ARCHITECTURE.md section 9). Every call returns
 * [AppError.AuthenticationRequired] at once: it holds no client, opens no socket and never falls back to another
 * provider or an API key. It reports no capability, so feature gates hide AI entry points.
 */
public class UnavailableAiProvider(override val id: String = ID) : AiProvider {
    private val mutableState = MutableStateFlow<AiProviderState>(AiProviderState.Disconnected)

    override val state: StateFlow<AiProviderState> = mutableState.asStateFlow()

    override fun capabilities(): AiCapabilities = AiCapabilities.NONE

    override suspend fun analyze(request: AiRequestEnvelope): Outcome<AiTextResult> = notConnected()

    override suspend fun generateStructuredResult(request: AiRequestEnvelope, schema: OutputSchema): Outcome<AiStructuredResult> =
        notConnected()

    override suspend fun generateImage(request: AiRequestEnvelope): Outcome<AiImageResult> = notConnected()

    private fun notConnected(): Outcome<Nothing> = Outcome.Failure(AppError.AuthenticationRequired(provider = id))

    public companion object {
        public const val ID: String = "unavailable"
    }
}

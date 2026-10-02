package dev.agentle.ai.api

import dev.agentle.core.common.Outcome

/**
 * The last check before an AI request leaves the device (privacy-ai-03). A provider calls it immediately before each
 * network send, including its own retries, with [sentInputSha256] =
 * [AiRequestEnvelope.inputDigest] of the exact instructions, data input and user input it is about to send.
 *
 * The implementation (EgressGuard in `:ai:context`) fails unless the envelope is in flight through EgressGuard, the
 * digest matches the approved [AiRequestEnvelope.inputSha256], the consent version is current and the user's current
 * grants still allow every category and source family in the envelope's lineage. A provider must not send on failure.
 */
public fun interface AiSendVerifier {
    public suspend fun verifyBeforeSend(envelope: AiRequestEnvelope, sentInputSha256: String): Outcome<Unit>
}

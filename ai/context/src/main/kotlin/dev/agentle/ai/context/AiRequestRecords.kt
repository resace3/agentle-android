package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.SourceFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * What the user sees before a user-initiated request leaves the phone (docs/ARCHITECTURE.md section 9, privacy-ai-13):
 * the purpose, the categories, the time range, the size and, rendered from the frozen canonical input itself
 * ([AiRequestEnvelope.dataInputJson] and [AiRequestEnvelope.userInputJson]), every block, field and value ([blocks]) and
 * the user's request as sent ([userRequestText]). Values are display strings: the UI shows them as plain text and never
 * interprets them (no markup, no links). [inputSha256] names the exact bytes shown; the provider sends those bytes or
 * nothing, so a rebuilt request has another digest and needs a new preview. Being personal, a preview is never logged
 * or stored.
 */
public data class AiRequestPreview(
    val requestId: String,
    val purpose: AiPurpose,
    val mode: AiRequestMode,
    val categories: Set<AiDataCategory>,
    val sourceFamilies: Set<SourceFamily>,
    val rangeStart: Instant?,
    val rangeEnd: Instant?,
    val rawEvents: Boolean,
    val aggregates: Boolean,
    val userText: Boolean,
    val itemCount: Int,
    val approximateBytes: Int,
    val inputSha256: String = "",
    val blocks: List<AiPreviewBlock> = emptyList(),
    val userRequestText: String? = null,
) {
    /** Never shows values or the request text. */
    override fun toString(): String = "AiRequestPreview(requestId=$requestId, purpose=$purpose, items=$itemCount)"

    public companion object {
        public fun of(envelope: AiRequestEnvelope): AiRequestPreview = AiRequestPreview(
            requestId = envelope.requestId,
            purpose = envelope.purpose,
            mode = envelope.mode,
            categories = envelope.categories.toSortedSet(),
            sourceFamilies = envelope.sourceFamilies.toSortedSet(),
            rangeStart = envelope.rangeStart,
            rangeEnd = envelope.rangeEnd,
            rawEvents = envelope.containsRawEvents,
            aggregates = envelope.containsAggregates,
            userText = envelope.userText != null,
            itemCount = envelope.blocks.sumOf { it.items.size },
            approximateBytes = envelope.approximateBytes,
            inputSha256 = envelope.inputSha256,
            blocks = PreviewRenderer.blocks(envelope.dataInputJson),
            userRequestText = envelope.userInputJson?.let(PreviewRenderer::userText),
        )

        /**
         * Whether a later round [next] of the exchange begun by [previous] needs a new preview and confirmation
         * (privacy-ai-13 decision 2): yes when it carries any personal content the first preview did not show (its
         * data or user item differs); no when only the model's previous output and app-constant instructions change.
         */
        public fun needsNewPreview(previous: AiRequestEnvelope, next: AiRequestEnvelope): Boolean = next.purpose != previous.purpose ||
            next.dataInputJson != previous.dataInputJson ||
            next.userInputJson != previous.userInputJson
    }
}

/** One block of a preview: its label, kind, categories and every item as a field and a display value. */
public data class AiPreviewBlock(val label: String, val kind: String, val categories: List<String>, val items: List<AiPreviewItem>)

/** One previewed value: its field code and the value as plain display text. */
public data class AiPreviewItem(val field: String, val value: String)

/** Renders the frozen canonical JSON, so the preview shows exactly what is sent. */
internal object PreviewRenderer {
    fun blocks(dataInputJson: String): List<AiPreviewBlock> =
        Json.parseToJsonElement(dataInputJson).jsonObject.getValue("blocks").jsonArray.map { element ->
            val block = element.jsonObject
            AiPreviewBlock(
                label = block.text("label"),
                kind = block.text("kind"),
                categories = block.getValue("categories").jsonArray.map { it.jsonPrimitive.content },
                items = block.getValue("items").jsonArray.map { item(it.jsonObject) },
            )
        }

    fun userText(userInputJson: String): String = Json.parseToJsonElement(userInputJson).jsonObject.text("text")

    private fun item(item: JsonObject): AiPreviewItem {
        val value = when (item.text("type")) {
            "quantity" -> item.text("value") + " " + item.text("unit")

            "time_of_day" -> item.text("time")

            "code" -> item.text("code")

            "text" -> item.text("text")

            "app_usage" -> item.text("app") + ": " + item.text("minutes") + " min" +
                (item["opens"]?.takeUnless { it is JsonNull }?.let { ", " + it.jsonPrimitive.content + " opens" }.orEmpty())

            else -> item.text("eventType") + " " + item.text("start") +
                (item["end"]?.takeUnless { it is JsonNull }?.let { " to " + it.jsonPrimitive.content }.orEmpty()) +
                item["values"]?.jsonObject?.entries.orEmpty().joinToString("") { (key, number) -> ", $key " + number.jsonPrimitive.content }
        }
        return AiPreviewItem(item.text("field"), value)
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}

/** Who started a request (the `initiator` column of `ai_request`). */
public enum class AiInitiator { USER, BACKGROUND }

/** Where a request ended. */
public enum class AiRequestStatus {
    /** Admitted by the EgressGuard and handed to the provider; no answer yet (or the app died meanwhile). */
    IN_FLIGHT,

    /** Sent and answered. */
    SENT,

    /** Refused by a consent check (EgressGuard gate or send-time check). Nothing was sent. */
    DENIED,

    /** The provider refused before sending (not signed in, usage limit, ...). Nothing was sent. */
    NOT_SENT,

    /** Cancelled because the consent changed while it ran. Its answer, if any, was discarded. */
    CANCELLED,

    /** Sent, then failed (network, timeout, ...). */
    FAILED,
    ;

    /** Whether data may have left the phone; such requests count against background budgets. */
    public val mayHaveBeenSent: Boolean get() = this == IN_FLIGHT || this == SENT || this == CANCELLED || this == FAILED
}

/**
 * The `ai_request` metadata record: what was sent, never a value (no payload, no text; only the payload's digest). The AI log screen
 * shows these records. Every background send has one (privacy-ai-01). [categories] are the categories present in
 * the request body (SEC-AI-06): the union of the `categories` of its blocks.
 */
public data class AiRequestRecord(
    val requestId: String,
    val purpose: AiPurpose,
    val mode: AiRequestMode,
    val providerId: String,
    val categories: Set<AiDataCategory>,
    val sourceFamilies: Set<SourceFamily>,
    val fields: Set<String>,
    val rangeStart: Instant?,
    val rangeEnd: Instant?,
    val rawEvents: Boolean,
    val aggregates: Boolean,
    val userText: Boolean,
    val approximateBytes: Int,
    val consentVersion: Int,
    val status: AiRequestStatus,
    /** `AppError.code` of the failure, if any. */
    val errorCode: String? = null,
    /** A reason code (snake case) for the failure, if any; never free text. */
    val reason: String? = null,
    /** True once the provider's send-time check approved the exact input. */
    val sendVerified: Boolean = false,
    /** SHA-256 of the exact input (the digest the send-time check compared); a digest, never the payload. */
    val payloadSha256: String = "",
    /** USER or BACKGROUND. */
    val initiator: AiInitiator = AiInitiator.USER,
    /** [AiInstructionSet.VERSION] of the instructions sent. */
    val promptVersion: String = AiInstructionSet.VERSION,
    /** Salted SHA-256 of the signed-in ChatGPT account's sub, so the log can tell accounts apart without naming them. */
    val accountSubHash: String? = null,
    /** The provider's `x-request-id`, when the provider reports one. */
    val providerRequestId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    public companion object {
        public fun of(
            envelope: AiRequestEnvelope,
            providerId: String,
            status: AiRequestStatus,
            createdAt: Instant,
            updatedAt: Instant = createdAt,
        ): AiRequestRecord = AiRequestRecord(
            requestId = envelope.requestId,
            purpose = envelope.purpose,
            mode = envelope.mode,
            providerId = providerId,
            categories = envelope.categories.toSortedSet(),
            sourceFamilies = envelope.sourceFamilies.toSortedSet(),
            fields = envelope.fields.toSortedSet(),
            rangeStart = envelope.rangeStart,
            rangeEnd = envelope.rangeEnd,
            rawEvents = envelope.containsRawEvents,
            aggregates = envelope.containsAggregates,
            userText = envelope.userText != null,
            approximateBytes = envelope.approximateBytes,
            consentVersion = envelope.consentVersion,
            status = status,
            payloadSha256 = envelope.inputSha256,
            initiator = if (envelope.mode == AiRequestMode.BACKGROUND) AiInitiator.BACKGROUND else AiInitiator.USER,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

/**
 * Persistence port of the `ai_request` table (the user-visible AI log). The EgressGuard writes a record before it hands
 * a request to the provider and updates it when the request ends. If it cannot write the record, nothing is sent.
 */
public interface AiAuditLog {
    /** Inserts the record, or replaces the one with the same request id. */
    public suspend fun record(record: AiRequestRecord): Outcome<Unit>

    /** Records of [purpose] and [mode] created at or after [since], oldest first (budgets and the log screen). */
    public suspend fun records(purpose: AiPurpose, mode: AiRequestMode, since: Instant): Outcome<List<AiRequestRecord>>
}

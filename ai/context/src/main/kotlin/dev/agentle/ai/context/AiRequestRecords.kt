package dev.agentle.ai.context

import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.AiRequestMode
import dev.agentle.core.common.Outcome
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.SourceFamily
import kotlin.time.Instant

/**
 * What the user sees before a user-initiated request leaves the phone (docs/ARCHITECTURE.md section 9): the purpose,
 * the categories, the time range, whether individual events, aggregates or the user's own text are included, and the
 * size. It never holds a value.
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
) {
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
        )
    }
}

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
 * The `ai_request` metadata record: what was sent, never a value (no payload, no text, no digest). The AI log screen
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

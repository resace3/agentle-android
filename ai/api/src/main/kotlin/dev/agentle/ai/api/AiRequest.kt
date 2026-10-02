package dev.agentle.ai.api

import dev.agentle.core.model.DataCategory
import kotlin.time.Instant

/** Why an AI request is made; each purpose has a fixed category allow-list in the ContextSelectionEngine. */
public enum class AiPurpose {
    SLEEP_INSIGHT,
    ACTIVITY_INSIGHT,
    SCREEN_TIME_INSIGHT,
    GENERAL_QUESTION,
    PATTERN_EXPLANATION,
    JITAI_FROM_NATURAL_LANGUAGE,
    JITAI_PROPOSAL_WORDING,
    INTERVENTION_TEXT,
}

/** One block of context data. [untrusted] marks personal or external text that must be treated as data only. */
public data class ContextBlock(
    val category: DataCategory,
    val label: String,
    val content: String,
    val untrusted: Boolean,
    val rawEvents: Boolean,
)

/**
 * The only thing an [AiProvider] receives. Built and checked by the ContextSelectionEngine: [categories] is exactly
 * the set of categories present in [blocks], all of which were allowed by the user at build time.
 */
public data class AiRequestEnvelope(
    val requestId: String,
    val purpose: AiPurpose,
    /** App-constant instructions; never contains user or personal text. */
    val instructions: String,
    /** The user's own question or request text, if any (treated as untrusted data). */
    val userText: String?,
    val blocks: List<ContextBlock>,
    val categories: Set<DataCategory>,
    val rangeStart: Instant?,
    val rangeEnd: Instant?,
    val createdAt: Instant,
) {
    init {
        require(blocks.map { it.category }.toSet() == categories) { "categories must equal the categories of blocks" }
    }

    val containsRawEvents: Boolean get() = blocks.any { it.rawEvents }
    val approximateBytes: Int get() = instructions.length + (userText?.length ?: 0) + blocks.sumOf { it.content.length + it.label.length }
}

/** Identifies the expected JSON output; the validator lives with the schema's owner (e.g. :jitai:dsl). */
public data class OutputSchema(val name: String, val version: Int, val jsonSchema: String?)

public data class AiTextResult(val text: String, val model: String?, val requestId: String)

public data class AiStructuredResult(val json: String, val model: String?, val requestId: String, val schema: OutputSchema)

public data class AiImageResult(val bytes: ByteArray, val mimeType: String, val requestId: String) {
    override fun equals(other: Any?): Boolean =
        other is AiImageResult && other.mimeType == mimeType && other.requestId == requestId && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + mimeType.hashCode()) + requestId.hashCode()
}

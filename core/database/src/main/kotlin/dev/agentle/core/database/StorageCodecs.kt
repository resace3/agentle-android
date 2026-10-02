package dev.agentle.core.database

import dev.agentle.core.model.DataCategory
import dev.agentle.core.model.EventCodec
import dev.agentle.core.model.EventPayload
import dev.agentle.core.model.Lineage
import dev.agentle.core.model.PersonalEvent
import dev.agentle.core.model.SourceFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * The 64-bit hashes of the storage contract (round 2 corrections 1 and 8). Both are the first 8 bytes of SHA-256, read
 * big-endian, over versioned UTF-8 input, so they are stable across processes, devices and app versions.
 */
object StorageHashes {
    private const val DEDUP_VERSION = "d1"
    private const val PAYLOAD_VERSION = "p1"
    private const val ALTERNATE_VERSION = "a1"

    /** The `dedup_hash` of [dedupKey] for [account] (the account term id, 0 when not account-bound). */
    fun dedupHash(account: Long, dedupKey: String): Long = h64("$DEDUP_VERSION|$account|$dedupKey")

    /** The [attempt]-th alternative hash for a key whose primary hash is taken by another key (collision check). */
    fun alternateDedupHash(account: Long, dedupKey: String, attempt: Int): Long = h64("$ALTERNATE_VERSION|$attempt|$account|$dedupKey")

    /**
     * The `payload_hash` of [event]: its type, instants, zone, confidence and payload. The payload part is the
     * producer's own `metadata.payloadHash` when it sets one, otherwise the canonical projection of the payload
     * ([canonicalPayload]); never the stored JSON text, which may change with encoder settings.
     */
    fun payloadHash(event: PersonalEvent): Long {
        val payloadPart = event.metadata.payloadHash?.let { "h:$it" } ?: "c:${canonicalPayload(event.payload)}"
        val end = event.endTime?.toEpochMilliseconds()?.toString() ?: "-"
        val confidence = event.confidence?.toString() ?: "-"
        return h64(
            "$PAYLOAD_VERSION|${event.type.name}|${event.startTime.toEpochMilliseconds()}|$end|${event.zoneId}|$confidence|$payloadPart",
        )
    }

    /**
     * A canonical, versioned projection of [payload]: its JSON tree (defaults omitted, so adding a field with a default
     * keeps old hashes) with object keys sorted and no whitespace.
     */
    fun canonicalPayload(payload: EventPayload): String {
        val tree = EventCodec.json.encodeToJsonElement(EventPayload.serializer(), payload)
        return buildString { appendCanonical(tree) }
    }

    fun h64(text: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until Long.SIZE_BYTES) value = (value shl Byte.SIZE_BITS) or (digest[i].toLong() and BYTE_MASK)
        return value
    }

    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(JsonPrimitive(key).toString()).append(':')
                    appendCanonical(element.getValue(key))
                }
                append('}')
            }

            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendCanonical(item)
                }
                append(']')
            }

            JsonNull -> append("null")

            is JsonPrimitive -> append(element.toString())
        }
    }

    private const val BYTE_MASK = 0xFFL
}

/**
 * Deletion lineage stored as text (red team privacy-ai-07): `|F:<family>|...|C:<category>|...|`, sorted, so a
 * deletion finds every derived row of a family or category with `instr(lineage, token) > 0`. [Lineage.NONE] is `|`.
 */
object LineageCodec {
    const val EMPTY: String = "|"
    private const val FAMILY = "F:"
    private const val CATEGORY = "C:"

    fun encode(lineage: Lineage): String = buildString {
        append('|')
        lineage.families.map { it.name }.sorted().forEach { append(FAMILY).append(it).append('|') }
        lineage.categories.map { it.name }.sorted().forEach { append(CATEGORY).append(it).append('|') }
    }

    /** Tokens of names this version does not know are ignored. */
    fun decode(text: String): Lineage {
        val tokens = text.split('|').filter { it.isNotEmpty() }
        val families = tokens.filter { it.startsWith(FAMILY) }
            .mapNotNull { token -> SourceFamily.entries.firstOrNull { it.name == token.removePrefix(FAMILY) } }
        val categories = tokens.filter { it.startsWith(CATEGORY) }
            .mapNotNull { token -> DataCategory.entries.firstOrNull { it.name == token.removePrefix(CATEGORY) } }
        return Lineage(families.toSet(), categories.toSet())
    }

    fun token(family: SourceFamily): String = "|$FAMILY${family.name}|"

    fun token(category: DataCategory): String = "|$CATEGORY${category.name}|"

    /** [text] without [token] (the family or category was deleted from the row's inputs). */
    fun without(text: String, token: String): String = text.replace(token, "|").ifEmpty { EMPTY }

    /** Category tokens only (the `categories` columns of AI audit rows and the text pool). */
    fun encodeCategories(categories: Set<DataCategory>): String = encode(Lineage(categories = categories))
}

package dev.agentle.jitai.dsl.codec

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.DiscoveredProposal
import dev.agentle.jitai.dsl.nl.JitaiProposal
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.validation.IssueCode
import dev.agentle.jitai.dsl.validation.IssueSeverity
import dev.agentle.jitai.dsl.validation.IssueSink
import dev.agentle.jitai.dsl.validation.Stage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * The strict JSON codec of the DSL (R10 §3.6).
 *
 * Reading is always: strict RFC 8259 read (depth <= 20, no duplicate keys, verbatim number tokens) -> closed schema walk
 * (unknown keys rejected, discriminator `type`, no polymorphic fallback) -> kotlinx.serialization decode with the strict
 * [json] instance. Writing is the canonical form: compact, every property present (nulls explicit), declaration order,
 * `args` keys sorted, instants `yyyy-MM-ddTHH:mm:ssZ`; so `encode(decode(x))` is a fixed point.
 *
 * Decode failures are [AppError.ValidationError] carrying the R10 codes and JSON-pointer paths only; never input text
 * or a parser message (red team privacy-ai-11).
 */
public object RuleCodec {
    /** Maximum UTF-8 size of a definition or proposal (R10 §4.6, §11.1 S1). */
    public const val MAX_BYTES: Int = 16_384

    /** The one strict `Json` instance for definitions and proposals (R10 §3.6). */
    @OptIn(ExperimentalSerializationApi::class)
    public val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        allowTrailingComma = false
        allowComments = false
        decodeEnumsCaseInsensitive = false
        explicitNulls = true
        encodeDefaults = true
        classDiscriminator = "type"
        prettyPrint = false
    }

    /** Properties left out of [contentHash] (see the KDoc there). */
    public val CONTENT_HASH_EXCLUDED: List<String> = listOf(
        "id", "version", "status", "enabled", "createdBy", "createdAt", "modifiedAt", "expiresAt",
        "userConfirmedUnknownOverrides", "provenance",
    )

    public fun encodeDefinition(definition: JitaiDefinition): String = json.encodeToString(JitaiDefinition.serializer(), definition)

    public fun decodeDefinition(text: String): Outcome<JitaiDefinition> = decode(text, Schemas.definition, JitaiDefinition.serializer())

    /** [decodeDefinition] with an explicit alias table (tests of a catalog rename). */
    internal fun decodeDefinition(text: String, aliases: Map<String, String>): Outcome<JitaiDefinition> =
        decode(text, Schemas.definition, JitaiDefinition.serializer(), aliases)

    /** A condition tree in the stored form (sparse `args`, no `appLabel`). */
    public fun encodeCondition(condition: Condition): String = json.encodeToString(Condition.serializer(), condition)

    public fun decodeCondition(text: String): Outcome<Condition> = decode(text, Schemas.storedCondition, Condition.serializer())

    /** A model reply in JitaiProposalSchema v1 (R10 §13.3). */
    public fun encodeProposal(proposal: JitaiProposal): String = json.encodeToString(JitaiProposal.serializer(), proposal)

    public fun decodeProposal(text: String): Outcome<JitaiProposal> = decode(text, Schemas.proposal, JitaiProposal.serializer())

    /** An AI-discovered proposal (R10 §14.7). */
    public fun encodeDiscovered(proposal: DiscoveredProposal): String = json.encodeToString(DiscoveredProposal.serializer(), proposal)

    public fun decodeDiscovered(text: String): Outcome<DiscoveredProposal> =
        decode(text, Schemas.discovered, DiscoveredProposal.serializer())

    /**
     * SHA-256 (lowercase hex) of the canonical JSON without the properties in [CONTENT_HASH_EXCLUDED] (R10 §3.1).
     *
     * Deviation from R10 §3.1, which removes only `version`, `modifiedAt`, `enabled` and `status`: with `id` and
     * `createdAt` inside the hash, two rules could never share a hash, so W01 (duplicate) could never fire. The hash
     * therefore covers what the rule does (trigger, window, conditions, delivery, content, limits, outcome, targets,
     * experiment) and not who made it or when.
     */
    public fun contentHash(definition: JitaiDefinition): String {
        val element = json.encodeToJsonElement(JitaiDefinition.serializer(), definition).jsonObject
        val semantic = JsonObject(element.filterKeys { it !in CONTENT_HASH_EXCLUDED })
        return sha256Hex(json.encodeToString(JsonObject.serializer(), semantic))
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [text] (no `HexFormat`: it is missing on older Android). */
    internal fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val v = byte.toInt() and BYTE_MASK
            out.append(HEX[v ushr NIBBLE_BITS]).append(HEX[v and NIBBLE_MASK])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
    private const val BYTE_MASK = 0xff
    private const val NIBBLE_MASK = 0x0f
    private const val NIBBLE_BITS = 4

    private fun <T> decode(
        text: String,
        spec: Spec,
        serializer: KSerializer<T>,
        aliases: Map<String, String> = FeatureAliases.ALIASES,
    ): Outcome<T> {
        val sink = IssueSink()
        val value = decodeInto(text, spec, serializer, sink, Stage.S4, aliases)
        return if (value != null && !sink.hasErrors) {
            Outcome.success(value)
        } else {
            val errors = sink.sorted(IssueSeverity.ERROR)
            val detail = errors.joinToString("; ") { "${it.code.name} ${it.path}" }
            Outcome.failure(AppError.ValidationError(errors.map { it.code.name }, detail))
        }
    }

    /** S1-S5 for [text]: size, strict read, schema walk, decode. Findings go to [sink]; returns the value or null. */
    internal fun <T> decodeInto(
        text: String,
        spec: Spec,
        serializer: KSerializer<T>,
        sink: IssueSink,
        walkStage: Stage,
        aliases: Map<String, String> = FeatureAliases.ALIASES,
    ): T? {
        val element = readStrict(text, sink) ?: return null
        return decodeElement(element, spec, serializer, sink, walkStage, aliases)
    }

    /** S1-S3: size limit, strict read. */
    internal fun readStrict(text: String, sink: IssueSink): JsonElement? {
        val bytes = utf8Length(text)
        if (bytes > MAX_BYTES) {
            sink.add(IssueCode.E002, Stage.S1, "", mapOf("bytes" to bytes.toString()))
            return null
        }
        return when (val result = StrictJsonReader.read(text)) {
            is StrictJsonReader.Result.Ok -> result.value

            is StrictJsonReader.Result.TooDeep -> {
                sink.add(IssueCode.E003, Stage.S2, "", mapOf("offset" to result.offset.toString()))
                null
            }

            is StrictJsonReader.Result.DuplicateKey -> {
                val detail = "duplicate key \"${result.key}\" at ${result.path.ifEmpty { "/" }}"
                sink.add(IssueCode.E001, Stage.S2, result.path, mapOf("detail" to detail))
                null
            }

            is StrictJsonReader.Result.SyntaxError -> {
                sink.add(IssueCode.E001, Stage.S3, "", mapOf("detail" to "syntax error at offset ${result.offset}"))
                null
            }

            StrictJsonReader.Result.TrailingText -> {
                sink.add(IssueCode.E001, Stage.S0, "", mapOf("detail" to "text before or after the object"))
                null
            }
        }
    }

    /** S4 walk then S5 decode; a decode failure after a clean walk is a validator bug (E099), never thrown. */
    internal fun <T> decodeElement(
        element: JsonElement,
        spec: Spec,
        serializer: KSerializer<T>,
        sink: IssueSink,
        walkStage: Stage,
        aliases: Map<String, String> = FeatureAliases.ALIASES,
    ): T? {
        // Stored documents may use renamed feature ids (FeatureAliases); model output never does.
        val stored = spec === Schemas.definition || spec === Schemas.storedCondition
        val input = if (stored) FeatureAliases.migrate(element, aliases) else element
        val walk = IssueSink()
        SchemaWalker(walk, walkStage).walk(input, spec, "")
        sink.addAll(walk)
        if (walk.hasErrors) return null
        return try {
            json.decodeFromJsonElement(serializer, input)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") ignored: Exception) {
            // The exception text may quote the input, so only the stage is reported (red team privacy-ai-11).
            sink.add(IssueCode.E099, Stage.S5, "", mapOf("stage" to "S5"))
            null
        }
    }

    /** UTF-8 length without allocating the encoded bytes. */
    internal fun utf8Length(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1

                c.code < 0x800 -> 2

                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }

                else -> 3
            }
            i++
        }
        return bytes
    }
}

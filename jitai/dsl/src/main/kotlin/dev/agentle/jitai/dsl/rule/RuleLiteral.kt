package dev.agentle.jitai.dsl.rule

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * A leaf literal exactly as it appeared on the wire (R10 §3.6, §4.1): a JSON number token, string or boolean.
 *
 * Literal fidelity: `45`, `45.0`, `4.5e1` and `"45"` are four different values, so the validator can report E015 for
 * the last three when the feature is an INT. The typed value is produced by [TypedLiterals.convert] from the feature's
 * catalog type; rules never hold floating point.
 */
@Serializable(with = RuleLiteralSerializer::class)
public sealed interface RuleLiteral {
    /** The literal as JSON text (`45`, `"22:00"`, `true`). */
    public val json: String

    /** A JSON number token, kept verbatim (`45`, `-5`, `45.0`, `4.5e1`). */
    public data class NumberToken(val token: String) : RuleLiteral {
        init {
            require(JSON_NUMBER.matches(token)) { "not a JSON number token" }
        }

        override val json: String get() = token

        /** True for an integer token without fraction or exponent (`45`, `-5`, `0`). */
        val isInteger: Boolean get() = INTEGER.matches(token)

        override fun toString(): String = token
    }

    /** A JSON string. */
    public data class Text(val value: String) : RuleLiteral {
        override val json: String get() = JsonPrimitive(value).toString()

        override fun toString(): String = json
    }

    /** `true` or `false`. */
    public data class Bool(val value: Boolean) : RuleLiteral {
        override val json: String get() = value.toString()

        override fun toString(): String = json
    }

    public companion object {
        internal val JSON_NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")
        internal val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")

        public fun of(value: Long): RuleLiteral = NumberToken(value.toString())

        public fun of(value: Int): RuleLiteral = NumberToken(value.toString())

        public fun of(value: String): RuleLiteral = Text(value)

        public fun of(value: Boolean): RuleLiteral = Bool(value)

        /**
         * The literal for a JSON element, or null when the element is not a number token, string or boolean (null, an
         * object, an array, or an unquoted token that is not valid JSON).
         */
        public fun fromJson(element: JsonElement): RuleLiteral? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive is JsonNull) return null
            val content = primitive.content
            return when {
                primitive.isString -> Text(content)
                content == "true" -> Bool(true)
                content == "false" -> Bool(false)
                JSON_NUMBER.matches(content) -> NumberToken(content)
                else -> null
            }
        }
    }
}

/** The JSON element of a literal; number tokens are written verbatim. */
@OptIn(ExperimentalSerializationApi::class)
public fun RuleLiteral.toJsonElement(): JsonElement = when (this) {
    is RuleLiteral.NumberToken -> JsonUnquotedLiteral(token)
    is RuleLiteral.Text -> JsonPrimitive(value)
    is RuleLiteral.Bool -> JsonPrimitive(value)
}

/** Reads and writes [RuleLiteral] as the raw JSON primitive; only usable with the JSON format. */
internal object RuleLiteralSerializer : KSerializer<RuleLiteral> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.agentle.jitai.dsl.RuleLiteral", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RuleLiteral {
        val input = decoder as? JsonDecoder ?: throw SerializationException("RuleLiteral is JSON-only")
        return RuleLiteral.fromJson(input.decodeJsonElement())
            ?: throw SerializationException("A rule literal must be a JSON number, string or boolean")
    }

    override fun serialize(encoder: Encoder, value: RuleLiteral) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("RuleLiteral is JSON-only")
        output.encodeJsonElement(value.toJsonElement())
    }
}

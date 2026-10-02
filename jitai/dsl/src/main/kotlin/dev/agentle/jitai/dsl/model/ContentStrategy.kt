package dev.agentle.jitai.dsl.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer

/**
 * How the message is produced (R10 §3.3); sealed with discriminator `type`. Placeholders `{{feature_id}}` are allowed
 * in `template` text only (also inside `variants`, the `ai_text` fallback and the `local_media` caption), and each must
 * name a feature of exactly one determining leaf of the rule (E063-E067).
 */
@Serializable
public sealed interface ContentStrategy {
    /** Fixed text without placeholders. */
    @Serializable
    @SerialName("static")
    public data class Static(val title: String, val body: String) : ContentStrategy

    /** Text with `{{feature_id}}` placeholders filled locally at delivery time. */
    @Serializable
    @SerialName("template")
    public data class Template(val title: String, val body: String) : ContentStrategy

    /** 2-8 template pairs chosen by `deliveryCount mod n`. */
    @Serializable
    @SerialName("variants")
    public data class Variants(val items: List<TextPair>, val selection: VariantSelection = VariantSelection.ROTATE) : ContentStrategy

    /** Text generated ahead of time through Sign in with ChatGPT; [fallback] is delivered whenever generation fails. */
    @Serializable
    @SerialName("ai_text")
    public data class AiText(
        val goal: String,
        val tone: Tone,
        @Serializable(with = TemplateFieldSerializer::class) val fallback: Template,
    ) : ContentStrategy

    /** A bundled picture or clip from the app's media catalog with a template caption (IMAGE and VIDEO channels). */
    @Serializable
    @SerialName("local_media")
    public data class LocalMedia(val assetId: String, @Serializable(with = TemplateFieldSerializer::class) val caption: Template) :
        ContentStrategy
}

/** One `variants` item; template placeholders allowed. */
@Serializable
public data class TextPair(val title: String, val body: String)

/** The `type` of a content strategy on the wire. */
public val ContentStrategy.wireType: String
    get() = when (this) {
        is ContentStrategy.Static -> "static"
        is ContentStrategy.Template -> "template"
        is ContentStrategy.Variants -> "variants"
        is ContentStrategy.AiText -> "ai_text"
        is ContentStrategy.LocalMedia -> "local_media"
    }

/**
 * Writes a [ContentStrategy.Template] field with its `"type": "template"` discriminator, as the schema requires. The
 * strategy serializer is looked up lazily (it contains this serializer) through the library's `serializer<T>()`.
 */
internal object TemplateFieldSerializer : KSerializer<ContentStrategy.Template> {
    private val strategy: KSerializer<ContentStrategy> by lazy { serializer<ContentStrategy>() }

    override val descriptor: SerialDescriptor get() = strategy.descriptor

    override fun serialize(encoder: Encoder, value: ContentStrategy.Template) {
        encoder.encodeSerializableValue(strategy, value)
    }

    override fun deserialize(decoder: Decoder): ContentStrategy.Template =
        decoder.decodeSerializableValue(strategy) as? ContentStrategy.Template
            ?: throw SerializationException("expected a template")
}

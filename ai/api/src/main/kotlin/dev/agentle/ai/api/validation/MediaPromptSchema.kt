package dev.agentle.ai.api.validation

import dev.agentle.ai.api.OutputSchema
import kotlinx.serialization.Serializable

/** What a media prompt is for (docs/research/09-media-pipelines.md): every kind is rendered on the device. */
@Serializable
public enum class MediaPromptKind {
    /** Notification title and body. */
    NOTIFICATION_TEXT,

    /** Text spoken with Android TextToSpeech. */
    VOICE_SCRIPT,

    /** One narration sentence per slide of a short recap video (3-6 slides, at most 90 s). */
    VIDEO_RECAP,

    /** Caption and alt text of a local template card. */
    IMAGE_CARD,
}

/** The local card templates (R09 section 6.3). The model only picks one; the app draws it with the user's own data. */
@Serializable
public enum class CardTemplate { QUOTE, SPARKLINE, STAT, STREAK }

@Serializable
public data class MediaCard(val templateId: CardTemplate, val caption: String, val altText: String)

/**
 * The text of one piece of local media (MediaPromptSchema v1). The model writes text and picks a card template; images,
 * voice and video are produced on the device (R09: AI image generation is off in v1). Every property is required and
 * the fields used depend on [kind] ([OutputCodes.MEDIA_KIND_INCONSISTENT]).
 */
@Serializable
public data class MediaPromptOutput(
    val schemaVersion: Int,
    val kind: MediaPromptKind,
    val title: String,
    val body: String?,
    val narration: List<String>,
    val card: MediaCard?,
)

/**
 * MediaPromptSchema v1, a design of this module. Per [MediaPromptKind]: NOTIFICATION_TEXT and VOICE_SCRIPT have a
 * body and nothing else; VIDEO_RECAP has 3-6 narration lines and no body; IMAGE_CARD has a card and nothing else. The
 * narration of a video may hold at most [MAX_NARRATION_WORDS] words, so it fits the 90-second cap of R09 section 3.4.
 * Text generated ahead of delivery (pooled JITAI text) is validated with
 * [OutputValidationContext.forPooledText], which forbids every number (L13).
 */
public object MediaPromptSchema {
    public const val NAME: String = "MediaPromptSchema"
    public const val VERSION: Int = 1
    public const val MAX_BYTES: Int = 8192
    public const val MIN_SLIDES: Int = 3
    public const val MAX_SLIDES: Int = 6

    /**
     * 90 s at about 150 spoken words per minute is 225 words; 200 leaves room for the gaps between slides and slower
     * voices. Design value: the speech rate of TextToSpeech voices is UNVERIFIED.
     */
    public const val MAX_NARRATION_WORDS: Int = 200

    public val TITLE: TextRules = TextRules(maxChars = 60, maxSentences = 1)
    public val NOTIFICATION_BODY: TextRules = TextRules(maxChars = 240, maxSentences = 3)
    public val VOICE_BODY: TextRules = TextRules(maxChars = 400, maxSentences = 5)
    public val NARRATION_LINE: TextRules = TextRules(maxChars = 200, maxSentences = 2)
    public val CAPTION: TextRules = TextRules(maxChars = 120, maxSentences = 2)
    public val ALT_TEXT: TextRules = TextRules(maxChars = 200, maxSentences = 2)

    private val WORD = Regex("\\S+")

    public val ROOT: SchemaNode.ObjectNode = SchemaNode.ObjectNode(
        properties = linkedMapOf(
            "schemaVersion" to SchemaNode.ConstNode(VERSION.toLong(), OutputCodes.UNSUPPORTED_SCHEMA_VERSION),
            "kind" to SchemaNode.StringNode(allowed = MediaPromptKind.entries.map { it.name }),
            "title" to textNode(TITLE),
            "body" to textNode(VOICE_BODY, nullable = true),
            "narration" to SchemaNode.ArrayNode(textNode(NARRATION_LINE), maxItems = MAX_SLIDES),
            "card" to SchemaNode.ObjectNode(
                properties = linkedMapOf(
                    "templateId" to SchemaNode.StringNode(allowed = CardTemplate.entries.map { it.name }),
                    "caption" to textNode(CAPTION),
                    "altText" to textNode(ALT_TEXT),
                ),
                forbidden = setOf("fields", "themeId"),
                nullable = true,
            ),
        ),
        forbidden = setOf("assetId", "themeId"),
    )

    public val SCHEMA: OutputSchema =
        OutputSchema(NAME, VERSION, renderJsonSchema("urn:agentle:media-prompt:v$VERSION", "$NAME v$VERSION", ROOT).toString())

    public val validator: SchemaValidator<MediaPromptOutput> = Validator

    /** Whitespace-separated words of every narration line. */
    public fun narrationWords(narration: List<String>): Int = narration.sumOf { line -> WORD.findAll(line).count() }

    private object Validator : SchemaBackedValidator<MediaPromptOutput>(SCHEMA, ROOT, MediaPromptOutput.serializer(), MAX_BYTES) {
        override fun semanticIssues(value: MediaPromptOutput, context: OutputValidationContext): List<ValidationIssue> {
            val issues = kindIssues(value).map { path ->
                ValidationIssue(OutputCodes.MEDIA_KIND_INCONSISTENT, path, ValidationStage.S6_SEMANTIC)
            }
                .toMutableList()
            if (narrationWords(value.narration) > MAX_NARRATION_WORDS) {
                issues += ValidationIssue(OutputCodes.NARRATION_TOO_LONG, "/narration", ValidationStage.S6_SEMANTIC)
            }
            val fields = buildList {
                add(TextField("/title", value.title, TITLE))
                value.body?.let { body ->
                    add(TextField("/body", body, if (value.kind == MediaPromptKind.VOICE_SCRIPT) VOICE_BODY else NOTIFICATION_BODY))
                }
                value.narration.forEachIndexed { index, line -> add(TextField("/narration/$index", line, NARRATION_LINE)) }
                value.card?.let { card ->
                    add(TextField("/card/caption", card.caption, CAPTION))
                    add(TextField("/card/altText", card.altText, ALT_TEXT))
                }
            }
            return issues + textPolicyIssues(fields, context)
        }

        /** Paths of the fields that do not fit the kind. */
        private fun kindIssues(value: MediaPromptOutput): List<String> = buildList {
            val needsBody = value.kind == MediaPromptKind.NOTIFICATION_TEXT || value.kind == MediaPromptKind.VOICE_SCRIPT
            if (needsBody != (value.body != null)) add("/body")
            val isVideo = value.kind == MediaPromptKind.VIDEO_RECAP
            if (isVideo && value.narration.size !in MIN_SLIDES..MAX_SLIDES) add("/narration")
            if (!isVideo && value.narration.isNotEmpty()) add("/narration")
            if ((value.kind == MediaPromptKind.IMAGE_CARD) != (value.card != null)) add("/card")
        }
    }
}

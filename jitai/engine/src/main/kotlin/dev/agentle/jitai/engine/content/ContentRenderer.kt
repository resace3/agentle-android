package dev.agentle.jitai.engine.content

import dev.agentle.analytics.features.FeatureGroup
import dev.agentle.analytics.features.FeatureScalar
import dev.agentle.analytics.features.FeatureSnapshot
import dev.agentle.analytics.features.FeatureValue
import dev.agentle.analytics.features.RealtimeFeatureCatalog
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.ContentStrategy
import dev.agentle.jitai.dsl.model.DeliveryChannel
import dev.agentle.jitai.dsl.model.JitaiCategory
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.model.SnoozeOption
import dev.agentle.jitai.engine.decision.DecisionKeys
import dev.agentle.jitai.engine.eval.RuleRefs
import dev.agentle.jitai.engine.eval.TraceValue
import dev.agentle.jitai.engine.ports.AiTextConsent
import dev.agentle.jitai.engine.ports.DisplaySettings
import dev.agentle.jitai.engine.ports.NotificationPrivacy
import dev.agentle.jitai.engine.ports.PooledText
import dev.agentle.jitai.engine.response.SnoozePolicies
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * What the [dev.agentle.jitai.engine.ports.DeliveryPort] receives: the texts, media and actions of one delivery. It is
 * serializable so the background team can hand it to a delivery worker and tests can keep golden copies.
 *
 * Two texts (jitai-correctness-18): [title] / [body] are the in-app text (placeholders filled, app labels), shown only
 * inside Agentle; [postedTitle] / [postedBody] are what the posted notification (and a VOICE utterance) may show: the
 * generic text unless the user turned on detailed notifications ([detailed]). Nothing else of this object (name,
 * category, in-app text) may appear in the posted notification. [localOnly] posts with `setLocalOnly(true)`, so the
 * notification is not bridged to a wearable unless the user opted in.
 *
 * @property channel the channel to post on; differs from [decidedChannel] after a downgrade ([downgradeReason]).
 * @property nonce the decision's per-delivery nonce; notification actions must present it (red team oauth-security-12).
 * @property contentRef what was chosen ([ContentRef]); stored in the decision so a retry renders the same text.
 * @property snoozeOptions the snooze actions of the rule's snooze policy (jitai-correctness-14); "Not now" is
 *   [notNowOption] and is always offered.
 */
@Serializable
public data class RenderedIntervention(
    val decisionKey: String,
    val jitaiId: String,
    val jitaiName: String,
    val category: JitaiCategory,
    val channel: DeliveryChannel,
    val decidedChannel: DeliveryChannel,
    val title: String,
    val body: String,
    val postedTitle: String,
    val postedBody: String,
    val detailed: Boolean,
    val localOnly: Boolean,
    val assetId: String?,
    val contentRef: ContentRef,
    val nonce: String,
    val timeoutMinutes: Int?,
    val snoozeOptions: List<SnoozeOption>,
    val downgradeReason: String? = null,
) {
    /** `notify(tag = decisionKey, ...)` (R10 §8.5). */
    public val notificationTag: String get() = decisionKey

    /** VOICE speaks after posting a silent companion notification (R10 §8.5 step 4). */
    public val speak: Boolean get() = channel == DeliveryChannel.VOICE

    /** What VOICE speaks: the posted text, so a generic notification is never read out in detail. */
    public val spokenText: String get() = postedBody

    /** "Not now": snoozed until the active window ends (jitai-correctness-14). */
    public val notNowOption: SnoozeOption get() = SnoozePolicies.NOT_NOW

    /** This delivery as a plain notification ([reason] = `TTS_UNAVAILABLE` or `MEDIA_UNAVAILABLE`). */
    public fun downgraded(reason: String): RenderedIntervention =
        copy(channel = DeliveryChannel.NOTIFICATION, assetId = null, downgradeReason = reason)
}

/** Which content a decision used (R10 §8.3 `contentRef`), stored as [encoded]. */
@Serializable(with = ContentRefSerializer::class)
public sealed interface ContentRef {
    public val encoded: String

    public data object StaticText : ContentRef {
        override val encoded: String get() = "static"
    }

    public data object TemplateText : ContentRef {
        override val encoded: String get() = "template"
    }

    public data class Variant(val index: Int) : ContentRef {
        override val encoded: String get() = "variant:$index"
    }

    public data class AiPooled(val itemId: String) : ContentRef {
        override val encoded: String get() = "ai:$itemId"
    }

    public data object AiFallback : ContentRef {
        override val encoded: String get() = "ai-fallback"
    }

    public data class Media(val assetId: String) : ContentRef {
        override val encoded: String get() = "media:$assetId"
    }

    public companion object {
        /** The ref for [encoded], or null when it is not one of the forms above. */
        public fun parse(encoded: String): ContentRef? = when {
            encoded == "static" -> StaticText
            encoded == "template" -> TemplateText
            encoded == "ai-fallback" -> AiFallback
            encoded.startsWith("variant:") -> encoded.removePrefix("variant:").toIntOrNull()?.takeIf { it >= 0 }?.let(::Variant)
            encoded.startsWith("ai:") -> encoded.removePrefix("ai:").takeIf { it.isNotEmpty() }?.let(::AiPooled)
            encoded.startsWith("media:") -> encoded.removePrefix("media:").takeIf { it.isNotEmpty() }?.let(::Media)
            else -> null
        }
    }
}

/** [ContentRef] as its [ContentRef.encoded] string. */
public object ContentRefSerializer : KSerializer<ContentRef> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.agentle.jitai.engine.ContentRef", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ContentRef): Unit = encoder.encodeString(value.encoded)

    override fun deserialize(decoder: Decoder): ContentRef =
        ContentRef.parse(decoder.decodeString()) ?: throw SerializationException("unknown content ref")
}

/** Formats placeholder values (R10 §3.3): locale-aware integers, `HH:mm` or `h:mm a` per the device 24-hour setting. */
public fun interface PlaceholderFormatter {
    /** Text for [value] of feature [featureId]; [appLabels] maps package names to display names. */
    public fun format(featureId: String, value: FeatureValue?, appLabels: Map<String, String>): String
}

/** The default [PlaceholderFormatter]. A value that is missing renders as [MISSING_VALUE], never as zero. */
public class DefaultPlaceholderFormatter(display: DisplaySettings = DisplaySettings()) : PlaceholderFormatter {
    private val locale: Locale = Locale.forLanguageTag(display.localeTag)
    private val use24Hour = display.use24HourClock

    override fun format(featureId: String, value: FeatureValue?, appLabels: Map<String, String>): String {
        val scalar = when (value) {
            is FeatureValue.Known -> value.value
            is FeatureValue.Stale -> value.lastValue
            is FeatureValue.Missing, null -> return MISSING_VALUE
        }
        return when (scalar) {
            is FeatureScalar.IntValue -> NumberFormat.getIntegerInstance(locale).format(scalar.value)
            is FeatureScalar.BoolValue -> if (scalar.value) "yes" else "no"
            is FeatureScalar.EnumValue -> scalar.value.lowercase(Locale.ROOT).replace('_', ' ')
            is FeatureScalar.DayOfWeekValue -> java.time.DayOfWeek.valueOf(scalar.value.name).getDisplayName(TextStyle.FULL, locale)
            is FeatureScalar.LocalTimeValue -> clock(scalar.minuteOfDay)
            is FeatureScalar.NightTimeValue -> clock(scalar.minuteOfDay)
            is FeatureScalar.PackageValue -> appLabels[scalar.packageName] ?: scalar.packageName
            FeatureScalar.NoPackage, FeatureScalar.Never -> MISSING_VALUE
        }
    }

    private fun clock(minuteOfDay: Int): String {
        val time = java.time.LocalTime.of(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR)
        return time.format(DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale))
    }

    public companion object {
        public const val MISSING_VALUE: String = "—"
        private const val MINUTES_PER_HOUR = 60
    }
}

/**
 * Hashes binding pooled AI text to the context it was written for (red team privacy-ai-01).
 *
 * [contextHash] covers the snapshot values (state and scalar, not `asOf`) of the rule's non-clock dependencies, sorted
 * by reference key, so the same situation hashes the same at a different minute. UNVERIFIED design choice: the red-team
 * note says "snapshot hash" without defining its scope; the pool producer must stamp items with this function.
 */
public object SnapshotHashes {
    public fun contextHash(definition: JitaiDefinition, snapshot: FeatureSnapshot): String {
        val keys = RuleRefs.of(definition)
            .filter { RealtimeFeatureCatalog[it.featureId]?.group != FeatureGroup.TIME }
            .map { it.key }
            .sorted()
        val text = keys.joinToString("\n") { key -> "$key=${describe(snapshot.values[key])}" }
        return DecisionKeys.sha256Hex(text)
    }

    private fun describe(value: FeatureValue?): String = when (value) {
        is FeatureValue.Known -> "known:${TraceValue.display(value.value)}"
        is FeatureValue.Stale -> "stale:${TraceValue.display(value.lastValue)}:${value.reason}"
        is FeatureValue.Missing -> "missing:${value.reason}"
        null -> "unresolved"
    }
}

/**
 * Chooses and renders a delivery's content (R10 §3.3, §8.5 step 3). Choice happens once, before the claim, and is stored
 * as a [ContentRef]; rendering is a pure function of the definition, the decision's stored snapshot and that ref.
 */
public class ContentRenderer(private val formatter: PlaceholderFormatter = DefaultPlaceholderFormatter()) {
    /**
     * Picks the content for a decision: the variant by `deliveryCount mod n` ([previousDeliveries] = counted deliveries of
     * this JITAI before this one), the oldest pooled AI text that passes [usablePooledText], else the AI fallback.
     */
    public fun choose(
        definition: JitaiDefinition,
        previousDeliveries: Long,
        pool: List<PooledText>,
        decisionHash: String?,
        now: Instant,
        consent: AiTextConsent,
    ): ContentRef = when (val content = definition.content) {
        is ContentStrategy.Static -> ContentRef.StaticText

        is ContentStrategy.Template, null -> ContentRef.TemplateText

        is ContentStrategy.Variants ->
            ContentRef.Variant(if (content.items.isEmpty()) 0 else Math.floorMod(previousDeliveries, content.items.size.toLong()).toInt())

        is ContentStrategy.AiText -> {
            val contentHash = RuleCodec.contentHash(definition)
            pool
                .filter { usablePooledText(it, definition.id, contentHash, decisionHash, now, consent) }
                .minWithOrNull(compareBy<PooledText>({ it.createdAt }, { it.id }))
                ?.let { ContentRef.AiPooled(it.id) }
                ?: ContentRef.AiFallback
        }

        is ContentStrategy.LocalMedia -> ContentRef.Media(content.assetId)
    }

    /**
     * Renders [ref] for a decision. [RenderInput.pooled] is the pool item an [ContentRef.AiPooled] names; when it is gone or
     * no longer passes [usablePooledText], the template fallback is rendered instead (never an unchecked AI text).
     */
    public fun render(input: RenderInput): RenderedIntervention {
        val definition = input.definition
        val (title, body, ref) = text(input)
        val detailed = input.privacy.detailedNotifications
        return RenderedIntervention(
            decisionKey = input.decisionKey,
            jitaiId = definition.id,
            jitaiName = definition.name,
            category = definition.category,
            channel = input.channel,
            decidedChannel = input.channel,
            title = title,
            body = body,
            postedTitle = if (detailed) title else input.display.genericTitle,
            postedBody = if (detailed) body else input.display.genericBody,
            detailed = detailed,
            localOnly = !input.privacy.bridgeToWearables,
            assetId = (definition.content as? ContentStrategy.LocalMedia)?.assetId,
            contentRef = ref,
            nonce = input.nonce,
            timeoutMinutes = definition.delivery.notificationTimeoutMinutes,
            snoozeOptions = SnoozePolicies.of(definition).options,
        )
    }

    private fun text(input: RenderInput): Triple<String, String, ContentRef> {
        val fill = { text: String -> fill(text, input.definition, input.snapshot) }
        return when (val content = input.definition.content) {
            is ContentStrategy.Static -> Triple(content.title, content.body, ContentRef.StaticText)

            is ContentStrategy.Template -> Triple(fill(content.title), fill(content.body), ContentRef.TemplateText)

            is ContentStrategy.Variants -> {
                val index = (input.ref as? ContentRef.Variant)?.index?.takeIf { it in content.items.indices } ?: 0
                val item = content.items.getOrNull(index)
                Triple(fill(item?.title.orEmpty()), fill(item?.body.orEmpty()), ContentRef.Variant(index))
            }

            is ContentStrategy.AiText -> {
                val contentHash = RuleCodec.contentHash(input.definition)
                val pooled = input.pooled?.takeIf {
                    input.ref is ContentRef.AiPooled && it.id == input.ref.itemId &&
                        usablePooledText(it, input.definition.id, contentHash, input.snapshotHash, input.now, input.consent)
                }
                if (pooled != null) {
                    Triple(pooled.title, pooled.body, ContentRef.AiPooled(pooled.id))
                } else {
                    Triple(fill(content.fallback.title), fill(content.fallback.body), ContentRef.AiFallback)
                }
            }

            is ContentStrategy.LocalMedia ->
                Triple(fill(content.caption.title), fill(content.caption.body), ContentRef.Media(content.assetId))

            null -> Triple(input.definition.name, "", ContentRef.TemplateText)
        }
    }

    /** Replaces every `{{feature_id}}` with the formatted snapshot value of the leaf it names (R10 §3.3). */
    public fun fill(text: String, definition: JitaiDefinition, snapshot: FeatureSnapshot?): String = PLACEHOLDER.replace(text) { match ->
        val featureId = match.groupValues[1]
        val leaf = RuleRefs.placeholderLeaf(definition, featureId)
        val value = if (leaf == null || snapshot == null) null else snapshot[leaf.ref]
        formatter.format(featureId, value, definition.provenance?.appLabels.orEmpty())
    }

    /** Input of [render]; [display] supplies the generic posted text, [privacy] the posting rules (jitai-correctness-18). */
    public data class RenderInput(
        val definition: JitaiDefinition,
        val decisionKey: String,
        val channel: DeliveryChannel,
        val snapshot: FeatureSnapshot?,
        val snapshotHash: String?,
        val ref: ContentRef,
        val pooled: PooledText?,
        val nonce: String,
        val now: Instant,
        val consent: AiTextConsent,
        val display: DisplaySettings = DisplaySettings(),
        val privacy: NotificationPrivacy = NotificationPrivacy(),
    )

    public companion object {
        private val PLACEHOLDER = Regex("\\{\\{([a-z][a-z0-9_]*)\\}\\}")

        /** Pooled AI text older than this is never delivered (red team privacy-ai-01, jitai-correctness-17). */
        public val MAX_POOLED_AGE: Duration = 24.hours

        /**
         * The delivery-time checks of red team privacy-ai-01 and jitai-correctness-17: the item belongs to the JITAI and to
         * its current content ([contentHash], `RuleCodec.contentHash`), is at most 24 h old, was made under the current
         * consent version, used only categories still allowed, and was written for the decision's context
         * ([SnapshotHashes.contextHash] equal to [decisionHash]).
         */
        public fun usablePooledText(
            item: PooledText,
            jitaiId: String,
            contentHash: String,
            decisionHash: String?,
            now: Instant,
            consent: AiTextConsent,
        ): Boolean = item.jitaiId == jitaiId &&
            item.contentHash == contentHash &&
            now - item.createdAt <= MAX_POOLED_AGE &&
            item.consentVersion == consent.version &&
            consent.allowedCategories.containsAll(item.categories) &&
            decisionHash != null && item.snapshotHash == decisionHash
    }
}

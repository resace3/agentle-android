package dev.agentle.jitai.dsl.rule

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * `args` objects: written with sorted keys (canonical form, R10 §3.1) and read with `null` values dropped (a proposal's
 * full args object reads like the sparse stored form, R10 §11.4 step 3).
 */
public object ArgsSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer().nullable)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        encoder.encodeSerializableValue(delegate, value.toSortedMap())
    }

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val raw = decoder.decodeSerializableValue(delegate)
        val result = sortedMapOf<String, String>()
        raw.forEach { (key, value) -> if (value != null) result[key] = value }
        return result
    }
}

/**
 * Instants in the canonical form `yyyy-MM-ddTHH:mm:ssZ` (UTC, whole seconds, R10 §3.1). Sub-second parts are dropped on
 * write; anything else is rejected on read.
 */
public object UtcInstantSerializer : KSerializer<Instant> {
    private val PATTERN = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.agentle.jitai.dsl.UtcInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(format(value))
    }

    override fun deserialize(decoder: Decoder): Instant =
        parse(decoder.decodeString()) ?: throw SerializationException("instant is not in the form yyyy-MM-ddTHH:mm:ssZ")

    /** Canonical text of [value], truncated to whole seconds. */
    public fun format(value: Instant): String = Instant.fromEpochSeconds(value.epochSeconds).toString()

    /** The instant for canonical [text], or null when [text] is not exactly `yyyy-MM-ddTHH:mm:ssZ` or not a real date. */
    public fun parse(text: String): Instant? {
        if (!PATTERN.matches(text)) return null
        val parsed = try {
            Instant.parse(text)
        } catch (ignored: IllegalArgumentException) {
            return null
        }
        return parsed.takeIf { format(it) == text }
    }
}

/** 24-hour `HH:mm` local times (R10 §4.4, regex `^([01][0-9]|2[0-3]):[0-5][0-9]$`). */
public object ClockTime {
    private val PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    public const val MINUTES_PER_DAY: Int = 1440

    public fun isValid(text: String): Boolean = PATTERN.matches(text)

    /** Minute of day 0..1439, or null when [text] is not a valid `HH:mm`. */
    public fun minuteOfDay(text: String): Int? {
        if (!isValid(text)) return null
        return text.substring(0, 2).toInt() * 60 + text.substring(3, 5).toInt()
    }

    /** `HH:mm` for a minute of day. */
    public fun format(minuteOfDay: Int): String {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minute of day out of range" }
        return "%02d:%02d".format(java.util.Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)
    }
}

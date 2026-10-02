package dev.agentle.connectors.googlehealth

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant

/**
 * Lenient reading of Google Health JSON (docs/research/05 §4.1): int64 as a string or a number, `"<n>s"` durations,
 * RFC 3339 timestamps with optional fractions, civil dates with omitted zero members, string enums. A missing or
 * mistyped field reads as null; nothing here throws.
 */
internal object GhJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val DURATION = Regex("""^(-?\d+)(\.\d+)?s$""")
    private const val MAX_OFFSET_SECONDS = 18 * 3600

    /** The body as a JSON object, or null when it is not one (`null`, an array, truncated text). */
    fun parseObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonObject.hasValue(key: String): Boolean = this[key].let { it != null && it !is JsonNull }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** An int64 sent as a JSON string or a number (R4 point D); null when absent, fractional or not a number. */
    fun JsonObject.long(key: String): Long? = primitive(key)?.content?.let { text ->
        text.toLongOrNull()
            ?: text.toDoubleOrNull()?.takeIf { it.isFinite() && it == Math.rint(it) && kotlin.math.abs(it) < 9.0e15 }?.toLong()
    }

    /** A double sent as a number (or, leniently, a numeric string); null when absent or not finite. */
    fun JsonObject.double(key: String): Double? = primitive(key)?.let { p ->
        (p.doubleOrNull ?: p.content.toDoubleOrNull())?.takeIf { it.isFinite() }
    }

    fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    fun JsonObject.instant(key: String): Instant? = string(key)?.let(::parseInstant)

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    /** RFC 3339 with `Z` or an offset; impossible dates (`2026-09-31T25:00:00Z`, R5j) give null. */
    fun parseInstant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** `"-14400s"`, `"900s"`, `"0.5s"` as seconds; null when malformed. */
    fun seconds(text: String?): Double? {
        val match = DURATION.matchEntire(text?.trim() ?: return null) ?: return null
        val whole = match.groupValues[1].toDoubleOrNull() ?: return null
        val fraction = match.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.0
        return if (whole < 0) whole - fraction else whole + fraction
    }

    /** A UTC offset duration in whole seconds; null when malformed or beyond ±18 h. */
    fun offsetSeconds(text: String?): Int? = seconds(text)?.toInt()?.takeIf { kotlin.math.abs(it) <= MAX_OFFSET_SECONDS }

    /** A duration in whole milliseconds; null when malformed or negative. */
    fun durationMs(text: String?): Long? = seconds(text)?.takeIf { it >= 0 }?.let { (it * MILLIS).toLong() }

    /** `{"year", "month", "day"}` as a date; null when incomplete or impossible. */
    @Suppress("ReturnCount")
    fun date(obj: JsonObject?): LocalDate? {
        if (obj == null) return null
        val year = obj.long("year")?.toInt() ?: return null
        val month = obj.long("month")?.toInt() ?: return null
        val day = obj.long("day")?.toInt() ?: return null
        return try {
            LocalDate(year, month, day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The date of a `CivilDateTime` (`{"date": {...}, "time": {...}}`); `time` is ignored (midnight when omitted). */
    fun civilDate(obj: JsonObject?): LocalDate? = date(obj?.obj("date"))

    /** A filter literal in physical time: RFC 3339 in UTC with `Z`, whole seconds. */
    fun physicalLiteral(instant: Instant): String = civilLiteral(instant, TimeZone.UTC) + "Z"

    /** A civil filter literal `YYYY-MM-DDTHH:mm:ss` of [instant] seen in [zone]. */
    fun civilLiteral(instant: Instant, zone: TimeZone): String = format(instant.toLocalDateTime(zone))

    private fun format(t: LocalDateTime): String = buildString {
        append(t.year.toString().padStart(4, '0')).append('-')
        append(t.month.ordinal.plus(1).toString().padStart(2, '0')).append('-')
        append(t.day.toString().padStart(2, '0')).append('T')
        append(t.hour.toString().padStart(2, '0')).append(':')
        append(t.minute.toString().padStart(2, '0')).append(':')
        append(t.second.toString().padStart(2, '0'))
    }

    /** The JSON text of [element], compact. */
    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    private const val MILLIS = 1000.0
}

package dev.agentle.ai.api.validation

import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.DataItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The numbers an AI text may use (privacy-ai-06, R10 section 14.9): every number in a model's text must appear in the
 * request it answered or in the app's template text. A number in the text matches when any reading of it (thousands
 * separators, decimal comma) equals a known number. A time of day `HH:mm` contributes its hour, minute and 12-hour
 * hour, and a measurement its roundings (and, for minutes, its hours). Signs are ignored: text writes "15 fewer", not
 * "-15".
 */
public class NumberProvenance private constructor(private val numbers: Set<BigDecimal>) {
    public operator fun plus(other: NumberProvenance): NumberProvenance = NumberProvenance(numbers + other.numbers)

    /** True if every number written in [text] is known. */
    public fun covers(text: String): Boolean = tokensIn(text).all { token -> readings(token).any { it in numbers } }

    public val size: Int get() = numbers.size

    public companion object {
        private val NUMBER_TOKEN = Regex("(?<![\\p{L}\\p{N}])\\p{Nd}+(?:[.,]\\p{Nd}+)*")
        private val THOUSANDS_COMMA = Regex("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
        private val DECIMAL_COMMA = Regex("^\\d+,\\d{1,2}$")
        private val THOUSANDS_DOT = Regex("^\\d{1,3}(\\.\\d{3})+$")
        private val TIME = Regex("^(\\d{1,2}):(\\d{2})$")
        private const val HOURS_HALF_DAY = 12
        private const val HOUR_SCALE = 6
        private val SIXTY = BigDecimal(60)

        /** The unit code of minute quantities ([DataItem.Quantity.unit]). */
        public const val MINUTES_UNIT: String = "min"

        public val EMPTY: NumberProvenance = NumberProvenance(emptySet())

        /** Exactly [values] (their absolute values). */
        public fun of(values: Iterable<Number>): NumberProvenance =
            NumberProvenance(values.mapNotNullTo(HashSet()) { value -> value.toString().toBigDecimalOrNull()?.abs()?.canonical() })

        /**
         * [values] plus their roundings to a whole number and to one decimal, so "about 7.5" may describe 7.46. With
         * [minutes], also the hours of each value (whole, rounded, one decimal) and the minutes left over, so 450 minutes
         * may be written "7.5 hours" or "7 h 30 min".
         */
        public fun ofMeasurements(values: Iterable<Number>, minutes: Boolean = false): NumberProvenance = NumberProvenance(
            values.flatMapTo(HashSet()) { value -> value.toString().toBigDecimalOrNull()?.let { derived(it.abs(), minutes) }.orEmpty() },
        )

        /** Each `HH:mm` time of [times] as its hour, minute and 12-hour hour; other strings as written numbers. */
        public fun ofTimes(times: Iterable<String>): NumberProvenance = fromTexts(times.flatMap(::timeTexts))

        /** Every number written in [texts] (app template text, evidence sentences). */
        public fun fromTexts(texts: Iterable<String>): NumberProvenance =
            NumberProvenance(texts.flatMapTo(HashSet()) { text -> tokensIn(text).flatMap(::readings) })

        /** The numbers of every value the envelope sends: data items, the user's own text and the user's request. */
        public fun fromEnvelope(envelope: AiRequestEnvelope): NumberProvenance {
            val values = mutableListOf<Number>()
            val minuteValues = mutableListOf<Number>()
            val texts = mutableListOf<String>()
            envelope.blocks.flatMap { it.items }.forEach { contextItem ->
                when (val item = contextItem.item) {
                    is DataItem.Quantity -> if (item.unit == MINUTES_UNIT) minuteValues += item.value else values += item.value

                    is DataItem.TimeOfDay -> texts += timeTexts(item.time)

                    is DataItem.Code -> Unit

                    is DataItem.Text -> texts += item.text

                    is DataItem.AppUsage -> {
                        minuteValues += item.minutes
                        item.opens?.let { values += it }
                    }

                    is DataItem.Event -> {
                        item.values.forEach { (key, value) -> if (isMinutesKey(key)) minuteValues += value else values += value }
                        texts += timeTexts(item.start.substringAfter('T')) + item.start
                        item.end?.let { end -> texts += timeTexts(end.substringAfter('T')) + end }
                    }
                }
            }
            envelope.userText?.let { texts += it.raw }
            return ofMeasurements(values) + ofMeasurements(minuteValues, minutes = true) + fromTexts(texts)
        }

        private fun isMinutesKey(key: String): Boolean = key.contains("minute", ignoreCase = true) || key.endsWith("_min")

        private fun derived(value: BigDecimal, minutes: Boolean): List<BigDecimal> {
            val out = mutableListOf(value, value.setScale(0, RoundingMode.HALF_UP), value.setScale(1, RoundingMode.HALF_UP))
            if (minutes) {
                val whole = value.divideToIntegralValue(SIXTY)
                val rest = value.subtract(whole.multiply(SIXTY))
                val hours = value.divide(SIXTY, HOUR_SCALE, RoundingMode.HALF_UP)
                out += listOf(whole, rest, rest.setScale(0, RoundingMode.HALF_UP))
                out += listOf(hours.setScale(1, RoundingMode.HALF_UP), hours.setScale(0, RoundingMode.HALF_UP))
            }
            return out.map { it.canonical() }
        }

        private fun timeTexts(time: String): List<String> {
            val match = TIME.matchEntire(time) ?: return listOf(time)
            val hour = match.groupValues[1].toInt()
            val twelve = if (hour % HOURS_HALF_DAY == 0) HOURS_HALF_DAY else hour % HOURS_HALF_DAY
            return listOf(time, hour.toString(), match.groupValues[2], twelve.toString())
        }

        internal fun tokensIn(text: String): List<String> = NUMBER_TOKEN.findAll(text).map { it.value }.toList()

        /** Every plausible value of one written number. */
        internal fun readings(token: String): Set<BigDecimal> {
            val ascii = token.map { char -> if (Character.isDigit(char)) Character.forDigit(Character.digit(char, 10), 10) else char }
                .joinToString("")
            val out = HashSet<BigDecimal>()
            fun add(text: String) {
                text.toBigDecimalOrNull()?.let { out += it.canonical() }
            }
            when {
                THOUSANDS_COMMA.matches(ascii) -> add(ascii.replace(",", ""))
                DECIMAL_COMMA.matches(ascii) -> add(ascii.replace(',', '.'))
                ',' in ascii -> ascii.split(',').forEach(::add)
            }
            if (THOUSANDS_DOT.matches(ascii)) add(ascii.replace(".", ""))
            if (',' !in ascii) add(ascii)
            return out
        }

        private fun BigDecimal.canonical(): BigDecimal = stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else it }
    }
}

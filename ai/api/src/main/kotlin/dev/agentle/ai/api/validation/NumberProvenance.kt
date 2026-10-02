package dev.agentle.ai.api.validation

import dev.agentle.ai.api.AiRequestEnvelope
import dev.agentle.ai.api.DataItem
import java.math.BigDecimal

/**
 * The numbers an AI text may use (privacy-ai-06, R10 section 14.9): every number in a model's text must appear in the
 * request it answered or in the app's template text. A number in the text matches when any reading of it (thousands
 * separators, decimal comma) equals a known number; a time of day `HH:mm` contributes its hour, minute and 12-hour hour.
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

        public val EMPTY: NumberProvenance = NumberProvenance(emptySet())

        public fun of(values: Iterable<Number>): NumberProvenance =
            NumberProvenance(values.mapNotNullTo(HashSet()) { value -> value.toString().toBigDecimalOrNull()?.canonical() })

        /** Every number written in [texts] (app template text, evidence sentences). */
        public fun fromTexts(texts: Iterable<String>): NumberProvenance =
            NumberProvenance(texts.flatMapTo(HashSet()) { text -> tokensIn(text).flatMap(::readings) })

        /** The numbers of every value the envelope sends: data items, the user's own text and the user's request. */
        public fun fromEnvelope(envelope: AiRequestEnvelope): NumberProvenance {
            val values = mutableListOf<Number>()
            val texts = mutableListOf<String>()
            envelope.blocks.flatMap { it.items }.forEach { contextItem ->
                when (val item = contextItem.item) {
                    is DataItem.Quantity -> values += item.value

                    is DataItem.TimeOfDay -> texts += timeTexts(item.time)

                    is DataItem.Code -> Unit

                    is DataItem.Text -> texts += item.text

                    is DataItem.AppUsage -> {
                        values += item.minutes
                        item.opens?.let { values += it }
                    }

                    is DataItem.Event -> {
                        values += item.values.values
                        texts += timeTexts(item.start.substringAfter('T')) + item.start
                        item.end?.let { end -> texts += timeTexts(end.substringAfter('T')) + end }
                    }
                }
            }
            envelope.userText?.let { texts += it.raw }
            return of(values) + fromTexts(texts)
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

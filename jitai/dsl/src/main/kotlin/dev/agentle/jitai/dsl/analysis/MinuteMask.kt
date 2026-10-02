package dev.agentle.jitai.dsl.analysis

import dev.agentle.jitai.dsl.rule.ClockTime

/**
 * An immutable set of minutes of the day (0..1439), the 1,440-bit mask of R10 §4.8 item 2. Used to intersect
 * `local_time_in` nodes, `local_time` leaves, the active window and quiet hours.
 */
public class MinuteMask private constructor(private val words: LongArray) {
    /** True if no minute is in the set. */
    public val isEmpty: Boolean get() = words.all { it == 0L }

    /** True if every minute of the day is in the set. */
    public val isFull: Boolean get() = cardinality == ClockTime.MINUTES_PER_DAY

    /** Number of minutes in the set. */
    public val cardinality: Int get() = words.sumOf { java.lang.Long.bitCount(it) }

    public operator fun contains(minute: Int): Boolean =
        minute in 0 until ClockTime.MINUTES_PER_DAY && (words[minute ushr WORD_SHIFT] and (1L shl (minute and WORD_MASK))) != 0L

    public infix fun and(other: MinuteMask): MinuteMask = MinuteMask(LongArray(WORDS) { words[it] and other.words[it] })

    public infix fun or(other: MinuteMask): MinuteMask = MinuteMask(LongArray(WORDS) { words[it] or other.words[it] })

    /** The complement within the day. */
    public operator fun not(): MinuteMask = MinuteMask(LongArray(WORDS) { words[it].inv() }.also(::clearTail))

    /** True if every minute of this set is also in [other]. */
    public fun isSubsetOf(other: MinuteMask): Boolean = words.indices.all { (words[it] and other.words[it].inv()) == 0L }

    /** The first minute in the set, or null when empty. */
    public fun first(): Int? {
        for (i in words.indices) {
            if (words[i] != 0L) return (i shl WORD_SHIFT) + java.lang.Long.numberOfTrailingZeros(words[i])
        }
        return null
    }

    override fun equals(other: Any?): Boolean = other is MinuteMask && words.contentEquals(other.words)

    override fun hashCode(): Int = words.contentHashCode()

    override fun toString(): String = "MinuteMask(${cardinality} min)"

    public companion object {
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63
        private const val WORDS = (ClockTime.MINUTES_PER_DAY + WORD_MASK) / 64

        /** No minute. */
        public val NONE: MinuteMask = MinuteMask(LongArray(WORDS))

        /** Every minute of the day. */
        public val ALL: MinuteMask = !NONE

        /** The single minute [minute]. */
        public fun of(minute: Int): MinuteMask = range(minute, minute)

        /** Minutes [first]..[last] inclusive, `first <= last`; an empty set when `first > last`. */
        public fun range(first: Int, last: Int): MinuteMask {
            val words = LongArray(WORDS)
            for (m in maxOf(first, 0)..minOf(last, ClockTime.MINUTES_PER_DAY - 1)) {
                words[m ushr WORD_SHIFT] = words[m ushr WORD_SHIFT] or (1L shl (m and WORD_MASK))
            }
            return MinuteMask(words)
        }

        /**
         * The half-open window `[start, end)` of minutes of day (R10 §10.3): same-day when `start < end`, crossing
         * midnight when `start > end`, empty when `start == end` (an invalid window, E025).
         */
        public fun window(start: Int, end: Int): MinuteMask = when {
            start < end -> range(start, end - 1)
            start > end -> range(start, ClockTime.MINUTES_PER_DAY - 1) or range(0, end - 1)
            else -> NONE
        }

        /** [window] for `HH:mm` strings, or null when either is not a valid time. */
        public fun window(start: String, end: String): MinuteMask? {
            val s = ClockTime.minuteOfDay(start) ?: return null
            val e = ClockTime.minuteOfDay(end) ?: return null
            return window(s, e)
        }

        private fun clearTail(words: LongArray) {
            val used = ClockTime.MINUTES_PER_DAY - (WORDS - 1) * 64
            words[WORDS - 1] = words[WORDS - 1] and ((1L shl used) - 1)
        }
    }
}

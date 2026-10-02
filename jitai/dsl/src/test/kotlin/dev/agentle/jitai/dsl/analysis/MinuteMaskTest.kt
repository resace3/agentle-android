package dev.agentle.jitai.dsl.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The 1,440-bit minute mask of R10 §4.8 item 2 and the half-open windows of R10 §10.3. */
class MinuteMaskTest {
    @Test
    fun `NONE, ALL, single minutes and clipped ranges`() {
        assertThat(MinuteMask.NONE.isEmpty).isTrue()
        assertThat(MinuteMask.NONE.first()).isNull()
        assertThat(MinuteMask.ALL.isFull).isTrue()
        assertThat(MinuteMask.ALL.cardinality).isEqualTo(1440)
        assertThat(MinuteMask.of(1439).first()).isEqualTo(1439)
        assertThat(MinuteMask.of(5).isFull).isFalse()
        assertThat(MinuteMask.range(-5, 5).cardinality).isEqualTo(6)
        assertThat(MinuteMask.range(1430, 2000).cardinality).isEqualTo(10)
        assertThat(MinuteMask.range(10, 9).isEmpty).isTrue()
    }

    @Test
    fun `contains is false outside the day`() {
        assertThat(-1 in MinuteMask.ALL).isFalse()
        assertThat(1440 in MinuteMask.ALL).isFalse()
        assertThat(0 in MinuteMask.ALL).isTrue()
        assertThat(1439 in MinuteMask.ALL).isTrue()
        assertThat(64 in MinuteMask.of(64)).isTrue()
        assertThat(63 in MinuteMask.of(64)).isFalse()
    }

    @ParameterizedTest(name = "{0}-{1}")
    @CsvSource("22:00,02:00,240,0", "08:00,09:30,90,480", "00:00,00:00,0,", "23:59,00:00,1,1439", "00:00,23:59,1439,0")
    fun `windows are half-open and cross midnight when the end is earlier`(start: String, end: String, minutes: Int, first: Int?) {
        val mask = checkNotNull(MinuteMask.window(start, end))

        assertThat(mask.cardinality).isEqualTo(minutes)
        assertThat(mask.first()).isEqualTo(first)
    }

    @Test
    fun `the end minute of a window is outside it (R10 12_M M9)`() {
        val s1 = checkNotNull(MinuteMask.window("22:00", "23:00"))
        val night = checkNotNull(MinuteMask.window("00:00", "09:00"))

        assertThat(22 * 60 in s1).isTrue()
        assertThat(22 * 60 + 30 in s1).isTrue()
        assertThat(23 * 60 - 1 in s1).isTrue()
        assertThat(23 * 60 in s1).isFalse()
        assertThat(8 * 60 + 59 in night).isTrue()
        assertThat(9 * 60 in night).isFalse()
    }

    @Test
    fun `invalid times give no window`() {
        assertThat(MinuteMask.window("24:00", "01:00")).isNull()
        assertThat(MinuteMask.window("01:00", "1:00")).isNull()
    }

    @Test
    fun `set algebra`() {
        val a = MinuteMask.range(0, 99)
        val b = MinuteMask.range(50, 149)

        assertThat((a and b).cardinality).isEqualTo(50)
        assertThat((a or b).cardinality).isEqualTo(150)
        assertThat((!a).cardinality).isEqualTo(1340)
        assertThat((!a).first()).isEqualTo(100)
        assertThat(!!a).isEqualTo(a)
        assertThat(!MinuteMask.ALL).isEqualTo(MinuteMask.NONE)
        assertThat(a.isSubsetOf(a or b)).isTrue()
        assertThat((a or b).isSubsetOf(a)).isFalse()
        assertThat(MinuteMask.NONE.isSubsetOf(a)).isTrue()
    }

    @Test
    fun `equality, hash code and text`() {
        val a = MinuteMask.range(0, 99)

        assertThat(a).isEqualTo(MinuteMask.range(0, 99))
        assertThat(a.hashCode()).isEqualTo(MinuteMask.range(0, 99).hashCode())
        assertThat(a).isNotEqualTo(MinuteMask.range(0, 98))
        assertThat(a.equals("MinuteMask(100 min)")).isFalse()
        assertThat(a.toString()).isEqualTo("MinuteMask(100 min)")
    }
}

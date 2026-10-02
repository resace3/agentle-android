package dev.agentle.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NumberFormatterTest {
    @Test
    fun `integers use the grouping of the locale`() {
        assertThat(NumberFormatter(Locale.US).integer(1_234_567)).isEqualTo("1,234,567")
        assertThat(NumberFormatter(Locale.GERMANY).integer(1_234_567)).isEqualTo("1.234.567")
    }

    @Test
    fun `french grouping uses a space, never a comma or a dot`() {
        val text = NumberFormatter(Locale.FRANCE).integer(1_234_567)
        assertThat(text.filter { it.isDigit() }).isEqualTo("1234567")
        assertThat(text).doesNotContain(",")
        assertThat(text).doesNotContain(".")
        assertThat(text.length).isEqualTo(9)
    }

    @Test
    fun `decimals drop trailing zeros and use the locale separator`() {
        assertThat(NumberFormatter(Locale.US).decimal(7.25, maxFractionDigits = 1)).isEqualTo("7.2")
        assertThat(NumberFormatter(Locale.US).decimal(7.0)).isEqualTo("7")
        assertThat(NumberFormatter(Locale.GERMANY).decimal(1234.5)).isEqualTo("1.234,5")
    }

    @Test
    fun `durations round to the nearest minute and never go negative`() {
        assertThat(HoursMinutes.of(7.hours + 12.minutes + 29.seconds)).isEqualTo(HoursMinutes(7, 12))
        assertThat(HoursMinutes.of(7.hours + 12.minutes + 31.seconds)).isEqualTo(HoursMinutes(7, 13))
        assertThat(HoursMinutes.of(59.minutes + 45.seconds)).isEqualTo(HoursMinutes(1, 0))
        assertThat(HoursMinutes.of(25.hours)).isEqualTo(HoursMinutes(25, 0))
        assertThat(HoursMinutes.of((-5).minutes)).isEqualTo(HoursMinutes(0, 0))
    }
}

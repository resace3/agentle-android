package dev.agentle.core.ui.format

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.time.Instant

/** The platform's localized time pattern follows the 12/24-hour setting and the locale (lowest and newest SDK). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 37])
class BestTimePatternTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val afternoon = Instant.parse("2026-10-02T12:30:00Z") // 14:30 in Berlin

    @Test
    fun `a 24-hour clock shows hours 0 to 23`() {
        assertThat(TimeFormatter(Locale.US, use24HourClock = true).time(afternoon, berlin)).isEqualTo("14:30")
        assertThat(TimeFormatter(Locale.GERMANY, use24HourClock = true).time(afternoon, berlin)).isEqualTo("14:30")
    }

    @Test
    fun `a 12-hour clock shows the day period of the locale`() {
        val text = TimeFormatter(Locale.US, use24HourClock = false).time(afternoon, berlin)
        // CLDR puts a (narrow) no-break space before "PM" on newer platforms.
        assertThat(text.replace(' ', ' ').replace(' ', ' ')).isEqualTo("2:30 PM")
    }

    @Test
    fun `the ambiguous hour keeps its offset with the platform pattern`() {
        val firstHalfPastTwo = Instant.parse("2026-10-25T00:30:00Z")
        assertThat(TimeFormatter(Locale.UK, use24HourClock = true).time(firstHalfPastTwo, berlin)).isEqualTo("02:30 (GMT+2)")
    }
}

package dev.agentle.core.ui.format

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The composable text helpers resolve the right strings and plurals (en-US resources). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class FormatTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val berlin = TimeZone.of("Europe/Berlin")

    @Test
    fun `relative times use words, plurals and the given zone`() {
        val texts = mutableMapOf<String, String>()
        compose.setContent {
            texts["justNow"] = relativeTimeText(now - 20.seconds, now, berlin)
            texts["oneMinute"] = relativeTimeText(now - 1.minutes, now, berlin)
            texts["minutes"] = relativeTimeText(now - 5.minutes, now, berlin)
            texts["hours"] = relativeTimeText(now - 3.hours, now, berlin)
            texts["inMinutes"] = relativeTimeText(now + 20.minutes, now, berlin)
            // 21:00 UTC on 1 October is 23:00 in Berlin: yesterday there.
            texts["yesterday"] = relativeTimeText(Instant.parse("2026-10-01T21:00:00Z"), now, berlin)
            // 22:30 UTC on 1 October is already 2 October in Berlin: today, 13 h 30 min ago.
            texts["today"] = relativeTimeText(Instant.parse("2026-10-01T22:30:00Z"), now, berlin)
        }
        compose.waitForIdle()
        assertThat(texts["justNow"]).isEqualTo("Just now")
        assertThat(texts["oneMinute"]).isEqualTo("1 min ago")
        assertThat(texts["minutes"]).isEqualTo("5 min ago")
        assertThat(texts["hours"]).isEqualTo("3 h ago")
        assertThat(texts["inMinutes"]).isEqualTo("In 20 min")
        // 23:00 in the 12- or 24-hour form of the platform's default setting.
        assertThat(texts["yesterday"]).matches("Yesterday, (11:00\\WPM|23:00)")
        assertThat(texts["today"]).isEqualTo("13 h ago")
    }

    @Test
    fun `durations show hours and minutes, never a negative or fractional value`() {
        val texts = mutableMapOf<Duration, String>()
        val durations = listOf(45.minutes, 2.hours, 7.hours + 12.minutes, 25.hours, 20.seconds, (-3).minutes)
        compose.setContent { durations.forEach { texts[it] = durationText(it) } }
        compose.waitForIdle()
        assertThat(texts[45.minutes]).isEqualTo("45 min")
        assertThat(texts[2.hours]).isEqualTo("2 h")
        assertThat(texts[7.hours + 12.minutes]).isEqualTo("7 h 12 min")
        assertThat(texts[25.hours]).isEqualTo("25 h")
        assertThat(texts[20.seconds]).isEqualTo("0 min")
        assertThat(texts[(-3).minutes]).isEqualTo("0 min")
    }

    @Test
    fun `numbers and dates follow the locale of the configuration`() {
        var number = ""
        var dateTime = ""
        compose.setContent {
            number = numberText(1_234_567)
            dateTime = dateTimeText(Instant.parse("2026-10-25T00:30:00Z"), berlin)
        }
        compose.waitForIdle()
        assertThat(number).isEqualTo("1,234,567")
        assertThat(dateTime).startsWith("Oct 25, 2026, ")
        assertThat(dateTime).endsWith("(GMT+2)")
    }
}

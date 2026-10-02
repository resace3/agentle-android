@file:OptIn(AiEnvelopeConstruction::class)

package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiEnvelopeConstruction
import dev.agentle.ai.api.AiPurpose
import dev.agentle.ai.api.BlockKind
import dev.agentle.ai.api.ContextBlock
import dev.agentle.ai.api.DataItem
import dev.agentle.ai.api.Fixtures
import dev.agentle.core.model.AiDataCategory
import dev.agentle.core.model.TextOrigin
import dev.agentle.core.model.UntrustedText
import org.junit.jupiter.api.Test

class NumberProvenanceTest {
    @Test
    fun `exact values and their absolute values`() {
        val provenance = NumberProvenance.of(listOf(412.0, -15, 7.25))
        assertThat(provenance.covers("412 minutes, 15 fewer, 7.25 hours")).isTrue()
        assertThat(provenance.covers("413 minutes")).isFalse()
        assertThat(provenance.covers("no numbers at all")).isTrue()
        assertThat(NumberProvenance.EMPTY.size).isEqualTo(0)
        assertThat(NumberProvenance.of(listOf(Double.NaN)).size).isEqualTo(0)
    }

    @Test
    fun `measurements allow roundings and minutes allow hours`() {
        val minutes = NumberProvenance.ofMeasurements(listOf(450, 432, 447.6), minutes = true)
        assertThat(minutes.covers("7.5 hours")).isTrue()
        assertThat(minutes.covers("7 h 30 min")).isTrue()
        assertThat(minutes.covers("7 h 12 min")).isTrue()
        assertThat(minutes.covers("about 7.2 hours")).isTrue()
        assertThat(minutes.covers("448 minutes")).isTrue()
        assertThat(minutes.covers("8 hours")).isTrue()
        assertThat(minutes.covers("9 hours")).isFalse()
        val plain = NumberProvenance.ofMeasurements(listOf(6250.4))
        assertThat(plain.covers("6,250 steps")).isTrue()
        assertThat(plain.covers("6250.4")).isTrue()
        assertThat(plain.covers("104 hours")).isFalse()
    }

    @Test
    fun `written numbers are read with every separator convention`() {
        val provenance = NumberProvenance.fromTexts(listOf("3,000 steps", "7,5 h", "1.234 points", "picks 3,4,5"))
        assertThat(provenance.covers("3000")).isTrue()
        assertThat(provenance.covers("3,000")).isTrue()
        assertThat(provenance.covers("7.5")).isTrue()
        assertThat(provenance.covers("1234")).isTrue()
        assertThat(provenance.covers("1.234")).isTrue()
        assertThat(provenance.covers("4")).isTrue()
        assertThat(provenance.covers("\u0663")).isTrue()
        assertThat(provenance.covers("6")).isFalse()
    }

    @Test
    fun `times contribute hour, minute and the 12-hour hour`() {
        val provenance = NumberProvenance.ofTimes(listOf("23:40", "17:00", "no time"))
        assertThat(provenance.covers("11:40 PM")).isTrue()
        assertThat(provenance.covers("5 PM")).isTrue()
        assertThat(provenance.covers("at 23")).isTrue()
        assertThat(provenance.covers("at 4")).isFalse()
        assertThat(NumberProvenance.ofTimes(listOf("00:30")).covers("12:30")).isTrue()
    }

    @Test
    fun `an envelope provides the numbers of every item and of the user's text`() {
        val block = ContextBlock(
            "mixed",
            AiDataCategory.SCREEN_TIME_TOTALS,
            BlockKind.AGGREGATES,
            listOf(
                Fixtures.item(DataItem.Quantity("screen.minutes_avg_7d", 185.0, NumberProvenance.MINUTES_UNIT)),
                Fixtures.item(DataItem.Quantity("screen.unlocks_avg_7d", 64.0, "count")),
                Fixtures.item(DataItem.TimeOfDay("sleep.bedtime_median_7d", "23:40")),
                Fixtures.item(DataItem.Code("trend", "UP")),
                Fixtures.item(DataItem.Text("goal", "Read 20 pages")),
                Fixtures.item(DataItem.AppUsage("top_app", "Maps", 42, 9)),
                Fixtures.item(DataItem.AppUsage("second_app", "Notes", 5)),
                Fixtures.item(
                    DataItem.Event(
                        "walk",
                        "EXERCISE",
                        "2026-09-30T18:05",
                        "2026-09-30T18:50",
                        mapOf(
                            "durationMinutes" to 45.0,
                            "steps" to 5120.0,
                        ),
                    ),
                ),
                Fixtures.item(DataItem.Event("nap", "SLEEP", "2026-09-30T14:00")),
            ),
        )
        val envelope = Fixtures.envelope(
            blocks = listOf(block),
            purpose = AiPurpose.GENERAL_QUESTION,
            userText = UntrustedText("Why 11 pm?", TextOrigin.USER_REQUEST),
        )
        val provenance = NumberProvenance.fromEnvelope(envelope)
        listOf("185", "3 h 5 min", "64", "11:40", "20 pages", "42", "9 opens", "5 minutes", "45 minutes", "5,120 steps", "6:05", "18:50")
            .plus(listOf("2 pm", "11 pm"))
            .forEach { text -> assertThat(provenance.covers(text)).isTrue() }
        assertThat(provenance.covers("77")).isFalse()
        assertThat(provenance.size).isGreaterThan(10)
    }
}

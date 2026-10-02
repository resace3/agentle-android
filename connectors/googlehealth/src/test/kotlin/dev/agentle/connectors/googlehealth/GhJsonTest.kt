package dev.agentle.connectors.googlehealth

import com.google.common.truth.Truth.assertThat
import dev.agentle.connectors.googlehealth.GhJson.boolean
import dev.agentle.connectors.googlehealth.GhJson.double
import dev.agentle.connectors.googlehealth.GhJson.long
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.time.Instant

class GhJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @ParameterizedTest(name = "{0}")
    @CsvSource("-14400s, -14400.0", "900s, 900.0", "0.5s, 0.5", "' 60s ', 60.0", "60, ", "abc, ", "'', ")
    fun `durations are seconds with an s suffix`(text: String?, expected: Double?) {
        assertThat(GhJson.seconds(text)).isEqualTo(expected)
    }

    @Test
    fun `offsets beyond 18 hours and negative durations are rejected`() {
        assertThat(GhJson.offsetSeconds("64800s")).isEqualTo(64_800)
        assertThat(GhJson.offsetSeconds("64801s")).isNull()
        assertThat(GhJson.durationMs("1.5s")).isEqualTo(1_500L)
        assertThat(GhJson.durationMs("-1s")).isNull()
        assertThat(GhJson.durationMs(null)).isNull()
    }

    @Test
    fun `R05 4 1 int64 values come as strings or numbers`() {
        val obj = json("""{"a": "12", "b": 12, "c": 1.5, "d": "x", "e": null, "f": 3.0, "g": true, "h": "true"}""")
        assertThat(obj.long("a")).isEqualTo(12L)
        assertThat(obj.long("b")).isEqualTo(12L)
        assertThat(obj.long("c")).isNull()
        assertThat(obj.long("d")).isNull()
        assertThat(obj.long("e")).isNull()
        assertThat(obj.long("f")).isEqualTo(3L)
        assertThat(obj.double("a")).isEqualTo(12.0)
        assertThat(obj.double("d")).isNull()
        assertThat(obj.boolean("g")).isTrue()
        assertThat(obj.boolean("h")).isTrue()
        assertThat(obj.boolean("d")).isNull()
    }

    @Test
    fun `R05 8 6 R5j impossible dates and timestamps are rejected`() {
        assertThat(GhJson.date(json("""{"year": 2026, "month": 2, "day": 30}"""))).isNull()
        assertThat(GhJson.date(json("""{"year": 2026, "month": 2}"""))).isNull()
        assertThat(GhJson.date(null)).isNull()
        assertThat(GhJson.date(json("""{"year": "2026", "month": "9", "day": "30"}"""))).isEqualTo(LocalDate(2026, 9, 30))
        assertThat(GhJson.civilDate(json("""{"date": {"year": 2026, "month": 9, "day": 30}, "time": {}}""")))
            .isEqualTo(LocalDate(2026, 9, 30))
        assertThat(GhJson.parseInstant("2026-09-31T25:00:00Z")).isNull()
        assertThat(GhJson.parseInstant("2026-09-30T08:00:00-04:00")).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"))
        assertThat(GhJson.parseObject("[1]")).isNull()
        assertThat(GhJson.parseObject("{")).isNull()
    }

    @Test
    fun `H3 filter literals are whole seconds, zero padded, UTC with Z or civil without`() {
        val at = Instant.parse("2026-03-05T04:07:09.987Z")
        assertThat(GhJson.physicalLiteral(at)).isEqualTo("2026-03-05T04:07:09Z")
        assertThat(GhJson.civilLiteral(at, TimeZone.of("America/St_Johns"))).isEqualTo("2026-03-05T00:37:09")
        assertThat(GhJson.physicalLiteral(Instant.parse("0999-01-01T00:00:00Z"))).isEqualTo("0999-01-01T00:00:00Z")
    }
}

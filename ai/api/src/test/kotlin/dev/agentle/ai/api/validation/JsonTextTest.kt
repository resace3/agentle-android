package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class JsonTextTest {
    @Test
    fun `extract trims and keeps a bare object`() {
        assertThat(JsonText.extract("  {\"a\":1}\n")).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `extract takes the inside of one fenced block`() {
        assertThat(JsonText.extract("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}")
        assertThat(JsonText.extract("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}")
        assertThat(JsonText.extract("```json \n  {\"a\":1}  \n```")).isEqualTo("{\"a\":1}")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Here you go: {\"a\":1}",
            "{\"a\":1} Hope this helps",
            "[1,2]",
            "```kotlin\n{\"a\":1}\n```",
            "```json\n{\"a\":1}```",
            "```json{\"a\":1}\n```",
            "```json\n{\"a\":1}\n```\n```json\n{\"b\":2}\n```",
            "```json\n{\"a\":1}\n",
            "",
        ],
    )
    fun `extract refuses anything but one object`(text: String) {
        assertThat(JsonText.extract(text)).isNull()
    }

    @Test
    fun `pre-scan bounds depth`() {
        val twenty = "[".repeat(19) + "{}" + "]".repeat(19)
        val twentyOne = "[".repeat(20) + "{}" + "]".repeat(20)
        assertThat(JsonText.preScan(twenty)).isNull()
        assertThat(JsonText.preScan(twentyOne)).isEqualTo(OutputCodes.TOO_DEEP)
        assertThat(JsonText.preScan("{\"a\":\"[[[[[[[[[[[[[[[[[[[[[[[[\"}")).isNull()
    }

    @Test
    fun `pre-scan finds repeated keys, also when escaped differently`() {
        assertThat(JsonText.preScan("{\"a\":1,\"a\":2}")).isEqualTo(OutputCodes.MALFORMED_JSON)
        assertThat(JsonText.preScan("{\"a\":1,\"\\u0061\":2}")).isEqualTo(OutputCodes.MALFORMED_JSON)
        assertThat(JsonText.preScan("{\"a\":[1,2],\"a\":3}")).isEqualTo(OutputCodes.MALFORMED_JSON)
        assertThat(JsonText.preScan("{\"a\\n\":1,\"a\\t\":2,\"a\\r\":3,\"a\\b\":4,\"a\\f\":5,\"a\\/\":6,\"a\\\"\":7}")).isNull()
    }

    @Test
    fun `pre-scan allows equal keys in different objects and equal values`() {
        assertThat(JsonText.preScan("{\"a\":{\"a\":1},\"b\":[{\"a\":1},{\"a\":2}],\"c\":\"a\",\"d\":\"a\"}")).isNull()
        assertThat(JsonText.preScan("{\"a\":\"x\\\"y\",\"b\":\"\\u12\"}")).isNull()
        assertThat(JsonText.preScan("{\"a\":\"unterminated")).isNull()
    }

    @Test
    fun `parse accepts valid objects only`() {
        assertThat(JsonText.parseObject("{\"a\":1.5e3,\"b\":[true,false,null],\"c\":-0}")).isNotNull()
        assertThat(JsonText.parseObject("{\"a\":}")).isNull()
        assertThat(JsonText.parseObject("[1]")).isNull()
        assertThat(JsonText.parseObject("{\"a\":1")).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"a\":abc}", "{\"a\":NaN}", "{\"a\":01}", "{\"a\":[tru]}", "{\"a\":{\"b\":1.}}"])
    fun `parse rejects literals that are not json`(text: String) {
        assertThat(JsonText.parseObject(text)).isNull()
    }
}

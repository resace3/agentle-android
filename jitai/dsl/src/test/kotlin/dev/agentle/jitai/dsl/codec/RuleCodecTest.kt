package dev.agentle.jitai.dsl.codec

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.errorOrNull
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.model.CreatedBy
import dev.agentle.jitai.dsl.model.JitaiStatus
import dev.agentle.jitai.dsl.model.Provenance
import dev.agentle.jitai.dsl.rule.ClockTime
import dev.agentle.jitai.dsl.rule.Condition
import dev.agentle.jitai.dsl.rule.RuleLiteral
import dev.agentle.jitai.dsl.rule.UtcInstantSerializer
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.with
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Instant

/** The strict codec (R10 §3.1 canonical form, §3.6 strict decoding, §4.1 literal fidelity). */
class RuleCodecTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("literals")
    fun `literal tokens survive decode and canonical encode`(token: String, expected: RuleLiteral) {
        val text = """{"type":"lt","feature":"steps_today","args":{},"value":$token,"onUnknown":null}"""

        val condition = RuleCodec.decodeCondition(text).getOrThrow() as Condition.Lt

        assertThat(condition.value).isEqualTo(expected)
        assertThat(RuleCodec.encodeCondition(condition)).isEqualTo(text)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    fun `decode failures carry codes and paths only`(row: String, text: String, code: String, detail: String) {
        val error = RuleCodec.decodeCondition(text).errorOrNull() as AppError.ValidationError

        assertWithMessage(row).that(error.codes).contains(code)
        assertThat(error.detail).isEqualTo(detail)
        assertThat(error.detail).doesNotContain(SECRET)
    }

    @Test
    fun `args are written with sorted keys and stored args take no appLabel or null`() {
        val text = """{"type":"gte","feature":"app_minutes_since","args":{"since":"22:00","package":"com.a.b"},""" +
            """"value":5,"onUnknown":null}"""

        val condition = RuleCodec.decodeCondition(text).getOrThrow() as Condition.Gte

        assertThat(condition.args).containsExactly("package", "com.a.b", "since", "22:00").inOrder()
        assertThat(RuleCodec.encodeCondition(condition)).isEqualTo(
            """{"type":"gte","feature":"app_minutes_since","args":{"package":"com.a.b","since":"22:00"},"value":5,"onUnknown":null}""",
        )
        assertThat(codes(RuleCodec.decodeCondition(text.replace("\"since\"", "\"appLabel\"")))).containsExactly("E006")
        assertThat(codes(RuleCodec.decodeCondition(text.replace("\"22:00\"", "null")))).containsExactly("E008")
    }

    @Test
    fun `proposal args read like the sparse stored form`() {
        val proposal = RuleCodec.decodeProposal(Fixtures.example2).getOrThrow()
        val leaf = checkNotNull(proposal.jitai).conditions as Condition.Lt

        assertThat(leaf.args).isEmpty()
        assertThat(RuleCodec.encodeProposal(proposal)).contains("\"args\":{}")
    }

    @Test
    fun `definitions are limited to 16 KiB of UTF-8`() {
        val golden = json(Fixtures.definition31)
        val fits = golden.with("/description", "é".repeat(500)).toString()
        val tooBig = golden.with("/description", "é".repeat(8_000)).toString()

        assertThat(RuleCodec.decodeDefinition(fits)).isInstanceOf(Outcome.Success::class.java)
        assertThat((RuleCodec.decodeDefinition(tooBig).errorOrNull() as AppError.ValidationError).codes).containsExactly("E002")
    }

    @Test
    fun `strict reading rejects duplicates, depth and trailing text with their codes`() {
        val duplicate = """{"type":"not","of":{"type":"local_time_in","start":"22:00","start":"23:00","end":"02:00"}}"""
        val deep = "[".repeat(25) + "]".repeat(25)

        assertThat(codes(RuleCodec.decodeCondition(duplicate))).containsExactly("E001")
        assertThat((RuleCodec.decodeCondition(duplicate).errorOrNull() as AppError.ValidationError).detail).isEqualTo("E001 /of")
        assertThat(codes(RuleCodec.decodeCondition(deep))).containsExactly("E003")
        assertThat(codes(RuleCodec.decodeCondition("""{"type":"local_time_in","start":"22:00","end":"02:00"} x"""))).containsExactly("E001")
        assertThat(codes(RuleCodec.decodeCondition("""{"type":"local_time_in",}"""))).containsExactly("E001")
    }

    @Test
    fun `proposals and discovered proposals round trip through the canonical form`() {
        val discovered = RuleCodec.decodeDiscovered(Fixtures.discovered).getOrThrow()
        val encoded = RuleCodec.encodeDiscovered(discovered)

        assertThat(RuleCodec.decodeDiscovered(encoded).getOrThrow()).isEqualTo(discovered)
        assertThat(RuleCodec.encodeDiscovered(RuleCodec.decodeDiscovered(encoded).getOrThrow())).isEqualTo(encoded)
        assertThat(encoded).contains("\"rateUnexposed\":0.250")
        assertThat(codes(RuleCodec.decodeProposal(Fixtures.example2.replace("\"priority\"", "\"prio\"")))).contains("E006")
        assertThat(codes(RuleCodec.decodeDiscovered("{}"))).contains("E007")
    }

    @Test
    fun `content hash ignores who made the rule and when`() {
        val definition = RuleCodec.decodeDefinition(Fixtures.definition31).getOrThrow()
        val sameRule = definition.copy(
            id = "11111111-2222-4333-8444-555555555555",
            version = 9,
            status = JitaiStatus.PAUSED,
            enabled = false,
            createdBy = CreatedBy.AI_NATURAL_LANGUAGE,
            createdAt = Instant.parse("2027-01-01T00:00:00Z"),
            modifiedAt = Instant.parse("2027-01-02T00:00:00Z"),
            expiresAt = Instant.parse("2027-02-01T00:00:00Z"),
            userConfirmedUnknownOverrides = true,
            provenance = Provenance(nlRequest = "different words"),
        )
        val otherRule = definition.copy(maxPerDay = 2)
        val hash = RuleCodec.contentHash(definition)

        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(RuleCodec.contentHash(sameRule)).isEqualTo(hash)
        assertThat(RuleCodec.contentHash(otherRule)).isNotEqualTo(hash)
        assertThat(RuleCodec.contentHash(definition.copy(name = "Other name", description = "Other words"))).isEqualTo(hash)
    }

    @Test
    fun `sha256 matches the FIPS 180-2 vectors`() {
        assertThat(RuleCodec.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(RuleCodec.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `utf8 length counts bytes like the encoder`() {
        val samples = listOf("", "a", "é", "€", "😀", "a😀é€")

        samples.forEach { assertThat(RuleCodec.utf8Length(it)).isEqualTo(it.toByteArray(Charsets.UTF_8).size) }
    }

    @Test
    fun `a lone surrogate counts as the three bytes of U+FFFD`() {
        listOf("\uD83D", "\uDE00", "x\uD83D", "\uD83Dx").forEach {
            assertThat(RuleCodec.utf8Length(it)).isEqualTo(3 * it.count(Char::isSurrogate) + it.count { c -> !c.isSurrogate() })
        }
    }

    @Test
    fun `instants are UTC with whole seconds`() {
        assertThat(UtcInstantSerializer.format(Instant.parse("2026-10-01T10:00:00.999Z"))).isEqualTo("2026-10-01T10:00:00Z")
        assertThat(UtcInstantSerializer.parse("2026-10-01T10:00:00Z")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"))
        listOf("2026-10-01T10:00:00.5Z", "2026-10-01T10:00:00+00:00", "2026-02-30T10:00:00Z", "2026-10-01T25:00:00Z", "x")
            .forEach { assertThat(UtcInstantSerializer.parse(it)).isNull() }
        val withMillis = json(Fixtures.definition31).with("/createdAt", "2026-10-01T10:00:00.000Z").toString()
        assertThat(RuleCodec.decodeDefinition(withMillis)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `clock times are 24-hour HH_mm`() {
        assertThat(ClockTime.format(0)).isEqualTo("00:00")
        assertThat(ClockTime.format(1439)).isEqualTo("23:59")
        assertThat(ClockTime.minuteOfDay("07:05")).isEqualTo(425)
        assertThat(ClockTime.isValid("24:00")).isFalse()
        assertThat(ClockTime.minuteOfDay("7:05")).isNull()
        assertThrows<IllegalArgumentException> { ClockTime.format(1440) }
        assertThrows<IllegalArgumentException> { ClockTime.format(-1) }
    }

    private fun codes(outcome: Outcome<*>): List<String> = (outcome.errorOrNull() as AppError.ValidationError).codes

    companion object {
        private const val SECRET = "my private words"

        @JvmStatic
        fun literals(): List<Arguments> = listOf(
            Arguments.of("45", RuleLiteral.NumberToken("45")),
            Arguments.of("45.0", RuleLiteral.NumberToken("45.0")),
            Arguments.of("4.5e1", RuleLiteral.NumberToken("4.5e1")),
            Arguments.of("\"45\"", RuleLiteral.Text("45")),
            Arguments.of("-0", RuleLiteral.NumberToken("-0")),
            Arguments.of("true", RuleLiteral.Bool(true)),
        )

        private fun row(id: String, text: String, code: String, detail: String) = Arguments.of(id, text, code, detail)

        @JvmStatic
        fun rejections(): List<Arguments> = listOf(
            row(
                "unknown key",
                """{"type":"local_time_in","start":"22:00","end":"02:00","note":"$SECRET"}""",
                "E006",
                "E006 /<unknown>",
            ),
            row("missing key", """{"type":"lt","feature":"steps_today","args":{},"value":45}""", "E007", "E007 /onUnknown"),
            row("unknown node type", """{"type":"regex","pattern":"$SECRET"}""", "E009", "E009 /type"),
            row("missing type", """{"of":[]}""", "E007", "E007 /type"),
            row("null literal", """{"type":"lt","feature":"steps_today","args":{},"value":null,"onUnknown":null}""", "E015", "E015 /value"),
            row("object literal", """{"type":"lt","feature":"steps_today","args":{},"value":{},"onUnknown":null}""", "E015", "E015 /value"),
            row(
                "bad onUnknown",
                """{"type":"lt","feature":"steps_today","args":{},"value":1,"onUnknown":"$SECRET"}""",
                "E009",
                "E009 /onUnknown",
            ),
            row(
                "arg value not a string",
                """{"type":"lt","feature":"steps_today","args":{"since":5},"value":1,"onUnknown":null}""",
                "E008",
                "E008 /args/since",
            ),
            row("syntax error", """{"type": $SECRET}""", "E001", "E001 "),
        )
    }
}

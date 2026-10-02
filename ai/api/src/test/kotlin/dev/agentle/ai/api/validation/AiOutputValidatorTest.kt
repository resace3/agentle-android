package dev.agentle.ai.api.validation

import com.google.common.truth.Truth.assertThat
import dev.agentle.ai.api.AiStructuredResult
import dev.agentle.ai.api.AiTextResult
import dev.agentle.ai.api.OutputSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AiOutputValidatorTest {
    @Test
    fun `one validator per schema version`() {
        assertThrows<IllegalArgumentException> { AiOutputValidator(listOf(InsightSchema.validator, InsightSchema.validator)) }
        assertThat(AiOutputValidator(emptyList()).schemas).isEmpty()
    }

    @Test
    fun `unknown schemas and versions fail closed`() {
        val router = AiOutputValidator.withBuiltIns()
        val none = OutputValidationContext()
        val wrongVersion = router.validate("{}", OutputSchema("InsightSchema", 2, null), none) as OutputValidation.Invalid
        assertThat(wrongVersion.codes).containsExactly(OutputCodes.SCHEMA_NOT_ROUTED)
        assertThat(wrongVersion.schema).isEqualTo(OutputSchema("InsightSchema", 2, null))
        val result = AiStructuredResult("{}", null, "r", MediaPromptSchema.SCHEMA)
        val mismatched = AiOutputValidator.validate(result, InsightSchema.validator, OutputValidationContext()) as OutputValidation.Invalid
        assertThat(mismatched.codes).containsExactly(OutputCodes.SCHEMA_NOT_ROUTED)
        assertThat(mismatched.toResultMeta("r")).isEqualTo(AiResultMeta("r", "MediaPromptSchema", 1, false, listOf("E110"), emptyList()))
        val otherVersion = AiStructuredResult("{}", null, "r", OutputSchema("InsightSchema", 9, null))
        assertThat((AiOutputValidator.validate(otherVersion, InsightSchema.validator, none) as OutputValidation.Invalid).codes)
            .containsExactly(OutputCodes.SCHEMA_NOT_ROUTED)
    }

    @Test
    fun `free text goes through the text policy`() {
        val rules = TextRules(maxChars = 120, maxSentences = 2)
        val seven = OutputValidationContext(provenance = NumberProvenance.of(listOf(7)))
        val ok = AiOutputValidator.validateText(AiTextResult("You slept 7 hours.", "m", "r"), rules, seven)
        assertThat(ok).isEqualTo(OutputValidation.Valid("You slept 7 hours.", AiOutputValidator.PLAIN_TEXT))
        val empty = OutputValidationContext(provenance = NumberProvenance.EMPTY)
        val bad = AiOutputValidator.validateText(AiTextResult("You slept 8 hours.", "m", "r"), rules, empty)
        assertThat((bad as OutputValidation.Invalid).issues).containsExactly(ValidationIssue("E102", "", ValidationStage.S6_SEMANTIC, "L9"))
    }

    @Test
    fun `utf8 length counts bytes without encoding`() {
        listOf("abc", "é", "€", "\uD83D\uDE00").forEach { text ->
            val expected = text.toByteArray(Charsets.UTF_8).size
            assertThat(AiOutputValidator.utf8Length(text)).isEqualTo(expected)
        }
        // A lone surrogate counts as three bytes: never less than any encoder writes for it.
        assertThat(AiOutputValidator.utf8Length("a\uD800b")).isEqualTo(5)
        assertThat(AiOutputValidator.utf8Length("\uDC00")).isEqualTo(3)
    }

    @Test
    fun `issues sort by stage, path, code and check and dedupe`() {
        val a = ValidationIssue("E061", "/b", ValidationStage.S6_SEMANTIC, "L1")
        val b = ValidationIssue("E008", "/a", ValidationStage.S4_SCHEMA)
        val c = ValidationIssue("E061", "/b", ValidationStage.S6_SEMANTIC, "L2")
        val invalid = OutputValidation.invalid(null, listOf(a, b, c, a))
        assertThat(invalid.issues).containsExactly(b, a, c).inOrder()
        assertThat(invalid.checks).containsExactly("L1", "L2").inOrder()
        assertThat(invalid.omitted).isEqualTo(0)
        assertThrows<IllegalArgumentException> { OutputValidation.Invalid(null, emptyList()) }
        assertThat(invalid.toResultMeta("r")).isEqualTo(AiResultMeta("r", null, null, false, listOf("E008", "E061"), listOf("L1", "L2")))
    }
}

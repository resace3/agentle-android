package dev.agentle.jitai.dsl

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.Outcome
import dev.agentle.core.common.getOrThrow
import dev.agentle.jitai.dsl.codec.RuleCodec
import dev.agentle.jitai.dsl.model.JitaiDefinition
import dev.agentle.jitai.dsl.nl.NlContract
import dev.agentle.jitai.dsl.testing.Fixtures
import dev.agentle.jitai.dsl.testing.errorCodes
import dev.agentle.jitai.dsl.testing.json
import dev.agentle.jitai.dsl.testing.with
import dev.agentle.jitai.dsl.validation.RuleValidator
import dev.agentle.jitai.dsl.validation.ValidationInput
import dev.agentle.jitai.dsl.validation.ValidationRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Golden corpus (integrator correction testing-build-09): every JSON example of R10 (§3.1, §4.7, §13.6, §14.7) and the
 * stored definition each proposal normalizes to are checked in under `src/test/resources/r10`. A codec, schema,
 * validator or normalizer change that breaks one of them fails the build.
 */
class GoldenCorpusTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "definition-3-1.json",
            "definition-12-r1.json",
            "definition-13-6-1.json",
            "definition-13-6-2.json",
            "definition-13-6-3.json",
            "definition-14-7.json",
        ],
    )
    fun `golden definitions decode, re-encode to the same canonical JSON and validate`(file: String) {
        val text = Fixtures.resource("r10/$file")
        val definition = RuleCodec.decodeDefinition(text).getOrThrow()

        assertThat(RuleCodec.encodeDefinition(definition)).isEqualTo(json(text).toString())
        val report = Fixtures.validateDefinition(definition, Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS))
        assertThat(report.errorCodes).isEmpty()
        assertThat(report.definition).isNotNull()
        assertThat(RuleValidator.revalidate(definition, Fixtures.MEDIA).errors).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["example-13-6-1.json", "example-13-6-2.json", "example-13-6-3.json"])
    fun `golden proposals decode and their canonical encoding is a fixed point`(file: String) {
        val proposal = RuleCodec.decodeProposal(Fixtures.resource("r10/$file")).getOrThrow()
        val encoded = RuleCodec.encodeProposal(proposal)

        assertThat(RuleCodec.decodeProposal(encoded).getOrThrow()).isEqualTo(proposal)
        assertThat(RuleCodec.encodeProposal(RuleCodec.decodeProposal(encoded).getOrThrow())).isEqualTo(encoded)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["condition-4-7-1.json", "condition-4-7-2.json"])
    fun `R10 4_7 condition examples decode as stored conditions`(file: String) {
        val text = Fixtures.resource("r10/$file")
        val condition = RuleCodec.decodeCondition(text)

        assertThat(condition).isInstanceOf(Outcome.Success::class.java)
        assertThat(RuleCodec.encodeCondition(condition.getOrThrow())).isEqualTo(json(text).toString())
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "example-13-6-1.json, definition-13-6-1.json, 1",
        "example-13-6-2.json, definition-13-6-2.json, 2",
        "example-13-6-3.json, definition-13-6-3.json, 3",
    )
    fun `13_6 proposals normalize to the golden stored definitions`(proposal: String, definition: String, request: Int) {
        val nlRequest = listOf(Fixtures.REQUEST_1, Fixtures.REQUEST_2, Fixtures.REQUEST_3)[request - 1]
        val report = Fixtures.validateText(
            Fixtures.resource("r10/$proposal"),
            Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS),
            nlRequest = nlRequest,
        )

        assertThat(report.errorCodes).isEmpty()
        assertThat(canonical(checkNotNull(report.definition))).isEqualTo(goldenText(definition))
    }

    @Test
    fun `14_7 discovered proposal normalizes to the golden stored definition`() {
        val report = RuleValidator.validate(
            ValidationRequest(ValidationInput.DiscoveredText(Fixtures.discovered)),
            Fixtures.context(settings = Fixtures.DEFAULT_SETTINGS),
        )

        assertThat(report.errorCodes).isEmpty()
        assertThat(canonical(checkNotNull(report.definition))).isEqualTo(goldenText("definition-14-7.json"))
    }

    /** The golden file in canonical form, with the catalog version of this build (the catalog text may change). */
    private fun goldenText(file: String): String {
        val golden = json(Fixtures.resource("r10/$file"))
        val catalogVersion = golden.jsonObject["provenance"]?.jsonObject?.get("catalogVersion")
        val pinned = if (catalogVersion is JsonPrimitive && catalogVersion.isString) {
            golden.with("/provenance/catalogVersion", NlContract.catalogVersion)
        } else {
            golden
        }
        return pinned.toString()
    }

    private fun canonical(definition: JitaiDefinition): String = RuleCodec.encodeDefinition(definition)
}

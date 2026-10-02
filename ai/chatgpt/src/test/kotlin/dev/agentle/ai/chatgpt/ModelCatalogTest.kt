package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.seconds

/** `GET /v1/models` (docs/research/06 §4.1): the fake's literal list through the client's parser, and the catalog cache. */
class ModelCatalogTest : SiwcFakeTest() {
    @Test
    fun `the R06 9_2 model list keeps only listed models in server order`() {
        val result = ModelListParser.parse(ChatGptFixtures.MODELS, "req_1")

        assertThat(result).isEqualTo(SiwcResult.Ok(listOf(ChatGptModel(ChatGptFixtures.MODEL, "GPT-6.1 Sol"))))
    }

    @Test
    fun `entries without a slug or a listed visibility are skipped and a missing label is null`() {
        val body = """{"models":[{"slug":"b","visibility":"list"},{"display_name":"no slug","visibility":"list"},""" +
            """{"slug":"","visibility":"list"},{"slug":"c","visibility":"hide"},{"slug":"a","display_name":"A","visibility":"list"},7]}"""

        val result = ModelListParser.parse(body, null)

        assertThat(result).isEqualTo(SiwcResult.Ok(listOf(ChatGptModel("b", null), ChatGptModel("a", "A"))))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["{}", "{\"models\":{}}", "[]"])
    fun `a JSON body without a model array is an invalid response`(body: String) {
        val result = ModelListParser.parse(body, "req_1") as SiwcResult.Failed

        assertThat(result.failure.error).isEqualTo(AppError.ParsingError("invalid_models"))
        assertThat(result.failure.status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.INVALID_RESPONSE, "req_1"))
    }

    @Test
    fun `a body that is not JSON at all is a captive portal and an oversized one is invalid`() {
        val portal = ModelListParser.parse("<html>Wi-Fi login</html>", null) as SiwcResult.Failed
        val oversized = ModelListParser.parse(null, null) as SiwcResult.Failed

        assertThat(portal.failure.status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.CAPTIVE_PORTAL))
        assertThat(oversized.failure.error).isEqualTo(AppError.ParsingError("invalid_models"))
    }

    @Test
    fun `the list is cached per registration until reloaded or invalidated`() = runTest {
        val siwc = graph()
        siwc.connect()

        assertThat(siwc.models.models().value().map { it.slug }).containsExactly(ChatGptFixtures.MODEL)
        siwc.models.models().value()
        assertThat(calls(FakeRoute.MODELS)).isEqualTo(1)

        siwc.models.models(reload = true).value()
        assertThat(calls(FakeRoute.MODELS)).isEqualTo(2)

        siwc.models.invalidate()
        siwc.models.models().value()
        assertThat(calls(FakeRoute.MODELS)).isEqualTo(3)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `the models call goes through the session, which refreshes once after a 401`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(3100.seconds)
        scenario(ChatGptScenario.EXPIRED_ACCESS_TOKEN)

        assertThat(siwc.models.models().value()).hasSize(1)

        assertThat(server.refreshCount()).isEqualTo(1)
        assertThat(server.requests().filter { it.route == FakeRoute.MODELS }.map { it.status }).containsExactly(401, 200).inOrder()
    }

    @ParameterizedTest(name = "R06 9.4 admission/server row {0}")
    @ValueSource(strings = ["ADMISSION_403", "ADMISSION_503", "SERVER_ERROR", "BAD_GATEWAY_HTML", "GATEWAY_TIMEOUT_EMPTY"])
    fun `a failed models call is not cached`(row: String) = runTest {
        val siwc = graph()
        siwc.connect()
        scenario(ChatGptScenario.valueOf(row))

        assertThat(siwc.models.models()).isInstanceOf(Outcome.Failure::class.java)

        scenario(ChatGptScenario.HAPPY)
        assertThat(siwc.models.models().value()).hasSize(1)
        assertThat(siwc.session.snapshot.value.status).isEqualTo(SiwcStatus.CONNECTED)
    }

    @Test
    fun `without a connection the catalog fails locally`() = runTest {
        val siwc = graph()

        assertThat(siwc.models.models().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "not_connected"))
        assertThat(server.requests()).isEmpty()
    }
}

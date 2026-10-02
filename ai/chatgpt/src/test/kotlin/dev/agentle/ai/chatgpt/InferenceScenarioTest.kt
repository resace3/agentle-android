package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.ChatGptScenario.ADMISSION_401
import dev.agentle.fakes.chatgpt.ChatGptScenario.ADMISSION_403
import dev.agentle.fakes.chatgpt.ChatGptScenario.ADMISSION_503
import dev.agentle.fakes.chatgpt.ChatGptScenario.BAD_GATEWAY_HTML
import dev.agentle.fakes.chatgpt.ChatGptScenario.CLIENT_NOT_ENABLED
import dev.agentle.fakes.chatgpt.ChatGptScenario.ERROR_EVENT
import dev.agentle.fakes.chatgpt.ChatGptScenario.EXPIRED_ACCESS_TOKEN
import dev.agentle.fakes.chatgpt.ChatGptScenario.GATEWAY_TIMEOUT_EMPTY
import dev.agentle.fakes.chatgpt.ChatGptScenario.GRANT_NOT_AUTHORIZED
import dev.agentle.fakes.chatgpt.ChatGptScenario.HAPPY
import dev.agentle.fakes.chatgpt.ChatGptScenario.INCOMPLETE
import dev.agentle.fakes.chatgpt.ChatGptScenario.INVALID_AUTHORIZATION_CONTEXT
import dev.agentle.fakes.chatgpt.ChatGptScenario.INVALID_USER
import dev.agentle.fakes.chatgpt.ChatGptScenario.MID_STREAM_UNAVAILABLE
import dev.agentle.fakes.chatgpt.ChatGptScenario.MID_STREAM_USAGE_LIMIT
import dev.agentle.fakes.chatgpt.ChatGptScenario.MODEL_NOT_FOUND
import dev.agentle.fakes.chatgpt.ChatGptScenario.NOT_ELIGIBLE
import dev.agentle.fakes.chatgpt.ChatGptScenario.NO_CONTENT_TYPE
import dev.agentle.fakes.chatgpt.ChatGptScenario.RATE_LIMITED_GENERIC
import dev.agentle.fakes.chatgpt.ChatGptScenario.SERVER_ERROR
import dev.agentle.fakes.chatgpt.ChatGptScenario.SLOW_STREAM
import dev.agentle.fakes.chatgpt.ChatGptScenario.STALL
import dev.agentle.fakes.chatgpt.ChatGptScenario.STREAM_CUT
import dev.agentle.fakes.chatgpt.ChatGptScenario.UNAVAILABLE
import dev.agentle.fakes.chatgpt.ChatGptScenario.UNSUPPORTED_CAPABILITY
import dev.agentle.fakes.chatgpt.ChatGptScenario.USAGE_LIMIT
import dev.agentle.fakes.chatgpt.ChatGptScenario.USER_UNAVAILABLE
import dev.agentle.fakes.chatgpt.ChatGptScenario.WRONG_CONTENT_TYPE
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.seconds

/**
 * The inference rows of docs/research/06 §9.4 (and the §5.5 codes the fake adds), each driven through the real
 * session manager and Responses client against the fake. Every row starts connected, 3100 s after sign-in: the
 * access token has 500 s left and `earliest_refresh_at` has passed, so a 401 may force one refresh (R06 §8.1).
 */
class InferenceScenarioTest : SiwcFakeTest() {
    /** One row: the scenario, the persisted state afterwards, and the error callers get (null: success). */
    data class Row(val scenario: ChatGptScenario, val status: SiwcStatus, val error: AppError?) {
        override fun toString(): String = "${scenario.name} (${scenario.row})"
    }

    private val request = ResponsesRequest(ChatGptFixtures.MODEL, "Answer briefly.", listOf(InputMessage(InputRole.USER, "Any tip?")))

    private suspend fun SiwcGraph.respond(): Outcome<ResponseText> = session.withAccessToken { responses.create(it, request) }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `each inference row maps to its documented state and error`(row: Row) = runTest {
        val siwc = graph(config = baseConfig().copy(streamReadTimeout = 1.seconds))
        siwc.connect()
        clock.advanceBy(3100.seconds)
        scenario(row.scenario)

        val result = siwc.respond()

        if (row.error == null) {
            assertThat(result.value()).isEqualTo(ResponseText(ChatGptFixtures.STREAM_TEXT, ChatGptFixtures.MODEL, null))
        } else {
            assertThat(result.error()).isEqualTo(row.error)
        }
        assertThat(vault().status).isEqualTo(row.status)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `an expired access token is refreshed once and the request retried once`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(3100.seconds)
        scenario(EXPIRED_ACCESS_TOKEN)

        assertThat(siwc.respond().value().text).isEqualTo(ChatGptFixtures.STREAM_TEXT)

        assertThat(server.refreshCount()).isEqualTo(1)
        assertThat(server.requests().filter { it.route == FakeRoute.RESPONSES }.map { it.status }).containsExactly(401, 200).inOrder()
    }

    @Test
    fun `a second 401 after the refresh keeps the tokens and asks for a new sign-in`() = runTest {
        val siwc = graph()
        siwc.connect()
        clock.advanceBy(3100.seconds)
        scenario(ADMISSION_401)

        assertThat(siwc.respond().error()).isEqualTo(AppError.AuthenticationRequired("chatgpt", "credential_rejected"))

        assertThat(server.refreshCount()).isEqualTo(1)
        assertThat(calls(FakeRoute.RESPONSES)).isEqualTo(2)
        assertThat(vault().registration!!.tokens).isNotNull()
    }

    @Test
    fun `the request carries exactly the whitelisted fields, the bearer token and Accept text-event-stream`() = runTest {
        val siwc = graph()
        siwc.connect()
        var sent: ByteArray? = null

        val result = siwc.session.withAccessToken { token -> siwc.responses.create(token, request) { body -> null.also { sent = body } } }

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(String(sent!!, Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        assertThat(body.keys).containsExactly("model", "instructions", "input", "store", "stream")
        assertThat(body["store"].toString()).isEqualTo("false")
        assertThat(body["stream"].toString()).isEqualTo("true")
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `a beforeSend veto stops the request before the body is written`() = runTest {
        val siwc = graph()
        siwc.connect()
        val veto = AppError.ConsentViolation(setOf("SLEEP"), "consent_version_changed")

        val result = siwc.session.withAccessToken { token -> siwc.responses.create(token, request) { veto } }

        assertThat(result.error()).isEqualTo(veto)
        assertThat(server.requests().filter { it.route == FakeRoute.RESPONSES }.map { it.status }).doesNotContain(200)
        assertThat(vault().status).isEqualTo(SiwcStatus.CONNECTED)
    }

    companion object {
        private fun status(state: SiwcState, reason: SiwcReason, param: String? = null) = SiwcStatus(state, reason, param = param)

        private val upstream = status(SiwcState.SERVER_ERROR, SiwcReason.UPSTREAM)

        /** `TestAgentleClock`'s default start; rows run 3100 s after it, Retry-After adds 20 s. */
        private val START = kotlin.time.Instant.parse("2026-10-01T12:00:00Z")

        @JvmStatic
        fun rows(): List<Row> = listOf(
            Row(HAPPY, SiwcStatus.CONNECTED, null),
            Row(EXPIRED_ACCESS_TOKEN, SiwcStatus.CONNECTED, null),
            Row(USAGE_LIMIT, status(SiwcState.RATE_LIMITED, SiwcReason.PLAN_LIMIT), AppError.NotEligible("usage_limit_reached")),
            Row(
                RATE_LIMITED_GENERIC,
                SiwcStatus(SiwcState.RATE_LIMITED, SiwcReason.TOO_MANY_REQUESTS, retryAtEpochMs = (START + 3120.seconds).toEpochMilliseconds()),
                AppError.RateLimited(20.seconds, "too_many_requests"),
            ),
            Row(NOT_ELIGIBLE, status(SiwcState.NOT_ELIGIBLE, SiwcReason.ACCOUNT_NOT_ELIGIBLE), AppError.NotEligible("account_not_eligible")),
            Row(
                UNAVAILABLE,
                status(SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.USAGE_UNAVAILABLE),
                AppError.RemoteServerError(503, "usage_unavailable"),
            ),
            Row(
                USER_UNAVAILABLE,
                status(SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.USER_UNAVAILABLE),
                AppError.RemoteServerError(503, "user_unavailable"),
            ),
            Row(
                INVALID_USER,
                status(SiwcState.REAUTH_REQUIRED, SiwcReason.REFRESH_REJECTED),
                AppError.AuthenticationRequired("chatgpt", "refresh_rejected"),
            ),
            Row(GRANT_NOT_AUTHORIZED, status(SiwcState.NOT_ELIGIBLE, SiwcReason.GRANT_NOT_AUTHORIZED), AppError.NotEligible("grant_not_authorized")),
            Row(
                INVALID_AUTHORIZATION_CONTEXT,
                status(SiwcState.NOT_ELIGIBLE, SiwcReason.GRANT_NOT_AUTHORIZED),
                AppError.NotEligible("grant_not_authorized"),
            ),
            Row(CLIENT_NOT_ENABLED, status(SiwcState.NOT_ELIGIBLE, SiwcReason.CLIENT_NOT_ENABLED), AppError.NotEligible("client_not_enabled")),
            Row(
                UNSUPPORTED_CAPABILITY,
                status(SiwcState.SERVER_ERROR, SiwcReason.UNSUPPORTED_CAPABILITY, param = "temperature"),
                AppError.UnsupportedFeature("temperature", "unsupported_capability"),
            ),
            Row(
                MODEL_NOT_FOUND,
                status(SiwcState.SERVER_ERROR, SiwcReason.MODEL_UNAVAILABLE),
                AppError.RemoteServerError(404, "model_unavailable"),
            ),
            Row(
                ADMISSION_401,
                status(SiwcState.REAUTH_REQUIRED, SiwcReason.CREDENTIAL_REJECTED),
                AppError.AuthenticationRequired("chatgpt", "credential_rejected"),
            ),
            Row(ADMISSION_403, status(SiwcState.NOT_ELIGIBLE, SiwcReason.POLICY_RESTRICTED), AppError.NotEligible("policy_restricted")),
            Row(
                ADMISSION_503,
                status(SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.ROUTING),
                AppError.RemoteServerError(503, "routing_unavailable"),
            ),
            Row(SERVER_ERROR, upstream, AppError.RemoteServerError(500, "upstream")),
            Row(BAD_GATEWAY_HTML, upstream, AppError.RemoteServerError(502, "upstream")),
            Row(GATEWAY_TIMEOUT_EMPTY, upstream, AppError.RemoteServerError(504, "upstream")),
            Row(MID_STREAM_USAGE_LIMIT, status(SiwcState.RATE_LIMITED, SiwcReason.PLAN_LIMIT), AppError.NotEligible("usage_limit_reached")),
            Row(
                MID_STREAM_UNAVAILABLE,
                status(SiwcState.PLAN_USAGE_UNAVAILABLE, SiwcReason.USAGE_UNAVAILABLE),
                AppError.RemoteServerError(503, "usage_unavailable"),
            ),
            Row(INCOMPLETE, status(SiwcState.SERVER_ERROR, SiwcReason.INCOMPLETE), AppError.RemoteServerError(200, "response_incomplete")),
            Row(ERROR_EVENT, upstream, AppError.RemoteServerError(500, "upstream")),
            Row(
                STREAM_CUT,
                status(SiwcState.SERVER_ERROR, SiwcReason.STREAM_INTERRUPTED),
                AppError.NetworkUnavailable("stream_interrupted"),
            ),
            Row(SLOW_STREAM, SiwcStatus.CONNECTED, null),
            Row(STALL, status(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.NONE), AppError.NetworkUnavailable("timeout")),
            Row(NO_CONTENT_TYPE, SiwcStatus.CONNECTED, null),
            Row(
                WRONG_CONTENT_TYPE,
                status(SiwcState.SERVER_ERROR, SiwcReason.INVALID_RESPONSE),
                AppError.ParsingError("invalid_content_type"),
            ),
        )
    }
}

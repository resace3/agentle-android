package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Red team oauth-security-13 and privacy-ai-11: every SIWC path, run with canary token values the log redactor does not
 * know, leaves no access token, refresh token, authorization code, PKCE verifier, state, nonce or ID token in any log
 * record, AppError or outcome, and no ID-token claim, personal text or AI payload in any log record.
 */
class SecretHygieneTest : SiwcFakeTest(tokenPrefix = CANARY) {
    /** Everything the app could show, store or report: outcomes, errors and published states. */
    private val shown = mutableListOf<Any?>()

    private suspend fun SiwcGraph.call() {
        shown += session.withAccessToken { SiwcResult.Ok(Unit) }
    }

    private suspend fun SiwcGraph.ask() {
        shown += provider.analyze(envelope())
    }

    private suspend fun SiwcGraph.reconnectIfNeeded() {
        scenario(ChatGptScenario.HAPPY)
        if (vault().registration?.tokens == null || session.currentClientId() == null) shown += signIn.signIn()
    }

    @Test
    fun `no secret reaches a log record, an AppError or an outcome on any SIWC path`() = runTest {
        val siwc = graph()

        // Sign-in failures, then a first sign-in.
        for (row in SIGN_IN_ROWS) {
            scenario(row)
            shown += siwc.signIn.signIn()
        }
        scenario(ChatGptScenario.HAPPY)
        shown += siwc.signIn.signIn()

        // Inference: success, expired access token (refresh and retry), then every R06 9.4 error row.
        siwc.ask()
        clock.advanceBy(3100.seconds)
        scenario(ChatGptScenario.EXPIRED_ACCESS_TOKEN)
        siwc.ask()
        for (row in INFERENCE_ROWS) {
            scenario(row)
            siwc.ask()
            siwc.reconnectIfNeeded()
        }

        // Refresh: transient, rotated identity of another account, terminal, invalid client.
        for (row in REFRESH_ROWS) {
            clock.advanceBy(1.hours)
            scenario(row)
            siwc.call()
            siwc.reconnectIfNeeded()
        }
        clock.advanceBy(1.hours)
        server.failNext(FakeRoute.TOKEN_REFRESH, 400, ChatGptFixtures.oauthError("invalid_grant"))
        siwc.call()
        siwc.reconnectIfNeeded()

        // Re-authentication as another account, then a disconnect whose revocation fails.
        scenario(ChatGptScenario.OTHER_ACCOUNT)
        shown += siwc.signIn.signIn()
        siwc.reconnectIfNeeded()
        scenario(ChatGptScenario.REVOCATION_FAILURE)
        shown += siwc.signIn.disconnect()
        runCurrent()
        shown += siwc.session.snapshot.value
        shown += siwc.provider.state.value

        val logs = sink.text()
        val outcomes = shown.joinToString("\n")
        val attemptSecrets = random.secrets()
        assertThat(attemptSecrets).isNotEmpty()
        assertThat(server.issuedAccessTokens()).isNotEmpty()
        assertThat(server.issuedAccessTokens().all { it.startsWith(CANARY) }).isTrue()
        assertThat(server.revokedTokens()).isNotEmpty()
        assertThat(sink.records.size).isGreaterThan(SIGN_IN_ROWS.size + INFERENCE_ROWS.size)
        for (secret in attemptSecrets + listOf(CANARY, JWT_PREFIX)) {
            assertWithMessage("a secret in the logs").that(logs).doesNotContain(secret)
            assertWithMessage("a secret in an outcome or error").that(outcomes).doesNotContain(secret)
        }
        assertWithMessage("the account id in an outcome or error").that(outcomes).doesNotContain(ChatGptFixtures.SUB)
        for (personal in listOf(ChatGptFixtures.SUB, ChatGptFixtures.EMAIL, ChatGptFixtures.NAME, ChatGptFixtures.STREAM_TEXT)) {
            assertWithMessage("an ID-token claim or AI payload in the logs").that(logs).doesNotContain(personal)
        }
        for (personal in listOf("How did I sleep?", "avg 7h 10m", "Explain the user's sleep pattern")) {
            assertWithMessage("personal text in the logs").that(logs).doesNotContain(personal)
        }
    }

    private companion object {
        const val CANARY = "CANARY-"

        /** Every JWT (an ID token) starts with the base64url of `{"`. */
        const val JWT_PREFIX = "eyJ"

        val SIGN_IN_ROWS = listOf(
            ChatGptScenario.JWKS_UNAVAILABLE,
            ChatGptScenario.JWKS_UNKNOWN_KID,
            ChatGptScenario.EXCHANGE_INVALID_GRANT,
            ChatGptScenario.CONSENT_DENIED,
            ChatGptScenario.REGISTRATION_INCOMPLETE,
            ChatGptScenario.PLAN_SCOPE_DECLINED,
        )

        val INFERENCE_ROWS = listOf(
            ChatGptScenario.USAGE_LIMIT,
            ChatGptScenario.RATE_LIMITED_GENERIC,
            ChatGptScenario.NOT_ELIGIBLE,
            ChatGptScenario.UNAVAILABLE,
            ChatGptScenario.USER_UNAVAILABLE,
            ChatGptScenario.GRANT_NOT_AUTHORIZED,
            ChatGptScenario.INVALID_AUTHORIZATION_CONTEXT,
            ChatGptScenario.CLIENT_NOT_ENABLED,
            ChatGptScenario.UNSUPPORTED_CAPABILITY,
            ChatGptScenario.MODEL_NOT_FOUND,
            ChatGptScenario.ADMISSION_403,
            ChatGptScenario.ADMISSION_503,
            ChatGptScenario.SERVER_ERROR,
            ChatGptScenario.BAD_GATEWAY_HTML,
            ChatGptScenario.GATEWAY_TIMEOUT_EMPTY,
            ChatGptScenario.MID_STREAM_USAGE_LIMIT,
            ChatGptScenario.MID_STREAM_UNAVAILABLE,
            ChatGptScenario.INCOMPLETE,
            ChatGptScenario.ERROR_EVENT,
            ChatGptScenario.STREAM_CUT,
            ChatGptScenario.WRONG_CONTENT_TYPE,
            ChatGptScenario.NO_CONTENT_TYPE,
            ChatGptScenario.ADMISSION_401,
            ChatGptScenario.INVALID_USER,
        )

        val REFRESH_ROWS = listOf(
            ChatGptScenario.REFRESH_TRANSIENT,
            ChatGptScenario.REFRESH_BAD_GATEWAY,
            ChatGptScenario.REFRESH_SOCKET_CLOSE,
            ChatGptScenario.REFRESH_WITH_ID_TOKEN,
            ChatGptScenario.REFRESH_ACCOUNT_MISMATCH,
            ChatGptScenario.REFRESH_INVALID_GRANT,
            ChatGptScenario.REFRESH_INVALID_CLIENT,
        )
    }
}

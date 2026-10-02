package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.fakes.chatgpt.ChatGptFixtures
import dev.agentle.fakes.chatgpt.ChatGptScenario
import dev.agentle.fakes.chatgpt.FakeRoute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.hours

/**
 * OpenID discovery (docs/research/06 §2.1, §8.2 and the §9.2 variants) through the fake: the issuer must match exactly,
 * every endpoint must be on the issuer's origin, S256 must be supported, and a discovery outage never blocks a refresh.
 */
class SiwcDiscoveryTest : SiwcFakeTest() {
    private val issuer: String get() = server.authBaseUrl()

    /** The fake's literal R06 §9.2 document with [change] applied. */
    private fun discovery(change: (MutableMap<String, JsonElement>) -> Unit = {}): String {
        val literal = ChatGptFixtures.DISCOVERY_TEMPLATE.replace("{issuer}", issuer).replace("{auth}", issuer)
        val document = (Json.parseToJsonElement(literal) as JsonObject).toMutableMap()
        change(document)
        return JsonObject(document).toString()
    }

    private class Rejected(val problem: String, val change: (MutableMap<String, JsonElement>) -> Unit)

    private fun rejected(row: String): Rejected = when (row.substringBefore(' ')) {
        "D1" -> Rejected("missing_endpoint") { it.remove("token_endpoint") }
        "D2" -> Rejected("missing_endpoint") { it["jwks_uri"] = JsonPrimitive(7) }
        "D3" -> Rejected("missing_endpoint") { it["authorization_endpoint"] = JsonPrimitive("$issuer/api/accounts/authorize?x=1") }
        "D4" -> Rejected("foreign_endpoint") { it["token_endpoint"] = JsonPrimitive("http://127.0.0.1:1/auth/api/accounts/oauth/token") }
        "D5" -> Rejected("foreign_endpoint") { it["jwks_uri"] = JsonPrimitive(issuer.replace("http://", "https://") + "/jwks") }
        "D6" -> Rejected("foreign_endpoint") { it["revocation_endpoint"] = JsonPrimitive("https://revoke.example.invalid/revoke") }
        "D7" -> Rejected("foreign_endpoint") { it["revocation_endpoint"] = JsonPrimitive("not a url") }
        "D8" -> Rejected("pkce_unsupported") { it["code_challenge_methods_supported"] = JsonArray(listOf(JsonPrimitive("plain"))) }
        "D9" -> Rejected("pkce_unsupported") { it["code_challenge_methods_supported"] = JsonPrimitive("S256") }
        "D10" -> Rejected("issuer_mismatch") { it["issuer"] = JsonPrimitive("$issuer/") }
        "D11" -> Rejected("issuer_mismatch") { it.remove("issuer") }
        else -> error("unknown row $row")
    }

    @Test
    fun `the R06 9_2 document is accepted with its optional revocation endpoint and fetched once per process`() = runTest {
        val siwc = graph()

        val endpoints = (siwc.discovery.endpoints() as SiwcResult.Ok).value
        siwc.discovery.endpoints()

        assertThat(endpoints.revocationEndpoint.toString()).isEqualTo("$issuer/api/accounts/oauth/revoke")
        assertThat(endpoints.copy(revocationEndpoint = null)).isEqualTo(SiwcEndpoints.documented(baseConfig()))
        assertThat(siwc.discovery.cachedOrDocumented()).isEqualTo(endpoints)
        assertThat(calls(FakeRoute.DISCOVERY)).isEqualTo(1)
        assertThat(sink.text()).doesNotContain("differ")
    }

    @Test
    fun `R06 9_2 an issuer other than the configured one fails sign-in as DISCOVERY_FAILED before the browser opens`() = runTest {
        scenario(ChatGptScenario.DISCOVERY_ISSUER_MISMATCH)
        val siwc = graph()

        val outcome = siwc.signIn.signIn()

        assertThat(outcome).isEqualTo(SignInOutcome.Failed(SiwcReason.DISCOVERY_FAILED, AppError.ParsingError("discovery_issuer_mismatch")))
        assertThat(browser.launched).isEmpty()
        assertThat(server.requests().map { it.route }).containsExactly(FakeRoute.DISCOVERY)
        assertThat(siwc.session.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED))
    }

    @Test
    fun `R06 9_2 a token endpoint on a foreign origin fails sign-in and nothing is sent there`() = runTest {
        scenario(ChatGptScenario.DISCOVERY_FOREIGN_ENDPOINT)

        val outcome = graph().signIn.signIn()

        assertThat(
            outcome,
        ).isEqualTo(SignInOutcome.Failed(SiwcReason.DISCOVERY_FAILED, AppError.ParsingError("discovery_foreign_endpoint")))
        assertThat(browser.launched).isEmpty()
        assertThat(server.exchangeCount()).isEqualTo(0)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "D1 token_endpoint missing",
            "D2 jwks_uri not a string",
            "D3 authorization endpoint with a query",
            "D4 token endpoint on another port",
            "D5 JWKS over another scheme",
            "D6 revocation endpoint on a foreign host",
            "D7 revocation endpoint not a URL",
            "D8 S256 not listed",
            "D9 code challenge methods not a list",
            "D10 issuer with a trailing slash",
            "D11 issuer missing",
        ],
    )
    fun `a discovery document that breaks a rule is refused with its problem and is not cached`(row: String) = runTest {
        val case = rejected(row)
        server.failNext(FakeRoute.DISCOVERY, 200, discovery(case.change))
        val siwc = graph()

        val failure = (siwc.discovery.endpoints() as SiwcResult.Failed).failure

        assertThat(failure.error).isEqualTo(AppError.ParsingError("discovery_${case.problem}"))
        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.SERVER_ERROR, SiwcReason.DISCOVERY_FAILED))
        assertThat(siwc.discovery.cachedOrDocumented()).isEqualTo(SiwcEndpoints.documented(baseConfig()))
        assertThat(siwc.discovery.endpoints()).isInstanceOf(SiwcResult.Ok::class.java)
        assertThat(calls(FakeRoute.DISCOVERY)).isEqualTo(2)
    }

    @Test
    fun `a document without code challenge methods is accepted, and one that is not an object is a captive portal`() = runTest {
        server.failNext(FakeRoute.DISCOVERY, 200, discovery { it.remove("code_challenge_methods_supported") })
        assertThat(graph().discovery.endpoints()).isInstanceOf(SiwcResult.Ok::class.java)

        server.failNext(FakeRoute.DISCOVERY, 200, "[]")
        val failure = (graph().discovery.endpoints() as SiwcResult.Failed).failure

        assertThat(failure.status).isEqualTo(SiwcStatus(SiwcState.NETWORK_UNAVAILABLE, SiwcReason.CAPTIVE_PORTAL))
    }

    @Test
    fun `same-origin endpoints on other paths are used, and only the field names are logged`() = runTest {
        server.failNext(FakeRoute.DISCOVERY, 200, discovery { it["token_endpoint"] = JsonPrimitive("$issuer/oauth2/v9/token") })

        val endpoints = (graph().discovery.endpoints() as SiwcResult.Ok).value

        assertThat(endpoints.tokenEndpoint.encodedPath).isEqualTo("/auth/oauth2/v9/token")
        assertThat(sink.text()).contains("discovered endpoints differ from the documented ones")
        assertThat(sink.text()).contains("token_endpoint")
        assertThat(sink.text()).doesNotContain("oauth2/v9")
    }

    @Test
    fun `a discovery outage is identity verification unavailable, keeps the state and is retried on the next sign-in`() = runTest {
        scenario(ChatGptScenario.DISCOVERY_UNAVAILABLE)
        val siwc = graph()

        assertThat(siwc.signIn.signIn()).isEqualTo(SignInOutcome.IdentityVerificationUnavailable)
        assertThat(siwc.session.snapshot.value).isEqualTo(SiwcSnapshot(SiwcStatus.DISCONNECTED))

        scenario(ChatGptScenario.HAPPY)
        siwc.connect()
        assertThat(calls(FakeRoute.DISCOVERY)).isEqualTo(2)
    }

    @Test
    fun `a refresh in a new process uses the documented token endpoint, so a discovery outage never blocks it`() = runTest {
        graph().connect()
        val restarted = graph()
        scenario(ChatGptScenario.DISCOVERY_UNAVAILABLE)
        val discoveries = calls(FakeRoute.DISCOVERY)
        clock.advanceBy(1.hours)

        val token = restarted.session.withAccessToken { SiwcResult.Ok(it) }

        assertThat(token.value()).isEqualTo("at_2")
        assertThat(calls(FakeRoute.DISCOVERY)).isEqualTo(discoveries)
        assertThat(restarted.discovery.cachedOrDocumented()).isEqualTo(SiwcEndpoints.documented(baseConfig()))
    }
}

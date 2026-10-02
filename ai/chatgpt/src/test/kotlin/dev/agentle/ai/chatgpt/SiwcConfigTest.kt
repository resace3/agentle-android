package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** The wire constants of docs/research/06 §2.1-2.9 and the endpoint configuration of §8.2 and R06 §11 item 1. */
class SiwcConfigTest {
    @Test
    fun `the defaults are the production endpoints and pass the release check`() {
        val config = SiwcConfig()

        assertThat(config.isProduction).isTrue()
        assertDoesNotThrow { config.assertProduction() }
        assertThat(config.discoveryUrl.toString()).isEqualTo("https://auth.openai.com/.well-known/openid-configuration")
        assertThat(config.modelsUrl.toString()).isEqualTo("https://api.openai.com/v1/models")
        assertThat(config.responsesUrl.toString()).isEqualTo("https://api.openai.com/v1/responses")
        assertThat(config.authHosts).containsExactly("auth.openai.com")
        assertThat(config.apiHosts).containsExactly("api.openai.com")
        assertThat(config.returnLink).isEqualTo("intent://siwc-done#Intent;scheme=agentle;package=dev.agentle;end")
    }

    @Test
    fun `the documented endpoints sit below the issuer and the revocation endpoint is never assumed`() {
        val endpoints = SiwcEndpoints.documented(SiwcConfig())

        assertThat(endpoints.authorizationEndpoint.toString()).isEqualTo("https://auth.openai.com/api/accounts/authorize")
        assertThat(endpoints.tokenEndpoint.toString()).isEqualTo("https://auth.openai.com/api/accounts/oauth/token")
        assertThat(endpoints.jwksUri.toString()).isEqualTo("https://auth.openai.com/.well-known/jwks.json")
        assertThat(endpoints.revocationEndpoint).isNull()
    }

    @Test
    fun `a config pointed at the fake, or allowing cleartext, fails the release check`() {
        val fake =
            SiwcConfig(issuer = "http://127.0.0.1:8080/auth", apiBaseUrl = "http://127.0.0.1:8080/api/v1", allowCleartextLoopback = true)
        val cleartextAllowed = SiwcConfig(allowCleartextLoopback = true)

        assertThat(fake.isProduction).isFalse()
        assertThat(cleartextAllowed.isProduction).isFalse()
        assertThrows<IllegalStateException> { fake.assertProduction() }
        assertThat(fake.authHosts).containsExactly("127.0.0.1")
    }

    @ParameterizedTest(name = "issuer {0}, api {1}, cleartext loopback {2}")
    @CsvSource(
        "http://127.0.0.1:8080/auth, https://api.openai.com/v1, false",
        "http://auth.example.invalid, https://api.openai.com/v1, true",
        "https://auth.openai.com, http://api.example.invalid/v1, true",
        "https://auth.openai.com/, https://api.openai.com/v1, false",
        "https://auth.openai.com, https://api.openai.com/v1/, false",
    )
    fun `cleartext is refused except on loopback when allowed, and base URLs have no trailing slash`(
        issuer: String,
        api: String,
        cleartext: Boolean,
    ) {
        assertThrows<IllegalArgumentException> { SiwcConfig(issuer = issuer, apiBaseUrl = api, allowCleartextLoopback = cleartext) }
    }

    @Test
    fun `the requested scopes are the six of R06 2_3 and both plan scopes are required`() {
        assertThat(SiwcConstants.SCOPES)
            .containsExactly("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")
            .inOrder()
        assertThat(SiwcConstants.PLAN_SCOPES).containsExactly("resource.invoke", "chatgpt.tokens.use.direct")
        assertThat(SiwcConstants.HOST_ID.matches("urn:uuid:123e4567-e89b-42d3-a456-426614174000")).isTrue()
        assertThat(SiwcConstants.HOST_ID.matches("123e4567-e89b-42d3-a456-426614174000")).isFalse()
    }
}

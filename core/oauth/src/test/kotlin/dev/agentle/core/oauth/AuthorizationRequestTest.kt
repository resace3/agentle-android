package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AuthorizationRequestTest {
    private val pkce = Pkce.from(Secret("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

    private fun request(
        endpoint: String = "https://auth.example.test/authorize",
        extra: List<Pair<String, String>> = emptyList(),
        allowLoopbackHttp: Boolean = false,
    ) = AuthorizationRequest(
        authorizationEndpoint = endpoint.toHttpUrl(),
        clientId = "client-1",
        redirectUri = "http://127.0.0.1:43210/auth/callback".toHttpUrl(),
        scopes = listOf("openid", "profile", "offline_access"),
        state = Secret("state-value"),
        pkce = pkce,
        nonce = Secret("nonce-value"),
        extraParameters = extra,
        allowLoopbackHttp = allowLoopbackHttp,
    )

    @Test
    fun `the URL carries every protocol parameter once`() {
        val url = request(extra = listOf("resource" to "https://api.example.test/v1")).toUrl()
        assertThat(url.queryParameter("response_type")).isEqualTo("code")
        assertThat(url.queryParameter("client_id")).isEqualTo("client-1")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo("http://127.0.0.1:43210/auth/callback")
        assertThat(url.queryParameter("scope")).isEqualTo("openid profile offline_access")
        assertThat(url.queryParameter("state")).isEqualTo("state-value")
        assertThat(url.queryParameter("nonce")).isEqualTo("nonce-value")
        assertThat(url.queryParameter("code_challenge")).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("resource")).isEqualTo("https://api.example.test/v1")
        assertThat(url.queryParameterNames).hasSize(9)
    }

    @Test
    fun `scope spaces are percent-encoded as %20 and the redirect URI is fully encoded`() {
        val encoded = request().toUrl().encodedQuery!!
        assertThat(encoded).contains("scope=openid%20profile%20offline_access")
        assertThat(encoded).contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A43210%2Fauth%2Fcallback")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = ["response_type", "client_id", "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method"],
    )
    fun `extra parameters cannot override protocol parameters`(name: String) {
        assertThrows<IllegalArgumentException> { request(extra = listOf(name to "x")) }
    }

    @Test
    fun `duplicate extra parameters are refused`() {
        assertThrows<IllegalArgumentException> { request(extra = listOf("prompt" to "a", "prompt" to "b")) }
    }

    @Test
    fun `plain http endpoints are refused unless the fake flavor allows loopback`() {
        assertThrows<IllegalArgumentException> { request(endpoint = "http://auth.example.test/authorize") }
        assertThrows<IllegalArgumentException> { request(endpoint = "http://127.0.0.1:9/authorize") }
        assertThrows<IllegalArgumentException> { request(endpoint = "http://10.0.2.2:9/authorize", allowLoopbackHttp = true) }
        assertThat(request(endpoint = "http://127.0.0.1:9/auth/authorize", allowLoopbackHttp = true).toUrl().host).isEqualTo("127.0.0.1")
    }

    @Test
    fun `an endpoint with its own query or a blank client is refused`() {
        assertThrows<IllegalArgumentException> { request(endpoint = "https://auth.example.test/authorize?x=1") }
        assertThrows<IllegalArgumentException> { request().copy(clientId = " ") }
        assertThrows<IllegalArgumentException> { request().copy(scopes = listOf("openid profile")) }
    }

    @Test
    fun `toString shows neither state nor nonce nor challenge`() {
        val text = request().toString()
        assertThat(text).doesNotContain("state-value")
        assertThat(text).doesNotContain("nonce-value")
        assertThat(text).doesNotContain(pkce.challenge)
    }

    @Test
    fun `nonce and scope are optional for plain OAuth`() {
        val url = request().copy(nonce = null, scopes = emptyList()).toUrl()
        assertThat(url.queryParameter("nonce")).isNull()
        assertThat(url.queryParameter("scope")).isNull()
    }
}

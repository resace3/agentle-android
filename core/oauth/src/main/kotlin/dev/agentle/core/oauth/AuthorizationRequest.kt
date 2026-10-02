package dev.agentle.core.oauth

import dev.agentle.core.network.TransportSecurityInterceptor
import okhttp3.HttpUrl

/**
 * An OAuth 2.0 authorization request with PKCE S256 (RFC 6749 §4.1.1, RFC 7636 §4.3) and, for OpenID Connect,
 * a `nonce`. [toUrl] builds the URL handed to the system browser.
 *
 * Provider-specific parameters (`resource`, `agent_name_hint`, `login_hint`, ...) go in [extraParameters]; they can
 * never override the protocol parameters this class owns.
 *
 * The URL contains `state`, `nonce` and the PKCE challenge: it is never logged (docs/research/04-privacy-security.md
 * §3.6 rule 3).
 */
public data class AuthorizationRequest(
    val authorizationEndpoint: HttpUrl,
    val clientId: String,
    val redirectUri: HttpUrl,
    val scopes: List<String>,
    val state: Secret,
    val pkce: PkcePair,
    val nonce: Secret? = null,
    val extraParameters: List<Pair<String, String>> = emptyList(),
    /** Only the fake flavor sets this: its in-process authorization server listens on http://127.0.0.1. */
    val allowLoopbackHttp: Boolean = false,
) {
    init {
        require(clientId.isNotBlank()) { "client_id must not be blank" }
        require(scopes.none { it.isBlank() || it.any(Char::isWhitespace) }) { "scopes must be single non-blank tokens" }
        require(
            authorizationEndpoint.isHttps || (allowLoopbackHttp && TransportSecurityInterceptor.isLoopback(authorizationEndpoint.host)),
        ) {
            "authorization endpoint must use https"
        }
        require(authorizationEndpoint.query == null) { "authorization endpoint must not carry a query" }
        val reserved = extraParameters.map { it.first }.filter { it in RESERVED }
        require(reserved.isEmpty()) { "extra parameters may not override $reserved" }
        val duplicates = extraParameters.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate extra parameters $duplicates" }
    }

    /** The authorization URL. Spaces in `scope` are percent-encoded as `%20`. */
    public fun toUrl(): HttpUrl = authorizationEndpoint.newBuilder().apply {
        addQueryParameter("response_type", "code")
        addQueryParameter("client_id", clientId)
        addQueryParameter("redirect_uri", redirectUri.toString())
        if (scopes.isNotEmpty()) addQueryParameter("scope", scopes.joinToString(" "))
        addQueryParameter("state", state.value)
        nonce?.let { addQueryParameter("nonce", it.value) }
        addQueryParameter("code_challenge", pkce.challenge)
        addQueryParameter("code_challenge_method", pkce.method)
        extraParameters.forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()

    override fun toString(): String = "AuthorizationRequest(endpoint=${authorizationEndpoint.host}${authorizationEndpoint.encodedPath})"

    public companion object {
        /** Parameters this class sets itself. */
        public val RESERVED: Set<String> = setOf(
            "response_type",
            "client_id",
            "redirect_uri",
            "scope",
            "state",
            "nonce",
            "code_challenge",
            "code_challenge_method",
        )
    }
}

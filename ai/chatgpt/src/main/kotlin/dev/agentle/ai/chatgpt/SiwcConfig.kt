package dev.agentle.ai.chatgpt

import dev.agentle.core.network.TransportSecurityInterceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Wire constants of docs/research/06 §2.1-2.9. Agentle's own registration only: no other product's client id (§7). */
public object SiwcConstants {
    public const val ISSUER: String = "https://auth.openai.com"
    public const val API_BASE_URL: String = "https://api.openai.com/v1"

    /** RFC 8707 resource indicator, sent on authorize, code exchange and refresh. */
    public const val RESOURCE: String = "https://api.openai.com/v1"

    /** The bootstrap client id of the non-RFC-7591 dynamic registration (§2.2). Never stored as a client id. */
    public const val BOOTSTRAP_CLIENT_ID: String = "dynamic_agent_client"
    public const val ISSUED_CLIENT_PREFIX: String = "oaiapp_"
    public val ISSUED_CLIENT_ID: Regex = Regex("^[A-Za-z0-9_-]{1,200}$")

    public const val SCOPE_RESOURCE_INVOKE: String = "resource.invoke"
    public const val SCOPE_PLAN_DIRECT: String = "chatgpt.tokens.use.direct"
    public val SCOPES: List<String> = listOf("openid", "profile", "email", "offline_access", SCOPE_RESOURCE_INVOKE, SCOPE_PLAN_DIRECT)

    /** Both plan scopes are required before inference (§2.8 recommendation). */
    public val PLAN_SCOPES: Set<String> = setOf(SCOPE_RESOURCE_INVOKE, SCOPE_PLAN_DIRECT)

    public const val CALLBACK_PATH: String = "/auth/callback"

    /** Documented paths below the issuer (§2.1); discovery may confirm them, the revocation path is never documented. */
    public const val DISCOVERY_PATH: String = "/.well-known/openid-configuration"
    public const val AUTHORIZE_PATH: String = "/api/accounts/authorize"
    public const val TOKEN_PATH: String = "/api/accounts/oauth/token"
    public const val JWKS_PATH: String = "/.well-known/jwks.json"
    public const val USAGE_PAGE: String = "https://chatgpt.com/settings/usage"

    /** Where users disconnect the app when revocation could not be confirmed (§2.12). */
    public const val SETTINGS_DISCONNECT_PATH: String =
        "Settings > Security and login > Login connections > Sign in with ChatGPT > Agentle > Manage connection > Disconnect"

    /** `ext_agent_host_id` formats accepted by OpenAI (§2.13). */
    public val HOST_ID: Regex = Regex("^(urn:uuid:[0-9a-f-]{36}|urn:ietf:params:oauth:jwk-thumbprint:.+|did:key:.+)$")
}

/**
 * Configuration of the SIWC components (docs/research/06 §8.2). [issuer] and [apiBaseUrl] are injectable so tests and
 * the fake flavor point them at `FakeChatGptServer`; release builds call [assertProduction].
 *
 * @property issuer the authorization server issuer, which is also the base of its documented paths (no trailing slash).
 * @property allowCleartextLoopback only for the fake flavor and JVM tests: http to 127.0.0.1.
 * @property applicationId the Android package, for the "Return to Agentle" `intent:` link on the callback page.
 * @property clockSkew the ID-token skew of R06 §2.7 (5 s), applied to `exp`.
 * @property issuedAtTolerance how far in the future an ID token's `iat` may lie. A token received over TLS a moment ago
 *   with a future `iat` only says the device clock is slow, so a few minutes are accepted (red team testing-build-18:
 *   ±2 min must sign in); beyond it the result is DEVICE_CLOCK_WRONG. A fast device clock fails on `exp` instead.
 */
public data class SiwcConfig(
    val issuer: String = SiwcConstants.ISSUER,
    val apiBaseUrl: String = SiwcConstants.API_BASE_URL,
    val appName: String = "Agentle",
    val applicationId: String = "dev.agentle",
    val userAgent: String = "Agentle",
    val allowCleartextLoopback: Boolean = false,
    val loopbackTimeout: Duration = 10.minutes,
    val refreshLeeway: Duration = 60.seconds,
    val clockSkew: Duration = 5.seconds,
    val streamCallTimeout: Duration = 180.seconds,
    val streamReadTimeout: Duration = 60.seconds,
    val loopbackPollInterval: Duration = 250.milliseconds,
    val issuedAtTolerance: Duration = 5.minutes,
) {
    val issuerUrl: HttpUrl = issuer.toHttpUrl()
    val apiBase: HttpUrl = apiBaseUrl.toHttpUrl()

    init {
        require(!issuer.endsWith("/") && !apiBaseUrl.endsWith("/")) { "base URLs have no trailing slash" }
        require(secure(issuerUrl) && secure(apiBase)) { "SIWC endpoints must use https (http only for the fake on 127.0.0.1)" }
    }

    private fun secure(url: HttpUrl): Boolean = url.isHttps || (allowCleartextLoopback && TransportSecurityInterceptor.isLoopback(url.host))

    public val discoveryUrl: HttpUrl get() = "$issuer${SiwcConstants.DISCOVERY_PATH}".toHttpUrl()
    public val modelsUrl: HttpUrl get() = "$apiBaseUrl/models".toHttpUrl()
    public val responsesUrl: HttpUrl get() = "$apiBaseUrl/responses".toHttpUrl()

    /** Egress allow-list of the auth client (red team oauth-security-09). */
    public val authHosts: Set<String> get() = setOf(issuerUrl.host)

    /** Egress allow-list of the API and SSE clients. */
    public val apiHosts: Set<String> get() = setOf(apiBase.host)

    /** The package-scoped return link of the callback page (docs/ARCHITECTURE.md §8). */
    public val returnLink: String get() = "intent://siwc-done#Intent;scheme=agentle;package=$applicationId;end"

    public val isProduction: Boolean
        get() = issuer == SiwcConstants.ISSUER && apiBaseUrl == SiwcConstants.API_BASE_URL && !allowCleartextLoopback

    /** Release builds call this at startup (R06 §11 item 1). */
    public fun assertProduction() {
        check(isProduction) { "release builds must use the production SIWC endpoints" }
    }
}

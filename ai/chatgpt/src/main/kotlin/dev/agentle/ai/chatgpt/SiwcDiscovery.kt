package dev.agentle.ai.chatgpt

import dev.agentle.core.common.Logger
import dev.agentle.core.oauth.OAuthResult
import dev.agentle.core.oauth.TokenClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The authorization server's endpoints, all on the issuer's origin. [revocationEndpoint] is optional (R06 §2.1). */
public data class SiwcEndpoints(
    val issuer: String,
    val authorizationEndpoint: HttpUrl,
    val tokenEndpoint: HttpUrl,
    val jwksUri: HttpUrl,
    val revocationEndpoint: HttpUrl?,
) {
    public companion object {
        /** The documented constants of R06 §2.1 below [config]'s issuer. The revocation path is never documented. */
        public fun documented(config: SiwcConfig): SiwcEndpoints = SiwcEndpoints(
            issuer = config.issuer,
            authorizationEndpoint = (config.issuer + SiwcConstants.AUTHORIZE_PATH).toHttpUrl(),
            tokenEndpoint = (config.issuer + SiwcConstants.TOKEN_PATH).toHttpUrl(),
            jwksUri = (config.issuer + SiwcConstants.JWKS_PATH).toHttpUrl(),
            revocationEndpoint = null,
        )
    }
}

/**
 * Fetches and validates OpenID discovery (docs/research/06 §2.1, §8.2) through the auth client and caches it in
 * memory: `issuer` must equal the configured issuer exactly, and every endpoint (also the optional revocation
 * endpoint) must have the issuer's origin. Discovered paths that differ from the documented ones are logged as a
 * diagnostic (names only).
 *
 * Sign-in and disconnect call [endpoints]. Refresh and JWKS use [cachedOrDocumented], so a discovery outage never blocks
 * a refresh: the token endpoint is a documented constant.
 */
public class SiwcDiscovery(private val config: SiwcConfig, private val tokenClient: TokenClient, private val logger: Logger = Logger.NONE) {
    private val mutex = Mutex()

    @Volatile private var cached: SiwcEndpoints? = null

    /** The validated endpoints, fetched once per process; failures are not cached. */
    public suspend fun endpoints(): SiwcResult<SiwcEndpoints> {
        cached?.let { return SiwcResult.Ok(it) }
        return mutex.withLock { cached?.let { SiwcResult.Ok(it) } ?: fetch() }
    }

    /** The validated endpoints if discovery succeeded in this process, otherwise the documented ones. */
    public fun cachedOrDocumented(): SiwcEndpoints = cached ?: SiwcEndpoints.documented(config)

    private suspend fun fetch(): SiwcResult<SiwcEndpoints> = when (
        val answer = tokenClient.fetchDocument(config.discoveryUrl, "discovery")
    ) {
        is OAuthResult.Failure -> {
            logger.w(COMPONENT, "discovery unavailable", fields = mapOf("outcome" to TokenClient.describe(answer.failure)))
            SiwcResult.Failed(SiwcErrorMapper.documentUnavailable(answer.failure))
        }

        is OAuthResult.Success -> when (val parsed = parse(answer.value)) {
            is Parsed.Valid -> {
                cached = parsed.endpoints
                logDifferences(parsed.endpoints)
                SiwcResult.Ok(parsed.endpoints)
            }

            is Parsed.Invalid -> {
                logger.w(COMPONENT, "discovery rejected", fields = mapOf("problem" to parsed.problem))
                SiwcResult.Failed(
                    if (parsed.problem == NOT_JSON) SiwcErrorMapper.captivePortal() else SiwcErrorMapper.discoveryFailed(parsed.problem),
                )
            }
        }
    }

    private sealed interface Parsed {
        data class Valid(val endpoints: SiwcEndpoints) : Parsed

        data class Invalid(val problem: String) : Parsed
    }

    private fun parse(body: String): Parsed {
        val document = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val required = REQUIRED_ENDPOINTS.map { document?.url(it) }
        val revocationPresent = document?.get(REVOCATION) != null
        val revocation = document?.url(REVOCATION)
        val problem = when {
            document == null -> NOT_JSON
            document.string("issuer") != config.issuer -> "issuer_mismatch"
            required.any { it == null } -> "missing_endpoint"
            required.any { it != null && !sameOrigin(it) } -> "foreign_endpoint"
            revocationPresent && (revocation == null || !sameOrigin(revocation)) -> "foreign_endpoint"
            !supportsS256(document) -> "pkce_unsupported"
            else -> null
        }
        val (authorize, token, jwks) = required
        return if (problem != null || authorize == null || token == null || jwks == null) {
            Parsed.Invalid(problem ?: "missing_endpoint")
        } else {
            Parsed.Valid(SiwcEndpoints(config.issuer, authorize, token, jwks, revocation))
        }
    }

    private fun sameOrigin(url: HttpUrl): Boolean =
        url.scheme == config.issuerUrl.scheme && url.host == config.issuerUrl.host && url.port == config.issuerUrl.port

    /** `code_challenge_methods_supported` is optional; when present it must list S256. */
    private fun supportsS256(document: JsonObject): Boolean {
        val methods = document["code_challenge_methods_supported"] ?: return true
        return (methods as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "S256" } == true
    }

    private fun logDifferences(endpoints: SiwcEndpoints) {
        val documented = SiwcEndpoints.documented(config)
        val differing = listOfNotNull(
            "authorization_endpoint".takeIf { endpoints.authorizationEndpoint != documented.authorizationEndpoint },
            "token_endpoint".takeIf { endpoints.tokenEndpoint != documented.tokenEndpoint },
            "jwks_uri".takeIf { endpoints.jwksUri != documented.jwksUri },
        )
        if (differing.isNotEmpty()) {
            logger.w(COMPONENT, "discovered endpoints differ from the documented ones", fields = mapOf("fields" to differing))
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.url(name: String): HttpUrl? = string(name)?.toHttpUrlOrNull()?.takeIf { it.query == null && it.fragment == null }

    private companion object {
        const val COMPONENT = "siwc.discovery"
        const val REVOCATION = "revocation_endpoint"
        const val NOT_JSON = "not_json"
        val REQUIRED_ENDPOINTS = listOf("authorization_endpoint", "token_endpoint", "jwks_uri")
    }
}

package dev.agentle.core.oauth

import dev.agentle.core.common.Logger
import dev.agentle.core.network.RetryAfter
import dev.agentle.core.time.AgentleClock
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** An unparsed 2xx token-endpoint body. It holds tokens, so it is a [Secret] and never printed. */
public data class RawTokenResponse(val body: Secret, val httpStatus: Int) {
    /** Validates the body; a parse failure never echoes it. */
    public fun parse(rules: TokenResponseRules): OAuthResult<TokenResponse> = TokenResponseParser.parse(body.value, rules, httpStatus)
}

/**
 * Token-endpoint and revocation calls of a public OAuth client (no client secret): authorization-code exchange
 * with PKCE (RFC 6749 §4.1.3, RFC 7636 §4.5), refresh (RFC 6749 §6) and revocation (RFC 7009 §2).
 *
 * Every call is one POST (`application/x-www-form-urlencoded`, `Accept: application/json`) on a client that never
 * follows redirects and never retries silently ([OAuthHttpClients.tokenEndpointClient]). Failures are returned as
 * [OAuthFailure], never thrown; cancellation is propagated. Nothing from a request or response body is logged.
 *
 * Persisting a rotated refresh token is the caller's job, and it must happen before the new tokens are used.
 */
public class TokenClient(baseClient: OkHttpClient, private val clock: AgentleClock, private val logger: Logger = Logger.NONE) {
    private val http = OAuthHttpClients.tokenEndpointClient(baseClient)

    /**
     * Redeems an authorization code. [redirectUri] must be identical to the one in the authorization request;
     * [extraParameters] carries provider parameters such as `resource` (RFC 8707).
     */
    public suspend fun exchangeCode(
        tokenEndpoint: HttpUrl,
        clientId: String,
        code: Secret,
        codeVerifier: Secret,
        redirectUri: HttpUrl,
        extraParameters: List<Pair<String, String>> = emptyList(),
        rules: TokenResponseRules = TokenResponseRules(),
    ): OAuthResult<TokenResponse> {
        val form = listOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code.value,
            "code_verifier" to codeVerifier.value,
            "redirect_uri" to redirectUri.toString(),
        ) + extraParameters
        return tokenCall(tokenEndpoint, form, rules, "authorization_code")
    }

    /** Uses [refreshToken] once. Never sends `scope` unless the provider asks for it in [extraParameters]. */
    public suspend fun refresh(
        tokenEndpoint: HttpUrl,
        clientId: String,
        refreshToken: Secret,
        extraParameters: List<Pair<String, String>> = emptyList(),
        rules: TokenResponseRules = TokenResponseRules.REFRESH,
    ): OAuthResult<TokenResponse> = when (val raw = refreshRaw(tokenEndpoint, clientId, refreshToken, extraParameters)) {
        is OAuthResult.Failure -> raw
        is OAuthResult.Success -> raw.value.parse(rules).also { logParsed("refresh_token", it) }
    }

    /**
     * Like [refresh], but returns the 2xx body unparsed, so a provider can checkpoint it durably before anything
     * else can fail: once the server answered, the old refresh token is spent and the body holds the only valid one
     * (docs/research/06 §8.2, red team oauth-security-02). Parse it with [RawTokenResponse.parse].
     */
    public suspend fun refreshRaw(
        tokenEndpoint: HttpUrl,
        clientId: String,
        refreshToken: Secret,
        extraParameters: List<Pair<String, String>> = emptyList(),
    ): OAuthResult<RawTokenResponse> {
        val form = listOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken.value) + extraParameters
        return rawTokenCall(tokenEndpoint, form, "refresh_token")
    }

    /**
     * GETs a JSON document of the authorization server (OIDC discovery, JWKS) on the same no-redirect client.
     * Any 2xx body up to 256 KiB is returned as text; everything else is an [OAuthFailure].
     */
    public suspend fun fetchDocument(url: HttpUrl, operation: String): OAuthResult<String> {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val result = when (val answer = execute(request, operation)) {
            is OAuthResult.Failure -> answer
            is OAuthResult.Success -> answer.value.use { response -> successBody(response) }
        }
        logger.d(COMPONENT, "document request finished", fields = mapOf("operation" to operation, "outcome" to outcomeOf(result)))
        return result
    }

    /**
     * Revokes [token] (RFC 7009). Any 2xx is success; the server answers 200 even for an already-invalid token.
     * One attempt; retrying transient failures is the caller's policy.
     */
    public suspend fun revoke(
        revocationEndpoint: HttpUrl,
        clientId: String,
        token: Secret,
        tokenTypeHint: String = "refresh_token",
    ): OAuthResult<Unit> {
        val form = listOf("token" to token.value, "token_type_hint" to tokenTypeHint, "client_id" to clientId)
        return when (val answer = post(revocationEndpoint, form, "revoke")) {
            is OAuthResult.Failure -> answer

            is OAuthResult.Success -> answer.value.use { response ->
                if (response.isSuccessful) OAuthResult.Success(Unit) else OAuthResult.Failure(failureOf(response))
            }
        }
    }

    private suspend fun tokenCall(
        endpoint: HttpUrl,
        form: List<Pair<String, String>>,
        rules: TokenResponseRules,
        grant: String,
    ): OAuthResult<TokenResponse> = when (val raw = rawTokenCall(endpoint, form, grant)) {
        is OAuthResult.Failure -> raw
        is OAuthResult.Success -> raw.value.parse(rules).also { logParsed(grant, it) }
    }

    private suspend fun rawTokenCall(endpoint: HttpUrl, form: List<Pair<String, String>>, grant: String): OAuthResult<RawTokenResponse> {
        val result = when (val posted = post(endpoint, form, grant)) {
            is OAuthResult.Failure -> posted

            is OAuthResult.Success -> posted.value.use { response ->
                when (val body = successBody(response)) {
                    is OAuthResult.Failure -> body
                    is OAuthResult.Success -> OAuthResult.Success(RawTokenResponse(Secret(body.value), response.code))
                }
            }
        }
        logger.i(COMPONENT, "token request finished", fields = mapOf("grant" to grant, "outcome" to outcomeOf(result)))
        return result
    }

    private fun logParsed(grant: String, result: OAuthResult<TokenResponse>) {
        if (result is OAuthResult.Failure) {
            logger.w(COMPONENT, "token response rejected", fields = mapOf("grant" to grant, "outcome" to describe(result.failure)))
        }
    }

    /** The body of a 2xx answer (bounded), or the failure the answer stands for. */
    private fun successBody(response: Response): OAuthResult<String> {
        if (!response.isSuccessful) return OAuthResult.Failure(failureOf(response))
        val body = try {
            response.bodyUpTo(MAX_BODY_BYTES)
        } catch (e: IOException) {
            return OAuthResult.Failure(OAuthFailure.Network(transportFailureKind(e)))
        }
        return body?.let { OAuthResult.Success(it) } ?: OAuthResult.Failure(OAuthFailure.InvalidResponse("body_too_large", response.code))
    }

    private suspend fun post(endpoint: HttpUrl, form: List<Pair<String, String>>, operation: String): OAuthResult<Response> {
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        return execute(Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build(), operation)
    }

    private suspend fun execute(request: Request, operation: String): OAuthResult<Response> {
        val response = try {
            http.newCall(request).awaitResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val failure = blockedReason(e)?.let(OAuthFailure::Blocked) ?: OAuthFailure.Network(transportFailureKind(e))
            logger.w(
                COMPONENT,
                "authorization server unreachable",
                fields = mapOf("operation" to operation, "outcome" to describe(failure)),
            )
            return OAuthResult.Failure(failure)
        }
        if (response.isRedirect || response.code in REDIRECT_CODES) {
            response.close()
            return OAuthResult.Failure(OAuthFailure.Redirected(response.code))
        }
        return OAuthResult.Success(response)
    }

    /** Non-2xx: OAuth error code if the body has one, otherwise the status and body shape. */
    private fun failureOf(response: Response): OAuthFailure {
        val requestId = response.header(REQUEST_ID_HEADER)?.take(MAX_REQUEST_ID)
        val body = try {
            response.bodyUpTo(MAX_BODY_BYTES)
        } catch (_: IOException) {
            null
        }
        val parsed = ErrorBody.parse(body, response.header("Content-Type"))
        return when (val code = parsed.code) {
            null -> OAuthFailure.HttpStatus(
                response.code,
                parsed.shape,
                RetryAfter.parse(response.header("Retry-After"), clock.now()),
                requestId,
            )

            else -> OAuthFailure.ErrorResponse(code, response.code, parsed.shape, requestId)
        }
    }

    public companion object {
        private const val COMPONENT = "oauth.token"
        private const val MAX_BODY_BYTES = 256L * 1024
        private const val MAX_REQUEST_ID = 128
        public const val REQUEST_ID_HEADER: String = "x-request-id"
        private val REDIRECT_CODES = 300..399

        private fun outcomeOf(result: OAuthResult<*>): String = when (result) {
            is OAuthResult.Success -> "ok"
            is OAuthResult.Failure -> describe(result.failure)
        }

        /** A loggable summary of a failure: codes and statuses only. */
        public fun describe(failure: OAuthFailure): String = when (failure) {
            is OAuthFailure.ErrorResponse -> "error:${failure.error}:${failure.httpStatus}"
            is OAuthFailure.HttpStatus -> "http:${failure.httpStatus}:${failure.shape}"
            is OAuthFailure.Network -> "network:${failure.kind}"
            is OAuthFailure.InvalidResponse -> "invalid:${failure.reason}"
            is OAuthFailure.Redirected -> "redirect:${failure.httpStatus}"
            is OAuthFailure.Blocked -> "blocked:${failure.reason}"
        }
    }
}

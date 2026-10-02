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
    ): OAuthResult<TokenResponse> {
        val form = listOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken.value) + extraParameters
        return tokenCall(tokenEndpoint, form, rules, "refresh_token")
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
    ): OAuthResult<TokenResponse> {
        val answer = when (val posted = post(endpoint, form, grant)) {
            is OAuthResult.Failure -> return posted
            is OAuthResult.Success -> posted.value
        }
        return answer.use { response ->
            if (!response.isSuccessful) return@use OAuthResult.Failure(failureOf(response))
            val body = try {
                response.bodyUpTo(MAX_BODY_BYTES)
            } catch (e: IOException) {
                return@use OAuthResult.Failure(OAuthFailure.Network(transportFailureKind(e)))
            } ?: return@use OAuthResult.Failure(OAuthFailure.InvalidResponse("body_too_large", response.code))
            TokenResponseParser.parse(body, rules, response.code)
        }.also { result ->
            val outcome = when (result) {
                is OAuthResult.Success -> "ok"
                is OAuthResult.Failure -> describe(result.failure)
            }
            logger.i(COMPONENT, "token request finished", fields = mapOf("grant" to grant, "outcome" to outcome))
        }
    }

    private suspend fun post(endpoint: HttpUrl, form: List<Pair<String, String>>, operation: String): OAuthResult<Response> {
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build()
        val response = try {
            http.newCall(request).awaitResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            val failure = if (isLocallyBlocked(e)) OAuthFailure.Blocked("cleartext") else OAuthFailure.Network(transportFailureKind(e))
            logger.w(COMPONENT, "token endpoint unreachable", fields = mapOf("operation" to operation, "outcome" to describe(failure)))
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

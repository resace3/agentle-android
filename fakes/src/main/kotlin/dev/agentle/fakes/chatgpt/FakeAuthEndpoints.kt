package dev.agentle.fakes.chatgpt

import dev.agentle.core.time.AgentleClock
import dev.agentle.fakes.chatgpt.ChatGptFixtures.BOOTSTRAP_CLIENT_ID
import dev.agentle.fakes.chatgpt.ChatGptFixtures.CLIENT_ID
import dev.agentle.fakes.chatgpt.ChatGptScenario.CONSENT_DENIED
import dev.agentle.fakes.chatgpt.ChatGptScenario.INVALID_STATE
import dev.agentle.fakes.chatgpt.ChatGptScenario.REGISTRATION_INCOMPLETE
import dev.agentle.fakes.chatgpt.FakeResponses.json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One request as the endpoints see it: the scenario and the base URL (`origin` + optional scenario prefix). */
internal class FakeRequest(val recorded: RecordedRequest, val scenario: ChatGptScenario, val base: String) {
    val authBase: String get() = "$base/auth"

    val body: String by lazy { recorded.body?.utf8().orEmpty() }

    /** Form fields (`application/x-www-form-urlencoded`), every value kept; null if the encoding is broken. */
    val form: Map<String, List<String>>? by lazy { parseForm(body) }

    fun formValue(name: String): String? = form?.get(name)?.singleOrNull()

    fun query(name: String): String? = recorded.url.queryParameterValues(name).singleOrNull()

    val parameterNames: Set<String>
        get() = recorded.url.queryParameterNames + form?.keys.orEmpty()

    private fun parseForm(text: String): Map<String, List<String>>? = try {
        text.split('&').filter { it.isNotEmpty() }.groupBy(
            { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) },
            { URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) },
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Discovery, JWKS, authorize, token and revocation endpoints of docs/research/06 §9.2, with the scenario behaviour of
 * docs/research/08 §5.4. Every check is written from the documented contract, not from the client's code.
 */
internal class FakeAuthEndpoints(
    private val state: FakeChatGptState,
    private val signer: FakeJwtSigner,
    private val clock: AgentleClock,
    private val tokenPrefix: String,
) {
    fun discovery(request: FakeRequest): MockResponse {
        val auth = request.authBase
        val issuer = if (request.scenario == ChatGptScenario.DISCOVERY_ISSUER_MISMATCH) ChatGptFixtures.PRODUCTION_ISSUER else auth
        val template = when (request.scenario) {
            ChatGptScenario.DISCOVERY_NO_REVOCATION -> ChatGptFixtures.DISCOVERY_NO_REVOCATION_TEMPLATE
            else -> ChatGptFixtures.DISCOVERY_TEMPLATE
        }
        var body = template.replace("{issuer}", issuer).replace("{auth}", auth)
        if (request.scenario == ChatGptScenario.DISCOVERY_FOREIGN_ENDPOINT) {
            body = body.replace("$auth/api/accounts/oauth/token", "https://foreign.example.invalid/api/accounts/oauth/token")
        }
        return when (request.scenario) {
            ChatGptScenario.DISCOVERY_UNAVAILABLE -> json(SERVICE_UNAVAILABLE, ChatGptFixtures.DETAIL_UNAVAILABLE)
            else -> json(OK, body)
        }
    }

    fun jwks(request: FakeRequest): MockResponse = when (request.scenario) {
        ChatGptScenario.JWKS_UNAVAILABLE -> json(SERVICE_UNAVAILABLE, ChatGptFixtures.DETAIL_UNAVAILABLE)
        ChatGptScenario.JWKS_EMPTY -> json(OK, ChatGptFixtures.JWKS_EMPTY)
        ChatGptScenario.JWKS_MALFORMED -> json(OK, ChatGptFixtures.JWKS_MALFORMED)
        else -> json(OK, signer.jwks())
    }

    /** R06 §9.2 authorize: validates, auto-consents and redirects to the loopback callback. */
    fun authorize(request: FakeRequest): MockResponse {
        val redirect = request.query("redirect_uri")
        val clientId = request.query("client_id")
        // RFC 6749 §4.1.2.1: never redirect to an unverified redirect URI or for an unknown client.
        if (redirect == null || !LOOPBACK_REDIRECT.matches(redirect)) return json(BAD_REQUEST, ChatGptFixtures.AUTHORIZE_INVALID_REDIRECT)
        if (clientId != BOOTSTRAP_CLIENT_ID && clientId != CLIENT_ID) return json(BAD_REQUEST, ChatGptFixtures.AUTHORIZE_UNKNOWN_CLIENT)
        recordAuthorizeHygiene(request, clientId)
        val sentState = request.query("state")
        val problem = authorizeProblem(request, clientId)
        val error = problem ?: "access_denied".takeIf { request.scenario == CONSENT_DENIED }
        if (error != null) {
            val stateParameter = sentState?.let { "&state=${enc(it)}" }.orEmpty()
            return FakeResponses.redirect("$redirect?error=$error$stateParameter")
        }
        val (code, attempt) = synchronized(state.lock) {
            state.codeCounter += 1
            val code = "${tokenPrefix}code_${state.codeCounter}"
            state.codes[code] = CodeGrant(
                clientId = CLIENT_ID,
                challenge = request.query("code_challenge").orEmpty(),
                redirectUri = redirect,
                nonce = request.query("nonce").orEmpty(),
                scope = request.query("scope").orEmpty(),
                expiresAt = clock.now() + CODE_LIFETIME,
            )
            code to state.attempt(request.scenario, FakeRoute.AUTHORIZE)
        }
        val returnedState = if (request.scenario == INVALID_STATE && attempt == 1) "TAMPERED" else sentState.orEmpty()
        val scope = if (request.scenario == ChatGptScenario.PLAN_SCOPE_DECLINED) {
            ChatGptFixtures.DECLINED_SCOPES.replace(' ', '+')
        } else {
            ChatGptFixtures.CALLBACK_SCOPES
        }
        val clientParameter = if (request.scenario == REGISTRATION_INCOMPLETE) "" else "&client_id=$CLIENT_ID"
        return FakeResponses.redirect("$redirect?code=$code&scope=$scope&state=${enc(returnedState)}$clientParameter")
    }

    private fun authorizeProblem(request: FakeRequest, clientId: String): String? = when {
        request.query("response_type") != "code" -> "unsupported_response_type"
        request.query("scope")?.split(' ')?.contains("openid") != true -> "invalid_scope"
        request.query("resource") != ChatGptFixtures.RESOURCE -> "invalid_target"
        request.query("state").isNullOrEmpty() || request.query("nonce").isNullOrEmpty() -> "invalid_request"
        request.query("code_challenge_method") != "S256" -> "invalid_request"
        request.query("code_challenge")?.let(CHALLENGE::matches) != true -> "invalid_request"
        request.query("ext_agent_host_id")?.let(HOST_ID::matches) != true -> "invalid_request"
        clientId == BOOTSTRAP_CLIENT_ID && request.query("agent_name_hint").isNullOrBlank() -> "invalid_request"
        else -> null
    }

    /** Things the server accepts but R06 §2.3 tells the client not to send. */
    private fun recordAuthorizeHygiene(request: FakeRequest, clientId: String) = synchronized(state.lock) {
        val url: HttpUrl = request.recorded.url
        if (url.queryParameter("id_token_hint") != null) state.violation("H-ID-TOKEN-HINT")
        if (url.queryParameter("force_reconsent") != null) state.violation("H-FORCE-RECONSENT")
        if (clientId == CLIENT_ID && url.queryParameter("agent_name_hint") != null) state.violation("H-AGENT-NAME-ON-REAUTH")
        if (url.queryParameter("prompt").let { it != null && it != "consent" }) state.violation("H-PROMPT")
        if (url.queryParameter("scope")?.split(' ')?.toSet() != REQUESTED_SCOPES) state.violation("H-SCOPES")
    }

    /** R06 §9.2 token endpoint; returns the route (exchange or refresh) for the journal. */
    fun token(request: FakeRequest): Pair<FakeRoute, MockResponse> {
        val grantType = request.formValue("grant_type")
        val route = if (grantType == "refresh_token") FakeRoute.TOKEN_REFRESH else FakeRoute.TOKEN_EXCHANGE
        val contentType = request.recorded.headers["Content-Type"].orEmpty()
        val injected = synchronized(state.lock) {
            if (route == FakeRoute.TOKEN_REFRESH) state.refreshCount += 1 else state.exchangeCount += 1
            state.takeInjected(route)
        }
        val response = when {
            injected != null -> injected
            !contentType.startsWith("application/x-www-form-urlencoded") || request.form == null -> invalidRequest()
            request.form.orEmpty().values.any { it.size > 1 } -> invalidRequest()
            grantType == "authorization_code" -> exchange(request)
            grantType == "refresh_token" -> refresh(request)
            else -> json(BAD_REQUEST, ChatGptFixtures.TOKEN_UNSUPPORTED_GRANT)
        }
        return route to response
    }

    @Suppress("ReturnCount") // One guard per documented exchange rule (R06 §2.6, §9.2).
    private fun exchange(request: FakeRequest): MockResponse {
        val clientId = request.formValue("client_id")
        val code = request.formValue("code")
        val verifier = request.formValue("code_verifier")
        val redirect = request.formValue("redirect_uri")
        if (clientId == null || code == null || verifier == null || redirect == null || request.formValue("resource") == null) {
            return invalidRequest()
        }
        if (clientId == BOOTSTRAP_CLIENT_ID) synchronized(state.lock) { state.violation("H-BOOTSTRAP-CLIENT-AT-EXCHANGE") }
        if (clientId != CLIENT_ID) return json(UNAUTHORIZED, ChatGptFixtures.TOKEN_INVALID_CLIENT)
        if (request.formValue("resource") != ChatGptFixtures.RESOURCE) return json(BAD_REQUEST, """{"error":"invalid_target"}""")
        val now = clock.now()
        val failure = synchronized(state.lock) {
            val attempt = state.attempt(request.scenario, FakeRoute.TOKEN_EXCHANGE)
            val grant = state.codes[code]
            val forced = request.scenario == ChatGptScenario.EXCHANGE_INVALID_GRANT ||
                (request.scenario == ChatGptScenario.EXCHANGE_INVALID_GRANT_ONCE && attempt == 1)
            val wasUsed = grant?.used == true
            grant?.used = true
            when {
                forced || grant == null || wasUsed || now >= grant.expiresAt || grant.clientId != clientId ->
                    ChatGptFixtures.TOKEN_CODE_INVALID

                grant.redirectUri != redirect -> ChatGptFixtures.TOKEN_REDIRECT_MISMATCH

                s256(verifier) != grant.challenge -> ChatGptFixtures.TOKEN_PKCE_MISMATCH

                else -> null
            }
        }
        if (failure != null) return json(BAD_REQUEST, failure)
        val grant = synchronized(state.lock) { state.codes.getValue(code) }
        return issueAfterExchange(request, grant, now)
    }

    private fun issueAfterExchange(request: FakeRequest, grant: CodeGrant, now: Instant): MockResponse {
        val declined = request.scenario == ChatGptScenario.PLAN_SCOPE_DECLINED
        val scope = if (declined) ChatGptFixtures.DECLINED_SCOPES else ChatGptFixtures.ALL_SCOPES
        val sub = if (request.scenario == ChatGptScenario.OTHER_ACCOUNT) ChatGptFixtures.OTHER_SUB else ChatGptFixtures.SUB
        val (accessToken, refreshToken) = issueTokens(grant.clientId, sub, scope, offline = "offline_access" in scope.split(' '))
        val idToken = idToken(request, grant.clientId, sub, grant.nonce, now)
        val earliest = when (request.scenario) {
            ChatGptScenario.EXCHANGE_EARLIEST_ISO -> "\"${now.plus(EARLIEST_OFFSET)}\""
            ChatGptScenario.EXCHANGE_NO_EARLIEST -> null
            ChatGptScenario.REFRESH_NOT_READY -> (now.epochSeconds + NOT_READY_OFFSET_SECONDS).toString()
            else -> (now.epochSeconds + EARLIEST_OFFSET.inWholeSeconds).toString()
        }
        val fields = listOfNotNull(
            "\"access_token\":\"$accessToken\"",
            refreshToken?.let { "\"refresh_token\":\"$it\"" },
            "\"id_token\":\"$idToken\"",
            "\"token_type\":\"Bearer\"",
            "\"expires_in\":$TOKEN_LIFETIME_SECONDS",
            "\"scope\":\"$scope\"",
            earliest?.let { "\"earliest_refresh_at\":$it" },
        )
        return json(OK, fields.joinToString(",", "{", "}"))
    }

    @Suppress("ReturnCount") // One guard per documented refresh rule (R06 §2.11, §9.2).
    private fun refresh(request: FakeRequest): MockResponse {
        val clientId = request.formValue("client_id")
        val token = request.formValue("refresh_token")
        val sentScope = request.form?.containsKey("scope") == true
        if (sentScope) synchronized(state.lock) { state.violation("H-SCOPE-ON-REFRESH") }
        val scenarioFailure = refreshScenarioFailure(request)
        if (clientId == null || token == null || sentScope || request.formValue("resource") != ChatGptFixtures.RESOURCE) {
            return invalidRequest()
        }
        if (clientId != CLIENT_ID) return json(UNAUTHORIZED, ChatGptFixtures.TOKEN_INVALID_CLIENT)
        if (scenarioFailure != null) return scenarioFailure
        val (grant, problem) = consumeRefreshToken(token, clientId)
        return if (grant == null) json(BAD_REQUEST, problem ?: ChatGptFixtures.REFRESH_INVALID_GRANT) else issueAfterRefresh(request, grant)
    }

    /** Marks [token] used (rotation: every refresh token is single-use) or says why it cannot be used. */
    private fun consumeRefreshToken(token: String, clientId: String): Pair<RefreshGrant?, String?> = synchronized(state.lock) {
        val grant = state.refreshTokens[token]
        val problem = when {
            grant == null || grant.clientId != clientId -> ChatGptFixtures.REFRESH_INVALID_GRANT
            grant.revoked -> ChatGptFixtures.REFRESH_REVOKED
            grant.used -> ChatGptFixtures.REFRESH_REUSED
            else -> null
        }
        if (problem == null) grant?.used = true
        (if (problem == null) grant else null) to problem
    }

    private fun issueAfterRefresh(request: FakeRequest, grant: RefreshGrant): MockResponse {
        val (accessToken, refreshToken) = issueTokens(grant.clientId, grant.sub, grant.scope, offline = true)
        val now = clock.now()
        val fields = mutableListOf(
            "\"access_token\":\"$accessToken\"",
            "\"refresh_token\":\"$refreshToken\"",
            "\"token_type\":\"Bearer\"",
            "\"expires_in\":$TOKEN_LIFETIME_SECONDS",
        )
        when (request.scenario) {
            ChatGptScenario.REFRESH_WITH_SCOPE -> fields += "\"scope\":\"${grant.scope}\""

            ChatGptScenario.REFRESH_WITH_ID_TOKEN -> fields += "\"id_token\":\"${idToken(request, grant.clientId, grant.sub, null, now)}\""

            ChatGptScenario.REFRESH_ACCOUNT_MISMATCH ->
                fields += "\"id_token\":\"${idToken(request, grant.clientId, ChatGptFixtures.OTHER_SUB, null, now)}\""

            else -> Unit
        }
        return json(OK, fields.joinToString(",", "{", "}"))
    }

    private fun refreshScenarioFailure(request: FakeRequest): MockResponse? {
        val attempt = synchronized(state.lock) { state.attempt(request.scenario, FakeRoute.TOKEN_REFRESH) }
        return when (request.scenario) {
            ChatGptScenario.REFRESH_INVALID_GRANT -> json(BAD_REQUEST, ChatGptFixtures.REFRESH_INVALID_GRANT)

            ChatGptScenario.REFRESH_INVALID_CLIENT -> json(UNAUTHORIZED, ChatGptFixtures.TOKEN_INVALID_CLIENT)

            ChatGptScenario.INVALID_USER -> json(BAD_REQUEST, ChatGptFixtures.REFRESH_REVOKED)

            ChatGptScenario.REFRESH_TRANSIENT ->
                if (attempt == 1) json(SERVICE_UNAVAILABLE, ChatGptFixtures.REFRESH_TEMPORARILY_UNAVAILABLE) else null

            ChatGptScenario.REFRESH_BAD_GATEWAY ->
                if (attempt == 1) FakeResponses.html(BAD_GATEWAY, ChatGptFixtures.BAD_GATEWAY_HTML) else null

            ChatGptScenario.REFRESH_SOCKET_CLOSE ->
                if (attempt == 1) MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else null

            else -> null
        }
    }

    /** RFC 7009: 200 with an empty body, even for an unknown token. */
    fun revoke(request: FakeRequest): MockResponse {
        val token = request.formValue("token") ?: return invalidRequest()
        if (request.formValue("token_type_hint") != "refresh_token" || request.formValue("client_id") == null) {
            synchronized(state.lock) { state.violation("H-REVOKE-FORM") }
        }
        if (request.scenario == ChatGptScenario.REVOCATION_FAILURE) {
            return json(SERVICE_UNAVAILABLE, ChatGptFixtures.REFRESH_TEMPORARILY_UNAVAILABLE)
        }
        synchronized(state.lock) {
            state.revoked += token
            state.refreshTokens[token]?.revoked = true
        }
        return FakeResponses.empty(OK)
    }

    private fun issueTokens(clientId: String, sub: String, scope: String, offline: Boolean): Pair<String, String?> =
        synchronized(state.lock) {
            state.tokenCounter += 1
            val n = state.tokenCounter
            val accessToken = "${tokenPrefix}at_$n"
            val refreshToken = if (offline) "${tokenPrefix}rt_$n" else null
            state.accessTokens[accessToken] = AccessGrant(clientId, sub, clock.now() + TOKEN_LIFETIME_SECONDS.seconds)
            refreshToken?.let { state.refreshTokens[it] = RefreshGrant(clientId, sub, scope) }
            accessToken to refreshToken
        }

    private fun idToken(request: FakeRequest, clientId: String, sub: String, nonce: String?, now: Instant): String {
        val claims = buildJsonObject {
            put("iss", request.authBase)
            put("aud", clientId)
            put("sub", sub)
            nonce?.let { put("nonce", it) }
            put("iat", now.epochSeconds)
            put("exp", now.epochSeconds + TOKEN_LIFETIME_SECONDS)
            put("email", ChatGptFixtures.EMAIL)
            put("name", ChatGptFixtures.NAME)
        }
        val keyId = if (request.scenario == ChatGptScenario.JWKS_UNKNOWN_KID) ChatGptFixtures.UNKNOWN_KEY_ID else ChatGptFixtures.KEY_ID
        return signer.sign(claims, keyId)
    }

    private fun invalidRequest(): MockResponse = json(BAD_REQUEST, ChatGptFixtures.TOKEN_INVALID_REQUEST)

    internal companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val BAD_GATEWAY = 502
        const val SERVICE_UNAVAILABLE = 503
        const val TOKEN_LIFETIME_SECONDS = 3600L
        const val NOT_READY_OFFSET_SECONDS = 7200L
        val CODE_LIFETIME = 60.seconds
        val EARLIEST_OFFSET = 3000.seconds
        val LOOPBACK_REDIRECT = Regex("""^http://127\.0\.0\.1:[1-9][0-9]{0,4}/auth/callback$""")
        val HOST_ID = Regex("""^(urn:uuid:[0-9a-f-]{36}|urn:ietf:params:oauth:jwk-thumbprint:.+|did:key:.+)$""")
        val CHALLENGE = Regex("""^[A-Za-z0-9_-]{43}$""")
        val REQUESTED_SCOPES = setOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct")

        fun s256(verifier: String): String = FakeJwtSigner.base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

        fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    }
}

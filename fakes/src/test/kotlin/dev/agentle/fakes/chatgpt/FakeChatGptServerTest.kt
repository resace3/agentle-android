package dev.agentle.fakes.chatgpt

import com.google.common.truth.Truth.assertThat
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Self-tests of [FakeChatGptServer] over plain HTTP. They use no client code at all, so the fake stays an independent
 * implementation of docs/research/06 §9 (red team oauth-security-14).
 */
class FakeChatGptServerTest {
    private val clock = TestAgentleClock()
    private val server = FakeChatGptServer(clock).start()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val auth: String get() = server.authBaseUrl()
    private val api: String get() = server.apiBaseUrl()

    @AfterEach
    fun stop() {
        server.close()
    }

    private class Answer(val status: Int, val body: String, val headers: Headers)

    private fun call(request: Request): Answer = http.newCall(request).execute().use { Answer(it.code, it.body.string(), it.headers) }

    private fun get(url: String, bearer: String? = null): Answer =
        call(Request.Builder().url(url).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build())

    private fun form(url: String, vararg fields: Pair<String, String>): Answer {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return call(Request.Builder().url(url).post(body).build())
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun claims(jwt: String): JsonObject = json(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8))

    private fun authorizeUrl(base: String = auth, change: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        "$base/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", ChatGptFixtures.BOOTSTRAP_CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", ChatGptFixtures.RESOURCE)
            .addQueryParameter("state", STATE)
            .addQueryParameter("nonce", NONCE)
            .addQueryParameter("code_challenge", CHALLENGE)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("ext_agent_host_id", HOST_ID)
            .addQueryParameter("agent_name_hint", "Agentle")
            .apply(change)
            .build()

    /** Plays the browser: authorize and return the loopback callback the fake redirected to. */
    private fun authorize(base: String = auth, on: FakeChatGptServer = server, change: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        checkNotNull(on.browserHop(authorizeUrl(base, change))) { "no redirect" }

    private fun exchange(
        code: String,
        base: String = auth,
        verifier: String = VERIFIER,
        overrides: Map<String, String> = emptyMap(),
    ): Answer {
        val fields = linkedMapOf(
            "grant_type" to "authorization_code",
            "client_id" to ChatGptFixtures.CLIENT_ID,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to REDIRECT,
            "resource" to ChatGptFixtures.RESOURCE,
        ) + overrides
        return form("$base/api/accounts/oauth/token", *fields.toList().toTypedArray())
    }

    private fun refresh(token: String, vararg extra: Pair<String, String>): Answer = form(
        "$auth/api/accounts/oauth/token",
        "grant_type" to "refresh_token",
        "client_id" to ChatGptFixtures.CLIENT_ID,
        "refresh_token" to token,
        "resource" to ChatGptFixtures.RESOURCE,
        *extra,
    )

    private fun revoke(vararg fields: Pair<String, String>): Answer = form("$auth/api/accounts/oauth/revoke", *fields)

    /** A complete sign-in through the fake; returns the token answer. */
    private fun signIn(base: String = auth, on: FakeChatGptServer = server): JsonObject {
        val code = checkNotNull(authorize(base, on).queryParameter("code"))
        return json(exchange(code, base).body)
    }

    private fun item(role: String): JsonObject = JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive("hi")))

    private fun responsesBody(change: (MutableMap<String, JsonElement>) -> Unit = {}): String {
        val body = linkedMapOf<String, JsonElement>(
            "model" to JsonPrimitive(ChatGptFixtures.MODEL),
            "instructions" to JsonPrimitive("Be brief."),
            "input" to JsonArray(listOf(item("user"))),
            "store" to JsonPrimitive(false),
            "stream" to JsonPrimitive(true),
        )
        change(body)
        return JsonObject(body).toString()
    }

    private fun responses(body: String, bearer: String, accept: String? = "text/event-stream", type: String = "application/json"): Answer =
        call(
            Request.Builder().url("$api/responses")
                .header("Authorization", "Bearer $bearer")
                .apply { accept?.let { header("Accept", it) } }
                .post(body.toRequestBody(type.toMediaType()))
                .build(),
        )

    @Test
    fun `the scenario listing names every scenario and unknown scenarios or services are refused`() {
        val listing = json(get("${server.rootUrl()}fake-chatgpt/scenarios").body)

        assertThat((listing.getValue("scenarios") as JsonArray).map { it.jsonPrimitive.content })
            .containsExactlyElementsIn(ChatGptScenario.entries.map { it.id }).inOrder()
        assertThat(get("${server.rootUrl()}fake-chatgpt/scenario/nope/auth/.well-known/openid-configuration").status).isEqualTo(400)
        assertThat(get("${server.rootUrl()}fake-chatgpt/other").status).isEqualTo(404)
        assertThat(ChatGptScenario.fromId("usage-limit")).isEqualTo(ChatGptScenario.USAGE_LIMIT)
        assertThat(ChatGptScenario.fromId("nope")).isNull()
    }

    @Test
    fun `discovery serves the R06 9_2 literal below whichever base it was asked on`() {
        val plain = json(get("$auth/.well-known/openid-configuration").body)
        val prefixed = server.authBaseUrl(ChatGptScenario.DISCOVERY_NO_REVOCATION)
        val variant = json(get("$prefixed/.well-known/openid-configuration").body)

        assertThat(plain.text("issuer")).isEqualTo(auth)
        assertThat(plain.text("token_endpoint")).isEqualTo("$auth/api/accounts/oauth/token")
        assertThat(plain.text("revocation_endpoint")).isEqualTo("$auth/api/accounts/oauth/revoke")
        assertThat(variant.text("issuer")).isEqualTo(prefixed)
        assertThat(variant).doesNotContainKey("revocation_endpoint")
        assertThat(server.requests().map { it.scenario })
            .containsExactly(ChatGptScenario.HAPPY, ChatGptScenario.DISCOVERY_NO_REVOCATION).inOrder()
        assertThat(server.requestsFor(ChatGptScenario.DISCOVERY_NO_REVOCATION).single().route).isEqualTo(FakeRoute.DISCOVERY)
    }

    @Test
    fun `the discovery variants change only what their row names`() {
        val url = "$auth/.well-known/openid-configuration"

        server.defaultScenario = ChatGptScenario.DISCOVERY_ISSUER_MISMATCH
        assertThat(json(get(url).body).text("issuer")).isEqualTo(ChatGptFixtures.PRODUCTION_ISSUER)
        server.defaultScenario = ChatGptScenario.DISCOVERY_FOREIGN_ENDPOINT
        assertThat(json(get(url).body).text("token_endpoint")).startsWith("https://foreign.example.invalid/")
        server.defaultScenario = ChatGptScenario.DISCOVERY_UNAVAILABLE
        assertThat(get(url).status).isEqualTo(503)
    }

    @Test
    fun `JWKS outages serve their literal bodies`() {
        val url = "$auth/.well-known/jwks.json"

        server.defaultScenario = ChatGptScenario.JWKS_UNAVAILABLE
        assertThat(get(url).status).isEqualTo(503)
        server.defaultScenario = ChatGptScenario.JWKS_EMPTY
        assertThat(get(url).body).isEqualTo(ChatGptFixtures.JWKS_EMPTY)
        server.defaultScenario = ChatGptScenario.JWKS_MALFORMED
        assertThat(get(url).body).isEqualTo(ChatGptFixtures.JWKS_MALFORMED)
    }

    @Test
    fun `authorize never redirects to an unverified redirect URI or for an unknown client`() {
        val evil = get(authorizeUrl { setQueryParameter("redirect_uri", "https://evil.example.invalid/auth/callback") }.toString())
        val unknown = get(authorizeUrl { setQueryParameter("client_id", "oaiapp_other") }.toString())

        assertThat(evil.status).isEqualTo(400)
        assertThat(evil.headers["Location"]).isNull()
        assertThat(evil.body).isEqualTo(ChatGptFixtures.AUTHORIZE_INVALID_REDIRECT)
        assertThat(unknown.status).isEqualTo(400)
        assertThat(unknown.body).isEqualTo(ChatGptFixtures.AUTHORIZE_UNKNOWN_CLIENT)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "A1 response_type token -> unsupported_response_type",
            "A2 scope without openid -> invalid_scope",
            "A3 another resource -> invalid_target",
            "A4 plain PKCE -> invalid_request",
            "A5 short code challenge -> invalid_request",
            "A6 host id that is not a URN -> invalid_request",
            "A7 bootstrap client without agent_name_hint -> invalid_request",
            "A8 no nonce -> invalid_request",
        ],
    )
    fun `authorize sends protocol errors back to the app with the state`(row: String) {
        val callback = authorize {
            when (row.substringBefore(' ')) {
                "A1" -> setQueryParameter("response_type", "token")
                "A2" -> setQueryParameter("scope", "profile email")
                "A3" -> setQueryParameter("resource", "https://api.example.invalid/v1")
                "A4" -> setQueryParameter("code_challenge_method", "plain")
                "A5" -> setQueryParameter("code_challenge", "abc")
                "A6" -> setQueryParameter("ext_agent_host_id", "host-1")
                "A7" -> removeAllQueryParameters("agent_name_hint")
                "A8" -> removeAllQueryParameters("nonce")
            }
        }

        assertThat(callback.queryParameter("error")).isEqualTo(row.substringAfter("-> "))
        assertThat(callback.queryParameter("state")).isEqualTo(STATE)
        assertThat(callback.queryParameter("code")).isNull()
    }

    @Test
    fun `the sign-in scenarios shape the callback as their R06 9_4 rows describe`() {
        server.defaultScenario = ChatGptScenario.CONSENT_DENIED
        assertThat(authorize().queryParameter("error")).isEqualTo("access_denied")

        server.defaultScenario = ChatGptScenario.INVALID_STATE
        assertThat(authorize().queryParameter("state")).isEqualTo("TAMPERED")
        assertThat(authorize().queryParameter("state")).isEqualTo(STATE)

        server.defaultScenario = ChatGptScenario.REGISTRATION_INCOMPLETE
        assertThat(authorize().queryParameter("client_id")).isNull()

        server.defaultScenario = ChatGptScenario.PLAN_SCOPE_DECLINED
        val declined = authorize()
        assertThat(declined.queryParameter("scope")).isEqualTo(ChatGptFixtures.DECLINED_SCOPES)
        assertThat(json(exchange(declined.queryParameter("code")!!).body).text("scope")).isEqualTo(ChatGptFixtures.DECLINED_SCOPES)
    }

    @Test
    fun `the code flow issues numbered tokens, a signed ID token and earliest_refresh_at, and a code works once`() {
        val callback = authorize()
        val code = checkNotNull(callback.queryParameter("code"))

        val tokens = json(exchange(code).body)
        val again = exchange(code)

        assertThat(callback.encodedPath).isEqualTo("/auth/callback")
        assertThat(callback.queryParameter("client_id")).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(callback.queryParameter("state")).isEqualTo(STATE)
        assertThat(callback.queryParameter("scope")).isEqualTo(ChatGptFixtures.ALL_SCOPES)
        assertThat(code).isEqualTo("code_1")
        assertThat(tokens.text("access_token")).isEqualTo("at_1")
        assertThat(tokens.text("refresh_token")).isEqualTo("rt_1")
        assertThat(tokens.text("scope")).isEqualTo(ChatGptFixtures.ALL_SCOPES)
        assertThat(tokens.getValue("expires_in").jsonPrimitive.long).isEqualTo(3600)
        assertThat(tokens.getValue("earliest_refresh_at").jsonPrimitive.long).isEqualTo(clock.now().epochSeconds + 3000)
        val idToken = tokens.text("id_token")
        val claims = claims(idToken)
        assertThat(claims.text("iss")).isEqualTo(auth)
        assertThat(claims.text("aud")).isEqualTo(ChatGptFixtures.CLIENT_ID)
        assertThat(claims.text("sub")).isEqualTo(ChatGptFixtures.SUB)
        assertThat(claims.text("nonce")).isEqualTo(NONCE)
        assertThat(claims.getValue("iat").jsonPrimitive.long).isEqualTo(clock.now().epochSeconds)
        val keys = JWKSet.parse(get("$auth/.well-known/jwks.json").body)
        assertThat(SignedJWT.parse(idToken).verify(RSASSAVerifier(keys.getKeyByKeyId(ChatGptFixtures.KEY_ID).toRSAKey()))).isTrue()
        assertThat(again.status).isEqualTo(400)
        assertThat(again.body).isEqualTo(ChatGptFixtures.TOKEN_CODE_INVALID)
        assertThat(server.exchangeCount()).isEqualTo(2)
        assertThat(server.issuedAccessTokens()).containsExactly("at_1")
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `the exchange enforces PKCE, the redirect URI, the issued client id and the code lifetime`() {
        fun freshCode(): String = checkNotNull(authorize().queryParameter("code"))

        assertThat(exchange(freshCode(), verifier = "x".repeat(43)).body).isEqualTo(ChatGptFixtures.TOKEN_PKCE_MISMATCH)
        assertThat(exchange(freshCode(), overrides = mapOf("redirect_uri" to "http://127.0.0.1:1/auth/callback")).body)
            .isEqualTo(ChatGptFixtures.TOKEN_REDIRECT_MISMATCH)
        val bootstrap = exchange(freshCode(), overrides = mapOf("client_id" to ChatGptFixtures.BOOTSTRAP_CLIENT_ID))
        assertThat(bootstrap.status).isEqualTo(401)
        assertThat(server.hygieneViolations()).containsExactly("H-BOOTSTRAP-CLIENT-AT-EXCHANGE")
        assertThat(exchange(freshCode(), overrides = mapOf("resource" to "https://api.example.invalid")).status).isEqualTo(400)
        val late = freshCode()
        clock.advanceBy(61.seconds)
        assertThat(exchange(late).body).isEqualTo(ChatGptFixtures.TOKEN_CODE_INVALID)
    }

    @Test
    fun `the token endpoint accepts only one-valued form posts with a known grant type`() {
        val asJson = call(
            Request.Builder().url("$auth/api/accounts/oauth/token")
                .post("""{"grant_type":"refresh_token"}""".toRequestBody("application/json".toMediaType())).build(),
        )
        val repeated = form("$auth/api/accounts/oauth/token", "grant_type" to "refresh_token", "grant_type" to "refresh_token")
        val unknown = form("$auth/api/accounts/oauth/token", "grant_type" to "password")
        val incomplete = form("$auth/api/accounts/oauth/token", "grant_type" to "authorization_code")

        assertThat(asJson.body).isEqualTo(ChatGptFixtures.TOKEN_INVALID_REQUEST)
        assertThat(repeated.body).isEqualTo(ChatGptFixtures.TOKEN_INVALID_REQUEST)
        assertThat(unknown.body).isEqualTo(ChatGptFixtures.TOKEN_UNSUPPORTED_GRANT)
        assertThat(incomplete.body).isEqualTo(ChatGptFixtures.TOKEN_INVALID_REQUEST)
    }

    @Test
    fun `refresh rotates single-use refresh tokens and tells reuse, revocation and unknown tokens apart`() {
        signIn()

        val rotated = json(refresh("rt_1").body)

        assertThat(rotated.keys).containsExactly("access_token", "refresh_token", "token_type", "expires_in")
        assertThat(rotated.text("refresh_token")).isEqualTo("rt_2")
        assertThat(refresh("rt_1").body).isEqualTo(ChatGptFixtures.REFRESH_REUSED)
        assertThat(refresh("rt_unknown").body).isEqualTo(ChatGptFixtures.REFRESH_INVALID_GRANT)
        assertThat(revoke("token" to "rt_2", "token_type_hint" to "refresh_token", "client_id" to ChatGptFixtures.CLIENT_ID).status)
            .isEqualTo(200)
        assertThat(refresh("rt_2").body).isEqualTo(ChatGptFixtures.REFRESH_REVOKED)
        assertThat(server.revokedTokens()).containsExactly("rt_2")
        assertThat(server.liveRefreshTokens()).isEmpty()
        assertThat(server.refreshCount()).isEqualTo(4)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @Test
    fun `refresh refuses a scope parameter, a foreign client and a missing resource`() {
        signIn()

        assertThat(refresh("rt_1", "scope" to "openid").status).isEqualTo(400)
        assertThat(server.hygieneViolations()).containsExactly("H-SCOPE-ON-REFRESH")
        val foreign = form(
            "$auth/api/accounts/oauth/token",
            "grant_type" to "refresh_token",
            "client_id" to "oaiapp_other",
            "refresh_token" to "rt_1",
            "resource" to ChatGptFixtures.RESOURCE,
        )
        assertThat(foreign.body).isEqualTo(ChatGptFixtures.TOKEN_INVALID_CLIENT)
        val noResource = form(
            "$auth/api/accounts/oauth/token",
            "grant_type" to "refresh_token",
            "client_id" to ChatGptFixtures.CLIENT_ID,
            "refresh_token" to "rt_1",
        )
        assertThat(noResource.body).isEqualTo(ChatGptFixtures.TOKEN_INVALID_REQUEST)
        assertThat(server.liveRefreshTokens()).containsExactly("rt_1")
    }

    @Test
    fun `the refresh variants add a scope or an ID token, and transient faults fail only the first attempt`() {
        signIn()

        server.defaultScenario = ChatGptScenario.REFRESH_WITH_SCOPE
        assertThat(json(refresh("rt_1").body).text("scope")).isEqualTo(ChatGptFixtures.ALL_SCOPES)
        server.defaultScenario = ChatGptScenario.REFRESH_WITH_ID_TOKEN
        val sameAccount = claims(json(refresh("rt_2").body).text("id_token"))
        assertThat(sameAccount.text("sub")).isEqualTo(ChatGptFixtures.SUB)
        assertThat(sameAccount).doesNotContainKey("nonce")
        server.defaultScenario = ChatGptScenario.REFRESH_ACCOUNT_MISMATCH
        assertThat(claims(json(refresh("rt_3").body).text("id_token")).text("sub")).isEqualTo(ChatGptFixtures.OTHER_SUB)
        server.defaultScenario = ChatGptScenario.REFRESH_TRANSIENT
        assertThat(refresh("rt_4").status).isEqualTo(503)
        assertThat(json(refresh("rt_4").body).text("refresh_token")).isEqualTo("rt_5")
        server.defaultScenario = ChatGptScenario.REFRESH_BAD_GATEWAY
        assertThat(refresh("rt_5").headers["Content-Type"]).startsWith("text/html")
        server.defaultScenario = ChatGptScenario.REFRESH_INVALID_CLIENT
        assertThat(refresh("rt_5").status).isEqualTo(401)
        server.defaultScenario = ChatGptScenario.REFRESH_INVALID_GRANT
        assertThat(refresh("rt_5").body).isEqualTo(ChatGptFixtures.REFRESH_INVALID_GRANT)
        assertThat(server.liveRefreshTokens()).containsExactly("rt_5")
    }

    @Test
    fun `earliest_refresh_at follows the exchange scenario`() {
        server.defaultScenario = ChatGptScenario.EXCHANGE_EARLIEST_ISO
        assertThat(signIn().text("earliest_refresh_at")).isEqualTo((clock.now() + 3000.seconds).toString())
        server.defaultScenario = ChatGptScenario.EXCHANGE_NO_EARLIEST
        assertThat(signIn()).doesNotContainKey("earliest_refresh_at")
        server.defaultScenario = ChatGptScenario.REFRESH_NOT_READY
        assertThat(signIn().getValue("earliest_refresh_at").jsonPrimitive.long).isEqualTo(clock.now().epochSeconds + 7200)
        server.defaultScenario = ChatGptScenario.OTHER_ACCOUNT
        assertThat(claims(signIn().text("id_token")).text("sub")).isEqualTo(ChatGptFixtures.OTHER_SUB)
        server.defaultScenario = ChatGptScenario.JWKS_UNKNOWN_KID
        assertThat(SignedJWT.parse(signIn().text("id_token")).header.keyID).isEqualTo(ChatGptFixtures.UNKNOWN_KEY_ID)
    }

    @Test
    fun `revocation answers 200 even for unknown tokens, records a sloppy form and fails in its scenario`() {
        assertThat(revoke("token" to "unknown").status).isEqualTo(200)
        assertThat(server.hygieneViolations()).containsExactly("H-REVOKE-FORM")
        assertThat(revoke().status).isEqualTo(400)

        server.defaultScenario = ChatGptScenario.REVOCATION_FAILURE
        val failed = revoke("token" to "rt_9", "token_type_hint" to "refresh_token", "client_id" to ChatGptFixtures.CLIENT_ID)

        assertThat(failed.status).isEqualTo(503)
        assertThat(server.revokedTokens()).containsExactly("unknown")
    }

    @Test
    fun `the API checks the bearer token on the shared clock and lists the models as literal JSON`() {
        val token = signIn().text("access_token")

        val models = get("$api/models", token)

        assertThat(get("$api/models").status).isEqualTo(401)
        assertThat(get("$api/models", "at_404").body).isEqualTo(ChatGptFixtures.DETAIL_UNAUTHORIZED)
        assertThat(models.status).isEqualTo(200)
        assertThat(models.body).isEqualTo(ChatGptFixtures.MODELS)
        clock.advanceBy(3600.seconds)
        val expired = get("$api/models", token)
        assertThat(expired.status).isEqualTo(401)
        assertThat(expired.body).isEqualTo(ChatGptFixtures.API_TOKEN_EXPIRED)
    }

    @Test
    fun `a valid responses request streams the R06 9_3 success body as an event stream`() {
        val token = signIn().text("access_token")

        val answer = responses(responsesBody(), token)

        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.headers["Content-Type"]).startsWith("text/event-stream")
        assertThat(answer.body).isEqualTo(ChatGptFixtures.STREAM_SUCCESS)
        assertThat(responses(responsesBody { it["model"] = JsonPrimitive(ChatGptFixtures.HIDDEN_MODEL) }, token).status).isEqualTo(200)
        assertThat(server.hygieneViolations()).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "R1 temperature -> temperature",
            "R2 previous_response_id -> previous_response_id",
            "R3 an undocumented field -> foo",
            "R4 store true -> store",
            "R5 store as a string -> store",
            "R6 stream false -> stream",
            "R7 a system item -> input",
            "R8 input that is not a list -> input",
            "R9 an image generation tool -> tools",
            "R10 a json_schema text format -> text.format",
        ],
    )
    fun `responses names the offending field of a refused request`(row: String) {
        val token = signIn().text("access_token")
        val body = responsesBody {
            when (row.substringBefore(' ')) {
                "R1" -> it["temperature"] = JsonPrimitive(1)
                "R2" -> it["previous_response_id"] = JsonPrimitive("resp_1")
                "R3" -> it["foo"] = JsonPrimitive(true)
                "R4" -> it["store"] = JsonPrimitive(true)
                "R5" -> it["store"] = JsonPrimitive("false")
                "R6" -> it["stream"] = JsonPrimitive(false)
                "R7" -> it["input"] = JsonArray(listOf(item("system")))
                "R8" -> it["input"] = JsonPrimitive("hi")
                "R9" -> it["tools"] = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("image_generation")))))
                "R10" -> it["text"] = JsonObject(mapOf("format" to JsonObject(mapOf("type" to JsonPrimitive("json_schema")))))
            }
        }

        val answer = responses(body, token)

        assertThat(answer.status).isEqualTo(400)
        assertThat(answer.body).isEqualTo(ChatGptFixtures.unsupportedCapability(row.substringAfter("-> ")))
    }

    @Test
    fun `responses refuses bodies that are not JSON and models that are not listed, and records a missing Accept`() {
        val token = signIn().text("access_token")

        assertThat(responses("not json", token).body).isEqualTo(ChatGptFixtures.INVALID_JSON)
        assertThat(responses(responsesBody(), token, type = "text/plain").body).isEqualTo(ChatGptFixtures.INVALID_JSON)
        val unknownModel = responses(responsesBody { it["model"] = JsonPrimitive("gpt-unknown") }, token)
        assertThat(unknownModel.status).isEqualTo(404)
        assertThat(unknownModel.body).isEqualTo(ChatGptFixtures.MODEL_NOT_FOUND)
        assertThat(responses(responsesBody(), token, accept = null).status).isEqualTo(200)
        assertThat(server.hygieneViolations()).containsExactly("H-ACCEPT-EVENT-STREAM")
    }

    @Test
    fun `the stream scenarios serve their R06 9_4 bodies and content types`() {
        val token = signIn().text("access_token")

        server.defaultScenario = ChatGptScenario.MID_STREAM_USAGE_LIMIT
        assertThat(responses(responsesBody(), token).body).isEqualTo(ChatGptFixtures.STREAM_FAILED_USAGE_LIMIT)
        server.defaultScenario = ChatGptScenario.INCOMPLETE
        assertThat(responses(responsesBody(), token).body).isEqualTo(ChatGptFixtures.STREAM_INCOMPLETE)
        server.defaultScenario = ChatGptScenario.WRONG_CONTENT_TYPE
        assertThat(responses(responsesBody(), token).headers["Content-Type"]).startsWith("application/json")
        server.defaultScenario = ChatGptScenario.NO_CONTENT_TYPE
        assertThat(responses(responsesBody(), token).headers["Content-Type"]).isNull()
        server.defaultScenario = ChatGptScenario.RATE_LIMITED_GENERIC
        val limited = responses(responsesBody(), token)
        assertThat(limited.status).isEqualTo(429)
        assertThat(limited.headers["Retry-After"]).isEqualTo("20")
        server.defaultScenario = ChatGptScenario.EXPIRED_ACCESS_TOKEN
        assertThat(responses(responsesBody(), token).status).isEqualTo(401)
        assertThat(responses(responsesBody(), token).status).isEqualTo(200)
    }

    @Test
    fun `other API routes are refused as unsupported and unknown paths are not found`() {
        val token = signIn().text("access_token")

        val files = get("$api/files", token)
        val postModels = call(Request.Builder().url("$api/models").post("{}".toRequestBody("application/json".toMediaType())).build())

        assertThat(files.status).isEqualTo(403)
        assertThat(files.body).isEqualTo(ChatGptFixtures.ROUTE_NOT_SUPPORTED)
        assertThat(postModels.status).isEqualTo(403)
        assertThat(get("$auth/nothing").status).isEqualTo(404)
        assertThat(server.requests().last().route).isEqualTo(FakeRoute.OTHER)
    }

    @Test
    fun `failNext answers first and then the scenario resumes, and reset forgets everything`() {
        val token = signIn().text("access_token")
        server.failNext(FakeRoute.MODELS, 503, ChatGptFixtures.DETAIL_UNAVAILABLE, times = 2, headers = mapOf("Retry-After" to "7"))

        val answers = List(3) { get("$api/models", token) }

        assertThat(answers.map { it.status }).containsExactly(503, 503, 200).inOrder()
        assertThat(answers.first().headers["Retry-After"]).isEqualTo("7")
        assertThrows<IllegalArgumentException> { server.failNext(FakeRoute.MODELS, 500, "", times = 0) }

        server.defaultScenario = ChatGptScenario.USAGE_LIMIT
        server.failNext(FakeRoute.JWKS, 500, "{}")
        server.reset()

        assertThat(server.defaultScenario).isEqualTo(ChatGptScenario.HAPPY)
        assertThat(server.requests()).isEmpty()
        assertThat(server.exchangeCount()).isEqualTo(0)
        assertThat(server.issuedAccessTokens()).isEmpty()
        assertThat(get("$auth/.well-known/jwks.json").status).isEqualTo(200)
        assertThat(get("$api/models", token).status).isEqualTo(401)
        assertThat(signIn().text("access_token")).isEqualTo("at_1")
    }

    @Test
    fun `the journal keeps parameter names only, never their values`() {
        signIn()

        val authorizeCall = server.requests().first { it.route == FakeRoute.AUTHORIZE }

        assertThat(authorizeCall.parameterNames).containsAtLeast("state", "nonce", "code_challenge", "ext_agent_host_id")
        assertThat(authorizeCall.toString()).doesNotContain(STATE)
        assertThat(server.requests().map { it.toString() }.joinToString()).doesNotContain(VERIFIER)
        assertThat(server.requests().first { it.route == FakeRoute.TOKEN_EXCHANGE }.parameterNames).contains("code_verifier")
    }

    @Test
    fun `the server clock offset moves iat and code expiry like a wrong device clock`() {
        server.clockOffset = 2.hours

        val claims = claims(signIn().text("id_token"))

        assertThat(claims.getValue("iat").jsonPrimitive.long).isEqualTo((clock.now() + 2.hours).epochSeconds)
        assertThat(claims.getValue("exp").jsonPrimitive.long).isEqualTo((clock.now() + 3.hours).epochSeconds)
    }

    @Test
    fun `a token prefix marks every code and token for leak tests`() {
        FakeChatGptServer(clock, tokenPrefix = "CANARY-").start().use { canary ->
            val code = checkNotNull(authorize(canary.authBaseUrl(), canary).queryParameter("code"))
            val tokens = json(exchange(code, canary.authBaseUrl()).body)

            assertThat(code).isEqualTo("CANARY-code_1")
            assertThat(tokens.text("access_token")).isEqualTo("CANARY-at_1")
            assertThat(tokens.text("refresh_token")).isEqualTo("CANARY-rt_1")
            assertThat(canary.port).isNotEqualTo(server.port)
        }
    }

    private companion object {
        const val REDIRECT = "http://127.0.0.1:43210/auth/callback"
        const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val STATE = "state-1f2e3d"
        const val NONCE = "nonce-4c5b6a"
        const val HOST_ID = "urn:uuid:123e4567-e89b-42d3-a456-426614174000"

        /** RFC 7636 Appendix B. */
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }
}

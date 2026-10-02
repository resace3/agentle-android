package dev.agentle.ai.chatgpt

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.agentle.core.oauth.OAuthHttpClients
import dev.agentle.core.oauth.Secret
import dev.agentle.core.oauth.TokenClient
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** ID-token rules of docs/research/06 §2.7 with Nimbus, on the injected clock (red team oauth-security-07/10). */
class IdTokenVerifierTest {
    private val clock = TestAgentleClock()
    private val fetches = AtomicInteger()

    @Volatile private var jwks: MockResponse = json(JWKSet(listOf(KEY.toPublicJWK())).toString())

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fetches.incrementAndGet()
                return if (request.url.encodedPath == "/auth/.well-known/jwks.json") jwks else MockResponse.Builder().code(404).build()
            }
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val issuer = "http://127.0.0.1:${server.port}/auth"
    private val config = SiwcConfig(issuer = issuer, apiBaseUrl = "http://127.0.0.1:${server.port}/api/v1", allowCleartextLoopback = true)
    private val tokenClient =
        TokenClient(OAuthHttpClients.authClient("Agentle/test", config.authHosts, allowCleartextLoopback = true), clock)
    private val verifier = IdTokenVerifier(config, SiwcDiscovery(config, tokenClient), tokenClient, clock)

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun json(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

    private fun date(offset: Duration): Date = Date((clock.now() + offset).toEpochMilliseconds())

    private fun token(key: RSAKey = KEY, keyId: String? = key.keyID, claims: JWTClaimsSet.Builder.() -> Unit = {}): Secret {
        val set = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(CLIENT)
            .subject("sub-1")
            .issueTime(date(Duration.ZERO))
            .expirationTime(date(1.hours))
            .claim("nonce", NONCE.value)
            .claim("email", "person@example.invalid")
            .apply(claims)
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), set)
        jwt.sign(RSASSASigner(key))
        return Secret(jwt.serialize())
    }

    private suspend fun verify(token: Secret, nonce: Secret? = NONCE): IdTokenCheck = verifier.verify(token, CLIENT, nonce)

    @Test
    fun `a token that follows every rule yields the account identity`() = runTest {
        val check = verify(token())

        assertThat(check).isEqualTo(IdTokenCheck.Valid(VerifiedIdentity("sub-1", "person@example.invalid", null)))
        val identity = (check as IdTokenCheck.Valid).identity
        assertThat(identity.label).isEqualTo("person@example.invalid")
        assertThat(identity.toString()).doesNotContain("sub-1")
        assertThat(VerifiedIdentity("s", null, "Name").label).isEqualTo("Name")
    }

    @Test
    fun `claims that break a rule make the token invalid`() = runTest {
        val broken = mapOf<String, JWTClaimsSet.Builder.() -> Unit>(
            "issuer" to { issuer("https://auth.example.invalid") },
            "audience" to { audience("oaiapp_other") },
            "azp" to { claim("azp", "oaiapp_other") },
            "azp required with several audiences" to { audience(listOf(CLIENT, "oaiapp_other")) },
            "sub missing" to { subject(null) },
            "sub blank" to { subject(" ") },
            "nonce" to { claim("nonce", "other-nonce") },
            "nonce missing" to { claim("nonce", null) },
            "iat after exp" to { issueTime(date(2.hours)) },
            "exp missing" to { expirationTime(null) },
        )

        broken.forEach { (rule, claims) ->
            assertWithMessage(rule).that(verify(token(claims = claims))).isEqualTo(IdTokenCheck.Invalid("claims"))
        }
    }

    @Test
    fun `several audiences are fine when azp names the client, and refresh tokens carry no nonce`() = runTest {
        assertThat(verify(token { audience(listOf(CLIENT, "other")).claim("azp", CLIENT) })).isInstanceOf(IdTokenCheck.Valid::class.java)
        assertThat(verify(token { claim("nonce", null) }, nonce = null)).isInstanceOf(IdTokenCheck.Valid::class.java)
    }

    @Test
    fun `exp has five seconds of skew, and beyond it a token that fits at its own iat means the device clock is wrong`() = runTest {
        assertThat(
            verify(
                token {
                    expirationTime(date((-3).seconds)).issueTime(date((-1).hours))
                },
            ),
        ).isInstanceOf(IdTokenCheck.Valid::class.java)
        assertThat(verify(token { expirationTime(date((-10).seconds)).issueTime(date((-1).hours)) })).isEqualTo(IdTokenCheck.ClockWrong)
    }

    @Test
    fun `an iat a few minutes ahead is accepted and one far ahead means the device clock is wrong`() = runTest {
        assertThat(verify(token { issueTime(date(4.minutes)).expirationTime(date(2.hours)) })).isInstanceOf(IdTokenCheck.Valid::class.java)
        assertThat(verify(token { issueTime(date(10.minutes)).expirationTime(date(2.hours)) })).isEqualTo(IdTokenCheck.ClockWrong)
    }

    @Test
    fun `only RS256 signatures from the issuer's keys are accepted`() = runTest {
        val hs256 = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), JWTClaimsSet.Builder().subject("s").build())
            .apply { sign(MACSigner(ByteArray(32) { 7 })) }

        assertThat(verify(Secret(hs256.serialize()))).isEqualTo(IdTokenCheck.Invalid("algorithm"))
        assertThat(verify(Secret("not-a-jwt"))).isEqualTo(IdTokenCheck.Invalid("malformed"))
        assertThat(verify(Secret("eyJhbGciOiJub25lIn0.eyJzdWIiOiJzIn0."))).isEqualTo(IdTokenCheck.Invalid("malformed"))
        assertThat(verify(token(key = IMPOSTOR))).isEqualTo(IdTokenCheck.Invalid("signature"))
    }

    @Test
    fun `a token without kid is checked against the issuer's keys`() = runTest {
        assertThat(verify(token(keyId = null))).isInstanceOf(IdTokenCheck.Valid::class.java)
    }

    @Test
    fun `an unknown kid refetches the keys at most every 30 seconds and finds rotated keys`() = runTest {
        assertThat(verify(token())).isInstanceOf(IdTokenCheck.Valid::class.java)
        assertThat(fetches.get()).isEqualTo(1)
        jwks = json(JWKSet(listOf(KEY.toPublicJWK(), ROTATED.toPublicJWK())).toString())

        assertThat(verify(token(key = ROTATED))).isEqualTo(IdTokenCheck.Unavailable)
        assertThat(fetches.get()).isEqualTo(1)

        clock.advanceBy(31.seconds)
        assertThat(verify(token(key = ROTATED))).isInstanceOf(IdTokenCheck.Valid::class.java)
        assertThat(fetches.get()).isEqualTo(2)
        assertThat(verify(token())).isInstanceOf(IdTokenCheck.Valid::class.java)
        assertThat(fetches.get()).isEqualTo(2)
    }

    @Test
    fun `a JWKS outage, a malformed set or an empty one is unavailable, never invalid`() = runTest {
        val outages = listOf(
            MockResponse.Builder().code(
                503,
            ).setHeader("Content-Type", "application/json").body("""{"detail":"Service Unavailable"}""").build(),
            json("""{"keys":[{"kty":"RSA","""),
            json("""{"keys":[]}"""),
        )

        outages.forEach { outage ->
            jwks = outage
            clock.advanceBy(31.seconds)
            assertThat(verify(token())).isEqualTo(IdTokenCheck.Unavailable)
        }
    }

    private companion object {
        const val CLIENT = "oaiapp_test1"
        val NONCE = Secret("nonce-1")
        val KEY: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
        val IMPOSTOR: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
        val ROTATED: RSAKey = RSAKeyGenerator(2048).keyID("k2").generate()
    }
}

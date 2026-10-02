package dev.agentle.ai.chatgpt

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import dev.agentle.core.common.Logger
import dev.agentle.core.oauth.OAuthResult
import dev.agentle.core.oauth.Secret
import dev.agentle.core.oauth.TokenClient
import dev.agentle.core.time.AgentleClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.ParseException
import java.util.Date
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The account identity of a verified ID token. Only [sub] is identity; [email] and [name] are labels (R06 §2.7). */
public data class VerifiedIdentity(val sub: String, val email: String?, val name: String?) {
    /** What the UI shows for the account. */
    val label: String? get() = email ?: name

    /** ID-token claims are never logged (red team oauth-security-13). */
    override fun toString(): String = "VerifiedIdentity(***)"
}

/** The verdict on one ID token. */
public sealed interface IdTokenCheck {
    public data class Valid(val identity: VerifiedIdentity) : IdTokenCheck

    /** JWKS could not be fetched, was malformed or empty, or lacks the token's `kid` after a refetch: retryable. */
    public data object Unavailable : IdTokenCheck

    /** Signature and claims are valid, but the time claims only fit at the token's own `iat`: the device clock is wrong. */
    public data object ClockWrong : IdTokenCheck

    /** [reason] is a stable code: `malformed`, `algorithm`, `signature` or `claims`. */
    public data class Invalid(val reason: String) : IdTokenCheck
}

/**
 * Verifies ID tokens as docs/research/06 §2.7 specifies, with Nimbus JOSE+JWT: RS256 only, keys from the JWKS fetched
 * through the auth client ([JwksCache], refetched once on an unknown `kid`), `iss` exactly the issuer, `aud` contains
 * the client id, `azp` equal to the client id when present (required with several audiences), required `iss`, `aud`,
 * `exp`, `iat`, `sub`, `nonce` equal to the value sent, and 5 s of skew on `exp`. Time comes from [AgentleClock]
 * (red team oauth-security-10). Nothing from a token or an exception is logged.
 */
public class IdTokenVerifier(
    private val config: SiwcConfig,
    discovery: SiwcDiscovery,
    tokenClient: TokenClient,
    private val clock: AgentleClock,
    private val logger: Logger = Logger.NONE,
) {
    private val jwks = JwksCache(clock, JWKS_MIN_REFETCH) {
        tokenClient.fetchDocument(discovery.cachedOrDocumented().jwksUri, "jwks")
    }

    /** [expectedNonce] is the attempt's nonce at sign-in, and null for an ID token returned by a refresh. */
    public suspend fun verify(idToken: Secret, clientId: String, expectedNonce: Secret?): IdTokenCheck {
        val jwt = try {
            SignedJWT.parse(idToken.value)
        } catch (_: ParseException) {
            null
        }
        val keys = jwt?.takeIf { it.header.algorithm == JWSAlgorithm.RS256 }?.let { jwks.keysFor(it.header.keyID) }
        val check = when {
            jwt == null -> IdTokenCheck.Invalid("malformed")
            jwt.header.algorithm != JWSAlgorithm.RS256 -> IdTokenCheck.Invalid("algorithm")
            keys == null -> IdTokenCheck.Unavailable
            else -> check(jwt, keys, clientId, expectedNonce)
        }
        logger.i(COMPONENT, "id token checked", fields = mapOf("outcome" to outcomeOf(check)))
        return check
    }

    private fun check(jwt: SignedJWT, keys: JWKSet, clientId: String, nonce: Secret?): IdTokenCheck = try {
        val processor = DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, FetchedJwkSource(keys))
            jwtClaimsSetVerifier = claimsVerifier(clientId, nonce) { clock.now() }
        }
        IdTokenCheck.Valid(identityOf(processor.process(jwt, null)))
    } catch (_: BadJWTException) {
        // Claims rejected after a valid signature (or unparseable claims): a clock problem if they fit at `iat`.
        if (validAtIssueTime(jwt, clientId, nonce)) IdTokenCheck.ClockWrong else IdTokenCheck.Invalid("claims")
    } catch (_: BadJOSEException) {
        IdTokenCheck.Invalid("signature")
    } catch (_: JOSEException) {
        IdTokenCheck.Invalid("signature")
    }

    private fun validAtIssueTime(jwt: SignedJWT, clientId: String, nonce: Secret?): Boolean {
        val claims = try {
            jwt.jwtClaimsSet
        } catch (_: ParseException) {
            null
        }
        val issuedAt = claims?.issueTime ?: return false
        return try {
            claimsVerifier(clientId, nonce) { Instant.fromEpochMilliseconds(issuedAt.time) }.verify(claims, null)
            true
        } catch (_: BadJWTException) {
            false
        }
    }

    private fun claimsVerifier(clientId: String, nonce: Secret?, now: () -> Instant): SiwcClaimsVerifier =
        SiwcClaimsVerifier(clientId, config.issuer, nonce, config.clockSkew, config.issuedAtTolerance, now)

    private fun identityOf(claims: JWTClaimsSet): VerifiedIdentity =
        VerifiedIdentity(claims.subject, claims.getClaim("email") as? String, claims.getClaim("name") as? String)

    private fun outcomeOf(check: IdTokenCheck): String = when (check) {
        is IdTokenCheck.Valid -> "valid"
        IdTokenCheck.Unavailable -> "unavailable"
        IdTokenCheck.ClockWrong -> "clock_wrong"
        is IdTokenCheck.Invalid -> "invalid:${check.reason}"
    }

    private companion object {
        const val COMPONENT = "siwc.idtoken"
        val JWKS_MIN_REFETCH = 30.seconds
    }
}

/**
 * The claim checks of R06 §2.7 on top of Nimbus' audience, required-claim, exact-issuer and `exp` checks. The current
 * time comes from [now] (the injected [AgentleClock], red team oauth-security-10), never from the JVM.
 */
internal class SiwcClaimsVerifier(
    private val clientId: String,
    issuer: String,
    private val expectedNonce: Secret?,
    skew: Duration,
    private val issuedAtTolerance: Duration,
    private val now: () -> Instant,
) : DefaultJWTClaimsVerifier<SecurityContext>(clientId, JWTClaimsSet.Builder().issuer(issuer).build(), REQUIRED_CLAIMS) {
    init {
        maxClockSkew = skew.inWholeSeconds.toInt()
    }

    override fun currentTime(): Date = Date(now().toEpochMilliseconds())

    override fun verify(claimsSet: JWTClaimsSet, context: SecurityContext?) {
        super.verify(claimsSet, context)
        val issuedAt = claimsSet.issueTime
        val expiresAt = claimsSet.expirationTime
        val rawAzp = claimsSet.getClaim("azp")
        val rawNonce = claimsSet.getClaim("nonce")
        val latestIssue = now() + issuedAtTolerance
        val problem = when {
            issuedAt == null || expiresAt == null || !expiresAt.after(issuedAt) -> "iat"
            issuedAt.time > latestIssue.toEpochMilliseconds() -> "iat_future"
            rawAzp != null && rawAzp != clientId -> "azp"
            rawAzp == null && claimsSet.audience.size > 1 -> "azp_required"
            claimsSet.subject.isNullOrBlank() -> "sub"
            expectedNonce != null && (rawNonce !is String || !expectedNonce.matches(rawNonce)) -> "nonce"
            else -> null
        }
        if (problem != null) throw BadJWTException(problem)
    }

    private companion object {
        val REQUIRED_CLAIMS = setOf("iss", "aud", "exp", "iat", "sub")
    }
}

/** A [JWKSource] over a key set that the auth client fetched (red team oauth-security-09: no Nimbus HTTP). */
internal class FetchedJwkSource(private val keys: JWKSet) : JWKSource<SecurityContext> {
    override fun get(jwkSelector: JWKSelector, context: SecurityContext?): List<JWK> = jwkSelector.select(keys)
}

/**
 * The JWKS cache of R06 §8.2: keys are fetched on first use and again when a token names a `kid` the cached set lacks
 * (key rotation), at most once per [minRefetchInterval] on the monotonic clock. A failed, malformed or empty fetch
 * yields null (an outage, never an invalid token).
 */
internal class JwksCache(
    private val clock: AgentleClock,
    private val minRefetchInterval: Duration,
    private val fetch: suspend () -> OAuthResult<String>,
) {
    private val mutex = Mutex()
    private var cachedKeys: JWKSet? = null
    private var fetchedAt: Duration? = null

    suspend fun keysFor(keyId: String?): JWKSet? = mutex.withLock {
        cachedKeys?.takeIf { it.has(keyId) } ?: refetch(keyId)
    }

    private suspend fun refetch(keyId: String?): JWKSet? {
        val last = fetchedAt
        val tooSoon = cachedKeys != null && last != null && clock.elapsed() - last < minRefetchInterval
        val fetched = if (tooSoon) null else (fetch() as? OAuthResult.Success)?.value?.let(::parse)
        if (fetched != null) {
            cachedKeys = fetched
            fetchedAt = clock.elapsed()
        }
        return fetched?.takeIf { it.has(keyId) }
    }

    private fun parse(body: String): JWKSet? = try {
        JWKSet.parse(body)
    } catch (_: ParseException) {
        null
    }

    private fun JWKSet.has(keyId: String?): Boolean = if (keyId == null) keys.isNotEmpty() else getKeyByKeyId(keyId) != null
}

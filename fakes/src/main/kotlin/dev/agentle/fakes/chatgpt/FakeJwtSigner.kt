package dev.agentle.fakes.chatgpt

import kotlinx.serialization.json.JsonObject
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * RS256 signing for the fake's ID tokens, written against `java.security` only (no JOSE library), so the client's
 * Nimbus-based verifier is checked against an independent implementation (red team oauth-security-14).
 */
internal class FakeJwtSigner private constructor(private val keyPair: KeyPair) {
    /** Base64url (no padding) of the unsigned big-endian modulus, as RFC 7518 §6.3.1.1 requires. */
    val modulus: String = base64Url(unsigned((keyPair.public as RSAPublicKey).modulus))

    fun jwks(): String = ChatGptFixtures.JWKS_TEMPLATE.replace("{n}", modulus)

    fun sign(claims: JsonObject, keyId: String): String {
        val header = """{"alg":"RS256","kid":"$keyId","typ":"JWT"}"""
        val signingInput = base64Url(header.toByteArray(Charsets.UTF_8)) + "." + base64Url(claims.toString().toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + base64Url(signature)
    }

    companion object {
        private const val KEY_BITS = 2048
        private val cache = ConcurrentHashMap<Long, FakeJwtSigner>()

        /** One RSA 2048 key pair per seed, generated once per JVM (key generation is slow). */
        fun forSeed(seed: Long): FakeJwtSigner = cache.getOrPut(seed) {
            val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, random) }
            FakeJwtSigner(generator.generateKeyPair())
        }

        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
    }
}

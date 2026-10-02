package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.security.SecureRandom

class PkceTest {
    /** Feeds fixed bytes, so the RFC 7636 Appendix B octets can be replayed through [OAuthRandom]. */
    private class FixedBytes(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(out: ByteArray) {
            bytes.copyInto(out, endIndex = out.size)
        }
    }

    private val rfcOctets = intArrayOf(
        116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
        22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
    ).map(Int::toByte).toByteArray()

    @Test
    fun `S256 challenge matches the RFC 7636 appendix B vector`() {
        val challenge = Pkce.challengeS256(Secret("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertThat(challenge).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `the RFC 7636 appendix B octets become the documented verifier and challenge`() {
        val pair = OAuthRandom(FixedBytes(rfcOctets)).pkce()
        assertThat(pair.verifier.value).isEqualTo("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertThat(pair.challenge).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertThat(pair.method).isEqualTo("S256")
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk, true",
        "abc.def~ghi_jkl-mno.pqr~stu_vwx-yz0123456789, true",
        "dBjftJeZ4CVP+mB92K27uhbUJU1p1r/wW1gFWFOEjXk, false",
        "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjX=, false",
    )
    fun `verifier syntax follows RFC 7636 section 4_1`(verifier: String, valid: Boolean) {
        assertThat(Pkce.isValidVerifier(verifier)).isEqualTo(valid)
    }

    @Test
    fun `verifiers must be 43 to 128 characters long`() {
        assertThat(Pkce.isValidVerifier("a".repeat(42))).isFalse()
        assertThat(Pkce.isValidVerifier("a".repeat(43))).isTrue()
        assertThat(Pkce.isValidVerifier("a".repeat(128))).isTrue()
        assertThat(Pkce.isValidVerifier("a".repeat(129))).isFalse()
        assertThrows<IllegalArgumentException> { Pkce.challengeS256(Secret("a".repeat(129))) }
    }

    @Test
    fun `state nonce and verifier are fresh 43 character base64url values from the injected random`() {
        val random = OAuthRandom(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        val values = List(20) { listOf(random.state().value, random.nonce().value, random.pkce().verifier.value) }.flatten()
        assertThat(values.toSet()).hasSize(values.size)
        values.forEach { value ->
            assertThat(value).hasLength(43)
            assertThat(value).matches("[A-Za-z0-9_-]{43}")
        }
    }

    @Test
    fun `token sizes outside 16 to 96 bytes are refused`() {
        val random = OAuthRandom(SecureRandom())
        assertThat(random.token(16)).hasLength(22)
        assertThat(random.token(96)).hasLength(128)
        assertThrows<IllegalArgumentException> { random.token(15) }
        assertThrows<IllegalArgumentException> { random.token(97) }
    }
}

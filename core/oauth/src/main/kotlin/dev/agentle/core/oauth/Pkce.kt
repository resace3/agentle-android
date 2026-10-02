package dev.agentle.core.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A PKCE verifier and its S256 challenge (RFC 7636 §4.1-4.2). Only S256 is supported: `plain` exposes the verifier
 * in the authorization URL (docs/research/04-privacy-security.md §3.5).
 */
public data class PkcePair(val verifier: Secret, val challenge: String) {
    val method: String get() = Pkce.METHOD_S256
}

public object Pkce {
    public const val METHOD_S256: String = "S256"

    /** RFC 7636 §4.1: 43-128 characters from the unreserved set `[A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~"`. */
    private val VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")

    public fun isValidVerifier(verifier: String): Boolean = VERIFIER.matches(verifier)

    /** `BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))` without padding (RFC 7636 §4.2). */
    public fun challengeS256(verifier: Secret): String {
        require(isValidVerifier(verifier.value)) { "code_verifier must be 43-128 unreserved characters" }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.value.toByteArray(Charsets.US_ASCII))
        return Base64Url.encode(digest)
    }

    public fun from(verifier: Secret): PkcePair = PkcePair(verifier, challengeS256(verifier))
}

/** Unpadded base64url (RFC 4648 §5), the encoding of every random OAuth value. */
public object Base64Url {
    public fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Fresh random values for one authorization attempt: `state`, `nonce` and the PKCE verifier are each 32 bytes from
 * the injected [SecureRandom], base64url-encoded to 43 characters (docs/research/06-openai-sign-in-with-chatgpt.md
 * §2.4). New values are drawn for every attempt, including retries.
 */
public class OAuthRandom(private val random: SecureRandom) {
    public fun state(): Secret = Secret(token())

    public fun nonce(): Secret = Secret(token())

    public fun pkce(): PkcePair = Pkce.from(Secret(token()))

    /** [byteCount] random bytes, base64url-encoded without padding. */
    public fun token(byteCount: Int = DEFAULT_BYTES): String {
        require(byteCount in MIN_BYTES..MAX_BYTES) { "byteCount must be in $MIN_BYTES..$MAX_BYTES" }
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }

    public companion object {
        public const val DEFAULT_BYTES: Int = 32
        private const val MIN_BYTES = 16
        private const val MAX_BYTES = 96
    }
}

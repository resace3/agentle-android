package dev.agentle.core.oauth

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * A credential or one-time value (token, authorization code, PKCE verifier, `state`, `nonce`). [toString] is masked,
 * so a secret printed by accident (string template, data class `toString`, exception message) never reaches a log
 * (docs/research/04-privacy-security.md §3.1, the `ToMask` pattern). Read [value] only where the raw value must go
 * on the wire or into the encrypted credential store.
 */
@Serializable
@JvmInline
public value class Secret(public val value: String) {
    override fun toString(): String = MASK

    /** Constant-time comparison (no early exit on the first differing character). */
    public fun matches(other: String): Boolean = ConstantTime.equals(value, other)

    public companion object {
        public const val MASK: String = "Secret(***)"
    }
}

/** Constant-time string comparison for `state`, `nonce` and similar values an attacker may probe. */
public object ConstantTime {
    /** True if [a] and [b] are equal. Only the length can leak through timing, which is public for these values. */
    public fun equals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

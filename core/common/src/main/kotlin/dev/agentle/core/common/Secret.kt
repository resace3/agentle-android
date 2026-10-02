package dev.agentle.core.common

/**
 * A secret value (token, authorization code, key material). [toString] is masked, so string templates, data-class
 * `toString` and log calls show `Secret(***)` (docs/research/04 §3.1, SEC-LOG-02). Not `Serializable` or `Parcelable`:
 * never put one in WorkManager `Data`, Intents, Bundles, notifications or the clipboard (SEC-TOK-02).
 */
@JvmInline
public value class Secret(public val value: String) {
    override fun toString(): String = MASK

    public companion object {
        public const val MASK: String = "Secret(***)"
    }
}

package dev.agentle.core.common

/**
 * Removes secrets and personal data from free text before it is logged or exported.
 *
 * Covered: JWTs (`eyJ…`), bearer tokens, OAuth parameters (`code`, `state`, `token`, `refresh_token`,
 * `access_token`, `id_token`, `client_secret`, `code_verifier`) and any key ending in `token`, `secret`, `verifier`,
 * `password` or `api_key`, `Authorization` headers, e-mail addresses, coordinates with more than 2 decimals, digit
 * runs of 7+ (phone numbers, OTPs, account numbers), opaque provider tokens and ids (`sk-`, `rt-`, `rt_`, `at_`,
 * `oaiapp_`, `ya29.`, `urn:uuid:`). Short keys match only as whole words, so diagnostic fields such as
 * `last_error_code` or `siwcState` stay readable.
 * It is a safety net: code must still avoid logging sensitive values in the first place.
 */
public object Redactor {
    private const val MASK = "[REDACTED]"
    private val SECRET_KEYS = listOf(
        "access_token", "refresh_token", "id_token", "token", "code", "code_verifier",
        "client_secret", "state", "nonce", "password", "api[_-]?key",
    ).joinToString("|")

    /** Suffixes that mark a compound key as secret wherever it appears (`oauth_token`, `x-api-key`, ...). */
    private const val SECRET_SUFFIXES = "token|secret|verifier|password|api[_-]?key"

    private val rules: List<Pair<Regex, String>> = listOf(
        // JWT: three base64url segments starting with eyJ
        Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""") to MASK,
        // Authorization headers and bearer tokens
        Regex("""(?i)(authorization\s*[:=]\s*)(bearer|basic)?\s*[^\s,;]+""") to "$1$MASK",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""") to "Bearer $MASK",
        // key=value or "key":"value" for OAuth parameters (whole keys only) and compound secret keys
        Regex("""(?i)((?<![A-Za-z0-9_-])"?(?:$SECRET_KEYS)"?\s*[:=]\s*"?)([^"&\s,\}]+)""") to "$1$MASK",
        Regex("""(?i)("?[A-Za-z0-9_-]*(?:$SECRET_SUFFIXES)"?\s*[:=]\s*"?)([^"&\s,\}]+)""") to "$1$MASK",
        // e-mail addresses
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "[EMAIL]",
        // precise coordinates: keep 2 decimals (about 1 km)
        Regex("""(-?\d{1,3}\.\d{2})\d{2,}""") to "$1",
        // long digit runs (phone numbers, OTP codes, account numbers)
        Regex("""\d{7,}""") to "[NUMBER]",
        // opaque provider tokens (OpenAI-style sk-/rt-/oaiapp_ prefixes, Google ya29.)
        Regex("""\b(?:sk|rt|sess)-[A-Za-z0-9_-]{12,}""") to MASK,
        Regex("""\b(?:rt|at)_[A-Za-z0-9_-]{16,}""") to MASK,
        Regex("""\boaiapp_[A-Za-z0-9_-]+""") to MASK,
        Regex("""\bya29\.[A-Za-z0-9._-]{10,}""") to MASK,
        Regex("""(?i)\burn:uuid:[0-9a-f-]{36}""") to MASK,
    )

    public fun redact(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    /** True if [text] still contains something [redact] would change. */
    public fun containsSensitive(text: String): Boolean = redact(text) != text
}

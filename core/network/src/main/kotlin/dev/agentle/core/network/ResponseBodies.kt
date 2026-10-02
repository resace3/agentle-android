package dev.agentle.core.network

import dev.agentle.core.common.Redactor

/**
 * Helpers for bodies that may not be what the API promised: HTML error pages from proxies (404/502), JSON with the
 * wrong Content-Type, or no Content-Type at all (docs/ARCHITECTURE.md §7, §8).
 */
public object ResponseBodies {
    private const val DEFAULT_SNIPPET = 512

    /** `application/json`, `application/problem+json`, `text/json`, with or without parameters. */
    public fun isJsonContentType(contentType: String?): Boolean {
        if (contentType == null) return false
        val mime = contentType.substringBefore(';').trim().lowercase()
        return mime == "application/json" || mime == "text/json" || (mime.startsWith("application/") && mime.endsWith("+json"))
    }

    /** True if the first non-blank character opens a JSON object or array. */
    public fun looksLikeJson(body: String): Boolean {
        val first = body.firstOrNull { !it.isWhitespace() } ?: return false
        return first == '{' || first == '['
    }

    /**
     * A short, redacted excerpt of an error body for diagnostics. Never the full body: error bodies can echo
     * request parameters or personal data.
     */
    public fun errorSnippet(body: String?, maxChars: Int = DEFAULT_SNIPPET): String? {
        if (body.isNullOrBlank()) return null
        val collapsed = body.replace(Regex("\\s+"), " ").trim()
        val cut = if (collapsed.length > maxChars) collapsed.take(maxChars) + "…" else collapsed
        return Redactor.redact(cut)
    }
}

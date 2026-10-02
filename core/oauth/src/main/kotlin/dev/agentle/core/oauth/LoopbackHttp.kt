package dev.agentle.core.oauth

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/** The request line and header fields of one HTTP/1.x request. The body is never read. */
internal data class RequestHead(val method: String, val target: String, val version: String, val headers: List<Pair<String, String>>) {
    fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    override fun toString(): String = "RequestHead($method)"
}

/** Result of reading a request head from a socket. */
internal sealed interface HeadRead {
    data class Ok(val head: RequestHead) : HeadRead

    /** The request line or the whole head exceeded its cap. */
    data object TooLarge : HeadRead

    /** Not a well-formed HTTP/1.x request head. */
    data object Malformed : HeadRead

    /** The peer closed the connection, or sent nothing before the read timeout (a browser's spare connection). */
    data object NoRequest : HeadRead
}

/**
 * A deliberately small HTTP/1.x request-head reader for the loopback listener (docs/research/06 §8.2: hand-rolled
 * and auditable). It reads at most [maxHeadBytes] bytes, rejects request lines over [maxRequestLineBytes], obsolete
 * line folding and whitespace before a header colon (RFC 9112 §5.1-5.2), and gives up when [expired] turns true.
 */
internal object RequestHeadReader {
    private const val CR = '\r'.code
    private const val LF = '\n'.code
    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val VERSIONS = setOf("HTTP/1.1", "HTTP/1.0")

    fun read(input: InputStream, maxRequestLineBytes: Int, maxHeadBytes: Int, expired: () -> Boolean): HeadRead {
        val bytes = java.io.ByteArrayOutputStream()
        var requestLineDone = false
        var previous = -1
        var beforePrevious = -1
        var result: HeadRead? = null
        while (result == null) {
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                -1
            }
            if (b != -1) bytes.write(b)
            result = when {
                b == -1 -> if (bytes.size() == 0) HeadRead.NoRequest else HeadRead.Malformed

                bytes.size() > maxHeadBytes -> HeadRead.TooLarge

                !requestLineDone && b != LF && bytes.size() > maxRequestLineBytes -> HeadRead.TooLarge

                // End of the head: an empty line, either CRLF CRLF or (tolerated, RFC 9112 §2.2) LF LF.
                b == LF && (previous == LF || (previous == CR && beforePrevious == LF)) -> parse(bytes.toByteArray())

                // Slow senders cannot hold a connection open past the head deadline, one byte per read timeout.
                expired() -> HeadRead.Malformed

                else -> null
            }
            if (b == LF) requestLineDone = true
            beforePrevious = previous
            previous = b
        }
        return result
    }

    private fun parse(raw: ByteArray): HeadRead {
        val lines = String(raw, Charsets.ISO_8859_1).split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
        val parts = lines.firstOrNull()?.split(' ').orEmpty()
        val headers = lines.drop(1).map(::headerField)
        val validLine = parts.size == 3 && TOKEN.matches(parts[0]) && parts[1].isNotEmpty() && parts[2] in VERSIONS
        return if (validLine && headers.none { it == null }) {
            HeadRead.Ok(RequestHead(parts[0], parts[1], parts[2], headers.filterNotNull()))
        } else {
            HeadRead.Malformed
        }
    }

    /** `name: value`, or null for obsolete line folding, whitespace before the colon or a non-token name. */
    private fun headerField(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        val name = if (colon > 0) line.substring(0, colon) else ""
        val folded = line.startsWith(' ') || line.startsWith('\t')
        return if (colon > 0 && !folded && TOKEN.matches(name)) name to line.substring(colon + 1).trim(' ', '\t') else null
    }
}

/** Splits `path?query` and decodes the query as `application/x-www-form-urlencoded` (RFC 6749 §4.1.2). */
internal object CallbackTarget {
    /** Path and raw query, or null for anything but origin-form (`/path?query`, no fragment). */
    fun split(target: String): Pair<String, String?>? {
        if (!target.startsWith("/") || target.contains('#')) return null
        val question = target.indexOf('?')
        return if (question < 0) target to null else target.substring(0, question) to target.substring(question + 1)
    }

    /** Every parameter with all its values, in order; null if the query is not validly percent-encoded. */
    fun parseQuery(query: String?): Map<String, List<String>>? {
        if (query.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MutableList<String>>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('=')) ?: return null
            val value = decode(if ('=' in pair) pair.substringAfter('=') else "") ?: return null
            result.getOrPut(name) { mutableListOf() } += value
        }
        return result
    }

    private fun decode(text: String): String? = try {
        URLDecoder.decode(text, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** One response of the loopback listener. Always `Connection: close`, never cached, never framed, never referred. */
internal class LoopbackResponse(
    val status: Int,
    val reason: String,
    val contentType: String,
    val body: ByteArray,
    val contentSecurityPolicy: String,
) {
    fun writeTo(output: OutputStream) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: ").append(contentSecurityPolicy).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }
}

/**
 * The pages the listener serves. Nothing from the request is ever reflected into them (docs/research/04 §3.5). The
 * page's only script removes `code` and `state` from the address bar and the history entry (`history.replaceState`);
 * script and style are allowed by their SHA-256 hashes under an otherwise `default-src 'none'` policy.
 */
internal class CallbackPages(private val appName: String, private val returnLink: String?) {
    private val csp = "default-src 'none'; script-src '${hash(SCRIPT)}'; style-src '${hash(STYLE)}'; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    /** 200: the authorization response was accepted. */
    fun success(): LoopbackResponse = page(OK, "OK", "Return to $appName", "Sign-in finished. Return to $appName to continue.")

    /** 200: the authorization server reported an error (for example `access_denied`). */
    fun notCompleted(): LoopbackResponse = page(OK, "OK", NOT_COMPLETED, "You can try again in $appName when you are ready.")

    /** 400: the response carried the right `state` but cannot be used (no code, wrong issuer, wrong client). */
    fun rejected(): LoopbackResponse = page(BAD_REQUEST, "Bad Request", NOT_COMPLETED, "You can try again in $appName when you are ready.")

    /** 400: wrong, duplicate or missing `state`; the attempt keeps waiting for the real response. */
    fun invalid(): LoopbackResponse =
        page(BAD_REQUEST, "Bad Request", "This sign-in response is not valid", "Return to $appName and start the sign-in again.")

    fun notFound(): LoopbackResponse = plain(NOT_FOUND, "Not Found")

    fun tooLarge(): LoopbackResponse = plain(HEADERS_TOO_LARGE, "Request Header Fields Too Large")

    fun badRequest(): LoopbackResponse = plain(BAD_REQUEST, "Bad Request")

    private fun plain(status: Int, reason: String) =
        LoopbackResponse(status, reason, "text/plain; charset=utf-8", "$reason\n".toByteArray(Charsets.UTF_8), "default-src 'none'")

    private fun page(status: Int, reason: String, title: String, message: String): LoopbackResponse {
        val link = returnLink?.let { "<p><a class=\"button\" href=\"${escape(it)}\">Return to ${escape(appName)}</a></p>" }.orEmpty()
        val html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escape(appName)}</title>" +
            "<style>$STYLE</style></head><body><main><h1>${escape(title)}</h1><p>${escape(message)}</p>$link</main>" +
            "<script>$SCRIPT</script></body></html>"
        return LoopbackResponse(status, reason, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), csp)
    }

    companion object {
        const val OK = 200
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val HEADERS_TOO_LARGE = 431
        const val NOT_COMPLETED = "Sign-in was not completed"

        const val SCRIPT = "history.replaceState(null,\"\",location.pathname);"
        const val STYLE = "body{font-family:sans-serif;margin:2rem;line-height:1.5}" +
            ".button{display:inline-block;padding:.75rem 1.25rem;border-radius:.5rem;background:#222;color:#fff;text-decoration:none}"

        /** CSP source expression for an inline element: `sha256-` + standard base64 of SHA-256 over its UTF-8 text. */
        fun hash(inline: String): String =
            "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(inline.toByteArray(Charsets.UTF_8)))

        fun escape(text: String): String = buildString(text.length) {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }
    }
}

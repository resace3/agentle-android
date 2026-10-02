package dev.agentle.core.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The shape of an error body; kept for diagnostics (docs/research/06 §5.5: "response body shape"). */
public enum class BodyShape {
    /** RFC 6749 §5.2: `{"error":"invalid_grant","error_description":"..."}`. */
    OAUTH_STRING,

    /** API style: `{"error":{"code":"...","message":"...","param":"...","type":"..."}}`. */
    ERROR_OBJECT,

    /** Admission style: `{"detail":"..."}`. */
    DETAIL,

    /** Any other JSON object or array. */
    OTHER_JSON,

    /** HTML (proxies, captive portals, gateways). */
    HTML,

    /** No body. */
    EMPTY,

    /** Anything else, including truncated JSON. */
    UNPARSEABLE,
}

/**
 * The machine-readable part of an error body. Human-readable texts (`error_description`, `message`, `detail`) are
 * deliberately dropped: servers can echo request input in them, so they must never reach logs or storage.
 */
public data class ErrorBody(val shape: BodyShape, val code: String?, val param: String?, val type: String?) {
    public companion object {
        private val CODE = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val PARAM = Regex("^[A-Za-z0-9_.\\[\\]-]{1,128}$")
        public val EMPTY: ErrorBody = ErrorBody(BodyShape.EMPTY, null, null, null)

        /** Parses [body]; never throws. Codes and params outside a conservative character set are dropped. */
        public fun parse(body: String?, contentType: String? = null): ErrorBody {
            val text = body?.trim().orEmpty()
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
            return when {
                text.isEmpty() -> EMPTY
                mime == "text/html" || text.startsWith("<") -> ErrorBody(BodyShape.HTML, null, null, null)
                else -> parseJson(text)
            }
        }

        private fun parseJson(text: String): ErrorBody {
            val element = try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                // kotlinx.serialization's SerializationException extends IllegalArgumentException; its message embeds
                // the input, so it is dropped here.
                null
            }
            return when (element) {
                null -> ErrorBody(BodyShape.UNPARSEABLE, null, null, null)
                is JsonObject -> parseObject(element)
                else -> ErrorBody(BodyShape.OTHER_JSON, null, null, null)
            }
        }

        private fun parseObject(obj: JsonObject): ErrorBody = when (val error = obj["error"]) {
            is JsonPrimitive -> ErrorBody(BodyShape.OAUTH_STRING, error.stringOrNull()?.takeIf(CODE::matches), null, null)

            is JsonObject -> ErrorBody(
                BodyShape.ERROR_OBJECT,
                error["code"].stringOrNull()?.takeIf(CODE::matches),
                error["param"].stringOrNull()?.takeIf(PARAM::matches),
                error["type"].stringOrNull()?.takeIf(CODE::matches),
            )

            else -> if (obj.containsKey("detail")) {
                ErrorBody(BodyShape.DETAIL, null, null, null)
            } else {
                ErrorBody(BodyShape.OTHER_JSON, null, null, null)
            }
        }

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

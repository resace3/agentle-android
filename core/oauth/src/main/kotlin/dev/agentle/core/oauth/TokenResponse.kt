package dev.agentle.core.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A successful token-endpoint answer (RFC 6749 §5.1), validated by [TokenResponseParser]. `toString` never shows
 * a token.
 *
 * @property scope the granted scope string as sent, or null if the response omitted it (refresh responses may).
 * @property earliestRefreshAt OpenAI's `earliest_refresh_at` (UNDOCUMENTED meaning; Unix seconds or ISO-8601,
 *   docs/research/06 §2.6), null if absent or unreadable.
 */
public data class TokenResponse(
    val accessToken: Secret,
    val tokenType: String,
    val expiresIn: Duration,
    val refreshToken: Secret? = null,
    val idToken: Secret? = null,
    val scope: String? = null,
    val earliestRefreshAt: Instant? = null,
) {
    /** The granted scopes, or null if the response did not say. */
    val grantedScopes: Set<String>? get() = scope?.let(::scopeSet)

    public companion object {
        public fun scopeSet(scope: String): Set<String> = scope.split(' ').filter { it.isNotEmpty() }.toSet()
    }
}

/**
 * Validation rules for a token response (docs/research/06 §2.6, DevKit-equivalent checks): `token_type` is bearer
 * (case-insensitive), `expires_in` is a finite number > 0, `access_token` is non-empty, `scope` is a string, and a
 * refresh token is present whenever [refreshTokenScope] was granted.
 */
public data class TokenResponseRules(
    val requireIdToken: Boolean = false,
    val requireScope: Boolean = false,
    val refreshTokenScope: String? = "offline_access",
) {
    public companion object {
        /** Refresh responses: `scope` and `id_token` may be omitted. */
        public val REFRESH: TokenResponseRules = TokenResponseRules(requireIdToken = false, requireScope = false, refreshTokenScope = null)
    }
}

public object TokenResponseParser {
    private const val MAX_EXPIRES_IN_SECONDS = 400L * 24 * 3600

    /** Parses and validates [body]; the failure reason is a stable code naming the offending field. */
    @Suppress("ReturnCount") // One guard clause per validation rule of docs/research/06 §2.6, in that order.
    public fun parse(body: String, rules: TokenResponseRules, httpStatus: Int = OK): OAuthResult<TokenResponse> {
        val obj = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return invalid("not_json_object", httpStatus)
        val accessToken = obj.string("access_token")?.takeIf { it.isNotEmpty() } ?: return invalid("access_token", httpStatus)
        val tokenType =
            obj.string("token_type")?.takeIf { it.equals("bearer", ignoreCase = true) } ?: return invalid("token_type", httpStatus)
        val expiresIn = expiresIn(obj["expires_in"]) ?: return invalid("expires_in", httpStatus)
        val scope = when {
            !obj.containsKey("scope") -> null
            else -> obj.string("scope") ?: return invalid("scope", httpStatus)
        }
        if (scope == null && rules.requireScope) return invalid("scope", httpStatus)
        val refreshToken = when (val field = optionalToken(obj, "refresh_token")) {
            Field.Invalid -> return invalid("refresh_token", httpStatus)
            Field.Absent -> null
            is Field.Present -> field.value
        }
        val idToken = when (val field = optionalToken(obj, "id_token")) {
            Field.Invalid -> return invalid("id_token", httpStatus)
            Field.Absent -> null
            is Field.Present -> field.value
        }
        if (rules.requireIdToken && idToken == null) return invalid("id_token", httpStatus)
        val offlineGranted = rules.refreshTokenScope != null && scope != null && rules.refreshTokenScope in TokenResponse.scopeSet(scope)
        if (offlineGranted && refreshToken == null) return invalid("refresh_token", httpStatus)
        return OAuthResult.Success(
            TokenResponse(
                accessToken = Secret(accessToken),
                tokenType = tokenType,
                expiresIn = expiresIn,
                refreshToken = refreshToken,
                idToken = idToken,
                scope = scope,
                earliestRefreshAt = instantOrNull(obj["earliest_refresh_at"]),
            ),
        )
    }

    /** Unix seconds (number or numeric string) or an ISO-8601 instant; anything else is ignored. */
    public fun instantOrNull(element: JsonElement?): Instant? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.contentOrNull == null) return null
        val seconds = if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
        if (seconds != null) {
            return seconds.takeIf { it.isFinite() && it > 0 }?.let { Instant.fromEpochMilliseconds((it * MILLIS).toLong()) }
        }
        return try {
            Instant.parse(primitive.content.trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun expiresIn(element: JsonElement?): Duration? {
        val primitive = element as? JsonPrimitive ?: return null
        val seconds = if (primitive.isString) {
            primitive.content.trim().toDoubleOrNull()
        } else {
            primitive.longOrNull?.toDouble()
                ?: primitive.doubleOrNull
        }
        return seconds?.takeIf { it.isFinite() && it > 0 && it <= MAX_EXPIRES_IN_SECONDS }?.let { (it * MILLIS).toLong().milliseconds }
    }

    private sealed interface Field {
        data object Absent : Field

        data object Invalid : Field

        data class Present(val value: Secret) : Field
    }

    /** Absent (missing or JSON null), a non-empty string, or invalid (anything else). */
    private fun optionalToken(obj: JsonObject, name: String): Field {
        val primitive = obj[name]?.let { it as? JsonPrimitive ?: return Field.Invalid } ?: return Field.Absent
        return when {
            primitive.contentOrNull == null -> Field.Absent
            primitive.isString && primitive.content.isNotEmpty() -> Field.Present(Secret(primitive.content))
            else -> Field.Invalid
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun invalid(reason: String, httpStatus: Int): OAuthResult<Nothing> =
        OAuthResult.Failure(OAuthFailure.InvalidResponse(reason, httpStatus))

    private const val OK = 200
    private const val MILLIS = 1000.0
}

/**
 * The tokens of one authorization, as persisted (encrypted) and used. [expiresAt] is computed from `expires_in` when
 * the response arrived (docs/research/06 §2.10: schedule refreshes from `expires_in`, never by parsing the token).
 */
public data class TokenSet(
    val accessToken: Secret,
    val refreshToken: Secret?,
    val scopes: Set<String>,
    val obtainedAt: Instant,
    val expiresAt: Instant,
    val earliestRefreshAt: Instant? = null,
) {
    /** True if at most [leeway] of lifetime remains at [now]. */
    public fun expiresWithin(now: Instant, leeway: Duration): Boolean = expiresAt - now <= leeway

    public fun isExpired(now: Instant): Boolean = now >= expiresAt

    /** False while `earliest_refresh_at` is in the future. */
    public fun refreshAllowed(now: Instant): Boolean = earliestRefreshAt == null || now >= earliestRefreshAt

    public companion object {
        /** The first token set of an authorization (code exchange). */
        public fun from(response: TokenResponse, now: Instant): TokenSet = TokenSet(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            scopes = response.grantedScopes.orEmpty(),
            obtainedAt = now,
            expiresAt = now + response.expiresIn,
            earliestRefreshAt = response.earliestRefreshAt,
        )

        /**
         * The token set after a refresh: a rotated refresh token replaces the old one; if the response omits it the old
         * one stays (RFC 6749 §6), and if it omits `scope` the previous scopes stay (docs/research/06 §2.11).
         */
        public fun rotate(previous: TokenSet, response: TokenResponse, now: Instant): TokenSet = TokenSet(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: previous.refreshToken,
            scopes = response.grantedScopes ?: previous.scopes,
            obtainedAt = now,
            expiresAt = now + response.expiresIn,
            earliestRefreshAt = response.earliestRefreshAt,
        )
    }
}

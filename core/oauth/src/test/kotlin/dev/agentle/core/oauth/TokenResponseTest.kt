package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** docs/research/06 §2.6 validation rules, one row per rule. */
class TokenResponseTest {
    private val rules = TokenResponseRules(requireIdToken = true, requireScope = true)

    private fun body(vararg overrides: Pair<String, String?>): String {
        val fields = linkedMapOf(
            "access_token" to "\"at_1\"",
            "refresh_token" to "\"rt_1\"",
            "id_token" to "\"idt\"",
            "token_type" to "\"Bearer\"",
            "expires_in" to "3600",
            "scope" to "\"openid offline_access\"",
        )
        overrides.forEach { (k, v) -> if (v == null) fields.remove(k) else fields[k] = v }
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRows")
    fun `invalid token responses are rejected with the offending field`(name: String, json: String, reason: String) {
        val result = TokenResponseParser.parse(json, rules)
        assertWithMessage(name).that(result).isEqualTo(OAuthResult.Failure(OAuthFailure.InvalidResponse(reason, 200)))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRows")
    fun `valid variants are accepted`(name: String, json: String) {
        assertWithMessage(name).that(TokenResponseParser.parse(json, rules)).isInstanceOf(OAuthResult.Success::class.java)
    }

    @Test
    fun `refresh rules accept a response without scope, id token or refresh token`() {
        val json = """{"access_token":"at_2","token_type":"bearer","expires_in":"3600"}"""
        val response = (TokenResponseParser.parse(json, TokenResponseRules.REFRESH) as OAuthResult.Success).value
        assertThat(response.scope).isNull()
        assertThat(response.grantedScopes).isNull()
        assertThat(response.refreshToken).isNull()
        assertThat(response.expiresIn).isEqualTo(3600.seconds)
    }

    @Test
    fun `fractional expires_in keeps millisecond precision`() {
        val response = (TokenResponseParser.parse(body("expires_in" to "1.5"), rules) as OAuthResult.Success).value
        assertThat(response.expiresIn).isEqualTo(1500.milliseconds)
    }

    @Test
    fun `earliest_refresh_at accepts unix seconds, numeric strings and ISO instants and ignores the rest`() {
        assertThat(TokenResponseParser.instantOrNull(JsonPrimitive(1_790_000_000))).isEqualTo(Instant.fromEpochSeconds(1_790_000_000))
        assertThat(TokenResponseParser.instantOrNull(JsonPrimitive("1790000000"))).isEqualTo(Instant.fromEpochSeconds(1_790_000_000))
        assertThat(
            TokenResponseParser.instantOrNull(JsonPrimitive("2026-10-01T12:50:00Z")),
        ).isEqualTo(Instant.parse("2026-10-01T12:50:00Z"))
        assertThat(TokenResponseParser.instantOrNull(JsonPrimitive("soon"))).isNull()
        assertThat(TokenResponseParser.instantOrNull(JsonPrimitive(-5))).isNull()
        assertThat(TokenResponseParser.instantOrNull(JsonPrimitive(true))).isNull()
        assertThat(TokenResponseParser.instantOrNull(JsonNull)).isNull()
        assertThat(TokenResponseParser.instantOrNull(null)).isNull()
        val parsed = (
            TokenResponseParser.parse(
                body("earliest_refresh_at" to "\"2026-10-01T12:50:00Z\""),
                rules,
            ) as OAuthResult.Success
            ).value
        assertThat(parsed.earliestRefreshAt).isEqualTo(Instant.parse("2026-10-01T12:50:00Z"))
    }

    @Test
    fun `token responses never print their tokens`() {
        val response = (TokenResponseParser.parse(body(), rules) as OAuthResult.Success).value
        assertThat(response.toString()).doesNotContain("at_1")
        assertThat(response.toString()).doesNotContain("rt_1")
        assertThat(response.toString()).doesNotContain("idt")
    }

    @Test
    fun `a first token set computes expiry from expires_in`() {
        val now = Instant.parse("2026-10-01T12:00:00Z")
        val response = (TokenResponseParser.parse(body(), rules) as OAuthResult.Success).value
        val set = TokenSet.from(response, now)
        assertThat(set.expiresAt).isEqualTo(now + 60.minutes)
        assertThat(set.scopes).containsExactly("openid", "offline_access")
        assertThat(set.expiresWithin(now + 59.minutes, 60.seconds)).isTrue()
        assertThat(set.expiresWithin(now + 58.minutes, 60.seconds)).isFalse()
        assertThat(set.isExpired(now + 60.minutes)).isTrue()
        assertThat(set.refreshAllowed(now)).isTrue()
        assertThat(set.toString()).doesNotContain("rt_1")
    }

    @Test
    fun `rotation keeps the old refresh token and scopes only when the response omits them`() {
        val now = Instant.parse("2026-10-01T12:00:00Z")
        val first = TokenSet(Secret("at_1"), Secret("rt_1"), setOf("openid", "offline_access"), now, now + 60.minutes)
        val rotated = TokenSet.rotate(first, TokenResponse(Secret("at_2"), "Bearer", 3600.seconds, Secret("rt_2")), now + 59.minutes)
        assertThat(rotated.refreshToken).isEqualTo(Secret("rt_2"))
        assertThat(rotated.scopes).isEqualTo(first.scopes)
        assertThat(rotated.expiresAt).isEqualTo(now + 119.minutes)
        val kept = TokenSet.rotate(first, TokenResponse(Secret("at_3"), "Bearer", 3600.seconds, scope = "openid"), now)
        assertThat(kept.refreshToken).isEqualTo(Secret("rt_1"))
        assertThat(kept.scopes).containsExactly("openid")
        val notReady = first.copy(earliestRefreshAt = now + 10.minutes)
        assertThat(notReady.refreshAllowed(now)).isFalse()
        assertThat(notReady.refreshAllowed(now + 10.minutes)).isTrue()
    }

    companion object {
        private fun row(name: String, json: String, reason: String) = Arguments.of(name, json, reason)

        @JvmStatic
        fun invalidRows(): List<Arguments> {
            val t = TokenResponseTest()
            return listOf(
                row("not JSON", "<html>", "not_json_object"),
                row("JSON array", "[]", "not_json_object"),
                row("missing access_token", t.body("access_token" to null), "access_token"),
                row("empty access_token", t.body("access_token" to "\"\""), "access_token"),
                row("numeric access_token", t.body("access_token" to "5"), "access_token"),
                row("token_type mac", t.body("token_type" to "\"mac\""), "token_type"),
                row("missing token_type", t.body("token_type" to null), "token_type"),
                row("expires_in 0", t.body("expires_in" to "0"), "expires_in"),
                row("expires_in negative", t.body("expires_in" to "-1"), "expires_in"),
                row("expires_in text", t.body("expires_in" to "\"soon\""), "expires_in"),
                row("expires_in missing", t.body("expires_in" to null), "expires_in"),
                row("expires_in absurd", t.body("expires_in" to "1e12"), "expires_in"),
                row("expires_in object", t.body("expires_in" to "{}"), "expires_in"),
                row("scope number", t.body("scope" to "1"), "scope"),
                row("scope missing but required", t.body("scope" to null), "scope"),
                row("refresh_token missing with offline_access", t.body("refresh_token" to null), "refresh_token"),
                row("refresh_token empty", t.body("refresh_token" to "\"\""), "refresh_token"),
                row("refresh_token object", t.body("refresh_token" to "{}"), "refresh_token"),
                row("id_token missing but required", t.body("id_token" to null), "id_token"),
                row("id_token number", t.body("id_token" to "7"), "id_token"),
            )
        }

        @JvmStatic
        fun validRows(): List<Arguments> {
            val t = TokenResponseTest()
            return listOf(
                Arguments.of("canonical", t.body()),
                Arguments.of("token_type lower case", t.body("token_type" to "\"bearer\"")),
                Arguments.of("token_type upper case", t.body("token_type" to "\"BEARER\"")),
                Arguments.of("expires_in as string", t.body("expires_in" to "\"3600\"")),
                Arguments.of("no offline_access, no refresh token", t.body("scope" to "\"openid\"", "refresh_token" to null)),
                Arguments.of("refresh_token null", t.body("scope" to "\"openid\"", "refresh_token" to "null")),
                Arguments.of("unknown fields ignored", t.body("x_extra" to "{\"a\":1}")),
            )
        }
    }
}

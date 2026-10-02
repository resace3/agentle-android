package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class SecretTest {
    @Serializable
    private data class Holder(val token: Secret, val label: String)

    @Test
    fun `toString never shows the value, also inside data classes and templates`() {
        val secret = Secret("rt_live_value_123")
        val holder = Holder(secret, "x")
        assertThat(secret.toString()).isEqualTo("Secret(***)")
        assertThat("$secret").doesNotContain("rt_live_value_123")
        assertThat(holder.toString()).doesNotContain("rt_live_value_123")
        assertThat(PkcePair(Secret("v".repeat(43)), "c").toString()).doesNotContain("v".repeat(43))
    }

    @Test
    fun `serializes as the plain value for the encrypted store`() {
        val json = Json.encodeToString(Holder.serializer(), Holder(Secret("abc"), "l"))
        assertThat(json).isEqualTo("""{"token":"abc","label":"l"}""")
        assertThat(Json.decodeFromString(Holder.serializer(), json).token.value).isEqualTo("abc")
    }

    @Test
    fun `constant time comparison agrees with equality`() {
        assertThat(Secret("state-1").matches("state-1")).isTrue()
        assertThat(Secret("state-1").matches("state-2")).isFalse()
        assertThat(Secret("state-1").matches("state-12")).isFalse()
        assertThat(ConstantTime.equals("", "")).isTrue()
        assertThat(ConstantTime.equals("é", "é")).isTrue()
    }
}

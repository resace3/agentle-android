package dev.agentle.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.Serializable

class SecretTest {
    private data class Credentials(val accessToken: Secret, val refreshToken: Secret, val expiresIn: Long)

    @Test
    fun `secrets are masked in toString, templates and data classes (SEC-LOG-02)`() {
        val access = Secret("at_CANARY_123")
        val credentials = Credentials(access, Secret("rt_CANARY_456"), 3600)
        assertThat(access.toString()).isEqualTo("Secret(***)")
        assertThat("token=$access").isEqualTo("token=Secret(***)")
        assertThat(credentials.toString()).doesNotContain("CANARY")
        assertThat(listOf(access).toString()).doesNotContain("CANARY")
        assertThat(access.value).isEqualTo("at_CANARY_123")
    }

    @Test
    fun `secrets are not java serializable (SEC-TOK-02)`() {
        val boxed: Any = Secret("x")
        assertThat(boxed is Serializable).isFalse()
    }
}

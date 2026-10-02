package dev.agentle.core.testing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The build runs every test JVM in a non-UTC zone (red team testing-build-04, build-logic `agentle.testZone`), for the
 * JVM (`user.timezone`) and for native code (`TZ`), so code that reads the default zone instead of
 * `AgentleClock.zone()` computes wrong local days in tests too.
 */
class TestJvmZoneTest {
    @Test
    fun `the JVM default zone and the TZ variable name the same zone`() {
        val jvmZone = System.getProperty("user.timezone")
        assertThat(jvmZone).isNotEmpty()
        assertThat(System.getenv("TZ")).isEqualTo(jvmZone)
    }
}

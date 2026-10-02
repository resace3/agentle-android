package dev.agentle.fakes.googlehealth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/** The resource files must stay byte-for-byte copies of docs/research/05 §8.4-8.6 (the source of truth until the spike). */
class GoogleHealthFixtureDriftTest {
    @ParameterizedTest
    @MethodSource("ids")
    fun `R05 8 fixture resource equals its documented body`(id: String) {
        val body = GoogleHealthFixtures.text(id)
        val fenced = listOf("json", "html", "text").any { lang -> "```$lang\n$body```" in doc }
        val inline = "`$body`" in doc
        assertThat(fenced || inline).isTrue()
    }

    @Test
    fun `every documented fixture id has a resource and E404-HTML keeps its probed length`() {
        assertThat(GoogleHealthFixtures.ALL_IDS).hasSize(65)
        assertThat(GoogleHealthFixtures.text("E404-HTML").toByteArray().size).isEqualTo(1575)
        GoogleHealthFixtures.ALL_IDS.forEach { id -> assertThat(GoogleHealthFixtures.text(id)).isNotEmpty() }
    }

    companion object {
        @JvmStatic
        fun ids(): List<String> = GoogleHealthFixtures.ALL_IDS

        private val doc: String by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, DOC).isFile) dir = dir.parentFile
            File(requireNotNull(dir) { "docs/research/05 not found above the working directory" }, DOC).readText()
        }

        private const val DOC = "docs/research/05-google-health-and-health-connect.md"
    }
}

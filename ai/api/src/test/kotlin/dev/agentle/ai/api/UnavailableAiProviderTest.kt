package dev.agentle.ai.api

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections

class UnavailableAiProviderTest {
    private val provider = UnavailableAiProvider()

    @Test
    fun `every call asks for authentication`() = runTest {
        val envelope = Fixtures.envelope()
        val expected = Outcome.Failure(AppError.AuthenticationRequired(provider = UnavailableAiProvider.ID))
        assertThat(provider.analyze(envelope)).isEqualTo(expected)
        assertThat(provider.generateStructuredResult(envelope, OutputSchema("InsightSchema", 1, null))).isEqualTo(expected)
        assertThat(provider.generateImage(envelope)).isEqualTo(expected)
    }

    @Test
    fun `it reports no capability and stays disconnected`() {
        assertThat(provider.capabilities()).isEqualTo(AiCapabilities.NONE)
        assertThat(AiCapability.entries.none { provider.capabilities().isAvailable(it) }).isTrue()
        assertThat(provider.state.value).isEqualTo(AiProviderState.Disconnected)
        assertThat(UnavailableAiProvider("other").id).isEqualTo("other")
    }

    @Test
    fun `its bytecode references no networking class`() {
        val forbidden = listOf("java/net/", "javax/net/", "java/nio/channels/", "okhttp3/", "io/ktor/")
        val classes = listOf(UnavailableAiProvider::class.java, UnavailableAiProvider.Companion::class.java)
        classes.forEach { type ->
            val resource = type.name.substringAfterLast('.') + ".class"
            val bytes = requireNotNull(type.getResourceAsStream(resource)).use { it.readBytes() }
            val constants = String(bytes, Charsets.ISO_8859_1)
            forbidden.forEach { prefix -> assertThat(constants).doesNotContain(prefix) }
        }
    }

    @Test
    fun `no call consults the proxy selector, so no connection is attempted`() = runTest {
        val previous = ProxySelector.getDefault()
        val selected = Collections.synchronizedList(mutableListOf<URI>())
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    selected += uri
                    return listOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            },
        )
        try {
            val envelope = Fixtures.envelope()
            provider.analyze(envelope)
            provider.generateStructuredResult(envelope, OutputSchema("InsightSchema", 1, null))
            provider.generateImage(envelope)
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertThat(selected).isEmpty()
    }
}

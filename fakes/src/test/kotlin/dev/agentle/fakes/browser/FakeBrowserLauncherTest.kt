package dev.agentle.fakes.browser

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.oauth.BrowserLauncher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** [FakeBrowserLauncher] plays the browser hop of docs/research/06 §9.1 between loopback hosts only. */
class FakeBrowserLauncherTest {
    private val hits = CopyOnWriteArrayList<String>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.url.encodedPath
                return when (request.url.encodedPath) {
                    "/authorize" -> redirect("/callback?code=c1")
                    "/callback" -> MockResponse.Builder().code(200).body("done").build()
                    "/refused" -> MockResponse.Builder().code(400).build()
                    "/away" -> redirect("https://auth.example.invalid/next")
                    "/loop" -> redirect("/loop")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    private val browser = FakeBrowserLauncher()

    @AfterEach
    fun stop() {
        server.close()
    }

    private fun redirect(location: String): MockResponse = MockResponse.Builder().code(302).setHeader("Location", location).build()

    private fun url(path: String): HttpUrl = "http://127.0.0.1:${server.port}$path".toHttpUrl()

    @Test
    fun `following a launch walks the redirect to the app and records the last status`() = runTest {
        val outcome = browser.launch(url("/authorize"))

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        assertThat(hits).containsExactly("/authorize", "/callback").inOrder()
        assertThat(browser.hopStatuses).containsExactly(200)
        assertThat(browser.launched).containsExactly(url("/authorize"))
    }

    @Test
    fun `a page that does not redirect ends the hop with its own status`() = runTest {
        browser.launch(url("/refused"))

        assertThat(browser.hopStatuses).containsExactly(400)
    }

    @Test
    fun `the browser never leaves loopback hosts and gives up after three hops`() = runTest {
        browser.launch(url("/away"))
        browser.launch(url("/loop"))
        browser.launch("https://auth.example.invalid/authorize".toHttpUrl())

        assertThat(browser.hopStatuses).containsExactly(-1, -1, -1)
        assertThat(hits).containsExactly("/away", "/loop", "/loop", "/loop").inOrder()
    }

    @Test
    fun `a hop that fails at the transport level is recorded as -1`() = runTest {
        val closed = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        val port = closed.port
        closed.close()

        browser.launch("http://127.0.0.1:$port/authorize".toHttpUrl())

        assertThat(browser.hopStatuses).containsExactly(-1)
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(names = ["CLOSE_TAB", "NO_BROWSER", "FAIL"])
    fun `a launch that is not followed sends nothing and reports what the user or device did`(behavior: FakeBrowserLauncher.Behavior) =
        runTest {
            browser.behavior = behavior

            val outcome = browser.launch(url("/authorize"))

            val expected = when (behavior) {
                FakeBrowserLauncher.Behavior.CLOSE_TAB -> Outcome.Success(Unit)
                FakeBrowserLauncher.Behavior.NO_BROWSER -> Outcome.Failure(BrowserLauncher.NO_BROWSER_ERROR)
                else -> Outcome.Failure(AppError.UnsupportedFeature("browser"))
            }
            assertThat(outcome).isEqualTo(expected)
            assertThat(browser.launched).containsExactly(url("/authorize"))
            assertThat(browser.hopStatuses).isEmpty()
            assertThat(hits).isEmpty()
        }
}

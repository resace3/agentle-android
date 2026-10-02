package dev.agentle.core.oauth

import com.google.common.truth.Truth.assertThat
import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.network.HttpClientConfig
import dev.agentle.core.network.HttpClientFactory
import dev.agentle.core.network.withAccessToken
import dev.agentle.core.testing.TestAgentleClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RotatingTokenSourceTest {
    private val server = MockWebServer()
    private val clock = TestAgentleClock()
    private val refreshes = AtomicInteger()
    private val usedRefreshTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile private var mode = "ok"

    @Volatile private var gate: Pair<CountDownLatch, CountDownLatch>? = null

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body!!.utf8().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                val n = refreshes.incrementAndGet()
                gate?.let { (received, release) ->
                    received.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                if (!usedRefreshTokens.add(form.getValue("refresh_token"))) return json(400, """{"error":"refresh_token_reused"}""")
                return when (mode) {
                    "terminal" -> json(400, """{"error":"invalid_grant"}""")

                    "invalid_client" -> json(401, """{"error":"invalid_client"}""")

                    "unavailable" -> json(503, """{"error":"temporarily_unavailable"}""")

                    else -> json(
                        200,
                        """{"access_token":"at_${n + 1}","refresh_token":"rt_${n + 1}","token_type":"Bearer","expires_in":3600}""",
                    )
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @AfterEach
    fun stop() = server.close()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun source(store: TokenSetStore) = RotatingTokenSource(
        tokenClient = TokenClient(HttpClientFactory.create(HttpClientConfig("t", allowCleartextLoopback = true)), clock),
        tokenEndpoint = server.url("/token"),
        clientId = "client-1",
        store = store,
        clock = clock,
        provider = "fake",
        extraParameters = listOf("resource" to "r"),
    )

    private fun tokens(expiresIn: kotlin.time.Duration) =
        TokenSet(Secret("at_1"), Secret("rt_1"), setOf("offline_access"), clock.now(), clock.now() + expiresIn)

    @Test
    fun `a token with more than 60 s left is used without a refresh`() = runTest {
        val store = InMemoryTokenSetStore(tokens(61.seconds))
        assertThat(source(store).accessToken()).isEqualTo(Outcome.Success("at_1"))
        assertThat(refreshes.get()).isEqualTo(0)
    }

    @Test
    fun `at 60 s or less the token is refreshed and the rotation persisted before it is returned`() = runTest {
        val store = InMemoryTokenSetStore(tokens(60.seconds))
        assertThat(source(store).accessToken()).isEqualTo(Outcome.Success("at_2"))
        assertThat(store.saved.single().refreshToken).isEqualTo(Secret("rt_2"))
        assertThat(store.saved.single().expiresAt).isEqualTo(clock.now() + 60.minutes)
    }

    @Test
    fun `50 concurrent callers with an expiring token cause exactly one refresh (R08 5_7 T-TOK-01)`() = runTest {
        val store = InMemoryTokenSetStore(tokens(10.seconds))
        val source = source(store)
        val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
        coroutineScope {
            repeat(50) {
                launch(Dispatchers.Default) {
                    seen += (source.accessToken() as Outcome.Success).value
                }
            }
        }
        assertThat(refreshes.get()).isEqualTo(1)
        assertThat(seen.toSet()).containsExactly("at_2")
    }

    @Test
    fun `a stale 401 after another caller refreshed causes no second refresh (T-TOK-02)`() = runTest {
        val store = InMemoryTokenSetStore(tokens(30.minutes))
        val source = source(store)
        assertThat(source.accessToken(forceRefresh = true, rejected = "at_1")).isEqualTo(Outcome.Success("at_2"))
        assertThat(source.accessToken(forceRefresh = true, rejected = "at_1")).isEqualTo(Outcome.Success("at_2"))
        assertThat(refreshes.get()).isEqualTo(1)
    }

    @Test
    fun `a 401 through withAccessToken refreshes once and retries with the new token`() = runTest {
        val source = source(InMemoryTokenSetStore(tokens(30.minutes)))
        val used = mutableListOf<String>()
        val result = source.withAccessToken("fake") { token ->
            used += token
            if (token == "at_1") Outcome.Failure(AppError.TokenExpired("fake")) else Outcome.Success("data")
        }
        assertThat(result).isEqualTo(Outcome.Success("data"))
        assertThat(used).containsExactly("at_1", "at_2").inOrder()
    }

    @Test
    fun `terminal refresh errors clear the tokens`() = runTest {
        mode = "terminal"
        val store = InMemoryTokenSetStore(tokens(0.seconds))
        assertThat(source(store).accessToken()).isEqualTo(Outcome.Failure(AppError.AuthenticationRequired("fake", "refresh_rejected")))
        assertThat(store.load()).isNull()
        assertThat(source(store).accessToken()).isEqualTo(Outcome.Failure(AppError.AuthenticationRequired("fake", "no_tokens")))
    }

    @Test
    fun `invalid_client also clears the tokens`() = runTest {
        mode = "invalid_client"
        val store = InMemoryTokenSetStore(tokens(0.seconds))
        assertThat(source(store).accessToken()).isInstanceOf(Outcome.Failure::class.java)
        assertThat(store.load()).isNull()
    }

    @Test
    fun `5xx and network failures keep the tokens`() = runTest {
        mode = "unavailable"
        val store = InMemoryTokenSetStore(tokens(0.seconds))
        val failure = source(store).accessToken() as Outcome.Failure
        assertThat(failure.error).isEqualTo(AppError.RemoteServerError(503, "oauth_error:temporarily_unavailable"))
        assertThat(store.load()!!.refreshToken).isEqualTo(Secret("rt_1"))
        server.close()
        val offline = source(store).accessToken() as Outcome.Failure
        assertThat(offline.error).isInstanceOf(AppError.NetworkUnavailable::class.java)
        assertThat(store.load()!!.refreshToken).isEqualTo(Secret("rt_1"))
    }

    @Test
    fun `no refresh token means the user must authorize again`() = runTest {
        val store = InMemoryTokenSetStore(tokens(0.seconds).copy(refreshToken = null))
        assertThat(source(store).accessToken()).isEqualTo(Outcome.Failure(AppError.AuthenticationRequired("fake", "no_refresh_token")))
    }

    @Test
    fun `a caller cancelled mid-refresh still persists the rotated refresh token`() = runTest {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        gate = received to release
        val store = InMemoryTokenSetStore(tokens(0.seconds))
        val source = source(store)
        val caller = launch(Dispatchers.Default) { source.accessToken() }
        withContext(Dispatchers.IO) { received.await(10, TimeUnit.SECONDS) }
        caller.cancel()
        release.countDown()
        caller.cancelAndJoin()
        assertThat(store.load()!!.refreshToken).isEqualTo(Secret("rt_2"))
        gate = null
        assertThat(source.accessToken()).isEqualTo(Outcome.Success("at_2"))
        assertThat(refreshes.get()).isEqualTo(1)
    }
}

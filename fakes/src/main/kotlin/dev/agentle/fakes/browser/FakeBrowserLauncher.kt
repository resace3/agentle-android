package dev.agentle.fakes.browser

import dev.agentle.core.common.AppError
import dev.agentle.core.common.Outcome
import dev.agentle.core.network.TransportSecurityInterceptor
import dev.agentle.core.oauth.BrowserLauncher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [BrowserLauncher] for the fake flavor and JVM tests (docs/research/08 §5.6): instead of opening a Custom Tab it
 * performs the browser hop of docs/research/06 §9.1 in-process. It GETs the authorization URL without following
 * redirects, reads `Location`, and sends that GET to the app's real loopback listener, so the real authorizer runs end
 * to end without a device browser. It only ever talks to loopback hosts.
 */
public class FakeBrowserLauncher(private val client: OkHttpClient = DEFAULT_CLIENT, private val io: CoroutineDispatcher = Dispatchers.IO) :
    BrowserLauncher {
    /** What the simulated user and browser do with the next launch. */
    public enum class Behavior {
        /** Authenticate, consent and follow the redirect to the app (the happy path). */
        FOLLOW,

        /** Open the page, then close the tab without finishing (R06 §9.4 "Login cancelled (tab closed)"). */
        CLOSE_TAB,

        /** No browser is installed or enabled (red team oauth-security-16). */
        NO_BROWSER,

        /** The browser could not be started for another reason. */
        FAIL,
    }

    @Volatile public var behavior: Behavior = Behavior.FOLLOW

    private val urls = CopyOnWriteArrayList<HttpUrl>()
    private val statuses = CopyOnWriteArrayList<Int>()

    /** Every URL the app asked to open, in order. */
    public val launched: List<HttpUrl> get() = urls.toList()

    /**
     * For each followed launch: the status of the last hop (the loopback listener's answer, or the authorization
     * server's when it did not redirect), or -1 if a hop failed at the transport level.
     */
    public val hopStatuses: List<Int> get() = statuses.toList()

    override suspend fun launch(url: HttpUrl): Outcome<Unit> {
        urls += url
        return when (behavior) {
            Behavior.FOLLOW -> {
                statuses += withContext(io) { follow(url) }
                Outcome.Success(Unit)
            }

            Behavior.CLOSE_TAB -> Outcome.Success(Unit)

            Behavior.NO_BROWSER -> Outcome.Failure(BrowserLauncher.NO_BROWSER_ERROR)

            Behavior.FAIL -> Outcome.Failure(AppError.UnsupportedFeature("browser"))
        }
    }

    /** Follows at most [MAX_HOPS] redirects between loopback hosts; returns the last status. */
    private fun follow(start: HttpUrl): Int {
        var url = start
        repeat(MAX_HOPS) {
            if (!TransportSecurityInterceptor.isLoopback(url.host)) return FAILED
            val (status, location) = get(url) ?: return FAILED
            url = location?.let(url::resolve) ?: return status
        }
        return FAILED
    }

    private fun get(url: HttpUrl): Pair<Int, String?>? = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            response.code to response.header("Location").takeIf { response.isRedirect }
        }
    } catch (_: IOException) {
        null
    }

    public companion object {
        private const val MAX_HOPS = 3
        private const val FAILED = -1
        private val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        }
    }
}

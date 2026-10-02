package dev.agentle.core.oauth

import dev.agentle.core.common.Outcome
import okhttp3.HttpUrl

/**
 * Opens an authorization URL in the user's browser (RFC 8252 §8.12: never an embedded WebView).
 *
 * The Android implementation launches a plain Custom Tab (`androidx.browser`; not Auth Tab, which rejects http
 * redirects) and falls back to `ACTION_VIEW`; the fake flavor and tests use `FakeBrowserLauncher`, which follows the
 * authorization redirect in-process. [launch] returns once the browser was asked to open [url]; the result of the
 * authorization arrives separately, through the [LoopbackCallbackServer].
 */
public fun interface BrowserLauncher {
    /** Failure (for example `AppError.UnsupportedFeature("browser")`) only if no browser could be started. */
    public suspend fun launch(url: HttpUrl): Outcome<Unit>
}

/** A [BrowserLauncher] that only records the URLs it was asked to open (unit tests that play the browser themselves). */
public class RecordingBrowserLauncher : BrowserLauncher {
    private val urls = java.util.concurrent.CopyOnWriteArrayList<HttpUrl>()

    public val launched: List<HttpUrl> get() = urls.toList()

    override suspend fun launch(url: HttpUrl): Outcome<Unit> {
        urls += url
        return Outcome.Success(Unit)
    }
}
